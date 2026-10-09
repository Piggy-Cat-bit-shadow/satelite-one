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

        /**
         * A refused publish is retried at most this many times.
         *
         * Each retry takes a fresh token, so it only helps when the refusal came from a
         * teardown that has since finished. Three is enough for the realistic case (a
         * stop's release overlapping the start) while keeping a pathological teardown from
         * spinning.
         */
        private const val MAX_START_SUPERSEDED_RETRIES = 3

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

    // `pendingRestart` used to live here as a @Volatile Boolean. It is gone on purpose:
    // a flag that a stale stop could clear (`pendingRestart = false` on the way out) had
    // to encode BOTH "a start was requested during teardown" and "whose request is it".
    // The lifecycle's start-attempt counter now carries both facts directly - a start
    // intent is claimed as a real attempt at the moment it is accepted, and
    // `startsSince(watermark)` answers "did a newer request arrive" without a second,
    // separately-mutable piece of state that another generation can clobber.
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

    /**
     * Build and publish the core for an attempt token that was **already claimed**.
     *
     * The token is claimed by [onStartCommand] on the main thread, deliberately not here.
     * Claiming it on this IO coroutine left a window the fifth round did not close: the
     * main thread had already written `Status.Starting`, but no new *start attempt* was
     * visible to the lifecycle yet — so a stop that was mid-release saw "no newer start"
     * and converged (Stopped + `stopSelf()`) onto a service the user had just started.
     * Taking the token at the request's entry point is what makes "the start request
     * owns the service" true from the moment the request is accepted.
     */
    private suspend fun startCore(attempt: Long): CoreStartResult {
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
        // The teardown is now in flight. `beginStop`/`endStop` bracket the window in which
        // `core`/`factSession`/`fileDescriptor` are dirty: a successor may hold a valid
        // token throughout, but it will not publish into those fields until this ends.
        //
        // `invalidate()` still runs, because a start requested *before* the stop must not
        // publish a core the stop is about to release. What it no longer does is decide the
        // successor's fate: `endStop` pins `stopStaleUpTo` to everything that was alive
        // here, so a start accepted after this point keeps its token instead of being
        // stranded (round-6 R7-P0).
        lifecycle.beginStop()
        try {
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
        } finally {
            // Always released, including when a step above throws: leaving the teardown
            // marked in-flight would block every future publish for the process lifetime.
            lifecycle.endStop()
        }
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

    /**
     * Stop the service and clear everything that belongs to this Service object.
     *
     * ## Why the notification and the receiver are closed HERE and not in a finalizer
     *
     * Round 6 found that `stopService` closed both *before* `releaseCore()`, on the
     * caller's thread. That is `unregisterReceiver` + `notification.close()` for a
     * generation that might already have been superseded — and both touch process-wide
     * Android state (the FGS notification slot and the registration), so a dying
     * generation could darken a live successor.
     *
     * The fix moved them behind the ownership check in the finalizer, which is correct
     * but relies on an invariant worth writing down: **every Service instance destroys
     * itself through here or through `stopAndAlert`, and an instance whose stop is
     * superseded is still destroyed by the successor's own stop later.** The receiver is
     * registered once per instance and the notification is a singleton slot, so there is
     * nothing a superseded stop leaves behind that the surviving generation does not
     * still own.
     *
     * A direct consequence also worth stating: because the notification is only closed
     * at convergence, the 5 s timeouts inside `PlatformFacts.detachAndDrain` (up to two
     * of them) delay user-visible feedback on a slow teardown. That is a real,
     * bounded cost — a user pressing Stop while a fact call is wedged waits ~10 s before
     * the notification disappears — and it is preferable to the alternative. The other
     * direction would close the notification while a core is still alive, which is the
     * "UI says disconnected but the tunnel is up" failure this class exists to prevent.
     * An emulator run measured the normal path at well under a second.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun stopService() {
        val current = status.value
        if (current == Status.Stopped || current == Status.Stopping) return
        val stopWatermark = lifecycle.currentStartAttempt()
        status.value = Status.Stopping
        notifyStopped()
        GlobalScope.launch(Dispatchers.IO) {
            // Facts stop flowing to the core before its teardown starts; the Android
            // sources themselves are process-scoped and stay installed.
            releaseCore()
            withContext(Dispatchers.Main) {
                // ONE decision, then all of the convergence, or none of it.
                //
                // Checking ownership *between* `unregisterReceiver`, `notification.close()`
                // and `status = Stopped` is not enough: the first two are themselves harm
                // to a successor. `notification` and `receiver` are per-BoxService objects,
                // but the foreground notification slot and the registration are shared
                // process-wide state — closing them after the user already started a new
                // generation removes the notification that generation just posted and
                // deregisters the receiver it is relying on, and `stopSelf()` then tears
                // the whole foreground service down underneath a live core.
                if (lifecycle.startsSince(stopWatermark)) {
                    android.util.Log.i(
                        "InterstellarUI",
                        "stop superseded by a newer start; withholding every convergence effect",
                    )
                    return@withContext
                }
                if (receiverRegistered) {
                    service.unregisterReceiver(receiver)
                    receiverRegistered = false
                }
                notification.close()
                status.value = Status.Stopped
                service.stopSelf()
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
        withContext(Dispatchers.Main) {
            // The alert is the ONE thing whose meaning does not depend on who owns the
            // service: the user must still learn that the core failed, and the failure
            // realmente happened. It runs before the ownership check on purpose, and it
            // has no side effect on the service's resources.
            binder.broadcast { callback ->
                callback.onServiceAlert(type.ordinal, message)
            }
            // Convergence is withheld wholesale when a newer generation owns the
            // service. Reporting the alert is not the same as acting as the owner, and
            // the previous shape did the acting first (unregister + close) and only then
            // asked whether it was still allowed to - which is how an old failure could
            // darken a healthy successor's notification and receiver.
            if (lifecycle.startsSince(stopWatermark)) {
                android.util.Log.i(
                    "InterstellarUI",
                    "alert raised by a superseded generation; reported without converging",
                )
                return@withContext
            }
            if (receiverRegistered) {
                service.unregisterReceiver(receiver)
                receiverRegistered = false
            }
            notification.close()
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

            // Still tearing down the previous run. The restart is not queued behind a
            // boolean here: the intent is claimed as a real start attempt BELOW, on this
            // same (main) thread, so the teardown that is in flight can see it and
            // withhold its own convergence instead of killing what the user just started.
            Status.Stopping -> Unit

            null, Status.Stopped -> Unit
        }

        // Claim the attempt HERE, synchronously, before `Status.Starting` is observable.
        //
        // This is the linearization point the invariant needs: "a start request owns the
        // service" must be established at the moment the request is accepted, not later
        // on the IO coroutine that builds the core. The previous shape wrote
        // `Status.Starting` first and only took a token inside `startCore()`, so a stop
        // releasing concurrently still saw no newer start and converged on top of it.
        //
        // The claim is synchronous and does no native work, so it is safe on the main
        // thread: `CoreLifecycle`'s lock is held only for counter arithmetic (the one
        // exception, `publish`, is held by the IO thread and calls only
        // `PlatformFacts.attach`, which is a map write plus queue sends).
        val attempt = lifecycle.beginStart()
        if (attempt == null) {
            // Lost a race with onDestroy between the check above and the claim.
            android.util.Log.i("InterstellarUI", "service destroyed while claiming a start; refusing")
            return Service.START_NOT_STICKY
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
            // A start attempt can be refused for two different reasons, and they need
            // different responses:
            //
            //  * it was superseded by a *newer start* - terminal, because that newer
            //    attempt owns the service and is building its own core;
            //  * a teardown was in flight when it tried to publish - a *transient*
            //    refusal. The teardown has since finished, so re-running here is what
            //    stops the Service from sitting in `Status.Starting` with no core
            //    (round-6 R7-P0). It is bounded so a pathological teardown cannot loop.
            var current: Long = attempt
            var tries = 0
            while (true) {
                when (val started = startCore(current)) {
                    is CoreStartResult.Failed -> {
                        stopAndAlert(Alert.StartCommandServer, started.cause.message)
                        return@launch
                    }

                    CoreStartResult.Published -> break

                    CoreStartResult.Superseded -> {
                        tries++
                        if (tries > MAX_START_SUPERSEDED_RETRIES) return@launch
                        // Only retry a *transient* refusal. If a newer attempt or a stop has
                        // taken the service, this start is genuinely done - continuing would
                        // apply a config to a core that was never published, show "Started"
                        // for a service that is going away, and re-post a notification from a
                        // dead generation.
                        val retry = lifecycle.beginStart() ?: return@launch
                        if (lifecycle.isSupersededByNewerStart(retry)) return@launch
                        current = retry
                    }
                }
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
     * The id of the newest start attempt, and the watermark a stop is stamped with.
     *
     * This is the *only* ownership counter, and it is moved by nothing but [beginStart].
     * That single-writer property is what makes it answer three separate questions at
     * once, each of which used to need its own bookkeeping:
     *
     *  - "is this the newest start?" — `attempt == startAttempts`;
     *  - "has a start happened since my stop was accepted?" — [startsSince];
     *  - "which attempts were alive while this stop's slots were dirty?" — [endStop]
     *    pins its value at the moment the teardown finishes.
     *
     * Round 6 kept a second `generation` counter that also moved on a release, because
     * [publish] had to refuse an attempt that was inside `startup()` when the stop
     * released. That worked for the attempt being released and wrongly for a *successor*
     * as well, which is the round-7 defect; [stopStaleUpTo] covers the first case without
     * touching the second, so the extra counter is gone.
     */
    private var startAttempts = 0L

    private var published: Long? = null
    private var closed = false

    /**
     * Highest attempt token made stale by a *teardown*; attempts at or below it are refused.
     *
     * Pinned by [endStop] to everything alive while the slots were dirty. An attempt
     * accepted after that keeps its token.
     */
    private var stopStaleUpTo = 0L

    /** Count of teardowns in flight; a publish must not run while this is non-zero. */
    private var teardownsInFlight = 0

    /**
     * Begin a start attempt, or **null** when this owner is already closed.
     *
     * Refusing here — before any native object is created — is what keeps a destroyed
     * Service from building a core it could later publish. Checking a flag after
     * `startup()` would still leave the CommandServer and the fact bridge alive.
     */
    fun beginStart(): Long? = synchronized(lock) {
        if (closed) return null
        // One counter, one meaning: this number IS the attempt id. It moves only here,
        // which is what makes `attempt == startAttempts` mean "is the newest start" and
        // what lets a stop's watermark ([currentStartAttempt]) be compared against it.
        //
        // A separate generation counter used to supply the token. It had to move on a
        // release as well - so that an attempt still inside `startup()` could not publish
        // into slots the release was clearing - and that is exactly what made a legitimate
        // successor's token stale too: the round-7 R7-P0 defect. [stopStaleUpTo] now states
        // that rule precisely, so the second counter earns nothing and is gone.
        ++startAttempts
        startAttempts
    }

    /**
     * A stop is releasing the published core.
     *
     * It does **not** decide any attempt's fate: that is [endStop]'s job, which pins the
     * stale boundary to everything alive while the slots were dirty. Keeping the two apart
     * is the whole round-7 fix - a release that also invalidated newer attempts is what
     * stranded a legitimate successor in `Status.Starting`.
     */
    fun invalidate() = synchronized(lock) {
        // "is anything published" must stop being true here. Leaving it set made
        // isPublished claim a live core after the core had already been shut down - an
        // assertion that tests then trusted.
        published = null
    }

    /**
     * A teardown begins; the stop accepts responsibility for everything alive right now.
     *
     * Paired with [endStop]. Between them, an attempt may still *run*, but it must not
     * publish into the `core`/`factSession`/`fileDescriptor` slots the teardown is
     * clearing — see [publish].
     */
    fun beginStop() = synchronized(lock) {
        teardownsInFlight++
    }

    /**
     * The teardown finished; everything that was alive while the slots were dirty is stale.
     *
     * ## Why the stale boundary is pinned here and not in [beginStop]
     *
     * `invalidate()` was unconditional. That closed "a start requested *before* the stop
     * publishes into slots being released", but it also made a start accepted **after**
     * the stop stale — the round-6 stranding bug, where the successor's `publish` was
     * refused by a release that had nothing to do with it and the Service sat in
     * `Status.Starting` with no core behind it.
     *
     * The distinction that matters is not "before or after the stop" but **"before or
     * after the slots became clean"**:
     *
     *  - an attempt alive while the teardown ran is refused, because publishing would put
     *    two cores in the same fields;
     *  - an attempt accepted after this point keeps its token, because by then there is
     *    nothing left to collide with.
     *
     * A refusal is *reported* ([publish] returns false), which is what makes it safe: the
     * Service re-runs its own start path instead of silently evaporating.
     */
    fun endStop() = synchronized(lock) {
        stopStaleUpTo = startAttempts
        if (teardownsInFlight > 0) teardownsInFlight--
        (lock as Object).notifyAll()
    }

    /** True while a teardown is in flight. Exposed so tests can assert the ordering. */
    internal val isTearingDown: Boolean get() = synchronized(lock) { teardownsInFlight > 0 }

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
     * Only [beginStart] moves this, which is what makes the answer mean "someone newer
     * owns the service now". A release deliberately does not: if it did, every stop would
     * look superseded by itself and convergence could never be reached.
     */
    fun startsSince(watermark: Long): Boolean = synchronized(lock) {
        closed || startAttempts != watermark
    }

    companion object {
        /**
         * How long a publish waits for an in-flight teardown before giving up.
         *
         * A teardown's own budget is two 5 s fact-drain waits plus the native close, so
         * 12 s covers a slow but healthy stop. Giving up returns false, and the caller
         * re-runs its start path — waiting forever would hold a core's `startup()` result
         * in limbo with no way to report it.
         */
        private const val PUBLISH_TEARDOWN_WAIT_MS = 12_000L
    }

    /**
     * Terminal. A destroyed Service instance must never start, restart, re-attach or
     * re-post a notification, so this both invalidates what is in flight and refuses
     * everything that follows.
     */
    fun close() = synchronized(lock) {
        // One flag covers both halves, and `ownsServiceLocked` consults it first:
        //  - an attempt already inside startup() is refused at publish, because `closed`
        //    is checked there too;
        //  - an attempt that has not begun is refused at beginStart, before any native
        //    object exists.
        // The round-6 version also bumped a generation counter here. That was a second
        // way of saying the same thing, and the flag is the one that also covers the
        // not-yet-begun case, so it is the one that stayed.
        closed = true
        published = null
    }

    /** True once [close] ran. Distinct from [isPublished]: both can be true at once. */
    val isClosed: Boolean get() = synchronized(lock) { closed }

    /**
     * True while [attempt] is still the live generation of an open owner.
     *
     * Used to decide whether a failure is still ours to report: once a stop's teardown has
     * covered this attempt, or a newer start has taken the service, it must stay silent
     * rather than drive the service to Stopped.
     */
    fun isCurrent(attempt: Long): Boolean = synchronized(lock) { ownsServiceLocked(attempt) }

    /**
     * True when [attempt] lost to a **newer start attempt** specifically.
     *
     * The distinction from [isCurrent] is what makes the successor's retry loop safe
     * rather than a spin: a refusal caused by a *teardown* is transient and worth
     * re-running, while a refusal caused by a newer attempt means that attempt owns the
     * service and this one must stay silent and stop.
     */
    fun isSupersededByNewerStart(attempt: Long): Boolean = synchronized(lock) {
        !closed && attempt != startAttempts
    }

    /**
     * Won by [attempt] only if it still owns the service **and** no teardown is in flight.
     *
     * ## Why two conditions and not one
     *
     * `published` and the `core`/`factSession`/`fileDescriptor` fields are single slots. Two
     * things can make an attempt unfit to fill them:
     *
     *  1. it is stale — a newer start superseded it, or a stop's teardown has already
     *     invalidated everything that was alive while the slots were dirty
     *     ([stopStaleUpTo], plus the newest-start equality in [ownsServiceLocked]);
     *  2. a teardown is *right now* clearing those slots, in which case publishing would
     *     leave two cores sharing one set of fields — one of them unreachable and never
     *     released.
     *
     * The round-5/6 shape collapsed these into "did the generation move", which refused a
     * legitimate successor whenever an unrelated release happened to run — stranding the
     * Service in `Status.Starting` with no core. Splitting them lets the successor keep its
     * token and simply **wait** for the teardown to finish.
     *
     * ## The wait is condition-based and re-checks staleness
     *
     * Waits are bounded and re-validate after every wakeup, so a teardown that ends cannot
     * let a meanwhile-superseded attempt through. [block] runs inside the critical section
     * on purpose: publishing the core and attaching its fact bridge must be one indivisible
     * step, so no observer can see a core without its bridge (or the reverse).
     *
     * @return true when [block] ran and the attempt is now the published one.
     */
    fun publish(attempt: Long, block: () -> Unit): Boolean {
        val monitor = lock as Object
        synchronized(lock) {
            if (!ownsServiceLocked(attempt)) return false
            val deadline = System.currentTimeMillis() + PUBLISH_TEARDOWN_WAIT_MS
            while (teardownsInFlight > 0) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return false
                // `wait` releases the lock, so ownership is re-checked on every wakeup: an
                // attempt that was superseded while it waited must not publish on the
                // strength of its original check.
                monitor.wait(remaining)
                if (!ownsServiceLocked(attempt)) return false
            }
            // Ownership is re-checked HERE, immediately before the write, and the lock is
            // held across both. A check-then-act pair with the lock dropped in between is
            // the classic shape this whole change exists to remove: an attempt could pass
            // the check, be superseded, and then write its core into a slot that now
            // belongs to someone else - one core reachable, one orphaned and never
            // released. `block` runs under the lock on purpose, exactly as before.
            if (!ownsServiceLocked(attempt)) return false
            block()
            published = attempt
            return true
        }
    }

    /**
     * True when [attempt] may fill the core slots if no teardown is clearing them.
     *
     * ## Why this needs BOTH counters, and why each has the shape it has
     *
     * `published` and the `core`/`factSession`/`fileDescriptor` fields are single slots, and
     * exactly one start attempt may fill them. Three separate events can take that right
     * away, and each is checked by the counter that actually moves for it:
     *
     *  1. **a newer start** — only [beginStart] moves [startAttempts], so
     *     `attempt == startAttempts` is the newest-start test. It has to be an *equality*,
     *     not "newer than some boundary": with a boundary, take token 2, publish it, then
     *     take token 3 — 3 > 2, so both publish and the first core is silently replaced by
     *     a second one nobody released.
     *  2. **a stop's teardown** — [endStop] pins [stopStaleUpTo] to everything alive while
     *     the slots were dirty, so `attempt > stopStaleUpTo` refuses those.
     *  3. **a destroy** — `closed`, checked first, refuses everything.
     *
     * A *release* is deliberately absent from this list. It moves no counter, because the
     * only attempts it may refuse are ones the teardown itself covered (2). The round-5/6
     * shape tested a counter that a release moved, so any release also refused a
     * legitimate successor and stranded the Service in `Status.Starting` with no core —
     * the round-7 R7-P0 defect. Testing (2) instead lets the successor keep its token and
     * simply **wait** for the teardown.
     *
     * Caller must hold [lock].
     */
    private fun ownsServiceLocked(attempt: Long): Boolean =
        !closed &&
            attempt == startAttempts &&
            attempt > stopStaleUpTo

    /** Forget [attempt] after a failed startup, without publishing anything. */
    fun abandon(attempt: Long) {
        synchronized(lock) {
            if (published == attempt) published = null
        }
    }

    /** True while some generation is published. */
    val isPublished: Boolean get() = synchronized(lock) { published != null }
}
