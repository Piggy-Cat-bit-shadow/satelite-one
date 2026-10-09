package com.interstellar.proxy.bg

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.interstellar.proxy.core.PlatformFactSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Collects raw Android platform facts and hands them to the running core.
 *
 * **This class contains no policy on purpose.** It reports what Android observed
 * — a trim level, foreground/background, screen on/off, "the user really came
 * back" — and sing-box's shared mobile runtime policy decides what that means.
 * There is deliberately no Kotlin branch that maps a level to an action, no
 * threshold, no timer, no RSS polling, no reconnect, and no pause. The one rule
 * it upholds is: *Android reports facts, the core decides actions.*
 *
 * Registration is application-scoped, which is why it is never unregistered:
 * these are app-level truths that outlive any single core session (a screen is
 * off or on regardless of whether a box is running). What bounds a session is
 * [attach]/[detach]: once detached the sink is gone, so no callback — including
 * one already queued — can touch a torn-down or not-yet-created core.
 */
object PlatformFacts {

    @Volatile
    private var sink: PlatformFactSink? = null

    /**
     * Single-threaded serialization. Facts must not reorder: a screen-off that
     * overtook its screen-on would leave the core believing the opposite of the
     * truth, and trim levels are ordered by definition.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    /** Last observed facts, so a core starting mid-session can be told immediately. */
    @Volatile
    private var appForeground = true
    @Volatile
    private var screenOn = true

    val isAppForeground: Boolean get() = appForeground
    val isScreenOn: Boolean get() = screenOn

    /**
     * Bind a freshly started core and immediately report the *current* facts.
     *
     * Without this the core would not know the truth until the next transition:
     * start it while the screen is already off, or while the app is already in the
     * background, and it would assume the opposite until the user did something.
     */
    fun attach(sink: PlatformFactSink) {
        this.sink = sink
        val foreground = appForeground
        val screen = screenOn
        scope.launch {
            sink.setScreenOn(screen)
            sink.setAppForeground(foreground)
        }
    }

    /** Unbind before teardown. Every later fact becomes a no-op. */
    fun detach() {
        sink = null
    }

    // ---- fact entry points ----

    /** Raw Android trim level, forwarded unchanged. Never remapped by the app. */
    fun onMemoryTrim(level: Int) = dispatch { it.memoryTrim(level) }

    fun onScreenChanged(on: Boolean) {
        screenOn = on
        dispatch { it.setScreenOn(on) }
    }

    fun onForegroundChanged(foreground: Boolean) {
        appForeground = foreground
        dispatch { it.setAppForeground(foreground) }
    }

    fun onUserPresent() = dispatch { it.reportDeviceWake() }

    private fun dispatch(action: (PlatformFactSink) -> Unit) {
        // Re-read the sink inside the dispatch: a teardown that lands between the
        // callback and this block must be a no-op, never a touch on a dead core.
        scope.launch { sink?.let(action) }
    }

    // ---- registration ----

    /**
     * Installs the two Android sources. Called once from Application.onCreate, so
     * it is intentionally unregistered only by process death.
     */
    fun install(context: Context) {
        // Screen + "user really returned". ServiceNotification separately watches
        // SCREEN_ON/OFF to decide whether to keep *its own* dynamic-notification
        // status stream — a different responsibility that must not be folded in
        // here (see the notification class).
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_ON -> onScreenChanged(true)
                        Intent.ACTION_SCREEN_OFF -> onScreenChanged(false)
                        Intent.ACTION_USER_PRESENT -> onUserPresent()
                    }
                }
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )

        // The screen may already be off when the process starts.
        screenOn = context.getSystemService<PowerManager>()?.isInteractive ?: true

        // App foreground/background. A started-activity counter is used rather than
        // Activity onPause/onResume: pausing also happens for a dialog or the
        // notification shade, which is not the app going to the background.
        (context as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                private var started = 0

                override fun onActivityStarted(activity: Activity) {
                    started++
                    if (started == 1) onForegroundChanged(true)
                }

                override fun onActivityStopped(activity: Activity) {
                    // A configuration change (rotation, language switch) stops and
                    // re-creates the activity; the user never left the app.
                    if (activity.isChangingConfigurations) return
                    started--
                    if (started <= 0) {
                        started = 0
                        onForegroundChanged(false)
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}
