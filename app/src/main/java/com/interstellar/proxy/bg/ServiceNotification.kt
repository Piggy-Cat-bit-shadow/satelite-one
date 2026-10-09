package com.interstellar.proxy.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.MutableLiveData
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.StatusMessage
import com.interstellar.proxy.MainActivity
import com.interstellar.proxy.R
import com.interstellar.proxy.InterstellarApplication
import com.interstellar.proxy.constant.Action
import com.interstellar.proxy.constant.Status
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.utils.CommandClient
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

class ServiceNotification(private val status: MutableLiveData<Status>, private val service: Service) :
    BroadcastReceiver(),
    CommandClient.Handler {
    companion object {
        private const val notificationId = 1
        private const val notificationChannel = "service"
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        fun checkPermission(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return true
            }
            return InterstellarApplication.notification.areNotificationsEnabled()
        }
    }

    /**
     * One generation's status client and the scope that owns its connect coroutine.
     *
     * The scope is cancelled by [close] and is **never revived**: a cancelled
     * CoroutineScope cannot be restarted, so reusing the same instance after a close
     * would leave `connect()` launching into a dead scope and the dynamic traffic
     * notification silently dead. A new generation therefore always gets a new pair.
     */
    private class Session(val scope: CoroutineScope, val client: CommandClient)

    private var receiverRegistered = false

    /** Serializes [registerReceiver] against [unregisterReceiverIfNeeded]. */
    private val receiverLock = Any()

    /** Guards session creation/teardown. Never nested inside [receiverLock]. */
    private val lifecycleLock = Any()

    /**
     * The publish/terminate order for this notification, extracted so it is provable in
     * a plain JVM test (see NotificationPublishGateTest).
     */
    private val gate = NotificationPublishGate()

    /** Current generation token; every publish and the receiver path consult the gate. */
    @Volatile
    private var generation = 0

    private var session: Session? = null

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(service, notificationChannel).setShowWhen(false).setOngoing(true)
            .setContentTitle(service.getString(R.string.app_name)).setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_stat)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(service, MainActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    flags,
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_LOW).apply {
                addAction(
                    NotificationCompat.Action.Builder(
                        0,
                        service.getText(R.string.stop),
                        PendingIntent.getBroadcast(
                            service,
                            0,
                            Intent(Action.SERVICE_CLOSE).setPackage(service.packageName),
                            flags,
                        ),
                    ).build(),
                )
            }
    }

    /** Last static title/text from [show]; restored when statistics are unavailable. */
    private var staticTitle: String? = null
    private var staticText: String? = null

    /** True while the notification currently carries a live traffic line. */
    private var showingTraffic = false

    fun show(profileName: String, @StringRes contentTextId: Int) {
        synchronized(lifecycleLock) {
            generation = gate.open()
            // A restart (pendingRestart Stop→Start) reuses this instance after close()
            // cancelled the previous scope, so a fresh session is mandatory here.
            if (session == null) session = newSession()
            showingTraffic = false
        }
        staticTitle = profileName.takeIf { it.isNotBlank() } ?: service.getString(R.string.app_name)
        staticText = service.getString(contentTextId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // re-creating with the same id updates the stored channel name on
            // language switches
            InterstellarApplication.notification.createNotificationChannel(
                NotificationChannel(
                    notificationChannel,
                    service.getString(R.string.channel_service),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        // On API 34+ the type must be passed explicitly (the 2-arg overload
        // leaves the service type-less — Android 16 then tears the FGS down
        // shortly after start, silently stopping the core)
        val fgsType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching {
                    service.packageManager.getServiceInfo(
                        android.content.ComponentName(service, service.javaClass),
                        0,
                    ).foregroundServiceType
                }.getOrDefault(0)
            } else {
                0
            }
        ServiceCompat.startForeground(
            service,
            notificationId,
            notificationBuilder
                .setContentTitle(profileName.takeIf { it.isNotBlank() } ?: service.getString(R.string.app_name))
                .setContentText(service.getString(contentTextId)).build(),
            fgsType,
        )
    }

    /** The live generation's client. Must be called before [close] invalidates it. */
    private fun currentClient(): CommandClient? = synchronized(lifecycleLock) {
        if (!gate.isOpen(generation)) return null
        session?.client ?: newSession().let { created ->
            // Store the Session (scope + client), not the client alone: the scope is what
            // close() must cancel, and keeping only the client would leak the scope.
            session = created
            created.client
        }
    }

    private fun newSession(): Session {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return Session(
            scope,
            CommandClient(scope, CommandClient.ConnectionType.Status, this, localOnly = true),
        )
    }

    suspend fun start() {
        if (Settings.dynamicNotification && checkPermission()) {
            currentClient()?.connect() ?: return
            withContext(Dispatchers.Main) {
                registerReceiver()
            }
        }
    }

    /**
     * Registration and unregistration share one lock.
     *
     * `start()` hops to the main thread to register while `close()` may already be
     * running; checking the generation alone is not enough, because the check and the
     * `registerReceiver` call are two separate steps and a `close()` landing between
     * them would leave a receiver installed that nothing ever unregisters — it would
     * then keep waking this object on every screen change for the life of the
     * process. Holding the lock across check-and-register (and across
     * check-and-unregister, with the generation closed first) makes either order safe.
     */
    private fun registerReceiver() = synchronized(receiverLock) {
        // A closed generation must never install a receiver: nothing would ever
        // unregister it, so it would keep waking this object for the process lifetime.
        if (!gate.isOpen(generation) || receiverRegistered) return
        service.registerReceiver(
            this,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        receiverRegistered = true
    }

    private fun unregisterReceiverIfNeeded() = synchronized(receiverLock) {
        if (!receiverRegistered) return
        service.unregisterReceiver(this)
        receiverRegistered = false
    }

    override fun updateStatus(status: StatusMessage) {
        if (!status.trafficAvailable) {
            // No kernel traffic manager for this config (a raw JSON that never
            // enables one). Reposting "0 B/s ↑ 0 B/s ↓" every second would advertise
            // a measurement that does not exist, so fall back to the static line.
            restoreStaticContent()
            return
        }
        updateTraffic(status.uplink, status.downlink)
    }

    /**
     * A kernel without a traffic manager has no rates at all; reposting "0 B/s ↑
     * 0 B/s ↓" every second would advertise a measurement that does not exist.
     * Falls back to the static line exactly once per transition.
     */
    private fun restoreStaticContent() {
        if (!Settings.dynamicNotification || !checkPermission()) return
        val title = staticTitle ?: return
        val text = staticText ?: return
        gate.publish(generation) {
            if (!showingTraffic) return@publish
            showingTraffic = false
            InterstellarApplication.notificationManager.notify(
                notificationId,
                notificationBuilder.setContentTitle(title).setContentText(text).build(),
            )
        }
    }

    /** Engine-agnostic traffic line. */
    fun updateTraffic(upPerSecond: Long, downPerSecond: Long) {
        if (!Settings.dynamicNotification || !checkPermission()) return
        val content =
            Libbox.formatBytes(upPerSecond) + "/s ↑\t" + Libbox.formatBytes(downPerSecond) + "/s ↓"
        // The gate makes the check and the post one indivisible step: close() flips the
        // generation inside the same lock, so this either posts entirely before close
        // (and close's own cancel removes it) or does nothing at all. That is what
        // removes the old window in which close could cancel between the check and the
        // post, leaving a notification that outlived the foreground service.
        gate.publish(generation) {
            showingTraffic = true
            InterstellarApplication.notificationManager.notify(
                notificationId,
                notificationBuilder.setContentText(content).build(),
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Bound to the live generation: after close() there is no client to drive, and
        // a late broadcast must not resurrect one on a cancelled scope.
        val client = synchronized(lifecycleLock) {
            if (gate.isOpen(generation)) session?.client else null
        } ?: return
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> client.connect()

            Intent.ACTION_SCREEN_OFF -> client.disconnect()
        }
    }

    fun close() {
        // Closing the generation first is what makes "no publish after close" true:
        // every publish consults this same gate.
        gate.close()
        val closing = synchronized(lifecycleLock) {
            val current = session
            // Drop the session before releasing the lock: `currentClient()` can no longer
            // hand this dead session out, and a later show() builds a new one.
            session = null
            current
        }
        // Past this point no publish can slip through: every publish consults the gate
        // closed above.
        closing?.let { runCatching { it.client.disconnect() } }
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // A publish may have completed just before the generation closed; cancel
        // explicitly so nothing from this generation survives service death.
        InterstellarApplication.notificationManager.cancel(notificationId)
        unregisterReceiverIfNeeded()
        // Last, and never before the teardown above: CommandClient.disconnect() runs
        // its native cleanup on an independent scope, so cancelling this one cannot
        // leave the disconnect half-done.
        closing?.scope?.cancel()
    }
}

/**
 * Generation gate for the service notification's publish/terminate order.
 *
 * Pure — no Android, no NotificationManager — so the ordering the previous
 * "check `@Volatile released`, then `notify`" could not prove is directly testable:
 *
 *  - [publish] runs its block only while the *specific* generation it was given is
 *    still open, and it holds the lock for the whole check-and-post;
 *  - [close] flips that state inside the same lock.
 *
 * So a publish either completes entirely before close (and the caller's
 * `NotificationManager.cancel` then removes what it posted), or it observes a closed
 * generation and posts nothing. No interleaving leaves a stale notification behind
 * after close returns — the window the old two-step check had.
 */
internal class NotificationPublishGate {

    private val lock = Any()
    private var generation = 0
    private var open = false

    /** Begin a generation and return its token. */
    fun open(): Int = synchronized(lock) {
        open = true
        ++generation
        generation
    }

    /** True while [candidate] is the open generation. */
    fun isOpen(candidate: Int): Boolean = synchronized(lock) { open && candidate == generation }

    /**
     * Run [block] only if [candidate] is still the open generation.
     *
     * @return true when the block ran.
     */
    fun publish(candidate: Int, block: () -> Unit): Boolean = synchronized(lock) {
        if (!open || candidate != generation) return false
        block()
        true
    }

    /** Close the current generation. Returns its token, or null when already closed. */
    fun close(): Int? = synchronized(lock) {
        if (!open) return null
        open = false
        val closed = generation
        ++generation
        closed
    }
}
