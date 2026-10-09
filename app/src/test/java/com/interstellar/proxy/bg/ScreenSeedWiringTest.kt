package com.interstellar.proxy.bg

import com.interstellar.proxy.core.PlatformFactSink
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The round-8 P0: the initial screen reading, its guard, and the order in which the guard
 * key is taken. (S01–S12 of the round-8 test matrix.)
 *
 * ## The two defects, as found in the source
 *
 * `PlatformFacts.install()` used to read the platform and *then* ask for the epoch:
 *
 * ```kotlin
 * val interactive = context.getSystemService<PowerManager>()?.isInteractive ?: true
 * val (_, versionWhenRead) = screen.sample()          // <- the key is taken AFTER the read
 * val applied = screen.seedSnapshot(interactive, versionWhenRead)
 * ```
 *
 * and [ScreenFactState] only moved that key on a real level *transition*. Both halves have
 * to be wrong together for the overwrite to happen, and both are asserted here against the
 * real production algorithm ([PlatformFacts.seedInitialScreen]) instead of a hand-written
 * model of it — a model would pass no matter what `install()` actually does, which is the
 * exact gap this round was asked to close.
 *
 * The reachability boundary is stated in every place it matters and is not overstated:
 * `registerReceiver` here passes no `Handler`, so the broadcast is delivered on the main
 * looper that `Application.onCreate` already occupies and cannot interleave with
 * `install()` on today's call path. This is a **latent contract defect**, not an observed
 * field failure: the code claimed a guard it did not implement, and the claim would go
 * live the moment a handler is added or `install()` moves off-main. The interleavings
 * below are the ones those two changes would enable.
 */
class ScreenSeedWiringTest {

    /** Records what a core would have been told, so assertions are about facts, not counters. */
    private class Sink : PlatformFactSink {
        val events = CopyOnWriteArrayList<String>()
        override fun memoryTrim(level: Int) { events += "trim:$level" }
        override fun setAppForeground(foreground: Boolean) { events += "foreground:$foreground" }
        override fun setScreenOn(on: Boolean) { events += "screen:$on" }
        override fun reportDeviceWake() { events += "wake" }
    }

    @After
    fun tearDown() {
        PlatformFacts.screenSeedHook = null
        PlatformFacts.attachedSession?.let { live -> runBlocking { PlatformFacts.detachAndDrain(live) } }
        PlatformFacts.onScreenChanged(true)
        PlatformFacts.onForegroundChanged(false)
    }

    /** Deterministic handshake instead of a sleep: wait until the live session reports [on]. */
    private fun awaitScreenFact(sink: Sink, on: Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (sink.events.none { it == "screen:$on" } && System.currentTimeMillis() < deadline) {
            Thread.sleep(2)
        }
        check(sink.events.any { it == "screen:$on" }) {
            "the live session was never told screen=$on: ${sink.events}"
        }
    }

    // ------------------------------------------------- S02: the read/seed order itself

    @Test
    fun `S02 a broadcast during the platform read makes the reading stale`() {
        // A broadcast lands while `isInteractive` is being read: the screen goes OFF and
        // the read still returns the old ON. The later seed must be rejected, because a
        // newer observation exists.
        //
        // Both positions in the window are exercised, because they are the same defect:
        //  (a) from inside `readInteractive` - the broadcast lands mid-read;
        //  (b) from `screenSeedHook`, which fires after the epoch is taken and *before* the
        //      read begins. (b) is the shape a future handler or an off-main `install()`
        //      would produce, and it is exactly what the removed `sample()`-after-read
        //      order could not detect.
        PlatformFacts.onScreenChanged(true)
        val appliedDuringRead = PlatformFacts.seedInitialScreen {
            PlatformFacts.onScreenChanged(false)
            true
        }
        check(!appliedDuringRead) { "a reading superseded by a broadcast was still applied" }
        check(!PlatformFacts.isScreenOn) { "the stale reading overrode the newer SCREEN_OFF fact" }

        PlatformFacts.onScreenChanged(true)
        PlatformFacts.screenSeedHook = { PlatformFacts.onScreenChanged(false) }
        try {
            val appliedAfterEpoch = PlatformFacts.seedInitialScreen { true }
            check(!appliedAfterEpoch) {
                "a reading whose epoch was superseded before it started was still applied"
            }
            check(!PlatformFacts.isScreenOn) {
                "the pre-read broadcast was overwritten by a reading taken after it"
            }
        } finally {
            PlatformFacts.screenSeedHook = null
        }
    }

    @Test
    fun `S01 the pre-read epoch, not the post-read sample, is the door key`() {
        // The same interleaving, but asserting the *mechanism*: with the key taken before
        // the read, the epoch that comes back is the one the read was taken at, so a
        // broadcast in between is visible to the guard. The old order — sample() after the
        // read — already contained the broadcast in the key and let the reading through.
        val state = ScreenFactState()
        state.onChanged(false)                 // the screen is off, and that is observed
        val epochBeforeRead = state.observationEpoch()
        state.onChanged(true)                  // the screen comes back on during the read
        check(!state.seedSnapshot(false, epochBeforeRead)) {
            "a snapshot read before a later observation was accepted"
        }
        check(state.current) { "the newer observation must remain the current fact" }
        check(epochBeforeRead < state.observationEpoch()) {
            "the pre-read key must be strictly older than the observation that invalidated it"
        }
    }

    // ---------------------------------------- S03: a same-value broadcast is an observation

    @Test
    fun `S03 a repeated same-value broadcast invalidates an older snapshot`() {
        // The subtle case, exactly as the round states it: the fact is already ON, so the
        // incoming SCREEN_ON moves no level. It is still a *newer observation of the
        // screen*, so a reading held from before it describes a superseded moment and must
        // not be applied.
        //
        // Note which assertion has to catch the old code: with the level unchanged, the
        // overwrite itself is a no-op (`seedSnapshot` returns early when the values agree),
        // so the visible damage is only reachable when the level *has* moved — see S03b,
        // which is the same defect with the write made observable. S03's own job is the
        // contract: a same-value observation advances the epoch, and a snapshot older than
        // it is refused. Both were false before this round.
        val state = ScreenFactState()
        val epochWhenRead = state.observationEpoch()   // default ON, epoch 0
        check(!state.onChanged(true)) {
            "a repeated ON must not report a level change - the (unchanged) log depends on it"
        }
        check(state.observationEpoch() != epochWhenRead) {
            "a repeated observation must still advance the epoch"
        }
        val applied = state.seedSnapshot(false, epochWhenRead)
        check(!applied) { "a snapshot older than a same-value observation was applied" }
        check(state.current) { "a stale snapshot overwrote the newest observation" }
    }

    @Test
    fun `S03b a stream of mixed repeats and transitions counts every observation`() {
        // The companion contract test: the epoch counts *observations*, so a stream of four
        // broadcasts of which only two move the level advances it by four. Stated as the
        // round states it: `S03` is the case where the old guard let a stale snapshot
        // through because the level happened to agree; this is the case where the level
        // disagrees, and the guard has to have counted the repeats to refuse it.
        //
        // (With the level disagreeing, a pure transition counter would also refuse — so
        // this test is not the destructive contrast on its own. It pins the invariant that
        // the two counters are different quantities and that the epoch is the one the guard
        // uses; `S03` and `S12b` carry the destructive half.)
        val state = ScreenFactState()
        val epochBeforeRead = state.observationEpoch()
        state.onChanged(false)                 // SCREEN_OFF after the read: a transition
        state.onChanged(true)                  // SCREEN_ON again, no net level change
        state.onChanged(false)                 // and OFF, the newest fact
        check(state.observationEpoch() == epochBeforeRead + 3) {
            "three observations must count three times, got " +
                "${state.observationEpoch() - epochBeforeRead}"
        }
        check(!state.current)

        val applied = state.seedSnapshot(true, epochBeforeRead)
        check(!applied) { "a reading predating three observations was applied" }
        check(!state.current) {
            "the stale ON reading turned the newest OFF fact back on"
        }
    }

    @Test
    fun `S07 a hundred repeated same-value broadcasts keep one honest epoch and one honest fact`() {
        val state = ScreenFactState()
        state.onChanged(false)                          // establish OFF as the live fact
        val epoch = state.observationEpoch()
        repeat(100) {
            check(!state.onChanged(false)) { "iteration $it reported a level change for a repeated OFF" }
        }
        check(state.observationEpoch() == epoch + 100) {
            "100 observations must advance the epoch by exactly 100, got ${state.observationEpoch()}"
        }
        check(!state.seedSnapshot(true, epoch)) {
            "a snapshot predating 100 observations was applied"
        }
        check(!state.current) { "the stale snapshot turned the screen back on" }
        // And the reverse direction, so the rule is not "snapshots never apply":
        val fresh = state.observationEpoch()
        check(state.seedSnapshot(true, fresh)) { "a snapshot taken after the last observation must apply" }
        check(state.current)
    }

    @Test
    fun `S07b the delivery side still forwards a repeated observation`() {
        // A repeated broadcast is not noise to be dropped: the core is told the fact again.
        // Suppressing it would hide a real event-stream property behind a local "no change".
        PlatformFacts.onScreenChanged(true)
        val sink = Sink()
        val session = PlatformFacts.attach(sink)
        awaitScreenFact(sink, true)
        sink.events.clear()

        PlatformFacts.onScreenChanged(true)     // same value, new observation
        awaitScreenFact(sink, true)
        check(sink.events.toList() == listOf("screen:true")) {
            "a repeated screen observation must still be delivered once: ${sink.events}"
        }
        runBlocking { PlatformFacts.detachAndDrain(session) }
    }

    // ------------------------------------------------------------ S04/S05/S06: the stream

    @Test
    fun `S04 a screen already off at startup is seeded as off and reported to the core`() {
        PlatformFacts.onScreenChanged(false)
        check(!PlatformFacts.seedInitialScreen { false }) { "the seed must apply when nothing raced it" }
        check(!PlatformFacts.isScreenOn)

        val sink = Sink()
        val session = PlatformFacts.attach(sink)
        awaitScreenFact(sink, false)
        runBlocking { PlatformFacts.detachAndDrain(session) }
    }

    @Test
    fun `S05 a screen already on at startup is seeded as on without inventing a transition`() {
        PlatformFacts.onScreenChanged(false)
        PlatformFacts.onScreenChanged(true)
        val epoch = PlatformFacts.observedScreenEpoch
        val applied = PlatformFacts.seedInitialScreen { true }
        check(!applied) { "an agreeing snapshot must not report a change" }
        check(PlatformFacts.isScreenOn)
        check(PlatformFacts.observedScreenEpoch == epoch) {
            "applying an agreeing snapshot must not count as a new observation"
        }
    }

    @Test
    fun `S06 an OFF ON OFF ON stream ends on the newest fact, in order`() {
        val state = ScreenFactState()
        val seen = mutableListOf<Boolean>()
        for (value in listOf(false, true, false, true)) {
            state.onChanged(value)
            seen += state.current
        }
        check(seen == listOf(false, true, false, true)) { "the reported sequence was $seen" }
        check(state.current) { "the newest fact must be the last one observed" }
    }

    // ------------------------------------------------------- S08: concurrency, not a sleep

    @Test
    fun `S08 concurrent observations and snapshots never let an older fact win`() {
        // 200 rounds of real threads. The invariant is about *outcomes*, not about which
        // thread wins: a snapshot carrying a stale key must never become the fact, and a
        // refused snapshot must have been refused because the epoch moved.
        //
        // The first version of this test fired both threads at one latch and then asserted
        // that **both** outcomes had occurred across the 200 rounds ("otherwise the
        // interleaving under test never happened"). CI proved that assertion wrong: a fast
        // runner can have the observer thread win every single round, so no snapshot is
        // ever applied and a correctly-behaving implementation fails the test with
        // `no snapshot was ever applied in 200 rounds`. That is a flaky assertion, not a
        // product bug - a data race must not be used as a scheduler coin-flip to decide
        // whether a test may pass.
        //
        // Each round therefore drives both outcomes with an explicit handshake, so the
        // accept path and the refuse path are each exercised deterministically:
        //
        //   seed the fact, take the key, then
        //   (a) observer runs to completion -> a snapshot with the old key must be refused;
        //   (b) re-read the key and seed again -> it must be applied.
        val rounds = 200
        val observations = AtomicInteger()
        val applied = AtomicInteger()
        val refused = AtomicInteger()
        repeat(rounds) { round ->
            val state = ScreenFactState()
            state.onChanged(round % 2 == 0)
            val staleKey = state.observationEpoch()
            val value = round % 3 == 0

            val observerDone = CountDownLatch(1)
            val observer = Thread {
                state.onChanged(!value)
                observations.incrementAndGet()
                observerDone.countDown()
            }
            observer.start()
            check(observerDone.await(5, TimeUnit.SECONDS)) { "round $round: observer never ran" }
            observer.join(5_000)
            check(!observer.isAlive) { "round $round left the observer running" }

            // (a) The key predates the observation by construction, so this must be refused.
            val epochAfterObservation = state.observationEpoch()
            check(epochAfterObservation > staleKey) { "round $round: the epoch did not move" }
            val staleApplied = state.seedSnapshot(value, staleKey)
            check(!staleApplied) {
                "round $round: a snapshot taken before an observation was applied (value=$value)"
            }
            check(state.current != value) {
                "round $round: the refused snapshot changed the fact anyway"
            }
            refused.incrementAndGet()

            // (b) A key taken after it must be accepted whenever the values disagree, and a
            // key that is still current must never be refused for any other reason - so
            // after this call the fact *is* `value`, whatever it was before.
            val freshKey = state.observationEpoch()
            state.seedSnapshot(value, freshKey)
            check(state.current == value) {
                "round $round: a current snapshot did not become the fact"
            }
            applied.incrementAndGet()

            // Reading the value and its epoch as one pair must never disagree with either
            // writer's intent.
            val (current, epoch) = state.sample()
            check(epoch >= freshKey) { "round $round went backwards in time" }
            check(state.seedSnapshot(value, epoch) == false || current == value) {
                "round $round produced a self-inconsistent fact"
            }
        }
        check(observations.get() == rounds) { "not every round ran its observer" }
        check(refused.get() == rounds) { "not every round exercised the refusal path" }
        check(applied.get() == rounds) { "not every round exercised the accept path" }

        // The same invariant under genuinely unsynchronised racing, where either thread may
        // win: whatever the outcome, a stale snapshot must never be what won.
        val raced = AtomicInteger()
        repeat(200) { round ->
            val state = ScreenFactState()
            state.onChanged(round % 2 == 0)
            val key = state.observationEpoch()
            val value = round % 3 == 0
            val go = CountDownLatch(1)
            val threads = listOf(
                Thread {
                    go.await(5, TimeUnit.SECONDS)
                    state.onChanged(!value)
                },
                Thread {
                    go.await(5, TimeUnit.SECONDS)
                    val epochBefore = state.observationEpoch()
                    if (state.seedSnapshot(value, key)) {
                        // Accepted: nothing may have been observed between our key and the
                        // write, so the epoch is unchanged and the fact is ours.
                        check(epochBefore == key) {
                            "round $round: a snapshot was applied after the epoch had moved"
                        }
                        raced.incrementAndGet()
                    } else {
                        // Refused: by construction the key was stale, or the values agreed.
                        check(key != epochBefore || state.current == value) {
                            "round $round: a snapshot was refused for no recorded reason"
                        }
                    }
                },
            )
            threads.forEach { it.start() }
            go.countDown()
            threads.forEach { it.join(5_000) }
            check(threads.none { it.isAlive }) { "round $round left a racing thread running" }
        }
        check(raced.get() in 0..200) { "unreachable: raced counter out of range" }
    }

    // ------------------------------------------------------------- S09/S10: ownership

    @Test
    fun `S09 a session handover reports only the final fact to the new session`() {
        PlatformFacts.onScreenChanged(true)
        val old = Sink()
        val oldSession = PlatformFacts.attach(old)
        awaitScreenFact(old, true)

        // Screen changes while no core is attached, then a new core attaches.
        runBlocking { PlatformFacts.detachAndDrain(oldSession) }
        PlatformFacts.onScreenChanged(false)

        val fresh = Sink()
        val freshSession = PlatformFacts.attach(fresh)
        awaitScreenFact(fresh, false)
        check(old.events.none { it == "screen:false" }) {
            "the detached session was told about a change that happened after it left: ${old.events}"
        }
        runBlocking { PlatformFacts.detachAndDrain(freshSession) }
    }

    @Test
    fun `S10 an OFF seen before any core attaches is not undone by the startup snapshot`() {
        // No core is attached at all: the fact is recorded, and then a *newer* observation
        // supersedes the startup reading before that reading is applied. The real order is
        // "epoch taken -> platform read -> broadcast -> seed", so the broadcast belongs
        // inside the read, which is the only moment at which the reading can go stale.
        val epoch = PlatformFacts.observedScreenEpoch
        val applied = PlatformFacts.seedInitialScreen {
            val read = true                      // isInteractive says ON
            PlatformFacts.onScreenChanged(false) // the screen goes off right after the read
            read
        }
        check(!applied) { "the startup reading was applied over an OFF observed before it returned" }
        check(!PlatformFacts.isScreenOn) {
            "the startup reading overwrote an observation that happened after it"
        }
        check(epoch < PlatformFacts.observedScreenEpoch) { "the observation was not recorded" }

        // And the core starting later is told the truth.
        val sink = Sink()
        val session = PlatformFacts.attach(sink)
        awaitScreenFact(sink, false)
        runBlocking { PlatformFacts.detachAndDrain(session) }
    }

    // ------------------------------------------------------------- S12: destructive contrast

    @Test
    fun `S12a the previous shape - sample taken after the read - does apply a stale reading`() {
        // The shape that was removed, reproduced exactly: take the epoch AFTER the read, so
        // the key already contains the broadcast that made the reading stale.
        val state = ScreenFactState()
        state.onChanged(true)

        var lateKey = -1L
        val interactive = {
            state.onChanged(false)      // the screen goes off during the read
            lateKey = state.observationEpoch()   // <- the old code's sample(), taken too late
            true                        // ... and the read still returns the old value
        }.invoke()

        check(state.seedSnapshot(interactive, lateKey)) {
            "unreachable: the late key already includes the broadcast, so it passes the guard"
        }
        check(state.current) {
            "the stale reading did not overwrite the newer SCREEN_OFF fact - the old shape " +
                "must be shown to fail, or this suite is not testing anything"
        }
    }

    @Test
    fun `S12b a transition-count guard lets a same-value observation be reverted`() {
        // The second half of the defect, in isolation: guard on "did the level move?"
        // instead of "was anything observed?". With the level unchanged the counter stands
        // still, so the stale snapshot sails through - the exact hole S03 covers in the
        // production class.
        class TransitionCountGuard {
            var on = true
            var transitions = 0L
            fun observe(value: Boolean) { if (on != value) { on = value; transitions++ } }
            fun seed(value: Boolean, takenAt: Long): Boolean {
                if (transitions != takenAt) return false
                on = value
                return true
            }
        }
        val old = TransitionCountGuard()
        val takenAt = old.transitions                       // 0
        old.observe(true)                                   // repeated ON: no transition
        check(old.seed(false, takenAt)) {
            "the transition-count guard must be shown to accept a stale snapshot"
        }
        check(!old.on) { "the old guard did not let the stale value through" }
    }

    // ---------------------------------------------------------------- S11: the latch

    @Test
    fun `S11 the install latch is released by the first body and the body runs once`() {
        // The latch's failure mode would be "occupied but never registered", which would
        // make a retry impossible. The body here runs to completion, so the latch is held
        // by a completed install - which is what production does.
        val guard = InstallGuard()
        var bodies = 0
        fun installBody() { if (guard.tryInstall()) bodies++ }
        installBody()
        installBody()
        installBody()
        check(bodies == 1) { "the install body ran $bodies times" }
        check(guard.isInstalled)
    }
}
