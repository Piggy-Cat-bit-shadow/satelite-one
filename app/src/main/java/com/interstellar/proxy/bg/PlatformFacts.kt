package com.interstellar.proxy.bg

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.interstellar.proxy.core.PlatformFactSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Collects raw Android platform facts and hands them to the running core.
 *
 * **This class contains no policy on purpose.** It reports what Android observed
 * — a trim level, foreground/background, screen on/off, "the user really came
 * back" — and sing-box's shared mobile runtime policy decides what that means.
 * There is deliberately no Kotlin branch that maps a level to an action, no
 * threshold, no timer, no RSS polling, no reconnect, and no pause. The one rule it
 * upholds is: *Android reports facts, the core decides actions.*
 *
 * ## Ownership
 *
 * Every [attach] creates a [Session] token. A delivery may only touch the bridge it
 * was created for, and that is re-checked **on the delivery thread, immediately
 * before the native call** — the same rule for the initial state report and for
 * ordinary facts. A detach that races a queued delivery turns that delivery into a
 * no-op instead of calling into a closed core.
 *
 * ## Why a channel rather than a plain "current sink" field
 *
 * Two properties are needed, and a nullable field provides neither:
 *
 *  1. **Order.** Facts must not reorder — a screen-off overtaking its own
 *     screen-on would leave the kernel believing the opposite of the truth.
 *  2. **A drain point.** `PlatformEvents.close()` must not run while a native call
 *     is still in flight on that same object. "Set the field to null" proves
 *     nothing about a call that has already started; [detachAndDrain] does.
 *
 * A single-consumer [Channel] gives strict FIFO plus exactly one runner, which is
 * what makes the drain barrier provable rather than hopeful.
 */
object PlatformFacts {

    /**
     * One attached core session. This token *is* the ownership proof — pass it back
     * to [detachAndDrain] so a stale holder (say, an old service instance finishing
     * its teardown) can never unbind a newer core's bridge.
     */
    class Session internal constructor(internal val id: Long, internal val sink: PlatformFactSink)

    private val lock = Any()

    @Volatile
    private var current: Session? = null
    private var nextId = 0L

    /**
     * Last observed facts, so a core starting mid-session can be told immediately.
     *
     * Foreground starts at **false**, not true: a process can be started with no
     * Activity at all (quick-settings tile, background service). "We have not seen
     * an onStart yet" is not evidence of being in the foreground, and reporting it
     * as one would be a fabricated fact.
     */
    @Volatile
    private var appForeground = false

    @Volatile
    private var screenOn = true

    val isAppForeground: Boolean get() = appForeground
    val isScreenOn: Boolean get() = screenOn

    /**
     * The session currently bound to a core, or null.
     *
     * Internal and read-only. It exists so tests can synchronize on the ownership
     * gate deterministically — asserting "the gate is closed" instead of sleeping
     * and hoping, which would make the delivery-isolation tests flaky.
     */
    internal val attachedSession: Session? get() = current

    // ---- the single delivery lane ----

    private val queue = Channel<() -> Unit>(Channel.UNLIMITED)
    private val laneScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val laneWorker = laneScope.launch {
        for (task in queue) {
            // One misbehaving sink must not kill the lane and silently freeze every
            // later fact; surface the failure instead.
            runCatching { task() }.onFailure {
                android.util.Log.w("InterstellarUI", "PlatformFacts delivery failed", it)
            }
        }
    }

    /**
     * Queue a native call for [target]. The ownership check happens when the task
     * *runs*, not when it is queued.
     */
    private fun enqueue(target: Session, action: (PlatformFactSink) -> Unit) {
        queue.trySend {
            // Re-checked here, on the lane thread, immediately before the native
            // call: a detach or re-attach that raced this job makes it a no-op.
            if (current?.id == target.id) action(target.sink)
        }
    }

    /**
     * Bind a freshly started core and immediately report the *current* facts.
     *
     * The read and the enqueue happen under the same lock that mutates the fact
     * fields, so enqueue order always matches state order: a screen event can never
     * be queued ahead of this report and then be overwritten by a staler initial
     * value.
     */
    fun attach(sink: PlatformFactSink): Session = synchronized(lock) {
        val created = Session(++nextId, sink)
        current = created
        val screen = screenOn
        val foreground = appForeground
        enqueue(created) { it.setScreenOn(screen) }
        enqueue(created) { it.setAppForeground(foreground) }
        created
    }

    /**
     * Unbind [expected] and wait until every delivery queued before this call has
     * finished, so the caller may then close the native bridge with nothing in
     * flight.
     *
     * A null [expected] unbinds whatever is attached; passing the token from
     * [attach] is what stops a stale holder from tearing down a newer session.
     */
    suspend fun detachAndDrain(expected: Session? = null) {
        val unbound = synchronized(lock) {
            val live = current
            if (live != null && (expected == null || live.id == expected.id)) {
                current = null
                true
            } else {
                false
            }
        }
        if (!unbound) return
        // The channel is FIFO, so once this barrier runs, every task sent before it
        // has completed. That — not a nullable field — is the synchronization
        // boundary between "facts stop" and "the bridge is closed".
        val barrier = CompletableDeferred<Unit>()
        queue.trySend { barrier.complete(Unit) }
        barrier.await()
    }

    // ---- fact entry points ----

    /** Raw Android trim level, forwarded unchanged. Never remapped by the app. */
    fun onMemoryTrim(level: Int) = synchronized(lock) {
        current?.let { live -> enqueue(live) { sink -> sink.memoryTrim(level) } }
    }

    fun onScreenChanged(on: Boolean) = synchronized(lock) {
        screenOn = on
        current?.let { live -> enqueue(live) { sink -> sink.setScreenOn(on) } }
    }

    fun onForegroundChanged(foreground: Boolean) = synchronized(lock) {
        appForeground = foreground
        current?.let { live -> enqueue(live) { sink -> sink.setAppForeground(foreground) } }
    }

    fun onUserPresent() = synchronized(lock) {
        current?.let { live -> enqueue(live) { sink -> sink.reportDeviceWake() } }
    }

    // ---- registration ----

    /**
     * Installs the two Android sources. Called once from Application.onCreate, so it
     * is intentionally unregistered only by process death: these are app-level
     * truths that outlive any single core session. What bounds a session is
     * [attach] / [detachAndDrain].
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

        // App foreground/background, from the real Activity lifecycle.
        val tracker = ActivityForegroundTracker { foreground -> onForegroundChanged(foreground) }
        (context as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) = tracker.onActivityStarted()

                override fun onActivityStopped(activity: Activity) =
                    tracker.onActivityStopped(activity.isChangingConfigurations)

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}

/**
 * Tracks whether the process currently has a user-visible Activity.
 *
 * The counter is balanced **by construction**: every counted `onStart` is
 * decremented by a matching `onStop`, including the stop performed by a
 * configuration change. Skipping that decrement (as an earlier version did, via an
 * early `return` on `isChangingConfigurations`) permanently inflates the count —
 * rotate three times and the app reports foreground forever, even sitting in the
 * background.
 *
 * A configuration change is absorbed without a timer instead: its stop is simply
 * not allowed to produce the background edge, because the replacement Activity's
 * `onStart` follows immediately. A redundant foreground report is truthful and
 * idempotent; a false background edge is not.
 *
 * Pure Kotlin, no Android types, so the counting rules are unit-testable.
 */
internal class ActivityForegroundTracker(
    private val onForegroundChanged: (Boolean) -> Unit,
) {
    private val lock = Any()
    private var started = 0

    fun onActivityStarted() = synchronized(lock) {
        started++
        if (started == 1) onForegroundChanged(true)
    }

    fun onActivityStopped(isChangingConfigurations: Boolean) = synchronized(lock) {
        // Always balance first: an inflating counter is a correctness bug.
        if (started > 0) started--
        if (started > 0) return@synchronized
        // A configuration change stops the old Activity and immediately starts its
        // replacement, so reporting background here would be a fabricated edge.
        // (If the process really is going away, its next onStop reports it.)
        if (isChangingConfigurations) return@synchronized
        onForegroundChanged(false)
    }

    internal val startedCount: Int get() = synchronized(lock) { started }
}
