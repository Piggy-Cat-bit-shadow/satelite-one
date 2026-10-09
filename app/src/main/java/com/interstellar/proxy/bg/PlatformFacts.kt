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
 * (The RSS the home page shows comes from the core's own StatusMessage, not from
 * anything here — and it is the process RSS from /proc/self/statm, not Go's heap.)
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

    /**
     * The screen fact, written by exactly one entry point ([onScreenChanged]).
     *
     * See [ScreenFactState] for why the initial `isInteractive` read had to stop being a
     * second, unlocked writer.
     */
    private val screen = ScreenFactState()

    /**
     * One-way latch: registering the receiver and the lifecycle callbacks must not happen
     * twice. See [InstallGuard].
     */
    private val installer = InstallGuard()

    val isAppForeground: Boolean get() = appForeground
    val isScreenOn: Boolean get() = screen.current

    /**
     * The session currently bound to a core, or null.
     *
     * Internal and read-only. It exists so tests can synchronize on the ownership
     * gate deterministically — asserting "the gate is closed" instead of sleeping
     * and hoping, which would make the delivery-isolation tests flaky.
     */
    internal val attachedSession: Session? get() = current

    // ---- the single delivery lane ----

    /**
     * Task queue for the lane. **Not capacity-bounded**: platform facts are rare
     * (screen transitions, trims, unlocks), so an unbounded queue cannot be driven
     * to meaningful depth by Android itself — but it is not a bounded buffer and
     * must not be described as one. Bounding it would mean dropping or merging
     * one-shot facts (a trim, an unlock) or reordering an ON/OFF pair, which is
     * worse than the theoretical growth.
     */
    private val queue = Channel<() -> Unit>(Channel.UNLIMITED)

    /** Ceiling on how long teardown may wait for a proven drain. */
    private const val DRAIN_TIMEOUT_MS = 5_000L
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
    private fun enqueue(target: Session, action: (PlatformFactSink) -> Unit): Boolean {
        val sent = queue.trySend {
            // Re-checked here, on the lane thread, immediately before the native
            // call: a detach or re-attach that raced this job makes it a no-op.
            if (current?.id == target.id) action(target.sink)
        }
        if (sent.isFailure) {
            // The lane is gone, so this fact can never be delivered. Losing one fact
            // is survivable; silently believing it was delivered is not, so it is
            // logged. (The channel is never closed and the worker never stops in this
            // app, so this is a defence against future changes, not a live path.)
            android.util.Log.w("InterstellarUI", "PlatformFacts lane unavailable; dropped one fact", sent.exceptionOrNull())
        }
        return sent.isSuccess
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
        // Read under the same lock that mutations take, so it cannot be a value a
        // concurrent fact has already superseded.
        val screenNow = screen.current
        val foreground = appForeground
        debugFact("attach", screenNow)
        enqueue(created) { it.setScreenOn(screenNow) }
        enqueue(created) { it.setAppForeground(foreground) }
        created
    }

    /**
     * Unbind [expected] and wait until every native call already started for **that**
     * session has finished, so the caller may then close its bridge safely.
     *
     * [expected] is required and non-null on purpose. There is deliberately no
     * "unbind whatever happens to be attached" overload: a teardown that lost its
     * token must never be able to tear down a *newer* session's bridge, and an
     * implicit force-detach reachable from ordinary teardown is exactly how that
     * happens.
     *
     * The two responsibilities are kept apart:
     *
     *  - **Unbinding** only happens when [expected] is still the live session. A
     *    replaced session leaves [current] untouched, so session B is never harmed.
     *  - **Draining** happens unconditionally. Even when [expected] was already
     *    replaced, its native calls may still be executing on the lane, and the
     *    caller is about to close that session's bridge — so we must still wait.
     *
     * @return true only when the barrier actually ran, which proves every delivery
     *   queued before this call has completed. **false means the drain is NOT
     *   proven** (the lane is unavailable); the caller must not close native state
     *   it cannot prove is idle.
     */
    suspend fun detachAndDrain(expected: Session): Boolean {
        synchronized(lock) {
            // Only our own session is ever unbound; a replaced session leaves
            // `current` pointing at the newer one.
            if (current?.id == expected.id) current = null
        }
        // Linearization point: the barrier is enqueued *after* the unbind above, so
        // no task submitted later can slip in front of it. The channel is FIFO and
        // single-consumer, so when the barrier runs, every task sent before it has
        // completed. A task queued before the unbind is a no-op (the ownership check
        // fails), and a task already running is waited for — which is the point.
        val barrier = CompletableDeferred<Unit>()
        if (queue.trySend { barrier.complete(Unit) }.isFailure) {
            android.util.Log.e(
                "InterstellarUI",
                "PlatformFacts lane unavailable; cannot prove session ${expected.id} drained",
            )
            return false
        }
        // Bounded: a lane that accepted the barrier but never runs it (for example a
        // future change that cancels the lane scope) must not hang service teardown
        // forever. Timing out reports "not proven" rather than pretending success.
        val drained = kotlinx.coroutines.withTimeoutOrNull(DRAIN_TIMEOUT_MS) { barrier.await() }
        if (drained == null) {
            android.util.Log.e(
                "InterstellarUI",
                "PlatformFacts drain for session ${expected.id} timed out after ${DRAIN_TIMEOUT_MS}ms",
            )
            return false
        }
        return true
    }

    // ---- fact entry points ----

    /** Raw Android trim level, forwarded unchanged. Never remapped by the app. */
    fun onMemoryTrim(level: Int) = synchronized(lock) {
        current?.let { live -> enqueue(live) { sink -> sink.memoryTrim(level) } }
    }

    fun onScreenChanged(on: Boolean) = synchronized(lock) {
        val changed = screen.onChanged(on)
        // Log the fact even when it is unchanged: "Android told us ON and we were already
        // ON" is itself evidence about the event stream, and suppressing it would make a
        // repeated-broadcast investigation look like the event never arrived.
        debugFact("screen", on, unchanged = !changed)
        current?.let { live -> enqueue(live) { sink -> sink.setScreenOn(on) } }
    }

    fun onForegroundChanged(foreground: Boolean) = synchronized(lock) {
        appForeground = foreground
        debugFact("foreground", foreground)
        current?.let { live -> enqueue(live) { sink -> sink.setAppForeground(foreground) } }
    }

    fun onUserPresent() = synchronized(lock) {
        current?.let { live -> enqueue(live) { sink -> sink.reportDeviceWake() } }
    }

    /**
     * Debug-build observation of the facts this class reports.
     *
     * A fact whose value cannot be seen is a fact nobody can check on a device, and the
     * foreground value has no other external surface. This is a log line — no control
     * port, no API, no stored state — and it is compiled out of release builds because
     * `BuildConfig.DEBUG` is a constant there, so it adds nothing to a shipped APK.
     */
    private fun debugFact(name: String, value: Boolean, unchanged: Boolean = false) {
        if (com.interstellar.proxy.BuildConfig.DEBUG) {
            val suffix = if (unchanged) " (unchanged)" else ""
            android.util.Log.d("InterstellarUI", "platform fact: $name=$value$suffix attached=${current != null}")
        }
    }

    // ---- registration ----

    /**
     * Installs the two Android sources. Called once from Application.onCreate, so it
     * is intentionally unregistered only by process death: these are app-level
     * truths that outlive any single core session. What bounds a session is
     * [attach] / [detachAndDrain].
     *
     * Idempotent by construction ([InstallGuard]): the receiver and the lifecycle
     * callbacks are registered exactly once, so a second call cannot double-count
     * Activity starts or deliver every screen event twice.
     */
    fun install(context: Context) {
        if (!installer.tryInstall()) {
            android.util.Log.w(
                "InterstellarUI",
                "PlatformFacts.install() called again; the receiver and lifecycle " +
                    "callbacks are already registered, so this call does nothing",
            )
            return
        }

        // Screen + "user really returned". ServiceNotification separately watches
        // SCREEN_ON/OFF to decide whether to keep *its own* dynamic-notification
        // status stream — a different responsibility that must not be folded in
        // here (see the notification class).
        //
        // No Handler is passed, so Android dispatches on the main looper — the same
        // thread `Application.onCreate` is already running on. That is what makes the
        // seed below race-free today; [ScreenFactState] documents why the code no longer
        // *depends* on that argument being true.
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

        // The screen may already be off when the process starts. Read the platform value
        // and the version it corresponds to, then apply it only if nothing has observed
        // the screen in between — so a SCREEN_OFF handled during this window wins instead
        // of being overwritten by the staler reading.
        //
        // Registration deliberately happens first: with no Handler the receiver dispatches
        // on the main looper, which `Application.onCreate` already occupies, so today the
        // broadcast cannot interleave at all. The version check is what keeps that true
        // rather than assumed if a handler is ever added or install() moves off-main.
        val interactive = context.getSystemService<PowerManager>()?.isInteractive ?: true
        val (_, versionWhenRead) = screen.sample()
        val applied = screen.seedSnapshot(interactive, versionWhenRead)
        debugFact("screen-seed", interactive, unchanged = !applied)

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
        //
        // DOCUMENTED PLATFORM LIMITATION: if the replacement Activity never reaches
        // onStart (it crashed, or its creation failed), this process keeps reporting the
        // last known foreground until some later lifecycle event arrives. There is no
        // platform callback that distinguishes "a rotation is in progress" from "the
        // replacement never came" without a timer, and a timer here would be exactly the
        // polling this design avoids. Android makes no statement we could forward, so we
        // forward none rather than guessing. See the test that pins this behaviour.
        if (isChangingConfigurations) return@synchronized
        onForegroundChanged(false)
    }

    internal val startedCount: Int get() = synchronized(lock) { started }
}
