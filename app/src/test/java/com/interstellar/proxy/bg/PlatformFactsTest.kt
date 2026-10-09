package com.interstellar.proxy.bg

import com.interstellar.proxy.core.PlatformFactSink
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Android side may only *report facts*; every decision lives in the core's
 * shared policy.
 *
 * Two classes of property are pinned here:
 *
 *  - **forwarding** — levels and booleans arrive verbatim, one fact produces
 *    exactly one call (no Kotlin-invented release/reconnect), and nothing is
 *    delivered while detached.
 *  - **ownership** — a delivery belongs to the session that created it, an
 *    in-flight native call is drained before the bridge may be closed, and a stale
 *    token can never unbind a newer session.
 *
 * `install()` is not exercised here — it needs a real Context; its two Android
 * sources are covered by the emulator acceptance run.
 */
class PlatformFactsTest {

    private open class RecordingSink : PlatformFactSink {
        val events = CopyOnWriteArrayList<String>()

        override fun memoryTrim(level: Int) {
            events += "trim:$level"
        }

        override fun setAppForeground(foreground: Boolean) {
            events += "foreground:$foreground"
        }

        override fun setScreenOn(on: Boolean) {
            events += "screen:$on"
        }

        override fun reportDeviceWake() {
            events += "wake"
        }
    }

    /** Blocks inside its first call until [gate] opens, so later tasks queue behind it. */
    private class GatedFirstCallSink(
        private val entered: CountDownLatch,
        private val gate: CountDownLatch,
    ) : RecordingSink() {
        private val first = java.util.concurrent.atomic.AtomicBoolean(true)

        override fun setScreenOn(on: Boolean) {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                gate.await(5, TimeUnit.SECONDS)
            }
            super.setScreenOn(on)
        }
    }

    @After
    fun tearDown() {
        // No tokenless "detach everything" API exists any more, so a test cleanup must
        // name the session it is tearing down.
        PlatformFacts.attachedSession?.let { live -> runBlocking { PlatformFacts.detachAndDrain(live) } }
        // leave deterministic facts for the next test
        PlatformFacts.onScreenChanged(true)
        PlatformFacts.onForegroundChanged(false)
    }

    private fun awaitAtLeast(sink: RecordingSink, count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (sink.events.size < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        check(sink.events.size >= count) { "expected >= $count events, got ${sink.events}" }
    }

    private fun settle() = Thread.sleep(150)

    // ---- forwarding ----

    @Test
    fun `attach immediately reports the current screen and foreground state`() {
        PlatformFacts.onScreenChanged(false)
        PlatformFacts.onForegroundChanged(true)
        val sink = RecordingSink()
        PlatformFacts.attach(sink)
        awaitAtLeast(sink, 2)
        check(sink.events.contains("screen:false")) { "screen fact must be seeded: ${sink.events}" }
        check(sink.events.contains("foreground:true")) { "foreground fact must be seeded: ${sink.events}" }
    }

    @Test
    fun `raw android trim levels are forwarded unchanged`() {
        val sink = RecordingSink()
        PlatformFacts.attach(sink)
        awaitAtLeast(sink, 2)
        sink.events.clear()

        val levels = listOf(5, 10, 15, 20, 40, 60, 80)
        levels.forEach { PlatformFacts.onMemoryTrim(it) }
        awaitAtLeast(sink, levels.size)

        check(sink.events.toList() == levels.map { "trim:$it" }) {
            "levels must be forwarded verbatim and in order, got ${sink.events}"
        }
    }

    @Test
    fun `ui hidden is reported as a fact and nothing else`() {
        val sink = RecordingSink()
        PlatformFacts.attach(sink)
        awaitAtLeast(sink, 2)
        sink.events.clear()

        val trimMemoryUiHidden = 20
        PlatformFacts.onMemoryTrim(trimMemoryUiHidden)
        awaitAtLeast(sink, 1)
        settle()

        check(sink.events.toList() == listOf("trim:$trimMemoryUiHidden")) {
            "UI_HIDDEN must not be translated into a trim/release/reconnect: ${sink.events}"
        }
    }

    @Test
    fun `one trim produces exactly one forwarded call`() {
        val sink = RecordingSink()
        PlatformFacts.attach(sink)
        awaitAtLeast(sink, 2)
        sink.events.clear()

        PlatformFacts.onMemoryTrim(80)
        awaitAtLeast(sink, 1)
        settle()

        check(sink.events.size == 1) { "Kotlin must not invent extra actions: ${sink.events}" }
        check(sink.events[0] == "trim:80")
    }

    @Test
    fun `foreground and screen booleans are forwarded as-is`() {
        val sink = RecordingSink()
        PlatformFacts.attach(sink)
        awaitAtLeast(sink, 2)
        sink.events.clear()

        PlatformFacts.onScreenChanged(false)
        PlatformFacts.onScreenChanged(true)
        PlatformFacts.onForegroundChanged(false)
        PlatformFacts.onForegroundChanged(true)
        awaitAtLeast(sink, 4)

        check(
            sink.events.toList() == listOf(
                "screen:false", "screen:true", "foreground:false", "foreground:true",
            ),
        ) { "facts must arrive verbatim and in order, got ${sink.events}" }
    }

    @Test
    fun `user present forwards exactly one wake fact`() {
        val sink = RecordingSink()
        PlatformFacts.attach(sink)
        awaitAtLeast(sink, 2)
        sink.events.clear()

        PlatformFacts.onUserPresent()
        awaitAtLeast(sink, 1)
        settle()

        check(sink.events.toList() == listOf("wake")) {
            "USER_PRESENT must report a wake fact and nothing more: ${sink.events}"
        }
    }

    @Test
    fun `detached sink receives nothing`() {
        val sink = RecordingSink()
        PlatformFacts.attach(sink)
        awaitAtLeast(sink, 2)
        PlatformFacts.attachedSession?.let { live -> runBlocking { PlatformFacts.detachAndDrain(live) } }
        sink.events.clear()

        PlatformFacts.onMemoryTrim(80)
        PlatformFacts.onScreenChanged(false)
        PlatformFacts.onForegroundChanged(false)
        PlatformFacts.onUserPresent()
        settle()

        check(sink.events.isEmpty()) { "a detached sink must not be touched: ${sink.events}" }
    }

    @Test
    fun `facts recorded while detached are still reported when the next core attaches`() {
        PlatformFacts.attachedSession?.let { live -> runBlocking { PlatformFacts.detachAndDrain(live) } }
        PlatformFacts.onScreenChanged(false)
        PlatformFacts.onForegroundChanged(false)
        settle()

        val sink = RecordingSink()
        PlatformFacts.attach(sink)
        awaitAtLeast(sink, 2)

        check(sink.events.contains("screen:false")) {
            "a core started while the screen is off must be told: ${sink.events}"
        }
        check(sink.events.contains("foreground:false")) {
            "a core started while the app is backgrounded must be told: ${sink.events}"
        }
    }

    @Test
    fun `an initial report never overwrites a newer screen fact`() {
        PlatformFacts.onScreenChanged(true)
        val sink = RecordingSink()
        PlatformFacts.attach(sink)
        // Enqueued after the initial report; FIFO must make it the final screen fact.
        PlatformFacts.onScreenChanged(false)
        awaitAtLeast(sink, 3)
        settle()

        check(sink.events.last { it.startsWith("screen:") } == "screen:false") {
            "a stale initial report overwrote a newer fact: ${sink.events}"
        }
    }

    // ---- ownership ----

    @Test
    fun `a queued initial report never touches a detached sink`() {
        PlatformFacts.onScreenChanged(true)
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val sink = GatedFirstCallSink(entered, gate)

        val session = PlatformFacts.attach(sink)
        check(entered.await(5, TimeUnit.SECONDS)) { "the initial report never started" }

        // These queue behind the blocked initial report.
        PlatformFacts.onMemoryTrim(80)
        PlatformFacts.onScreenChanged(false)

        val drained = CountDownLatch(1)
        Thread {
            runBlocking { PlatformFacts.detachAndDrain(session) }
            drained.countDown()
        }.start()

        // Deterministic handshake: detachAndDrain closes the ownership gate
        // synchronously before it waits on the barrier, so wait for the gate itself
        // rather than sleeping and hoping the detach won the race.
        val deadline = System.currentTimeMillis() + 5_000
        while (PlatformFacts.attachedSession != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(2)
        }
        check(PlatformFacts.attachedSession == null) { "the ownership gate never closed" }

        // Let the blocked call finish; everything queued behind it must now be a no-op.
        gate.countDown()
        check(drained.await(5, TimeUnit.SECONDS)) { "drain did not complete" }
        settle()

        check(sink.events.none { it == "trim:80" }) {
            "a queued fact reached a detached sink: ${sink.events}"
        }
        check(sink.events.none { it == "screen:false" }) {
            "a queued screen fact reached a detached sink: ${sink.events}"
        }
    }

    @Test
    fun `detachAndDrain waits for an in-flight native call`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val sink = object : RecordingSink() {
            override fun memoryTrim(level: Int) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                super.memoryTrim(level)
                finished.countDown()
            }
        }

        PlatformFacts.attach(sink)
        awaitAtLeast(sink, 2)
        PlatformFacts.onMemoryTrim(80)
        check(started.await(5, TimeUnit.SECONDS)) { "the lane never started the trim call" }

        val live = PlatformFacts.attachedSession!!
        val drained = CountDownLatch(1)
        Thread {
            runBlocking { PlatformFacts.detachAndDrain(live) }
            drained.countDown()
        }.start()

        // The whole point of the drain: it must not report "done" while a native call
        // is still executing against the bridge that is about to be closed.
        check(!drained.await(300, TimeUnit.MILLISECONDS)) {
            "detachAndDrain returned while a native call was still in flight"
        }

        release.countDown()
        check(finished.await(5, TimeUnit.SECONDS)) { "in-flight call never finished" }
        check(drained.await(5, TimeUnit.SECONDS)) { "detachAndDrain must complete after the drain" }
    }


    // ---- P0-C: the handover itself ----

    @Test
    fun `B stays bound and keeps receiving while A is being drained`() {
        // The P0-C handover in one test: A is mid-native-call, A's teardown starts, and B
        // starts and attaches BEFORE A's teardown finishes.
        //
        // This is the shape of the round-7 Stop->Restart defect at the fact-bridge layer.
        // The property that matters is not merely "A is discarded" (already covered) but
        // "**B is still bound and still receives events**" while A's teardown is running.
        PlatformFacts.attachedSession?.let { live -> runBlocking { PlatformFacts.detachAndDrain(live) } }
        PlatformFacts.onScreenChanged(true)

        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val a = GatedFirstCallSink(entered, gate)
        val aSession = PlatformFacts.attach(a)
        check(entered.await(5, TimeUnit.SECONDS)) { "A's initial report never started" }

        // A's teardown begins while A is still inside that call.
        PlatformFacts.onMemoryTrim(20)          // queues behind A's blocked call
        val drained = CountDownLatch(1)
        Thread {
            runBlocking { PlatformFacts.detachAndDrain(aSession) }
            drained.countDown()
        }.start()

        // Deterministic: the ownership gate closes synchronously before the wait.
        val deadline = System.currentTimeMillis() + 5_000
        while (PlatformFacts.attachedSession != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(2)
        }
        check(PlatformFacts.attachedSession == null) { "A's gate never closed" }

        // B attaches while A's teardown is still parked inside the native call.
        val b = RecordingSink()
        val bSession = PlatformFacts.attach(b)
        check(PlatformFacts.attachedSession?.id == bSession.id) { "B did not become the live session" }

        // Release A. Everything A had queued must vanish; B must be untouched by that.
        gate.countDown()
        check(drained.await(5, TimeUnit.SECONDS)) { "A's drain never completed" }
        settle()

        check(a.events.none { it == "trim:20" }) {
            "A's queued fact reached the detached sink: ${a.events}"
        }
        check(b.events.isNotEmpty()) {
            "B received nothing even though it attached while A was draining"
        }

        // And B is genuinely still the live session: a new fact reaches B and not A.
        val aCountBefore = a.events.size
        PlatformFacts.onScreenChanged(false)
        awaitAtLeast(b, b.events.size + 1)
        settle()
        check(b.events.contains("screen:false")) {
            "B stopped receiving after A's teardown: ${b.events}"
        }
        check(a.events.size == aCountBefore) {
            "A received a fact after its teardown finished: ${a.events}"
        }
        check(b.events.none { false }) { "unreachable" }
        // B's own close, so the next test starts clean.
        runBlocking { PlatformFacts.detachAndDrain(bSession) }
    }

    @Test
    fun `a drain that cannot be proven reports failure instead of claiming safety`() {
        // P0-C item 6. A sink that is permanently wedged must produce "cannot prove the
        // drain" - NOT a false success, and NOT a hang. `detachAndDrain` returns false, and
        // the caller (BoxService) is then required not to close native state it cannot
        // prove is idle.
        //
        // The sink blocks past the class's own 5 s DRAIN_TIMEOUT_MS, so the answer is
        // produced by the timeout rather than by the call finishing. This test therefore
        // costs ~5 s; that is the price of exercising the real bound instead of a mocked one.
        PlatformFacts.attachedSession?.let { live -> runBlocking { PlatformFacts.detachAndDrain(live) } }

        val entered = CountDownLatch(1)
        val never = CountDownLatch(1)
        val wedged = object : RecordingSink() {
            private val first = java.util.concurrent.atomic.AtomicBoolean(true)
            override fun setScreenOn(on: Boolean) {
                if (first.compareAndSet(true, false)) {
                    entered.countDown()
                    never.await(30, TimeUnit.SECONDS)   // longer than DRAIN_TIMEOUT_MS
                }
                super.setScreenOn(on)
            }
        }
        val session = PlatformFacts.attach(wedged)
        check(entered.await(5, TimeUnit.SECONDS)) { "the wedged call never started" }

        var proven: Boolean? = null
        val done = CountDownLatch(1)
        Thread {
            proven = runBlocking { PlatformFacts.detachAndDrain(session) }
            done.countDown()
        }.start()

        check(done.await(15, TimeUnit.SECONDS)) {
            "detachAndDrain hung past its own bound; a teardown must not hang forever"
        }
        check(proven == false) {
            "an unprovable drain reported success (got $proven) - the caller would then " +
                "close a native bridge that may still be in use"
        }

        never.countDown()   // let the wedged call finish so the lane is usable again
        settle()
    }

    @Test
    fun `a stale token cannot unbind a newer session`() {
        val first = RecordingSink()
        val stale = PlatformFacts.attach(first)
        awaitAtLeast(first, 2)
        runBlocking { PlatformFacts.detachAndDrain(stale) }

        val second = RecordingSink()
        PlatformFacts.attach(second)
        awaitAtLeast(second, 2)

        // An old service instance finishing late must not tear the new bridge down.
        runBlocking { PlatformFacts.detachAndDrain(stale) }
        second.events.clear()
        PlatformFacts.onUserPresent()
        awaitAtLeast(second, 1)

        check(second.events.toList() == listOf("wake")) {
            "a stale detach unbound the live session: ${second.events}"
        }
    }

    @Test
    fun `a replaced session stops receiving facts`() {
        PlatformFacts.onScreenChanged(true)
        val old = RecordingSink()
        PlatformFacts.attach(old)
        awaitAtLeast(old, 2)

        PlatformFacts.attachedSession?.let { live -> runBlocking { PlatformFacts.detachAndDrain(live) } }
        val fresh = RecordingSink()
        PlatformFacts.attach(fresh)
        awaitAtLeast(fresh, 2)
        old.events.clear()

        PlatformFacts.onMemoryTrim(60)
        PlatformFacts.onScreenChanged(false)
        awaitAtLeast(fresh, 2)
        settle()

        check(old.events.isEmpty()) { "the replaced session still received facts: ${old.events}" }
        check(fresh.events.contains("trim:60")) { "the live session missed facts: ${fresh.events}" }
    }

    // ---- ownership: the two hazards the previous revision left open ----

    @Test
    fun `a replaced session still drains its in-flight call and leaves the new one alone`() {
        // The old implementation returned early when `expected` was no longer current:
        // it neither waited for A's running native call NOR touched B. The first half of
        // that was a use-after-close waiting to happen, because the caller closes A's
        // bridge right after this returns.
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val sinkA = object : RecordingSink() {
            override fun memoryTrim(level: Int) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                super.memoryTrim(level)
                finished.countDown()
            }
        }
        val sessionA = PlatformFacts.attach(sinkA)
        awaitAtLeast(sinkA, 2)
        PlatformFacts.onMemoryTrim(64)
        check(started.await(5, TimeUnit.SECONDS)) { "A's trim never started" }

        // B replaces A while A's native call is still blocked. B's own initial report
        // queues behind that call — the lane is deliberately serial, so it is NOT
        // expected to arrive yet.
        val sinkB = RecordingSink()
        PlatformFacts.attach(sinkB)

        val drained = CountDownLatch(1)
        var drainResult: Boolean? = null
        Thread {
            drainResult = runBlocking { PlatformFacts.detachAndDrain(sessionA) }
            drained.countDown()
        }.start()

        // Must NOT report "drained" while A's call is still executing.
        check(!drained.await(300, TimeUnit.MILLISECONDS)) {
            "detachAndDrain(A) returned while A's native call was still in flight"
        }
        release.countDown()
        check(finished.await(5, TimeUnit.SECONDS)) { "A's call never finished" }
        check(drained.await(5, TimeUnit.SECONDS)) { "the drain never completed" }
        check(drainResult == true) { "a completed drain must report true" }

        // B was never unbound: once the lane drains, B gets both its own initial report
        // and the next fact.
        awaitAtLeast(sinkB, 2)
        check(sinkB.events.contains("screen:true")) { "B never got its initial report: ${sinkB.events}" }
        sinkB.events.clear()
        PlatformFacts.onUserPresent()
        awaitAtLeast(sinkB, 1)
        check(sinkB.events.contains("wake")) {
            "draining the replaced session A damaged the live session B: ${sinkB.events}"
        }
    }

    @Test
    fun `unbinding is refused for a session that is no longer current`() {
        val first = RecordingSink()
        val sessionA = PlatformFacts.attach(first)
        awaitAtLeast(first, 2)
        val second = RecordingSink()
        PlatformFacts.attach(second)
        awaitAtLeast(second, 2)

        // A is stale; the call must still drain (returns true) but must not unbind B.
        val drained = runBlocking { PlatformFacts.detachAndDrain(sessionA) }
        check(drained) { "a stale session must still be drainable" }
        check(PlatformFacts.attachedSession != null) { "B was unbound by A's teardown" }
        second.events.clear()
        PlatformFacts.onMemoryTrim(80)
        awaitAtLeast(second, 1)
        check(second.events.contains("trim:80")) { "B stopped receiving facts: ${second.events}" }
    }

    @Test
    fun `an undrainable lane is reported instead of pretending success`() {
        // A sink that never returns blocks the single worker forever. The drain must
        // give up after its bound and report "not proven" rather than hanging teardown
        // or claiming the session is idle.
        val entered = CountDownLatch(1)
        val never = CountDownLatch(1)
        val sink = object : RecordingSink() {
            override fun memoryTrim(level: Int) {
                entered.countDown()
                never.await(30, TimeUnit.SECONDS)
                super.memoryTrim(level)
            }
        }
        val session = PlatformFacts.attach(sink)
        awaitAtLeast(sink, 2)
        PlatformFacts.onMemoryTrim(80)
        check(entered.await(5, TimeUnit.SECONDS)) { "the blocking trim never started" }

        val startedAt = System.currentTimeMillis()
        val proven = try {
            runBlocking { PlatformFacts.detachAndDrain(session) }
        } finally {
            // MUST run even if the assertions below fail: the worker is shared by every
            // test in this class, and leaving it blocked would cascade failures into
            // unrelated tests instead of reporting one honest failure.
            never.countDown()
        }
        val elapsed = System.currentTimeMillis() - startedAt
        check(!proven) { "an undrainable lane must report false" }
        check(elapsed < 20_000) { "the drain must be bounded, took ${elapsed}ms" }
    }
}
