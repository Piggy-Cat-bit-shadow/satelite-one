package com.interstellar.proxy.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.interstellar.proxy.data.UpdateWorker

/**
 * Debug-only trigger for the subscription worker.
 *
 * Periodic WorkManager work cannot be forced before its scheduled time: the
 * scheduler uses elapsedRealtime and WorkManager refuses a forced job that is
 * "before schedule". Emulator and device verification therefore needs an
 * explicit one-shot enqueue with the same [UpdateWorker]. This class lives in
 * the debug source set and is not part of a release build.
 */
class DebugUpdateWorkerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        WorkManager.getInstance(context)
            .enqueue(OneTimeWorkRequestBuilder<UpdateWorker>().build())
    }

    companion object {
        const val ACTION = "com.interstellar.proxy.debug.RUN_UPDATE_WORKER"
    }
}
