package com.interstellar.proxy

import android.app.Application
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.core.content.getSystemService
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import com.interstellar.proxy.ktx.wrapAppLocale
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.Locale

class InterstellarApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        // wrap BEFORE super so every getString on the application context is
        // localized; AppLanguage reads settings.properties off `base` itself
        super.attachBaseContext(base?.wrapAppLocale())
        application = this
    }

    override fun onCreate() {
        super.onCreate()

        // pinned language also drives JVM-default formatting / libbox messages
        val pinnedTag = com.interstellar.proxy.data.Settings.appLanguage
            .takeIf { it != com.interstellar.proxy.ktx.AppLanguage.SYSTEM }
        if (pinnedTag != null) {
            // NB: android's java.util.Locale only has the singular forLanguageTag
            runCatching { Locale.setDefault(Locale.forLanguageTag(pinnedTag)) }
        }

        runCatching {
            Libbox.setLocale(Locale.getDefault().toLanguageTag())
        }

        val baseDir = filesDir
        baseDir.mkdirs()
        val workingDir = getExternalFilesDir(null)
        val tempDir = cacheDir
        tempDir.mkdirs()
        workingDir?.mkdirs()

        if (workingDir != null) {
            setupLibbox(baseDir, workingDir, tempDir)
        }

        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO) {
            // regenerate the active config if missing (e.g. after a failed
            // generation in a previous run)
            if (com.interstellar.proxy.data.ConfigStore.readActiveConfig() == null) {
                runCatching { com.interstellar.proxy.data.SubscriptionRepository.regenerateActiveConfig() }
            }
        }

        // subscription auto-update schedule
        scheduleAutoUpdate()

        // Android platform facts (screen / user-present / app foreground) are
        // collected for the whole process; they are only *delivered* while a core
        // session is attached. See PlatformFacts: facts here, policy in the core.
        runCatching { com.interstellar.proxy.bg.PlatformFacts.install(this) }
    }

    /**
     * Raw Android memory-pressure level, forwarded to the running core unchanged.
     *
     * No Kotlin policy: the level is not remapped, not compared against a
     * threshold, and `TRIM_MEMORY_UI_HIDDEN` is treated as exactly what it is — a
     * UI-visibility hint, not memory pressure. Delivery hops off the main thread
     * inside PlatformFacts; when no core is attached this is a no-op.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.interstellar.proxy.bg.PlatformFacts.onMemoryTrim(level)
    }

    private fun scheduleAutoUpdate() {
        runCatching {
            com.interstellar.proxy.data.UpdateWorker.reschedule(this)
        }
    }

    private fun setupLibbox(baseDir: java.io.File, workingDir: java.io.File, tempDir: java.io.File) {
        Libbox.setup(
            SetupOptions().also {
                it.basePath = baseDir.path
                it.workingPath = workingDir.path
                it.tempPath = tempDir.path
                it.logMaxLines = 3000
            },
        )
    }

    companion object {
        lateinit var application: InterstellarApplication
            private set
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val packageManager by lazy { application.packageManager }
        val powerManager by lazy { application.getSystemService<PowerManager>()!! }
        val notificationManager by lazy { application.getSystemService<NotificationManager>()!! }
        val wifiManager by lazy { application.getSystemService<WifiManager>()!! }
        val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
    }
}
