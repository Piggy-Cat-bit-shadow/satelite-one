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
 *  - Round 8 found the guard that replaced it was keyed on the wrong quantity *and* was
 *    read at the wrong moment: `install()` called `sample()` **after** querying
 *    `PowerManager`, so the "before" key it compared already included the read it was
 *    meant to protect. Both are fixed here and pinned by tests that drive the real
 *    production helper ([PlatformFacts.seedInitialScreen]) rather than a model of it.
 *
 * ## The rule
 *
 * **Both writers go through [onChanged], and both take the lock.** Then the ordering
 * question stops mattering: whichever write happens second wins *and* is the newer fact,
 * because a broadcast can only run after `install()` has already seeded. No caller has to
 * know which thread anything is on.
 *
 * ## What the guard must compare — and why it is not the transition count
 *
 * A snapshot is stale when **something observed the screen after the snapshot was read**.
 * The earlier revision compared a counter that only moved on a real `on`/`off`
 * *transition*, which answers a different question: "did the screen move?" A repeated
 * broadcast (`ACTION_SCREEN_ON` while the fact is already ON — a lock/unlock, a double
 * delivery, an OEM quirk) is a **new observation of the same value**. It moves no level,
 * so the transition counter stood still and a snapshot read *before* it still passed the
 * guard:
 *
 * ```text
 * default ON (epoch 0); the caller reads `isInteractive`, holding a stale-looking OFF
 * ACTION_SCREEN_ON arrives, value unchanged  -> transition count still 0
 * seedSnapshot(OFF, 0) passes the guard      -> the newest observation is overwritten
 * ```
 *
 * So the guard compares [observationEpoch], which every real broadcast bumps, and
 * [onChanged] keeps returning "did the *value* change?" for the delivery side and for the
 * `(unchanged)` debug line. The two questions stay separate because they have different
 * answers; the epoch is the one that decides whether a snapshot may still be applied.
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
     * Counts **observations**, not transitions: every broadcast that reports a screen
     * state bumps it, including one that repeats the value already held.
     *
     * This is the whole basis for rejecting a stale [seedSnapshot]. The pre-read epoch is
     * the door key: if it still matches when the platform reading comes back, nothing has
     * been observed in between, so the reading is the newest fact. If it moved — for any
     * reason, level change or not — the reading describes a moment that has already been
     * superseded, whatever its value.
     */
    private var observationEpoch = 0L

    /** The value to report to a core that attaches now. */
    val current: Boolean get() = synchronized(lock) { on }

    /**
     * The pre-read door key for [seedSnapshot]. Read it **before** querying the platform,
     * never after: a snapshot taken after the read no longer identifies the moment the
     * read describes, and the whole guard becomes decorative. See [PlatformFacts.
     * seedInitialScreen], which is the only production caller and does this in order.
     */
    fun observationEpoch(): Long = synchronized(lock) { observationEpoch }

    /** [current] together with the epoch it belongs to, read as one consistent pair. */
    fun sample(): Pair<Boolean, Long> = synchronized(lock) { on to observationEpoch }

    /**
     * Record a screen fact. Both the broadcast path and the initial snapshot use this.
     *
     * @return true when the value actually changed, so a caller can skip work — and so the
     *   debug line can tell a genuine transition from a repeated observation. **Every**
     *   call bumps [observationEpoch], changed or not; the boolean describes the level,
     *   the epoch describes the observation.
     */
    fun onChanged(value: Boolean): Boolean = synchronized(lock) {
        ++observationEpoch
        if (on == value) return false
        on = value
        true
    }

    /**
     * Apply an initial `PowerManager.isInteractive` reading, but only if nothing has
     * observed the screen since [observedBefore] — the [observationEpoch] that was current
     * when the caller read the platform value.
     *
     * @return true when the reading became the new fact.
     *
     * The failure this prevents, concretely: the caller reads `isInteractive == true`, a
     * `SCREEN_OFF` broadcast is handled before the read is applied, and the profile is then
     * told the screen is on. It would keep believing that until the *next* screen event,
     * which on a device left locked can be a long time.
     *
     * The mirror-image failure is subtler and is why the guard is an observation count
     * rather than a transition count: a broadcast that *repeats* the current value is still
     * newer than the reading, so it too must invalidate it.
     *
     * A rejection is silent by design: the newer fact is already recorded, so there is
     * nothing left to say. The caller may log it in debug builds.
     */
    fun seedSnapshot(value: Boolean, observedBefore: Long): Boolean = synchronized(lock) {
        if (observationEpoch != observedBefore) return false
        if (on == value) return false
        on = value
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
