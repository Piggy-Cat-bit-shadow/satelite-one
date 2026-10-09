package com.interstellar.proxy.bg

/**
 * The last observed screen fact, and the rule about who may write it.
 *
 * ## The problem this exists for
 *
 * `PlatformFacts.install()` registers the screen receiver and then reads
 * `PowerManager.isInteractive` to seed the initial value. Two writers therefore touch the
 * same fact from two places:
 *
 *  - the **broadcast path** ([onChanged]), which is serialized,
 *  - the **initial snapshot** ([seedInitial]), which was not.
 *
 * A first reading of that code says "a `SCREEN_OFF` arriving between the two writes gets
 * overwritten by a staler snapshot". That reading is worth stating precisely, because the
 * conclusion is not the obvious one:
 *
 *  - `context.registerReceiver(receiver, filter)` with **no handler** dispatches on the
 *    main looper, and `Application.onCreate` already runs there — so the broadcast is
 *    queued behind `install()` and cannot interleave with it. **The overwrite is not
 *    reachable on today's call path.**
 *  - But the snapshot write was an ordinary unsynchronized field write from a different
 *    code path than the locked one, so the class relied for correctness on an argument
 *    about looper affinity that lives in a *different file* and is not checked anywhere.
 *    That is a latent defect: a future caller of `install()` off the main thread, or a
 *    handler added to the receiver, silently turns it live.
 *
 * ## The rule
 *
 * **Both writers go through [onChanged], and both take the lock.** Then the ordering
 * question stops mattering: whichever write happens second wins *and* is the newer fact,
 * because a broadcast can only run after `install()` has already seeded. No caller has to
 * know which thread anything is on.
 *
 * ## Why this is a separate class
 *
 * So the ordering can be **tested deterministically** on the JVM. Robolectric is not a
 * dependency of this project, and "register a real receiver and hope a broadcast lands"
 * is exactly the sleep-based test the round forbids. Here a test supplies the snapshot
 * and the broadcast in either order and asserts the outcome.
 */
internal class ScreenFactState {

    private val lock = Any()
    private var on = true

    /**
     * Bumped by every accepted **change**.
     *
     * This is what makes [seedSnapshot] able to tell "the platform still agrees with what
     * I read a moment ago" from "something observed the screen since". Without it the seed
     * is just another last-write-wins writer and a stale value that happens to land second
     * still wins — which is exactly what the first version of this class did, and what
     * [ScreenFactOrderingTest] caught.
     */
    private var version = 0L

    /** The value to report to a core that attaches now. */
    val current: Boolean get() = synchronized(lock) { on }

    /** [current] together with the version it belongs to, read as one consistent pair. */
    fun sample(): Pair<Boolean, Long> = synchronized(lock) { on to version }

    /**
     * Record a screen fact. Both the broadcast path and the initial snapshot use this.
     *
     * @return true when the value actually changed, so a caller can skip work — and so a
     *   test can tell "the newer fact won" from "the older one overwrote it" without
     *   reading private state. An unchanged observation does **not** bump the version:
     *   only real transitions count as "the screen was observed to move".
     */
    fun onChanged(value: Boolean): Boolean = synchronized(lock) {
        if (on == value) return false
        on = value
        ++version
        true
    }

    /**
     * Apply an initial `PowerManager.isInteractive` reading, but only if the screen has not
     * been observed since [takenAt] — the version that was current when the caller read it.
     *
     * @return true when the reading was applied.
     *
     * The failure this prevents, concretely: the caller reads `isInteractive == true`, a
     * `SCREEN_OFF` broadcast is handled before the read is applied, and the profile is then
     * told the screen is on. It would keep believing that until the *next* screen event,
     * which on a device left locked can be a long time.
     *
     * A rejection is silent by design: the newer fact is already recorded, so there is
     * nothing left to say. The caller may log it in debug builds.
     */
    fun seedSnapshot(value: Boolean, takenAt: Long): Boolean = synchronized(lock) {
        if (version != takenAt) return false
        if (on == value) return false
        on = value
        ++version
        true
    }
}

/**
 * Makes `PlatformFacts.install()` idempotent.
 *
 * `install()` registers a `BroadcastReceiver` and `ActivityLifecycleCallbacks`. Neither is
 * registered twice in production — it is called once, from `Application.onCreate` — but
 * nothing in the class said so, and a second call would double-register both: every screen
 * event delivered twice, every Activity start counted twice, and the foreground counter
 * inflated with no way to notice.
 *
 * A guard turns "happens to be called once" into "cannot be installed twice". It is a
 * one-way latch with no reset on purpose: there is no legitimate reason to reinstall, and
 * offering a reset would invite the double-registration it exists to prevent.
 */
internal class InstallGuard {
    private val lock = Any()
    private var installed = false

    /** @return true the first time only. */
    fun tryInstall(): Boolean = synchronized(lock) {
        if (installed) return false
        installed = true
        true
    }

    val isInstalled: Boolean get() = synchronized(lock) { installed }
}
