package com.interstellar.proxy.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.interstellar.proxy.utils.CommandTarget
import java.util.concurrent.TimeUnit

/**
 * Periodic subscription auto-update. Re-schedules itself whenever settings
 * change so the interval always reflects the user's choice.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!Settings.autoUpdateEnabled) return Result.success()
        val messages = SubscriptionRepository.refreshAll()
        // core hot-reload picks up the regenerated active config
        if (messages.isNotEmpty() && Settings.tileActive) {
            runCatching { CommandTarget.standaloneClient().serviceReload() }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "subscription_auto_update"

        /** Re-schedules the periodic work with the current settings. */
        fun reschedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            if (!Settings.autoUpdateEnabled) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }
            val hours = Settings.autoUpdateIntervalHours.coerceIn(1, 24)
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
