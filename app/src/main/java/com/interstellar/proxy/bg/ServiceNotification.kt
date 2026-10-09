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
     * Owns the notification's status client. Cancelled in [close] so a connect
     * coroutine started here cannot outlive the notification object.
     *
     * Cancelling this scope does NOT lose the native teardown: CommandClient's
     * own disconnect runs on its independent cleanup scope precisely because the
     * caller's scope may already be gone.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val commandClient =
        CommandClient(scope, CommandClient.ConnectionType.Status, this, localOnly = true)
    private var receiverRegistered = false

    /** Serializes [registerReceiver] against [unregisterReceiverIfNeeded]. */
    private val receiverLock = Any()

    /**
     * Set by close(): the service notification is gone. A late traffic
     * callback (a race with core shutdown — the traffic job is cancelled
     * only after close) must not re-post it via NotificationManager.notify —
     * that re-posted notification is no longer bound to the foreground
     * service and survives stopSelf() as a stale "still connected" one.
     */
    @Volatile
    private var released = false

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
        released = false
        staticTitle = profileName.takeIf { it.isNotBlank() } ?: service.getString(R.string.app_name)
        staticText = service.getString(contentTextId)
        showingTraffic = false
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

    suspend fun start() {
        if (Settings.dynamicNotification && checkPermission()) {
            commandClient.connect()
            withContext(Dispatchers.Main) {
                registerReceiver()
            }
        }
    }

    /**
     * Registration and unregistration share one lock.
     *
     * `start()` hops to the main thread to register while `close()` may already be
     * running; checking `released` alone is not enough, because the check and the
     * `registerReceiver` call are two separate steps and a `close()` landing between
     * them would leave a receiver installed that nothing ever unregisters — it would
     * then keep waking this object on every screen change for the life of the
     * process. Holding the lock across check-and-register (and across
     * check-and-unregister, with `released` set first) makes either order safe.
     */
    private fun registerReceiver() = synchronized(receiverLock) {
        if (released || receiverRegistered) return
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
        if (!showingTraffic) return
        showingTraffic = false
        if (released || !Settings.dynamicNotification || !checkPermission()) return
        val title = staticTitle ?: return
        val text = staticText ?: return
        InterstellarApplication.notificationManager.notify(
            notificationId,
            notificationBuilder.setContentTitle(title).setContentText(text).build(),
        )
    }

    /** Engine-agnostic traffic line. */
    fun updateTraffic(upPerSecond: Long, downPerSecond: Long) {
        if (released || !Settings.dynamicNotification || !checkPermission()) return
        showingTraffic = true
        val content =
            Libbox.formatBytes(upPerSecond) + "/s ↑\t" + Libbox.formatBytes(downPerSecond) + "/s ↓"
        InterstellarApplication.notificationManager.notify(
            notificationId,
            notificationBuilder.setContentText(content).build(),
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> commandClient.connect()

            Intent.ACTION_SCREEN_OFF -> commandClient.disconnect()
        }
    }

    fun close() {
        released = true
        commandClient.disconnect()
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // a traffic update may have slipped in just before stopForeground ran;
        // cancel it explicitly — it would not be removed by service death
        InterstellarApplication.notificationManager.cancel(notificationId)
        unregisterReceiverIfNeeded()
        // After the teardown above, never before it. CommandClient.disconnect() runs
        // its native teardown on an independent cleanup scope, so cancelling this one
        // cannot leave the disconnect half-done.
        scope.cancel()
    }
}
