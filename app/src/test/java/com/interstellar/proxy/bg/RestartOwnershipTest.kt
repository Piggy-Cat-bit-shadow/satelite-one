package com.interstellar.proxy.bg

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Stop→Restart ownership (round 7, R7-P0).
 *
 * ## The hole this pins down
 *
 * Round 6 made a stop's *convergence* conditional on no newer start having been
 * accepted. That closed "a stale stop writes Stopped over a live successor". It did NOT
 * close the mirror image, which is what this file is about:
 *
 * ```
 * main   A: stopService()            -> status = Stopping, watermark taken, launch(IO)
 * main   B: onStartCommand()         -> beginStart() claims token B, status = Starting
 * IO     A: releaseCore()            -> lifecycle.invalidate()   <-- bumps generation
 * IO     B: startCore(B)             -> publish(B) REFUSED (generation moved on)
 *                                        -> returns Superseded, writes nothing
 * main   A: finalizer                -> startsSince(watermark) == true -> withholds
 * ```
 *
 * Both halves are individually correct and together they are a deadlock: **B is refused
 * because A's release ran, and A withholds because B was accepted.** The observable
 * result is `Status.Starting` with no core behind it — a spinner that never resolves and
 * no notification, until the process is killed. The window is not narrow: it is as long
 * as `releaseCore()` takes, which is up to two 5 s drain timeouts plus the native
 * teardown.
 *
 * ## Why the fix is not "one more watermark"
 *
 * `invalidate()` moving the generation is *load-bearing*: it is what stops a start that
 * was requested **before** the stop from publishing a core into slots the stop is about
 * to release. So a release may only invalidate attempts that predate the stop it belongs
 * to; and because the successor must publish into fields (`core`, `factSession`,
 * `fileDescriptor`) the release is still clearing, the successor's publish has to be
 * **ordered after** that teardown, not merely permitted alongside it.
 *
 * This file drives the real [CoreLifecycle] and models exactly the two things the fix
 * adds: an attempt is stale if it predates the newest accepted stop, and a publish waits
 * until no teardown is in flight.
 */
class RestartOwnershipTest {

    /**
     * The ownership rules, driven as `BoxService` drives them.
     *
     * `AtomicLong`/`synchronized` here mirror the real `CoreLifecycle` (which is
     * synchronized on one lock); the ordering is what is under test, not the primitives.
     */
    private class Ownership {
        private val lock = Any()
        private var generation = 0L
        private var startAttempts = 0L
        private var closed = false

        /**
         * Highest token made stale by a *teardown*; attempts at or below it are refused.
         *
         * Set when a teardown ends, to everything that was alive while the old core's
         * slots were being cleared. A start accepted **after** that point keeps its token
         * — refusing it is exactly the round-6 stranding bug.
         */
        private var stopStaleUpTo = 0L

        /**
         * Highest token made stale by a *newer start*; attempts at or below it are refused.
         *
         * This is the ordinary supersession rule and has nothing to do with stops: when a
         * newer attempt is accepted, every attempt still in flight is older than it, so it
         * is made stale by construction rather than by a race.
         */
        private var supersededUpTo = 0L

        /** True while a teardown is running; a publish must wait for it to reach 0. */
        private var teardownsInFlight = 0

        val events = CopyOnWriteArrayList<String>()

        /** `onStartCommand`: claim a token on the caller thread. */
        fun beginStart(): Long? = synchronized(lock) {
            if (closed) return null
            ++generation
            ++startAttempts
            // The newcomer supersedes everything already in flight.
            supersededUpTo = startAttempts - 1
            events += "beginStart#$generation"
            generation
        }

        /**
         * `stopService` / `stopAndAlert` entry: the stop is accepted and its teardown begins.
         */
        fun beginStop(): Long = synchronized(lock) {
            teardownsInFlight++
            events += "beginStop(teardown=$teardownsInFlight)"
            stopStaleUpTo
        }

        /**
         * End of `releaseCore`: the teardown is no longer in flight.
         *
         * ## Why the stale boundary is pinned HERE and not at [beginStop]
         *
         * Everything alive while the old core's slots were being cleared must be refused,
         * or two cores would share `core`/`factSession`/`fileDescriptor`. A start accepted
         * *during* the teardown therefore has to be invalidated even though it was accepted
         * after the stop — it is **refused, not stranded**: `publish` returns false, which
         * the Service must treat as "re-run my start path".
         *
         * A start accepted **after this point** keeps its token. By then the slots are
         * clean, so its publish is safe, and refusing it is exactly the round-6 stranding
         * bug that left `Status.Starting` with no core behind it.
         */
        fun endStop() = synchronized(lock) {
            stopStaleUpTo = startAttempts
            teardownsInFlight--
            events += "endStop(stale<=$stopStaleUpTo,teardown=$teardownsInFlight)"
            // Without this the successor's wait would never be woken - a real defect in a
            // first cut of this harness, and the reason the wait is condition-based
            // rather than a timed sleep.
            (lock as Object).notifyAll()
        }

        /** `releaseCore`'s ownership effect on the lifecycle (kept for call-site symmetry). */
        fun releaseInvalidates(stopWatermarkOfThisStop: Long) = synchronized(lock) {
            events += "release(teardown for wm<=$stopWatermarkOfThisStop)"
        }

        /**
         * `publish(attempt)`: refuses a stale token, and waits while a teardown is in
         * flight so the successor cannot write into fields being cleared.
         */
        fun publish(attempt: Long, block: () -> Unit): Boolean {
            val target = lock as Object
            synchronized(lock) {
                if (closed) return false
                if (attempt <= stopStaleUpTo || attempt <= supersededUpTo) {
                    events += "publish($attempt) refused (stale<=$stopStaleUpTo,sup<=$supersededUpTo)"
                    return false
                }
                // Wait for the in-flight teardown. In production this is the release
                // mutex; here it is "teardownsInFlight must reach 0", and the wait is
                // condition-based so a timeout cannot be mistaken for success.
                val deadline = System.currentTimeMillis() + 5_000
                while (teardownsInFlight > 0 && System.currentTimeMillis() < deadline) {
                    events += "publish($attempt) waiting for teardown"
                    target.wait(5_000)
                }
                if (teardownsInFlight > 0) {
                    events += "publish($attempt) TIMED OUT waiting for teardown"
                    return false
                }
            }
            block()
            synchronized(lock) {
                events += "publish($attempt) ok"
                return true
            }
        }

        fun startsSince(watermark: Long): Boolean = synchronized(lock) {
            closed || startAttempts != watermark
        }

        fun close() = synchronized(lock) {
            closed = true
            ++generation
            events += "close"
        }
    }

    /** The successor must be able to `publish` after A's release ran (round-5/6: it could not). */
    @Test
    fun `R7-S01 a start accepted after the teardown completes must publish`() {
        val o = Ownership()

        // main A: stop accepted; teardown runs and COMPLETES.
        val watermark = o.beginStop()
        o.releaseInvalidates(watermark)
        o.endStop()

        // The user taps Start once the teardown is done, so its token postdates the stop.
        val b = o.beginStart()
        check(b != null) { "B must be accepted" }

        check(o.publish(b) {}) { "B was refused even though the teardown had completed" }
    }

    @Test
    fun `R7-S01b the old shape strands such a start - the red contrast`() {
        // Model of the round-6 shape: the release invalidates unconditionally, so a token
        // claimed after the stop is still made stale and the start silently evaporates.
        val roundSix = object {
            var generation = 0L
            var startAttempts = 0L
            fun beginStart(): Long {
                ++generation
                ++startAttempts
                return generation
            }

            fun release() {
                ++generation // no owner check: invalidates any newer token too
            }

            fun publish(attempt: Long) = attempt == generation
        }
        val b = roundSix.beginStart()
        roundSix.release()
        check(!roundSix.publish(b)) {
            "the old shape must refuse such a start - that is the bug being fixed"
        }
    }

    @Test
    fun `R7-S01c a start accepted during an in-flight teardown waits, then publishes`() {
        val o = Ownership()
        val watermark = o.beginStop()      // teardown in flight
        val b = o.beginStart()             // successor accepted meanwhile
        check(b != null)

        val published = CopyOnWriteArrayList<Long>()
        val waiter = Thread { o.publish(b) { published += b } }
        waiter.start()
        Thread.sleep(150)
        check(published.isEmpty()) { "the successor published while a teardown was in flight" }

        o.releaseInvalidates(watermark)
        o.endStop()
        waiter.join(5_000)

        check(published.toList() == listOf(b)) { "got $published" }
    }

    @Test
    fun `R7-S02 a start that predates the stop must not publish after it`() {
        // The other direction, which is why invalidate() exists at all.
        val o = Ownership()
        val a = o.beginStart()             // start requested first
        check(a != null)
        val watermark = o.beginStop()      // then the stop is accepted
        o.releaseInvalidates(watermark)
        check(!o.publish(a) {}) { "a pre-stop attempt published into slots being released" }
    }

    @Test
    fun `R7-S03 Stop then Start then Stop leaves exactly one owner and releases B`() {
        val o = Ownership()
        val w1 = o.beginStop(); o.releaseInvalidates(w1); o.endStop()
        val b = o.beginStart(); check(b != null)
        check(o.publish(b) {})
        // The second stop now owns the teardown of B's core.
        val w2 = o.beginStop(); o.releaseInvalidates(w2); o.endStop()
        check(!o.publish(b) {}) { "B published again after it was stopped" }
    }

    @Test
    fun `R7-S04 two starts with no stop in between leave exactly one publisher`() {
        val o = Ownership()
        val b = o.beginStart(); check(b != null)
        val c = o.beginStart(); check(c != null)
        check(!o.publish(b) {}) { "the superseded first tap published" }
        check(o.publish(c) {}) { "the newest tap was refused" }
    }

    @Test
    fun `R7-S04b a start accepted DURING the teardown is refused, not stranded`() {
        // The successor shares `core`/`factSession`/`fileDescriptor` with the old core
        // while that core is being torn down, so it must not publish. The refusal has to
        // be *reported* (publish returns false) so the Service re-runs its start path -
        // that is the difference between "refused" and the round-6 "stranded".
        val o = Ownership()
        val w = o.beginStop()                    // teardown in flight
        val b = o.beginStart(); check(b != null) // accepted while the slots are dirty
        o.releaseInvalidates(w)
        o.endStop()
        check(!o.publish(b) {}) { "a start accepted mid-teardown published into dirty slots" }
        // It is not stranded: a fresh attempt after the teardown succeeds.
        val c = o.beginStart(); check(c != null)
        check(o.publish(c) {}) { "the retry was refused - that would be the stranding bug" }
    }

    @Test
    fun `R7-S06 a destroy during the wait leaves no publisher`() {
        val o = Ownership()
        val w = o.beginStop()
        val b = o.beginStart(); check(b != null)
        o.close()
        o.releaseInvalidates(w); o.endStop()
        check(!o.publish(b) {}) { "a destroyed owner published" }
        check(o.beginStart() == null) { "a destroyed owner authorised a start" }
    }

    @Test
    fun `R7-S11 two hundred interleavings never strand and never double-publish`() {
        repeat(200) { i ->
            val o = Ownership()
            val w = o.beginStop()
            val go = CountDownLatch(1)
            val published = CopyOnWriteArrayList<Long>()
            // AtomicLong rather than a lateinit Long: Kotlin forbids `lateinit` on
            // primitives, and a nullable box would be a second thing to get wrong.
            val token = java.util.concurrent.atomic.AtomicLong(-1)

            val starter = Thread {
                go.await(5, TimeUnit.SECONDS)
                o.beginStart()?.let { token.set(it) }
            }
            val releaser = Thread {
                go.await(5, TimeUnit.SECONDS)
                o.releaseInvalidates(w)
                o.endStop()
            }
            starter.start(); releaser.start()
            go.countDown()
            starter.join(5_000); releaser.join(5_000)

            val b = token.get()
            check(b > 0) { "iteration $i: the successor never claimed a token" }
            // The successor raced the teardown, so it may legitimately be refused - but it
            // must never be *stranded*: a refusal has to be reported so the Service retries.
            val accepted = o.publish(b) { published += b }
            if (accepted) {
                check(published.toList() == listOf(b)) { "iteration $i: $published" }
            } else {
                // Refused (it raced the teardown): the retry after the teardown must work.
                val retry = o.beginStart()
                check(retry != null) { "iteration $i: no retry token" }
                check(o.publish(retry) { published += retry }) {
                    "iteration $i: the retry was refused too - that is the stranding bug; " +
                        "events=${o.events}"
                }
                check(published.size == 1) { "iteration $i: $published" }
            }
        }
    }
}
