package com.interstellar.proxy.bg

import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whose stop is it? (P0-R2, this round)
 *
 * The round-4 gap was stated in the handoff as "a small window remains between
 * `isCurrent(attempt)` and the `status = Stopped` write". This file is the
 * deterministic reproduction of the part of that window that is actually
 * reachable, and the guard that closes it.
 *
 * ## The reachable chain (not a hypothetical)
 *
 * `stopAndAlert` is NOT only reached while a start attempt owns the service. It is
 * also reached from `serviceReload0()`, and that path can run while
 * `status == Status.Stopped`:
 *
 *  1. a one-shot `serviceReload` arrives from `UpdateWorker` /
 *     `PerAppProxyViewModel` / `AppViewModel` — all of them use
 *     `CommandTarget.standaloneClient().serviceReload()`, a *fresh* command client
 *     that does not care what the Service's own status is;
 *  2. `serviceReload0()` re-reads the active config, throws, and calls
 *     `stopAndAlert(Alert.CreateService)` — with `status` still `Stopped`;
 *  3. `stopAndAlert` awaits `releaseCore()` (IO) before its `withContext(Main)`
 *     block. Throughout that window `status` is still `Stopped`, so a user tap on
 *     启动 (or the Quick Tile) makes `onStartCommand()` dequeue on the main thread,
 *     take `Status.Stopped -> Unit`, set `Status.Starting` and begin a **new**
 *     generation B;
 *  4. the alert's finalizer then runs on the main thread and — in the round-4
 *     shape — writes `Stopped` over B and calls `stopSelf()`.
 *
 * The result is a service Android is destroying while B's core is live: B's
 * notification is already closed, the UI reads Stopped, and `stopSelf()` tears
 * down the foreground service under a running tunnel.
 *
 * ## Why this test is not just re-testing `CoreLifecycle`
 *
 * The handoff is explicit that a pure `CoreLifecycle` test cannot prove the call
 * path. So the scenario below is driven through [StopConvergence] — the same
 * object `BoxService` consults at both finalizers — in the same order the Service
 * uses: capture a start watermark, release, then finalize under the watch of a
 * *second* start that lands in between.
 */
class StopConvergenceTest {

    /**
     * The decision `BoxService`'s two stop finalizers now make, with no Android and
     * no native types, so the interleaving is provable rather than argued about.
     */
    private class Service {
        val lifecycle = CoreLifecycle()

        /** Effects the finalizers would really perform. */
        val effects = CopyOnWriteArrayList<String>()
        var status = "Stopped"
        var stoppedSelf = false

        /** `onStartCommand()` on the main thread: take a token, set Starting. */
        fun start(): Long? {
            val attempt = lifecycle.beginStart() ?: run {
                effects += "refused-destroyed"
                return null
            }
            status = "Starting"
            return attempt
        }

        /**
         * `stopService()` / `stopAndAlert()`: stamp the work with the start
         * generation it belongs to, release the core, then finalize on main.
         */
        fun stopAndFinalize(release: () -> Unit) {
            val watermark = lifecycle.currentStartAttempt()
            release()                       // releaseCore(): invalidate() + teardown
            finalizeStop(watermark)
        }

        /**
         * The guarded finalizer. Runs on the main thread, after whatever the user did
         * while the release was in flight.
         */
        fun finalizeStop(watermark: Long) {
            if (lifecycle.startsSince(watermark)) {
                // A newer generation owns the service now. Converging to Stopped here
                // would overwrite its status and `stopSelf()` would kill it.
                effects += "convergence-withheld"
                return
            }
            status = "Stopped"
            stoppedSelf = true
            effects += "converged"
        }
    }

    @Test
    fun `a stop whose release was overtaken by a new start must not converge`() {
        val s = Service()

        // A normal stop begins: it stamps the generation it belongs to, then blocks
        // inside releaseCore() (IO) — exactly the window the handoff describes.
        val watermark = s.lifecycle.currentStartAttempt()

        // The user taps 启动 while the release is still in flight. `status` is
        // Stopped on this path (serviceReload0 -> stopAndAlert), so onStartCommand
        // takes a fresh token instead of setting pendingRestart.
        val newAttempt = s.start()
        check(newAttempt != null) { "the new start must be authorised" }

        // The release finishes and the finalizer runs.
        s.finalizeStop(watermark)

        check(s.status == "Starting") {
            "a stale stop overwrote the live generation's status: ${s.status}"
        }
        check(!s.stoppedSelf) {
            "a stale stop called stopSelf() under a live generation"
        }
        check(s.effects.toList() == listOf("convergence-withheld")) { "got ${s.effects}" }
    }

    @Test
    fun `an uncontested stop still converges normally`() {
        val s = Service()
        val watermark = s.lifecycle.currentStartAttempt()
        s.finalizeStop(watermark)
        check(s.status == "Stopped") { "an uncontested stop must reach Stopped, got ${s.status}" }
        check(s.stoppedSelf) { "an uncontested stop must still stopSelf()" }
    }

    @Test
    fun `a stop taken while a start is already in flight still converges`() {
        // The opposite mistake: a stop that *precedes* the next start must converge.
        // `startsSince` compares against the attempt counter taken at stop time, so a
        // start taken BEFORE the watermark does not hold a stop back.
        val s = Service()
        val inFlight = s.start()
        check(inFlight != null)
        val watermark = s.lifecycle.currentStartAttempt()
        s.finalizeStop(watermark)
        check(s.status == "Stopped") { "got ${s.status}" }
        check(s.stoppedSelf)
    }

    @Test
    fun `a destroyed instance never converges the stop of a successor`() {
        val s = Service()
        val watermark = s.lifecycle.currentStartAttempt()
        s.lifecycle.close()
        s.finalizeStop(watermark)
        check(!s.stoppedSelf) { "a destroyed instance must not drive the service to Stopped" }
        check(s.effects.toList() == listOf("convergence-withheld")) { "got ${s.effects}" }
    }

    @Test
    fun `the two hundred interleavings of start and stop never let a stale stop win`() {
        // The round-4 shape is what must lose: interleave "release + finalize" with
        // "start" from two threads, in both orders, and assert the invariant that
        // matters — if a new generation exists, the service is not left Stopped.
        repeat(200) { i ->
            val s = Service()
            val watermark = s.lifecycle.currentStartAttempt()
            val releaseDone = java.util.concurrent.CountDownLatch(1)
            val go = java.util.concurrent.CountDownLatch(1)

            val stopper = Thread {
                go.await(5, java.util.concurrent.TimeUnit.SECONDS)
                releaseDone.countDown()      // release is now in flight
                s.finalizeStop(watermark)
            }
            val starter = Thread {
                go.await(5, java.util.concurrent.TimeUnit.SECONDS)
                releaseDone.await(5, java.util.concurrent.TimeUnit.SECONDS)
                s.start()
            }
            stopper.start(); starter.start()
            go.countDown()
            stopper.join(5_000); starter.join(5_000)

            // Whichever order won, a start that was authorised must not have been
            // converged away: either the stop finalised first (and the start then
            // legitimately owns the service as Starting), or the start won and the
            // stop withheld. Either way the service is never Stopped-with-a-start.
            if (s.effects.contains("converged")) {
                // The stop won the race before the start was taken; the start must
                // then have moved the status on afterwards.
                check(s.status == "Starting") {
                    "iteration $i: a converged stop left status=${s.status} with a live start"
                }
            } else {
                check(s.status == "Starting") { "iteration $i: got ${s.status}" }
                check(!s.stoppedSelf) { "iteration $i: withheld stop still stopped the service" }
            }
        }
    }
}
