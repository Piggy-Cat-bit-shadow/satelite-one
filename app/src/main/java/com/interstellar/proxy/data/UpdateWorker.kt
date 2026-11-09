package com.interstellar.proxy.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.interstellar.proxy.bg.BoxService
import com.interstellar.proxy.utils.CommandTarget
import java.util.concurrent.TimeUnit

/**
 * Periodic subscription auto-update. Re-schedules itself whenever settings
 * change so the interval always reflects the user's choice.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!Settings.autoUpdateEnabled) return Result.success()
        // The same instance identity a manual update uses: a scheduled run that outlives the profile
        // (or the Box) it was scheduled for must not reload or refresh a different instance, and a
        // batch it committed is only applied while its revision is still the current one.
        val start = instanceSnapshot()
        val activeBefore = ConfigStore.readActiveConfig()
        val messages = SubscriptionRepository.refreshAll(regenerate = false)
        var owns = SubscriptionUpdateCoordinator.stillOwns(
            start,
            instanceSnapshot(),
            ConfigStore.activeConfigRevision(),
        )
        android.util.Log.i(
            "InterstellarUI",
            "UpdateWorker: gen=${start.engineGeneration} messages=${messages.size} owns=$owns",
        )
        if (!owns) {
            android.util.Log.i("InterstellarUI", "UpdateWorker: result superseded by a newer instance")
            return Result.success()
        }
        if (messages.isEmpty() || !Settings.tileActive) return Result.success()
        // Commit the fetched bytes only after the ownership check, then decide whether the config
        // actually changed; only a changed config needs a hot reload.
        SubscriptionRepository.regenerateActiveConfig()
        val changed = SubscriptionUpdateCoordinator.configChanged(
            activeBefore,
            ConfigStore.readActiveConfig(),
        )
        val expectedRevision = ConfigStore.activeConfigRevision()
        owns = SubscriptionUpdateCoordinator.stillOwns(start, instanceSnapshot(), expectedRevision)
        if (!owns) {
            android.util.Log.i("InterstellarUI", "UpdateWorker: commit superseded; nothing applied")
            return Result.success()
        }
        if (changed) {
            val rpc = runCatching { CommandTarget.standaloneClient().serviceReload() }
            val verdict = BoxService.lastReload
            val applied = rpc.isSuccess && verdict != null &&
                verdict.configRevision == expectedRevision && verdict.success
            android.util.Log.i(
                "InterstellarUI",
                "UpdateWorker: reload rpc=${rpc.isSuccess} verdict=" +
                    (verdict?.let { "rev=${it.configRevision} success=${it.success} error=${it.error}" } ?: "none"),
            )
            if (!applied) {
                // The core refused the replacement and released the old Box; refreshing providers on
                // an instance that no longer exists would report a success for nothing.
                return Result.success()
            }
        }
        runCatching { CommandTarget.standaloneClient().refreshRemoteProviders("", 15_000) }
        return Result.success()
    }

    private fun instanceSnapshot(): SubscriptionUpdateCoordinator.InstanceSnapshot =
        SubscriptionUpdateCoordinator.InstanceSnapshot(
            activeSubscriptionId = SubscriptionRepository.activeSubscriptionId,
            mixEnabled = Settings.mixEnabled,
            mixSubscriptionIds = Settings.mixSubscriptionIds,
            engineGeneration = CoreRuntime.engineGeneration,
            configRevision = ConfigStore.activeConfigRevision(),
        )

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
