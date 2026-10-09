package com.interstellar.proxy.bg

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Screen-fact ordering, and the install latch (P0-A / P0-B, and the P1-B call-site rule).
 *
 * ## What was actually wrong, stated without overclaiming
 *
 * `install()` registered the screen receiver and then seeded the fact from
 * `PowerManager.isInteractive`. The seed was an unsynchronized field write reached through
 * a *different* code path than the locked broadcast handler.
 *
 * The tempting reading — "a `SCREEN_OFF` between the two writes is overwritten by a staler
 * snapshot" — is **not reachable on today's call path**, and the tests below say so
 * explicitly rather than pretending to have found a live product bug:
 * `context.registerReceiver(receiver, filter)` with no handler dispatches on the main
 * looper, and `Application.onCreate` already occupies it, so a broadcast is queued behind
 * `install()` and cannot interleave.
 *
 * What justified changing the code was the *shape*, not a reproduction: correctness rested
 * on a looper-affinity argument made in a different file and checked nowhere, so adding a
 * handler to the receiver or calling `install()` off the main thread would silently make it
 * live. Both writers now go through one locked entry point ([ScreenFactState.onChanged]),
 * which removes the ordering question instead of answering it.
 *
 * **Round 8 corrected this class in two ways**, both found by reading the source rather
 * than the report:
 *
 *  1. The guard's key was taken *after* the platform read (`sample()` was called once
 *     `isInteractive` had already been read), so the key already contained the very
 *     observation it was supposed to detect. The tests here sampled before the simulated
 *     broadcast, i.e. they pinned the *ideal* usage of the API, not the order
 *     `PlatformFacts.install()` actually performed — green JVM, unprotected production.
 *  2. The key counted level *transitions*, so a broadcast repeating the current value was
 *     invisible to it. See `A04b` for why the old assertion about that was backwards, and
 *     `ScreenSeedWiringTest` for the tests that drive the real production helper
 *     ([PlatformFacts.seedInitialScreen]) instead of a model of it.
 *
 * These tests therefore pin the *rule*, and the destructive contrast shows what the rule
 * is protecting against.
 */
class ScreenFactOrderingTest {

    // ---------------------------------------------------------------- P0-A: ordering

    @Test
    fun `A01 a broadcast that arrives before the seed wins - the seed must not overwrite it`() {
        val state = ScreenFactState()

        // The caller reads the platform value and the version it belongs to.
        val (_, versionWhenRead) = state.sample()
        // A SCREEN_OFF broadcast is handled BEFORE the reading is applied.
        check(state.onChanged(false)) { "SCREEN_OFF must register as a change from the default" }
        // Now the stale reading is applied. It must be rejected, because the screen was
        // observed to move after it was read.
        val applied = state.seedSnapshot(true, versionWhenRead)
        check(!applied) { "a reading taken before a broadcast must not be applied afterwards" }
        check(!state.current) {
            "the stale isInteractive seed overwrote the newer SCREEN_OFF fact"
        }
    }

    @Test
    fun `A02 the normal order - seed then broadcast - also ends on the newer fact`() {
        val state = ScreenFactState()
        val (_, v) = state.sample()
        check(state.seedSnapshot(true, v) || state.current) {
            "the seed applies (or the fail-safe default already agreed)"
        }
        check(state.current) { "the seed is what attach() will report" }
        check(state.onChanged(false)) { "SCREEN_OFF after the seed is a genuine change" }
        check(!state.current) { "the broadcast must win - it is the newer fact" }
    }

    @Test
    fun `A03 a screen already off at process start is reported as off, not as the default`() {
        // The round's explicit question: if the core starts while the screen is off, does
        // attach() send setScreenOn(false) rather than a wrong default true?
        val state = ScreenFactState()
        val (_, v) = state.sample()
        check(state.seedSnapshot(false, v)) { "the seed must be applied when nothing raced it" }
        check(!state.current) { "an already-off screen must not be reported as on" }
    }

    @Test
    fun `A04 repeated identical values are idempotent and reported as unchanged`() {
        val state = ScreenFactState()
        check(!state.onChanged(true)) { "the default is already ON, so this is not a change" }
        check(!state.onChanged(true)) { "still not a change" }
        check(state.current) { "and the value is still on" }
    }

    @Test
    fun `A04b the changed flag tracks level transitions, the epoch tracks observations`() {
        // These are two different questions and they have different answers, which round 8
        // had to separate. The boolean says "did the level move?", which is what the
        // `(unchanged)` debug line and the delivery side care about. The epoch says "was
        // the screen observed at all?", which is the only thing that can decide whether a
        // reading taken earlier is still current.
        //
        // The earlier revision of this test asserted that an unchanged observation must
        // NOT bump the version, reasoning that "an unrelated repeated broadcast would make
        // a legitimately current reading look stale and silently drop it". That reasoning
        // is backwards: a repeated broadcast *is* newer than the reading, so the reading is
        // no longer current and dropping it is the correct outcome. Keeping the old
        // assertion would have required keeping the hole it protected — a stale snapshot
        // could pass the guard and revert the newest observation. See
        // `ScreenSeedWiringTest.S03`, which is the destructive counterpart.
        val state = ScreenFactState()
        val epoch0 = state.observationEpoch()
        check(!state.onChanged(true)) { "ON over the default ON is not a level change" }
        check(state.observationEpoch() != epoch0) { "it is still an observation, so the epoch moves" }
        check(state.onChanged(false)) { "OFF is a real transition" }
        check(state.observationEpoch() > epoch0) { "and it moves the epoch too" }
        check(!state.onChanged(false)) { "a repeated OFF is not a transition" }
        check(state.observationEpoch() > epoch0 + 1) { "but it is still an observation" }
    }

    @Test
    fun `A05 the full event stream ends on the newest fact`() {
        val state = ScreenFactState()
        // SCREEN_OFF -> SCREEN_ON (notification glow, no unlock) -> SCREEN_OFF
        state.onChanged(false); state.onChanged(true); state.onChanged(false)
        check(!state.current) { "the last OFF must be the reported fact" }
        // SCREEN_ON after that must still win.
        state.onChanged(true)
        check(state.current)
    }

    @Test
    fun `A06 a hundred alternating events never leave an older fact as current`() {
        val state = ScreenFactState()
        repeat(100) { i ->
            val value = i % 2 == 0
            state.onChanged(value)
            check(state.current == value) { "iteration $i left ${state.current} instead of $value" }
        }
    }

    // ------------------------------------------- P0-A: the destructive contrast

    @Test
    fun `A07 the old shape - an unlocked snapshot write - does overwrite the newer fact`() {
        // Model of the shape that was replaced: the broadcast path is serialized, but the
        // seed is a plain field write outside it. This is the "old red" the fix removes;
        // it is a model of the SHAPE, not of reachability, and is labelled as such.
        class OldShape {
            @Volatile var on = true
            fun broadcast(value: Boolean) { on = value }      // serialized in production
            fun seed(isInteractive: Boolean) { on = isInteractive }  // unlocked, unguarded
        }
        val old = OldShape()
        old.broadcast(false)      // SCREEN_OFF seen
        old.seed(true)            // stale isInteractive read lands afterwards
        check(old.on) {
            "the old shape must be shown to overwrite the newer fact - that is what A01 pins"
        }
    }

    // ------------------------------------------------- P0-B: the call-site rule

    @Test
    fun `B01 no screen or resume handler reaches a wake or pause primitive`() {
        // The forbidden rewrites, asserted against the SOURCE rather than trusted to
        // review: mapping ACTION_SCREEN_ON to CommandServer.Wake() (which may mean
        // "un-pause the device", not merely "record a Resumed edge"), or reintroducing a
        // Doze pause path that writes the same state as PlatformEvents.
        //
        // `SingBoxCore.pause()`/`wake()` exist in the tree and wrap those command-server
        // calls, so the risk is not hypothetical: they are simply never called. The day
        // someone wires one to a screen event, this fails.
        val root = java.io.File("src/main/java/com/interstellar/proxy")
        check(root.isDirectory) { "cannot find the source root from the test working directory" }
        val offenders = CopyOnWriteArrayList<String>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            // Only files that handle platform facts matter; a definition of pause()/wake()
            // inside the core bridge is expected and allowed.
            val handlesScreenFacts = text.contains("ACTION_SCREEN_ON") ||
                text.contains("ACTION_USER_PRESENT") ||
                text.contains("onScreenChanged") ||
                text.contains("onUserPresent")
            if (!handlesScreenFacts) return@forEach
            if (text.contains(".wake()") || text.contains(".pause()")) {
                offenders += "${file.name} handles screen facts and calls .wake()/.pause()"
            }
        }
        check(offenders.isEmpty()) { "forbidden wake/pause wiring: $offenders" }
    }

    @Test
    fun `B02 a screen event does not touch the network or restart anything`() {
        // The other forbidden family: "wake" implemented as a reconnect. Checked on the
        // file that owns screen facts, because that is where such a call would be added.
        val file = java.io.File("src/main/java/com/interstellar/proxy/bg/PlatformFacts.kt")
        check(file.isFile) { "PlatformFacts.kt not found from the test working directory" }
        val text = file.readText()
        for (forbidden in listOf("ResetNetwork", "restartService", "reload", "closeService",
                                 "Thread.sleep", "delay(")) {
            check(!text.contains(forbidden)) {
                "PlatformFacts must not contain '$forbidden' - a screen fact must not " +
                    "trigger a reconnect, a reset or a timed reaction"
            }
        }
    }


    // ------------------------------------------------- P1-E: network vs wake

    @Test
    fun `E01 the network path cannot reach a screen or wake fact`() {
        // P1-E: a network handoff must not be misread as a screen wake. The structural
        // guarantee is that the two never touch: the files that implement default-network
        // tracking must not mention a screen fact, a wake report or a screen broadcast.
        //
        // The tempting wrong fix this keeps out is "on a network change, wake the core" -
        // a handoff is not a user returning, and reporting it as one would fire
        // reportDeviceWake() for something the user never did.
        val root = java.io.File("src/main/java/com/interstellar/proxy")
        check(root.isDirectory) { "cannot find the source root" }
        val networkFiles = listOf("DefaultNetworkMonitor.kt", "DefaultNetworkListener.kt",
                                  "PlatformInterfaceWrapper.kt", "InterfaceResolutionEpoch.kt")
        val offenders = mutableListOf<String>()
        for (name in networkFiles) {
            val f = root.walkTopDown().firstOrNull { it.name == name } ?: continue
            val text = f.readText()
            for (needle in listOf("reportDeviceWake", "setScreenOn", "ACTION_SCREEN",
                                  "USER_PRESENT", "onUserPresent")) {
                if (text.contains(needle)) offenders += "$name contains $needle"
            }
        }
        check(offenders.isEmpty()) { "the network path touches a wake/screen fact: $offenders" }
    }

    @Test
    fun `E02 reportDeviceWake has exactly one producer, and it is USER_PRESENT`() {
        // The fact's provenance, asserted rather than assumed. If a second producer ever
        // appears, one of them is somebody else's event wearing the user's name.
        val root = java.io.File("src/main/java/com/interstellar/proxy")
        check(root.isDirectory) { "cannot find the source root" }
        val producers = mutableListOf<String>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            f.readText().lineSequence().forEachIndexed { i: Int, line: String ->
                // Count only ENQUEUES of the fact, not its declaration or its bridge
                // forwarding. The first cut also excluded anything containing
                // "sink.reportDeviceWake" and so excluded the real enqueue as well - the
                // actual line reads `enqueue(live) { sink -> sink.reportDeviceWake() }` -
                // which made the list come back empty. Exclude DECLARATIONS, not calls.
                if (line.contains("reportDeviceWake()") &&
                    !line.contains("fun reportDeviceWake") &&
                    !line.contains("override fun") &&
                    !line.contains("platformEvents?.reportDeviceWake")
                ) {
                    producers += "${f.name}:${i + 1}"
                }
            }
        }
        check(producers.size == 1) {
            "expected exactly one enqueue site for reportDeviceWake, found $producers"
        }
        // ... and that single site is the USER_PRESENT path.
        //
        // Read the function body by line scan rather than a delimiter substring: the first
        // cut embedded a literal newline inside a string literal here and did not compile.
        val factLines = java.io.File(root, "bg/PlatformFacts.kt").readText().lines()
        val start = factLines.indexOfFirst { it.contains("fun onUserPresent()") }
        check(start >= 0) { "onUserPresent() not found in PlatformFacts.kt" }
        val body = factLines.subList(start, minOf(start + 3, factLines.size)).joinToString(" ")
        check(body.contains("reportDeviceWake")) {
            "the single reportDeviceWake producer is not onUserPresent(): $body"
        }
    }

    @Test
    fun `E03 no screen handler rebuilds the tunnel`() {
        // The other forbidden family: "screen on -> rebuild the VPN" as a blanket remedy.
        val root = java.io.File("src/main/java/com/interstellar/proxy")
        check(root.isDirectory) { "cannot find the source root" }
        val offenders = mutableListOf<String>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            if (!text.contains("ACTION_SCREEN") && !text.contains("onScreenChanged")) return@forEach
            for (needle in listOf("ResetNetwork", "restartService", "closeService(",
                                  "rebuildVpn", "forceReconnect")) {
                if (text.contains(needle)) offenders += "${f.name} contains $needle"
            }
        }
        check(offenders.isEmpty()) { "a screen handler rebuilds the tunnel: $offenders" }
    }

    // ---------------------------------------------------------- the install latch

    @Test
    fun `A08 install runs its body exactly once`() {
        val guard = InstallGuard()
        check(guard.tryInstall()) { "the first install must proceed" }
        check(!guard.tryInstall()) { "a second install must be refused" }
        check(!guard.tryInstall()) { "and every later one too" }
        check(guard.isInstalled)
    }

    @Test
    fun `A09 the install latch holds under concurrent callers`() {
        repeat(200) {
            val guard = InstallGuard()
            val go = CountDownLatch(1)
            val winners = CopyOnWriteArrayList<Int>()
            val threads = (1..8).map { i ->
                Thread {
                    go.await(5, TimeUnit.SECONDS)
                    if (guard.tryInstall()) winners += i
                }
            }
            threads.forEach { it.start() }
            go.countDown()
            threads.forEach { it.join(5_000) }
            check(winners.size == 1) { "expected exactly one winner, got $winners" }
        }
    }
}
