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
            ?: return CoreStartResult.Superseded.also {
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
            // The failure is only OURS to report while this attempt still owns the
            // service. A start that a stop (or a newer start) already superseded must
            // stay silent: reporting it would let a dead generation stop a live one.
            return if (lifecycle.isCurrent(attempt)) {
                CoreStartResult.Failed(e)
            } else {
                android.util.Log.i("InterstellarUI", "superseded start failed; not reporting it")
                CoreStartResult.Superseded
            }
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
            return CoreStartResult.Superseded
        }
        return CoreStartResult.Published
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
        var drained = session == null || runCatching { PlatformFacts.detachAndDrain(session) }
            .onFailure { android.util.Log.w("InterstellarUI", "fact bridge drain failed", it) }
            .getOrDefault(false)
        if (!drained && session != null) {
            // A 5s wait bound is a bound, NOT a proof of safety: an undrained lane means a
            // fact call may still be executing on that session's PlatformEvents. Retry
            // once before making the risky call below - a lane that was merely slow (a
            // long GC pause, a device under memory pressure) usually drains on the second
            // attempt, and both retries are cheap compared with the alternatives.
            android.util.Log.w("InterstellarUI", "fact drain unproven; retrying before release")
            drained = runCatching { PlatformFacts.detachAndDrain(session) }
                .onFailure { android.util.Log.w("InterstellarUI", "fact bridge drain retry failed", it) }
                .getOrDefault(false)
        }

        // 2) Only close the native bridge once the drain is proven. Unproven means a
        // fact call may still be executing on that object; leaving one unreachable Go
        // object behind is strictly better than a use-after-close.
        if (running != null) {
            if (drained) {
                runCatching { running.closePlatformEvents() }
            } else {
                // Deliberate tradeoff, and only after two bounded waits failed:
                //
                //  * the PlatformEvents object is NOT closed, because a fact call may
                //    still be executing *on it* - that is a use-after-close;
                //  * the CommandServer IS still shut down below. Leaving it running would
                //    keep the tunnel, the command socket and the Go goroutines alive with
                //    no owner left to stop them, i.e. a VPN the user cannot turn off while
                //    the UI reports "disconnected". That is the worse failure, so the
                //    server teardown proceeds and this line records exactly what was
                //    sacrificed (one unreachable Go bridge object) and why.
                android.util.Log.e(
                    "InterstellarUI",
                    "fact drain unproven after two bounded waits; leaving the platform-events " +
                        "bridge open and still shutting the server down (an unstoppable tunnel " +
                        "is worse than one leaked bridge object)",
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
        // Stamp this stop with the start generation it belongs to, BEFORE anything
        // can be released. `releaseCore()` bumps the lifecycle's generation, so a
        // watermark taken afterwards could never tell "my own release" apart from
        // "a newer start took the service".
        val stopWatermark = lifecycle.currentStartAttempt()
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
                // A start intent that arrived while this stop was tearing down may
                // already have moved the service on. Converging to Stopped here would
                // overwrite the new generation's status, and `stopSelf()` would tear
                // the foreground service down under a core that is already live and
                // whose notification this stop has just closed.
                if (lifecycle.startsSince(stopWatermark)) {
                    android.util.Log.i(
                        "InterstellarUI",
                        "stop superseded by a newer start; not converging to Stopped",
                    )
                    return@withContext
                }
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
        // Same watermark rule as stopService(): taken before the release, because the
        // release itself bumps the lifecycle generation.
        //
        // This path needs it more than the normal stop does: `stopAndAlert` is also
        // reachable from `serviceReload0()` while `status` is already `Stopped` (a
        // one-shot reload command client does not consult the Service's status), and
        // with `status == Stopped` nothing stops `onStartCommand` from taking a fresh
        // attempt during the release. See StopConvergenceTest for the interleaving.
        val stopWatermark = lifecycle.currentStartAttempt()
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
            // The alert itself is still reported — the user must learn that the core
            // failed. What is withheld is only the claim of ownership: a newer
            // generation may have started during the release, and writing Stopped over
            // it (plus stopSelf()) would kill a healthy service and leave its core
            // running with a closed notification.
            if (lifecycle.startsSince(stopWatermark)) {
                android.util.Log.i(
                    "InterstellarUI",
                    "alert raised by a superseded generation; reporting it without converging",
                )
                return@withContext
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
            // startCore() no longer throws for a startup failure: it reports one only
            // while the attempt still owns the service (see CoreStartResult.Failed), so a
            // superseded attempt cannot stop a newer generation.
            when (val started = startCore()) {
                is CoreStartResult.Failed -> {
                    stopAndAlert(Alert.StartCommandServer, started.cause.message)
                    return@launch
                }

                // A superseded start is terminal. Continuing would apply a config to a
                // core that was never published, show "Started" for a service that is
                // going away, and re-post a notification from a dead generation.
                CoreStartResult.Superseded -> return@launch

                CoreStartResult.Published -> Unit
            }
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
internal sealed interface CoreStartResult {
    /** The core was published and the caller may proceed to `startService()`. */
    object Published : CoreStartResult

    /**
     * A stop or destroy won the race, **or** this attempt failed after it had already
     * been superseded. Either way the caller must stop: it owns nothing to report.
     */
    object Superseded : CoreStartResult

    /**
     * This attempt failed while it still owned the service. Only then may the caller
     * turn the failure into a user-visible stop - a superseded attempt that throws must
     * not write "Stopped" over a newer, healthy generation.
     */
    data class Failed(val cause: Throwable) : CoreStartResult
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

    /**
     * Bumped by every ownership move: a new start attempt ([beginStart]), a release
     * ([invalidate]) or a destroy ([close]). Its only job is to make an *older*
     * attempt stale for [publish], which is why a release has to bump it too.
     */
    private var generation = 0L

    /**
     * The id of the newest start attempt, and the watermark a stop is stamped with.
     *
     * Separate from [generation] on purpose, and the separation is what closes the
     * stale-stop hole. [generation] moves on every ownership change — including a
     * release — because [publish] needs exactly that. A stop path, by contrast, must
     * be able to tell "my own release" apart from "a newer start took the service":
     * stamped with [currentStartAttempt] and asked through [startsSince], only
     * [beginStart] can answer it, and this counter is moved by nothing else.
     */
    private var startAttempts = 0L

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
        // The stop watermark moves only here: a *new start* is the one event that may
        // suppress a stop's convergence, and a release must never look like one.
        ++startAttempts
        // The attempt id stays the generation: `publish`/`isCurrent` compare against
        // the value that a release also moves.
        generation
    }

    /** Invalidate in-flight attempts without closing: a normal stop may still restart. */
    fun invalidate() = synchronized(lock) {
        ++generation
        // A stop releases the published core, so "is anything published" must stop being
        // true here. Leaving it set made isPublished claim a live core after the core had
        // already been shut down - an assertion that tests then trusted.
        published = null
    }

    /**
     * The watermark a stop path stamps itself with before it starts releasing.
     *
     * Exposed as a plain counter read so a finalizer can be written as
     * `startsSince(watermark)` instead of "check, then act" — the two-step shape is
     * exactly what let a stale stop overwrite a successor.
     */
    fun currentStartAttempt(): Long = synchronized(lock) { startAttempts }

    /**
     * True when a **new start attempt** has been taken since [watermark].
     *
     * Deliberately not `generation`: a release moves that on, so comparing against it
     * would make every stop look superseded by itself. Only [beginStart] moves this,
     * which is what makes the answer mean "someone newer owns the service now".
     */
    fun startsSince(watermark: Long): Boolean = synchronized(lock) {
        closed || startAttempts != watermark
    }

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
        published = null
    }

    /** True once [close] ran. Distinct from [isPublished]: both can be true at once. */
    val isClosed: Boolean get() = synchronized(lock) { closed }

    /**
     * True while [attempt] is still the live generation of an open owner.
     *
     * Used to decide whether a failure is still ours to report: once a stop or a newer
     * start has moved the generation on, this attempt must stay silent rather than
     * drive the service to Stopped.
     */
    fun isCurrent(attempt: Long): Boolean = synchronized(lock) { !closed && attempt == generation }

    /**
     * Run [block] only if [attempt] is still the youngest generation.
     *
     * [block] runs inside the critical section on purpose: publishing the core and
     * attaching its fact bridge must be one indivisible step, so no observer can see
     * a core without its bridge (or the reverse).
     */
    fun publish(attempt: Long, block: () -> Unit): Boolean = synchronized(lock) {
        // Only [generation] is consulted here, and that is sufficient by construction:
        // it moves on every ownership change - a release, a newer start and a destroy -
        // so any attempt older than the newest one is refused, and [beginStart] refuses
        // to issue an attempt at all after a destroy. A `closed` test here would be
        // unreachable code pretending to be the protection.
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
