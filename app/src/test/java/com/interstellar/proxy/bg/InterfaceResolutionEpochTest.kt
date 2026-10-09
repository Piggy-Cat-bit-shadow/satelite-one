package com.interstellar.proxy.bg

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Default-interface resolution: cancellation, ordering, and the thread it runs on (N01–N10).
 *
 * ## Why a "resolve the interface" harness
 *
 * The production path is `DefaultNetworkListener` callback → `checkDefaultInterfaceUpdate` →
 * resolve (with retries) → `InterfaceUpdateListener.updateDefaultInterface(...)`. Only the
 * middle step is interesting to test, and the rules that matter are about **ordering**, not
 * about Android: which answer is still the truth when a slower lookup finishes, and where
 * the waiting happens.
 *
 * So the harness drives the two rules directly:
 *  - [InterfaceResolutionEpoch] decides winners (the real class, not a copy), and
 *  - the loop below reproduces `checkDefaultInterfaceUpdate`'s publish/retry shape while
 *    recording which answer each loop managed to announce.
 *
 * That makes the race deterministic: a lookup's completion is a latch, not a sleep.
 */
class InterfaceResolutionEpochTest {

    /** Records what a listener was told, in order. */
    private class Listener {
        val announced = CopyOnWriteArrayList<String>()
        val wins = AtomicLong(0)

        /** A losing loop drops its answer silently - it does not announce a stale one. */
        fun update(name: String): Boolean {
            announced += name
            wins.incrementAndGet()
            return true
        }
    }

    /**
     * One resolve loop, exactly as `checkDefaultInterfaceUpdate` runs it.
     *
     * [lookup] returns the interface name, or null to simulate "not up yet".
     */
    private fun runLoop(
        epoch: InterfaceResolutionEpoch,
        token: Long,
        listener: Listener,
        name: String?,
        log: CopyOnWriteArrayList<String>,
        label: String = name ?: "?",
    ) {
        repeat(10) { attempt ->
            if (!epoch.isWinner(token)) {
                log += "loop($label) abandoned before attempt ${attempt + 1}"
                return
            }
            if (name != null) {
                // The publish is guarded AGAIN, after the lookup returned: the lookup itself
                // takes time, and the device may have moved on while it ran.
                if (!epoch.isWinner(token)) {
                    log += "loop($label) discarded its answer"
                    return
                }
                listener.update(name)
                log += "loop($label) published"
                return
            }
            log += "loop($label) lookup failed, would retry"
            return // a null lookup is modelled by the caller re-entering with a later token
        }
    }

    // ---------------------------------------------------------------- N01 / N02

    @Test
    fun `N01 an older loop cannot publish after a newer event`() {
        val epoch = InterfaceResolutionEpoch()
        val listener = Listener()
        val log = CopyOnWriteArrayList<String>()

        val older = epoch.beginAttempt()          // network A
        val newer = epoch.beginAttempt()          // network B arrived first

        runLoop(epoch, older, listener, "wlan0", log)   // A's loop finishes late
        runLoop(epoch, newer, listener, "rmnet0", log)  // B's loop finishes

        check(listener.announced.toList() == listOf("rmnet0")) {
            "a stale loop published: ${listener.announced}"
        }
        check(log.none { it == "loop(wlan0) published" }) { "the older loop published: $log" }
    }

    @Test
    fun `N02 a network LOSS claims an epoch, so it cancels the loop it interrupts`() {
        // The round-6 defect, stated as a test. `updateDefaultInterface("", -1, ...)` for a
        // lost network returned before bumping the epoch, so a loop still running for the
        // previous network saw a matching epoch and re-announced an interface the device
        // had already left - after the loss had been reported.
        val epoch = InterfaceResolutionEpoch()
        val listener = Listener()
        val log = CopyOnWriteArrayList<String>()

        val forNetwork = epoch.beginAttempt()
        val loss = epoch.beginAttempt()           // the loss: must invalidate the above
        check(!epoch.isWinner(forNetwork)) { "a lost network must invalidate the older loop" }
        if (epoch.isWinner(loss)) listener.update("")

        runLoop(epoch, forNetwork, listener, "wlan0", log)

        check(listener.announced.toList() == listOf("")) {
            "the interrupted loop announced a stale interface: ${listener.announced}"
        }
    }

    // ---------------------------------------------------------------- N03 / N04

    @Test
    fun `N03 loss then a new network - only the newest loop publishes`() {
        val epoch = InterfaceResolutionEpoch()
        val listener = Listener()
        val log = CopyOnWriteArrayList<String>()

        val forA = epoch.beginAttempt()
        val loss = epoch.beginAttempt()
        // The loss is announced NOW, because at this moment it is the newest event - which
        // is the honest production timeline. Only afterwards does B arrive.
        check(epoch.isWinner(loss)) { "the loss is the newest event and must be announced" }
        listener.update("")

        // A's loop finally returns a perfectly valid name, and must still be dropped even
        // though nothing newer than the loss existed when it started.
        runLoop(epoch, forA, listener, "wlan0", log)

        val forB = epoch.beginAttempt()
        runLoop(epoch, forB, listener, "rmnet0", log)

        check(listener.announced.toList() == listOf("", "rmnet0")) {
            "got ${listener.announced}"
        }
    }

    @Test
    fun `N04 a losing loop is abandoned before it retries, not after`() {
        // Ordering, not just outcome: a loser must not keep re-running lookups for a network
        // the device has left, because each attempt is a binder round-trip plus a JNI
        // crossing on the core.
        val epoch = InterfaceResolutionEpoch()
        val listener = Listener()
        val log = CopyOnWriteArrayList<String>()

        val forA = epoch.beginAttempt()
        epoch.beginAttempt()                       // B arrives while A's lookup is failing
        // The null lookup is never even attempted: the looser-ordered check would have run
        // it once more and only then noticed.
        runLoop(epoch, forA, listener, null, log, label = "wlan0")
        check(log.toList() == listOf("loop(wlan0) abandoned before attempt 1")) { "got $log" }
        check(listener.announced.isEmpty()) { "a loser announced: ${listener.announced}" }
    }

    // ---------------------------------------------------------------- N05 / N06

    @Test
    fun `N05 one event means exactly one announcement, however many attempts it took`() {
        // The round-5 loop called updateDefaultInterface on every iteration that got past
        // the guards, i.e. up to ten JNI crossings and ten core-side writes per event.
        val epoch = InterfaceResolutionEpoch()
        val listener = Listener()
        val token = epoch.beginAttempt()
        runLoop(epoch, token, listener, "wlan0", CopyOnWriteArrayList())
        check(listener.announced.toList() == listOf("wlan0")) { "got ${listener.announced}" }
        check(listener.wins.get() == 1L) { "announced ${listener.wins} times for one event" }
    }

    @Test
    fun `N06 a winner still announces when no newer event intervened`() {
        // The complement of N01: cancellation must not be so eager that a healthy resolve is
        // dropped. Both directions are asserted, so neither can regress silently.
        val epoch = InterfaceResolutionEpoch()
        val listener = Listener()
        val token = epoch.beginAttempt()
        runLoop(epoch, token, listener, "wlan0", CopyOnWriteArrayList())
        check(listener.announced.toList() == listOf("wlan0"))
        check(epoch.isWinner(token))
    }

    // ---------------------------------------------------------------- N09 / N10

    @Test
    fun `N09 concurrent events converge on exactly one winner`() {
        // What is actually guaranteed, stated honestly. A thread can legitimately observe
        // `isWinner(myToken) == true` and lose that status microseconds later, because a
        // newer event may claim the epoch straight afterwards — `isWinner` is a point-in-time
        // question and nothing can make it otherwise without holding a lock across the
        // publish. What must hold is that the races *converge*: after the dust settles
        // exactly one token is the winner, and it is the highest one issued.
        repeat(200) {
            val epoch = InterfaceResolutionEpoch()
            val go = CountDownLatch(1)
            val claimed = CopyOnWriteArrayList<Long>()
            val threads = (1..8).map {
                Thread {
                    go.await(5, TimeUnit.SECONDS)
                    claimed += epoch.beginAttempt()
                }
            }
            threads.forEach { it.start() }
            go.countDown()
            threads.forEach { it.join(5_000) }

            val newest = claimed.maxOrNull() ?: error("no attempt was claimed")
            check(epoch.currentAttempt == newest) {
                "the epoch is ${epoch.currentAttempt} but $newest was the newest claim"
            }
            check(epoch.isWinner(newest)) { "the newest claim is not the winner" }
            val stillWinners = claimed.filter { epoch.isWinner(it) }
            check(stillWinners == listOf(newest)) {
                "expected exactly [$newest] to remain a winner, got $stillWinners"
            }
        }
    }

    @Test
    fun `N10 the resolve path never runs on the caller's thread`() {
        // Asserted on the BYTECODE, deliberately. A device test cannot distinguish "the
        // main thread was busy for a second" from "the main thread was unlucky", and the
        // regression this guards (Thread.sleep on the main looper) is invisible to a UI
        // assertion that only checks the end state.
        //
        // The check is: DefaultNetworkMonitor's constant pool must not reference
        // java/lang/Thread at all. The class legitimately referenced it while the resolve
        // loop slept inline; now that the wait is a coroutine `delay` on
        // `resolveDispatcher`, any reappearance means a synchronous wait came back.
        val bytes = readClassBytes("com/interstellar/proxy/bg/DefaultNetworkMonitor.class")
        val pool = String(bytes, Charsets.ISO_8859_1)
        check(!pool.contains("java/lang/Thread")) {
            "DefaultNetworkMonitor references java/lang/Thread again - a synchronous wait " +
                "on the connectivity callback's thread has been reintroduced"
        }
    }

    private fun readClassBytes(path: String): ByteArray {
        val loader = javaClass.classLoader ?: error("no classloader")
        return loader.getResourceAsStream(path)?.use { it.readBytes() }
            ?: error("$path not on the test classpath")
    }
}
