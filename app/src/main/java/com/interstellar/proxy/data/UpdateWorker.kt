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
 *
 * The worker applies the same production path as a manual update: the repository validates and
 * commits the fetched bodies, the active config is one revision, the reload is bound to a receipt,
 * and a refused reload is recovered by [ActiveConfigRecovery] while that revision is still current.
 * The earlier version wrote the body and active config, asked the Box to reload, and returned
 * `Result.success()` even when the Box had refused the document and stopped: the next start then
 * read the bad body again. A late failure must not roll back a newer config either, which is why the
 * recovery is the same CAS-guarded call the manual path uses.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!Settings.autoUpdateEnabled) return Result.success()
        // The same instance identity a manual update uses: a scheduled run that outlives the profile
        // (or the Box) it was scheduled for must not reload or refresh a different instance, and a
        // batch it committed is only applied while its revision is still the current one.
        val start = instanceSnapshot()
        val activeBefore = ConfigStore.readActiveConfig()
        val ownerBefore = ConfigStore.readActiveConfigOwner()
        val activeIdBefore = start.activeSubscriptionId
        val rawBefore = activeIdBefore.takeIf { it.isNotBlank() }?.let { SubscriptionRepository.readRawBody(it) }
        val wasServing = CoreRuntime.appliedConfigRevision >= 0
        val messages = SubscriptionRepository.refreshAll(regenerate = false)
        var owns = SubscriptionUpdateCoordinator.stillOwns(
            start,
            instanceSnapshot(),
            ConfigStore.activeConfigRevision(),
        )
        log("gen=${start.engineGeneration} messages=${messages.size} owns=$owns serving=$wasServing")
        if (!owns) {
            log("result superseded by a newer instance; nothing committed")
            return Result.success()
        }
        if (messages.isEmpty() || !Settings.tileActive) return Result.success()
        // Commit the fetched bytes only after the ownership check, then decide whether the config
        // actually changed; only a changed config needs a hot reload.
        SubscriptionRepository.regenerateActiveConfig()
        val committed = ConfigStore.snapshot()
        val expectedRevision = committed.meta.revision
        val changed = SubscriptionUpdateCoordinator.configChanged(activeBefore, committed.content)
        owns = SubscriptionUpdateCoordinator.stillOwns(start, instanceSnapshot(), expectedRevision)
        if (!owns) {
            log("commit superseded; nothing applied")
            return Result.success()
        }
        if (!changed) {
            // Same document: the Box is untouched (no reload), but its remote node sources are still
            // the point of the periodic run.
            refreshProviders()
            return Result.success()
        }

        // One receipt-bound reload, exactly like the manual path.
        val intent = BoxService.registerReloadIntent(expectedRevision)
        val request = ReloadRequest(
            requestId = intent.id,
            revision = expectedRevision,
            engineGenerationBefore = CoreRuntime.engineGeneration,
        )
        val rpc = runCatching { CommandTarget.standaloneClient().serviceReload() }
        val receipt = BoxService.lastReload
        val verdict = ActiveConfigApply.classify(
            request = request,
            rpcSucceeded = rpc.isSuccess,
            receipt = receipt,
            currentRevision = ConfigStore.activeConfigRevision(),
            currentGeneration = CoreRuntime.engineGeneration,
            appliedRevision = CoreRuntime.appliedConfigRevision,
        )
        log(
            "reload rpc=${rpc.isSuccess} verdict=$verdict receipt=" +
                (receipt?.let { "req=${it.requestId} rev=${it.configRevision} success=${it.success} error=${it.error}" } ?: "none"),
        )
        when (verdict) {
            ActiveConfigApply.Verdict.APPLIED -> Unit
            ActiveConfigApply.Verdict.REFUSED -> {
                val recovery = ActiveConfigRecovery.restore(
                    ActiveConfigRecovery.FailedCandidate(
                        committedRevision = expectedRevision,
                        committedOwner = committed.meta.ownerSubscriptionId,
                        previousConfig = activeBefore,
                        previousOwner = ownerBefore,
                        rawOwnerId = activeIdBefore.takeIf { it.isNotBlank() },
                        committedRawBody = activeIdBefore
                            .takeIf { it.isNotBlank() }
                            ?.let { SubscriptionRepository.readRawBody(it) },
                        previousRawBody = rawBefore,
                    ),
                )
                when (recovery) {
                    is ActiveConfigRecovery.Result.Restored -> {
                        log(
                            "reload refused; restored previous revision=${recovery.revision}" +
                                " rawRestored=${recovery.rawRestored}",
                        )
                        // The kernel released the Box on the refusal. Bring one back from the restored
                        // document when one was serving before; a background FGS start may be
                        // rejected by the platform, in which case the restored files still make the
                        // next start good and the log records that no restart happened.
                        if (wasServing) {
                            val restarted = restartStoredConfig()
                            log("restart from restored config requested=$restarted")
                        }
                    }

                    ActiveConfigRecovery.Result.Superseded ->
                        log("reload refused but a newer commit owns the config; nothing restored")

                    ActiveConfigRecovery.Result.NothingToRestore ->
                        log("reload refused with no previous document to restore")
                }
                // The candidate is refused; a WorkManager failure would retry the same bad body with
                // backoff and keep the worker in a failure state. The periodic schedule is the retry.
                return Result.success()
            }

            ActiveConfigApply.Verdict.SUPERSEDED -> {
                log("reload result belongs to a newer write/instance; nothing applied")
                return Result.success()
            }

            ActiveConfigApply.Verdict.UNCONFIRMED -> {
                log("reload unconfirmed; leaving the committed config untouched")
                return Result.success()
            }
        }

        refreshProviders()
        return Result.success()
    }

    /** Refreshes remote node sources on the live Box; a failure is recorded, not retried. */
    private suspend fun refreshProviders() {
        val result = runCatching { CommandTarget.standaloneClient().refreshRemoteProviders("", 15_000) }
        log("providers refreshed=${result.isSuccess}")
    }

    /** Starts the Box from the config on disk; returns false when the platform refused the start. */
    private fun restartStoredConfig(): Boolean {
        val content = ConfigStore.readActiveConfig() ?: return false
        if (content.isBlank()) return false
        if (CoreRuntime.appliedConfigRevision == ConfigStore.activeConfigRevision()) return true
        return runCatching {
            BoxService.start()
            true
        }.getOrElse { error ->
            log("restart from restored config failed: ${error.message}")
            false
        }
    }

    private fun log(message: String) {
        android.util.Log.i("InterstellarUI", "UpdateWorker: $message")
        com.interstellar.proxy.core.AppLog.log("worker", "auto update: $message")
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
