package com.interstellar.proxy.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import com.interstellar.proxy.MainActivity
import com.interstellar.proxy.R
import com.interstellar.proxy.InterstellarApplication
import com.interstellar.proxy.constant.Action
import com.interstellar.proxy.constant.Alert
import com.interstellar.proxy.constant.Status
import com.interstellar.proxy.core.CoreEngines
import com.interstellar.proxy.core.CoreHost
import com.interstellar.proxy.core.CoreOverrides
import com.interstellar.proxy.core.ProxyCore
import com.interstellar.proxy.core.SystemProxyState
import com.interstellar.proxy.data.ConfigStore
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.ktx.hasPermission
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class BoxService(private val service: Service, private val platformInterface: PlatformInterface) :
    CoreHost {
    companion object {
        private const val TAG = "BoxService"

        fun start() {
            start(VPNService::class.java)
        }

        /** Core without VPN — used to url-test while the UI stays 未连接. */
        fun startHeadless() {
            start(ProxyService::class.java)
        }

        private fun start(clazz: Class<*>) {
            ContextCompat.startForegroundService(
                InterstellarApplication.application,
                Intent(InterstellarApplication.application, clazz),
            )
        }

        fun stop() {
            InterstellarApplication.application.sendBroadcast(
                Intent(Action.SERVICE_CLOSE).setPackage(InterstellarApplication.application.packageName),
            )
        }

        fun notifyStopped() {
            InterstellarApplication.application.sendBroadcast(
                Intent(Action.SERVICE_STOPPED).setPackage(InterstellarApplication.application.packageName),
            )
        }
    }

    var fileDescriptor: ParcelFileDescriptor? = null

    private val status = MutableLiveData(Status.Stopped)
    private val binder = ServiceBinder(status)
    private val notification = ServiceNotification(status, service)
    private var core: ProxyCore? = null

    /**
     * The fact bridge belonging to [core]. Held so teardown can unbind *its own*
     * session: a stale instance finishing late must not detach a newer core.
     */
    private var factSession: PlatformFacts.Session? = null

    /** Serializes [releaseCore] between a normal stop and a failed-start teardown. */
    private val releaseMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Decides which start attempt may publish a core. [releaseMutex] only serializes
     * *releasers*; this is what stops a start that is still inside `startup()` from
     * writing its core back after a teardown has already run.
     */
    private val lifecycle = CoreLifecycle()

    private var receiverRegistered = false

    /**
     * A start intent that arrived while the previous run was still tearing
     * down (core switching stops-then-starts): honor it by restarting in
     * place once the shutdown finishes, instead of dropping it.
     */
    @Volatile
    private var pendingRestart = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Action.SERVICE_CLOSE -> {
                        stopService()
                    }

                }
            }
        }

    private fun buildOverrides() =
        CoreOverrides(
            autoRedirect = Settings.autoRedirect,
            perAppEnabled = Settings.perAppProxyEnabled,
            perAppInclude = Settings.perAppProxyMode == Settings.PER_APP_PROXY_INCLUDE,
            perAppPackages = Settings.perAppProxyList,
            selectedTag = Settings.selectedOutboundTag.takeIf { it.isNotBlank() },
        )

    private suspend fun startCore(): CoreStartResult {
        // A destroyed instance refuses to build a core at all (P0-A). Returning here
        // means no CommandServer, no fact bridge and no socket ever come into being.
        val attempt = lifecycle.beginStart()
            ?: return CoreStartResult.SUPERSEDED.also {
                com.interstellar.proxy.core.AppLog.log("service", "service destroyed; refused to start a core")
            }
        com.interstellar.proxy.core.AppLog.log("service", "启动内核 sing-box (attempt $attempt)")
        val created = CoreEngines.create(platformInterface, this)
        try {
            created.startup()
        } catch (e: Exception) {
            // startup() builds real native objects (the CommandServer, the fact bridge)
            // before it can fail. Dispose the object we actually hold — not the `core`
            // field, which was never assigned — so a partial start cannot leak a
            // CommandServer or leave a socket behind.
            runCatching { created.closePlatformEvents() }
            runCatching { created.shutdown() }
            lifecycle.abandon(attempt)
            throw e
        }
        // Publishing is the linearization point: a teardown that ran while startup()
        // was in flight has already bumped the generation, so this publish is refused
        // and the core can never be written back after being released.
        val published = lifecycle.publish(attempt) {
            core = created
            // Attach inside the same critical section as the publish: there is no
            // window in which a core is visible without its fact bridge, or vice versa.
            factSession = PlatformFacts.attach(created)
        }
        if (!published) {
            android.util.Log.i("InterstellarUI", "core start superseded by a stop; disposing the new core")
            runCatching { created.closePlatformEvents() }
            runCatching { created.shutdown() }
            return CoreStartResult.SUPERSEDED
        }
        return CoreStartResult.PUBLISHED
    }

    /**
     * The one place that releases the core and everything bound to it.
     *
     * Idempotent by state (a second caller finds no core, no fd and no session) and
     * serialized by [releaseMutex], so a normal stop racing a failed start cannot
     * close the same fd twice or shut the same core twice.
     *
     * Every path that can destroy the core funnels through here precisely so the
     * ORDER cannot drift between them:
     *
     *  1. unbind the fact bridge and **wait for in-flight deliveries to drain**, so
     *     the `PlatformEvents.close()` inside [ProxyCore.shutdown] can never race a
     *     native call on the same object
     *  2. release the tun fd
     *  3. stop the network monitor
     *  4. shut the core down and drop it
     *
     * Step 1 before step 4 is the whole point: `stopAndAlert` used to skip it, which
     * is how a failed start could close the bridge with facts still queued.
     */
    private suspend fun releaseCore() = releaseMutex.withLock {
        // Stop authorising any in-flight start attempt first: a start that is still
        // inside startup() must not publish its core after this point.
        lifecycle.invalidate()

        val session = factSession
        factSession = null
        val running = core
        core = null

        // 1) Facts stop, and any native call already running for THIS session drains.
        // The token is non-null and identifies our own session, so a newer session
        // attached by someone else can never be unbound here.
        val drained = session == null || runCatching { PlatformFacts.detachAndDrain(session) }
            .onFailure { android.util.Log.w("InterstellarUI", "fact bridge drain failed", it) }
            .getOrDefault(false)

        // 2) Only close the native bridge once the drain is proven. Unproven means a
        // fact call may still be executing on that object; leaving one unreachable Go
        // object behind is strictly better than a use-after-close.
        if (running != null) {
            if (drained) {
                runCatching { running.closePlatformEvents() }
            } else {
                android.util.Log.e(
                    "InterstellarUI",
                    "fact drain unproven; leaving the platform-events bridge open",
                )
            }
        }

        // 3) Then the Android-side resources, then the server itself.
        val pfd = fileDescriptor
        if (pfd != null) {
            runCatching { pfd.close() }
            fileDescriptor = null
        }
        DefaultNetworkMonitor.stop(this)
        runCatching { running?.shutdown() }
    }

    private suspend fun startService() {
        try {
            if (status.value != Status.Starting) return
            withContext(Dispatchers.Main) {
                notification.show(service.getString(R.string.app_name), R.string.status_starting)
            }

            val content = ConfigStore.readActiveConfig()
            if (content == null) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            DefaultNetworkMonitor.start(this)

            try {
                core?.applyConfig(content, buildOverrides())
            } catch (e: Exception) {
                stopAndAlert(Alert.CreateService, e.message)
                return
            }

            if (core?.needWifiState() == true) {
                val wifiPermission =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    } else {
                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    }
                if (!service.hasPermission(wifiPermission)) {
                    stopAndAlert(Alert.RequestLocationPermission)
                    return
                }
            }

            if (status.value != Status.Starting) return
            android.util.Log.d("InterstellarUI", "core STARTED")
            // flip to Started and post the notification on the main thread
            // atomically wrt stopService (also main-thread): a show() that
            // slips past a stop would resurrect the notification after close()
            withContext(Dispatchers.Main) {
                if (status.value == Status.Starting) {
                    status.value = Status.Started
                    notification.show(service.getString(R.string.app_name), R.string.status_started)
                    notification.start()
                }
            }
        } catch (e: Exception) {
            stopAndAlert(Alert.StartService, e.message)
            return
        }
    }

    // ---- CoreHost: callbacks from the active engine ----

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCoreRequestStop() {
        // Core dropped the tun (VPN revoked, another app took the
        // system proxy, crash). Tear the Android service down so a
        // later start isn't blocked on Status.Starting.
        GlobalScope.launch(Dispatchers.Main) {
            stopService()
        }
    }

    override fun onCoreRequestReload() {
        serviceReload()
    }

    override fun systemProxyState(): SystemProxyState? {
        val vpn = service as? VPNService ?: return null
        return SystemProxyState(vpn.systemProxyAvailable, vpn.systemProxyEnabled)
    }

    override fun onSetSystemProxy(enabled: Boolean) {
        serviceReload()
    }

    override fun onCoreTraffic(upPerSecond: Long, downPerSecond: Long) {
        // The native callback carries no client token of its own; the notification
        // resolves it against the live generation and refuses it once closed.
        notification.updateCoreTraffic(upPerSecond, downPerSecond)
    }

    fun serviceReload() {
        runBlocking {
            serviceReload0()
        }
    }

    suspend fun serviceReload0() {
        val content = ConfigStore.readActiveConfig()
        if (content == null) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }
        try {
            core?.applyConfig(content, buildOverrides())
        } catch (e: Exception) {
            stopAndAlert(Alert.CreateService, e.message)
            return
        }

        if (core?.needWifiState() == true) {
            val wifiPermission =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                } else {
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                }
            if (!service.hasPermission(wifiPermission)) {
                stopAndAlert(Alert.RequestLocationPermission)
                return
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun stopService() {
        val current = status.value
        if (current == Status.Stopped || current == Status.Stopping) return
        status.value = Status.Stopping
        notifyStopped()
        if (receiverRegistered) {
            service.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        notification.close()
        GlobalScope.launch(Dispatchers.IO) {
            // Facts stop flowing to the core before its teardown starts; the Android
            // sources themselves are process-scoped and stay installed.
            releaseCore()
            withContext(Dispatchers.Main) {
                status.value = Status.Stopped
                if (pendingRestart) {
                    pendingRestart = false
                    onStartCommand()
                } else {
                    service.stopSelf()
                }
            }
        }
    }

    private suspend fun stopAndAlert(type: Alert, message: String? = null) {
        android.util.Log.e("InterstellarUI", "service stopped: $type msg=$message", Throwable("trace"))
        com.interstellar.proxy.core.AppLog.log("service", "已停止: $type${message?.let { " · $it" } ?: ""}")
        // Same shared release as the normal stop — this path used to reach
        // core.shutdown() without unbinding the fact bridge at all.
        releaseCore()
        // This path is a failure, not a switch: it must not honour (or keep) a start
        // intent that arrived while tearing down, or a stale flag would trigger an
        // unwanted restart after some later, unrelated stop.
        pendingRestart = false
        withContext(Dispatchers.Main) {
            if (receiverRegistered) {
                service.unregisterReceiver(receiver)
                receiverRegistered = false
            }
            notification.close()
            binder.broadcast { callback ->
                callback.onServiceAlert(type.ordinal, message)
            }
            status.value = Status.Stopped
            notifyStopped()
            service.stopSelf()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("SameReturnValue")
    internal fun onStartCommand(): Int {
        // A destroyed instance is terminal (P0-A). Android can deliver onStartCommand
        // for a Service it is recreating, but that is a NEW instance - this one must
        // not restart, re-attach, re-register its receiver or re-post a notification.
        if (lifecycle.isClosed) {
            android.util.Log.i("InterstellarUI", "onStartCommand on a destroyed service instance; refusing")
            return Service.START_NOT_STICKY
        }
        when (status.value) {
            Status.Starting, Status.Started -> return Service.START_NOT_STICKY

            // still tearing down the previous run — run again right after
            Status.Stopping -> {
                pendingRestart = true
                return Service.START_NOT_STICKY
            }

            null, Status.Stopped -> Unit
        }
        status.value = Status.Starting

        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                service,
                receiver,
                IntentFilter().apply {
                    // Only service control lives here. The device axis (screen /
                    // user-present / trim) is reported through PlatformFacts to the
                    // core's shared policy — keeping a second Doze-driven
                    // pause()/wake() writer here would fight that policy.
                    addAction(Action.SERVICE_CLOSE)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }

        GlobalScope.launch(Dispatchers.IO) {
            val started = try {
                startCore()
            } catch (e: Exception) {
                stopAndAlert(Alert.StartCommandServer, e.message)
                return@launch
            }
            // P0-D: a superseded start is terminal. Continuing would apply a config to a
            // core that was never published, show "Started" for a service that is going
            // away, and re-post a notification from a dead generation.
            if (started != CoreStartResult.PUBLISHED) return@launch
            // A stop that landed while startCore() was in flight must win. The check
            // below is not enough on its own: startCore() has already assigned `core`
            // and attached the fact bridge by now, so simply returning here would
            // leave a running core, an attached bridge, a log stream and a command
            // socket behind a service that was already told to stop. releaseCore() is
            // idempotent and mutex-serialized, so it also covers the ordering where
            // the stop path's own release ran before this assignment.
            if (status.value != Status.Starting) {
                releaseCore()
                return@launch
            }
            startService()
        }
        return Service.START_NOT_STICKY
    }

    internal fun onBind(): IBinder = binder

    @OptIn(DelicateCoroutinesApi::class)
    internal fun onDestroy() {
        binder.close()
        // Terminal, and deliberately UNCONDITIONAL. The old guard below only tidied up
        // when `core`/`factSession`/`fileDescriptor` were already set - but the window
        // that matters is exactly the one where all three are still null, with
        // startCore() sitting inside created.startup(). Closing the lifecycle here
        // invalidates that attempt and refuses every later one, so it cannot publish a
        // core behind a destroyed Service (P0-A).
        lifecycle.close()
        if (receiverRegistered) {
            runCatching { service.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        runCatching { notification.close() }
        // Also unconditional: releaseCore() is idempotent, mutex-serialized, and only
        // touches fields this instance owns. It cannot damage a successor, because the
        // process-global DefaultNetworkMonitor is now released by owner key rather than
        // by the singleton (see its stop()).
        // GlobalScope is deliberate rather than incidental: every Android-owned scope
        // may already be dying with this Service, and the native teardown (fact drain,
        // then CommandServer shutdown) must still reach completion.
        GlobalScope.launch(Dispatchers.IO) { releaseCore() }
    }

    internal fun onRevoke() {
        stopService()
    }

    internal fun sendNotification(notification: Notification) {
        val channel = "notification-${notification.typeID}"
        val builder =
            NotificationCompat.Builder(service, channel).setShowWhen(false)
                .setContentTitle(notification.title).setContentText(notification.body)
                .setOnlyAlertOnce(true).setSmallIcon(R.drawable.ic_stat)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
        if (!notification.subtitle.isNullOrBlank()) {
            builder.setContentInfo(notification.subtitle)
        }
        if (!notification.openURL.isNullOrBlank()) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(service, MainActivity::class.java).apply {
                        setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        data = Uri.parse(notification.openURL)
                    },
                    ServiceNotification.flags,
                ),
            )
        }
        GlobalScope.launch(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                InterstellarApplication.notification.createNotificationChannel(
                    NotificationChannel(
                        channel,
                        notification.typeName,
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            InterstellarApplication.notification.notify(notification.identifier, notification.typeID, builder.build())
        }
    }

    internal fun cancelNotification(identifier: String, typeID: Int) {
        GlobalScope.launch(Dispatchers.Main) {
            InterstellarApplication.notification.cancel(identifier, typeID)
        }
    }
}

/** Outcome of one core start attempt. See `BoxService.startCore`. */
internal enum class CoreStartResult {
    /** The core was published and the caller may proceed to `startService()`. */
    PUBLISHED,

    /** A stop or destroy won the race; nothing was published and the caller must stop. */
    SUPERSEDED,
}

/**
 * Ownership gate for "which core generation may be published".
 *
 * The hazard it removes is a released core being written back by a start that was
 * already in flight:
 *
 *   start: beginStart() ──► startup() (slow) ─┐
 *   stop:  invalidate() ──► releaseCore()     │  publish() now refused
 *                                             ┘
 *
 * Pure Kotlin, no Android and no native types, so every interleaving above is
 * unit-testable without a device.
 */
internal class CoreLifecycle {

    private val lock = Any()
    private var generation = 0L
    private var published: Long? = null
    private var closed = false

    /**
     * Begin a start attempt, or **null** when this owner is already closed.
     *
     * Refusing here — before any native object is created — is what keeps a destroyed
     * Service from building a core it could later publish. Checking a flag after
     * `startup()` would still leave the CommandServer and the fact bridge alive.
     */
    fun beginStart(): Long? = synchronized(lock) {
        if (closed) return null
        ++generation
    }

    /** Invalidate in-flight attempts without closing: a normal stop may still restart. */
    fun invalidate() = synchronized(lock) { ++generation }

    /**
     * Terminal. A destroyed Service instance must never start, restart, re-attach or
     * re-post a notification, so this both invalidates what is in flight and refuses
     * everything that follows.
     */
    fun close() = synchronized(lock) {
        // Two halves, each load-bearing and each separately tested:
        //  - the generation bump invalidates every attempt already in flight, so a
        //    start() that is inside startup() can no longer publish;
        //  - the closed flag refuses every attempt that has not begun yet.
        // Removing either one re-opens a distinct hole, so neither is decoration.
        closed = true
        ++generation
    }

    /** True once [close] ran. Distinct from [isPublished]: both can be true at once. */
    val isClosed: Boolean get() = synchronized(lock) { closed }

    /**
     * Run [block] only if [attempt] is still the youngest generation.
     *
     * [block] runs inside the critical section on purpose: publishing the core and
     * attaching its fact bridge must be one indivisible step, so no observer can see
     * a core without its bridge (or the reverse).
     */
    fun publish(attempt: Long, block: () -> Unit): Boolean = synchronized(lock) {
        // Only the generation is consulted here, and that is sufficient by
        // construction: [close] bumps the generation, so every attempt taken before a
        // destroy is stale by the time it tries to publish, and [beginStart] refuses to
        // issue an attempt after one. A `closed` test here would be unreachable code
        // pretending to be the protection - the generation bump is the protection, and
        // the test suite injects its removal to prove that.
        if (attempt != generation) return false
        block()
        published = attempt
        true
    }

    /** Forget [attempt] after a failed startup, without publishing anything. */
    fun abandon(attempt: Long) {
        synchronized(lock) {
            if (published == attempt) published = null
        }
    }

    /** True while some generation is published. */
    val isPublished: Boolean get() = synchronized(lock) { published != null }
}
