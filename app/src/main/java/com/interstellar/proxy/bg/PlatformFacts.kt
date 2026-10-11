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
     * UI-layer consumers of the app foreground fact.
     *
     * A list rather than a single callback because the fact has more than one legitimate consumer
     * (the core's device policy, and the UI's own control plane) and they must all be fed from the
     * same edge.
     *
     * **Never invoked while [lock] is held** — see [dispatchForegroundListeners]. The copy taken
     * here is what makes mutating the list from inside a callback safe; the lock is released before
     * the callbacks run, and those are two different problems that the old comment ran together.
     */
    private val foregroundListeners = mutableListOf<(Boolean) -> Unit>()

    /**
     * The delivery order, so a listener never acts on a superseded fact.
     *
     * [foregroundSequence] is taken under [lock], which is what makes it the RECORD order; each
     * notification then waits for its predecessors in [foregroundDelivered] before it runs. Both are
     * volatile because they are read across threads without the lock — deliberately, since the whole
     * point of the dispatch is that it does not hold the lock.
     */
    @Volatile
    private var foregroundSequence = 0L

    /**
     * The highest ticket whose notification has completely finished. **`-1` means "nothing has been
     * delivered yet"**, which is a state the first ticket needs to be distinguishable from ticket `0`
     * having been delivered — see [awaitForegroundTurn].
     *
     * This used to start at `0L`, which conflated those two states and left the *first* pair of facts
     * unordered: ticket `0` does not wait (`-1 < -1` is false, correctly), but ticket `1` then compared
     * `foregroundDelivered` (`0`) against its predecessor (`0`), saw "already delivered", and ran its
     * listener while ticket `0`'s listener was still on the stack. Two facts recorded `true` then
     * `false` reached the listener as `false` then `true`, so the last value it acted on was one the
     * object had already superseded — the exact hazard this ordering exists to remove, reachable on
     * the first pair after every process start and therefore on the pair that matters most.
     */
    @Volatile
    private var foregroundDelivered = -1L

    /**
     * True on a thread that is already running a listener.
     *
     * A listener may record this same fact - `connect()` reaches [addForegroundListener], and a
     * subscriber's replay handler is a listener - so the notification path has to be re-entrant
     * without waiting for a turn that would only come from the dispatch this thread is inside.
     */
    private val insideListener = ThreadLocal.withInitial { false }

    /**
     * One-way latch: registering the receiver and the lifecycle callbacks must not happen
     * twice. See [InstallGuard].
     */
    private val installer = InstallGuard()

    val isAppForeground: Boolean get() = appForeground
    val isScreenOn: Boolean get() = screen.current

    /**
     * How many screen observations have been recorded, read-only.
     *
     * Exposed so a test can assert the epoch contract at the *seam* it is used at — "a
     * broadcast in the middle of the startup read moves this, an agreeing snapshot does
     * not" — without reaching into [ScreenFactState]'s private state or re-implementing it.
     */
    internal val observedScreenEpoch: Long get() = screen.observationEpoch()

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

    fun onForegroundChanged(foreground: Boolean) {
        synchronized(lock) {
            appForeground = foreground
            debugFact("foreground", foreground)
            current?.let { live -> enqueue(live) { sink -> sink.setAppForeground(foreground) } }
        }
        // UI-layer observers are notified from HERE, and only here.
        //
        // The tracker above is the one component that knows a stop caused by a configuration change
        // is not a background transition (ActivityForegroundTracker.onActivityStopped(
        // isChangingConfigurations)). A consumer that derives its own edge from a Compose lifecycle
        // - an ON_STOP effect - does not know that, so it would treat a theme switch, a UI-mode
        // change, a display-size change or a font-scale change as the app going to the background.
        // That is a second writer of one fact, which is what this layer exists to prevent.
        //
        // OUTSIDE the block above, and that placement is the whole point of this function's shape. It
        // used to be an expression body - `= synchronized(lock) { ... }` - so everything in the braces
        // ran under the lock, notifications included, and the dispatch below re-entered a lock this
        // call was still holding. `dispatchForegroundListeners` documents why that is wrong; the
        // braces here are what make its own acquisition a fresh one.
        dispatchForegroundListeners(foreground)
    }

    /**
     * Runs the foreground listeners on a copy, after releasing [lock], in the order the facts were
     * recorded.
     *
     * # Why the lock is released first
     *
     * A listener is not a leaf. The UI's one calls `resumeUiClient()` / `suspendUiClient()`, which
     * reach `connect()` / `disconnect()`, which call [addForegroundListener] and
     * [removeForegroundListener] — back into this object, from inside the notification. The JVM lock
     * is reentrant, so that never deadlocked and never looked wrong; it did something worse. The
     * nested `addForegroundListener` delivered the canonical value *reentrantly*, from inside the
     * outer listener, so one edge could produce a second one, and whether it did depended on whether
     * a client happened to be subscribed at that moment. A notification path whose behaviour depends
     * on the lock being held is a behaviour nobody can reason about from the call site.
     *
     * Holding the lock across a listener also puts an arbitrary listener body inside this object's
     * lock order. That is the shape the core side already paid for and banned in
     * `runtimecoord.PlatformEvents` ("It is NEVER held across a target call ... the shape of the
     * ABBA deadlock this fork has already paid for"), and the reasoning transfers unchanged: decide
     * under the lock, act after releasing it.
     *
     * The copy is still required, for a different reason than the lock: a listener is allowed to
     * register or unregister another listener, and iterating the live list would then throw
     * `ConcurrentModificationException`. Copy under the lock, invoke outside it.
     *
     * # Why it also has to keep the record order
     *
     * Releasing the lock gave up something the lock was providing for free. Two facts recorded in
     * order on two threads — `true` then `false` — could reach the listeners as `false` then `true`,
     * so the LAST value a listener acted on was a value the object had already superseded. That is
     * not a cosmetic ordering nit: the UI's listener turns it into side effects, and a stale `true`
     * delivered last calls `resumeUiClient()`, which connects a client while the canonical fact is
     * "the app is in the background", with nothing later to correct it — `mayReconnect()` gates new
     * connects and never disconnects an existing client.
     *
     * Each notification therefore takes a sequence number under [lock] and delivers in turn, so the
     * delivery order is the record order by construction rather than by timing. A re-entrant
     * notification from inside a listener does not wait — see [insideListener] — because waiting
     * there would be a self-deadlock, and by the time a listener is running the earlier fact has
     * already been delivered to every listener that existed then.
     *
     * # What removal does and does not promise
     *
     * [removeForegroundListener] is not a barrier. The list is copied when a notification begins, so
     * a listener removed while a dispatch is in flight or queued can still receive that value; it
     * receives nothing recorded after the copy. That is the ordinary listener contract and it is what
     * the notification is: a snapshot of one fact to the subscribers that existed when it was
     * recorded. The invariant that matters for correctness is the ORDER of what does arrive, and
     * that is now guaranteed.
     *
     * Today every writer of this fact is main-thread, so none of the interleavings above are
     * reachable in the shipped app. The reason to close them anyway is that the primitive is
     * documented — and used — as thread-safe, and a property it only has on one thread is a property
     * its callers cannot rely on.
     */
    private fun dispatchForegroundListeners(foreground: Boolean) {
        // A reentrant notification is nested inside a listener of an earlier one, on this same thread.
        // It is NOT a position in the delivery order of its own: its listeners run while the outer
        // frame is still on the stack, so there is no instant at which "this ticket has been delivered"
        // is true and observable - by the time it is true the outer ticket is done as well.
        //
        // Giving it a ticket anyway is what broke the ordering. The nested frame had to publish
        // something or the next notification would wait for a boundary that never arrived, and
        // publishing its own predecessor made the outer frame's publication move the watermark
        // BACKWARDS (measured: ticket 0 published 0, nested ticket 1 published 0, ticket 0 re-published
        // 0, and ticket 2 then spun in `awaitForegroundTurn` waiting for 1 - a notification that never
        // arrives, which in the app is a UI client that silently stops updating).
        //
        // So only outermost frames take a ticket. They are the only boundaries the watermark has to
        // describe, the numbers stay contiguous, and `foregroundDelivered != ticket - 1` keeps meaning
        // exactly "my predecessor's whole frame, nested frames included, has finished".
        val reentrant = insideListener.get()
        val listeners = synchronized(lock) { foregroundListeners.toList() }
        val ticket: ForegroundDelivery?
        if (reentrant) {
            // Deliver inline, in order, without a position of its own. This is strictly newer than
            // everything already delivered, so depth-first delivery preserves the record order.
            ticket = null
        } else {
            val next = synchronized(lock) { ForegroundDelivery(foregroundSequence++, foreground) }
            ticket = next
            awaitForegroundTurn(next.sequence)
        }
        insideListener.set(true)
        try {
            listeners.forEach { listener ->
                runCatching { listener(foreground) }
            }
        } finally {
            insideListener.set(reentrant)
        }
        if (ticket != null) {
            // Monotonic as well: the watermark records how far delivery has provably got, and a value
            // that can move backwards strands every notification that follows it.
            foregroundDelivered = maxOf(foregroundDelivered, ticket.sequence)
        }
    }

    /**
     * Blocks until every earlier notification has been delivered.
     *
     * Bounded by yielding rather than by a timeout: the wait is for a listener body, which is
     * arbitrary code, so a deadline would turn a slow listener into a silently reordered fact. The
     * wait is always finite today because a listener is only ever called from a thread that will make
     * progress, but note the shape plainly: a listener that blocks forever now blocks later
     * notifications as well, which is the price of the ordering guarantee and the reason the ordering
     * exists in the first place — the alternative is delivering a superseded fact.
     *
     * Ticket `0` does not wait: its predecessor is `-1`, which is the "nothing delivered" sentinel, so
     * the comparison has to be an equality rather than `<` to keep that case from spinning.
     */
    private fun awaitForegroundTurn(ticket: Long) {
        while (foregroundDelivered != ticket - 1) {
            Thread.yield()
        }
    }

    /** One recorded fact and its place in the delivery order. */
    private class ForegroundDelivery(val sequence: Long, val foreground: Boolean)

    /**
     * Subscribe to the app foreground fact.
     *
     * The canonical value is delivered immediately, so a subscriber never has to guess and can
     * never fabricate a transition from a stale seed.
     *
     * The registration happens under [lock] and the immediate replay happens **outside** it, for the
     * same reason [dispatchForegroundListeners] documents: this is called from `connect()`, which is
     * itself reachable from inside a listener, and the subscriber's own replay handler re-enters this
     * object. Delivering the canonical value while holding the lock made that nesting deliver a second
     * edge from inside the first. Note the consequence, which is the point of the split: the listener
     * may be invoked before this function returns, so a caller must read its own verdict *after*
     * subscribing rather than assuming the subscribe was inert.
     */
    fun addForegroundListener(listener: (Boolean) -> Unit) {
        val canonical = synchronized(lock) {
            foregroundListeners.add(listener)
            appForeground
        }
        runCatching { listener(canonical) }
    }

    fun removeForegroundListener(listener: (Boolean) -> Unit) = synchronized(lock) {
        foregroundListeners.remove(listener)
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

    // ---- the initial screen reading ----

    /**
     * Test seam that fires **after the pre-read epoch is captured and before the platform
     * value is read** — the exact instant at which "this reading is already stale" becomes
     * possible.
     *
     * The interesting failure is an *interleaving*, and the production code deliberately
     * contains no sleep, no handler and no timer to create one. So the seam is an explicit
     * hook that only a test sets: the test runs the real [seedInitialScreen] and fires a
     * broadcast from here, which demonstrates the stale read without inventing a second
     * implementation to test instead. A test may equally fire it from inside
     * `readInteractive`; that is the same window and `ScreenSeedWiringTest.S02` uses both.
     * Null in production, so `install()` takes no extra step.
     */
    @Volatile
    internal var screenSeedHook: (() -> Unit)? = null

    /**
     * The production screen-seed algorithm: **take the observation epoch, then read the
     * platform, then apply the reading only if that epoch is still current.**
     *
     * The order of the first two steps is the defect this function exists to keep fixed.
     * The earlier wiring read `PowerManager.isInteractive` and only then sampled the
     * epoch — the key it compared was taken *after* the read it was meant to protect, so a
     * broadcast landing in between was already folded into the key and the stale reading
     * passed the guard. Taking the epoch first is what makes the guard mean "nothing has
     * been observed since this reading began".
     *
     * This is intentionally the *only* production path that applies an initial screen
     * reading, and it takes the reader as a parameter so a test can interleave a broadcast
     * at the exact point where production is otherwise racily silent.
     *
     * @return true when the reading became the current fact (see
     *   [ScreenFactState.seedSnapshot] for the two rejection cases).
     */
    internal fun seedInitialScreen(readInteractive: () -> Boolean): Boolean {
        val observedBefore = screen.observationEpoch()
        screenSeedHook?.invoke()
        val interactive = readInteractive()
        return screen.seedSnapshot(interactive, observedBefore)
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
        // and apply it only if nothing observed the screen in between, so a SCREEN_OFF —
        // or a repeat of the current value — that is handled during this window wins
        // instead of being overwritten by the staler reading.
        //
        // Registration deliberately happens first: with no Handler the receiver dispatches
        // on the main looper, which `Application.onCreate` already occupies, so today the
        // broadcast cannot interleave at all. The epoch check is what keeps that true
        // rather than assumed if a handler is ever added or install() moves off-main.
        val applied = seedInitialScreen { context.getSystemService<PowerManager>()?.isInteractive ?: true }
        debugFact("screen-seed", screen.current, unchanged = !applied)

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
