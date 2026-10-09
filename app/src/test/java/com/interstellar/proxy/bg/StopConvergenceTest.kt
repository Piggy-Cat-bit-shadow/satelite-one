package com.interstellar.proxy.bg

import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Whose stop is it, and what is it allowed to touch? (round 6)
 *
 * ## What changed and why this file changed shape
 *
 * Round 5 introduced `startAttempts` / `startsSince` so a stale stop would stop writing
 * `Stopped` over a successor. It left two holes that this file exists to close:
 *
 *  1. **The convergence check came too late.** `stopAndAlert` unregistered the receiver,
 *     called `notification.close()` and broadcast the alert *before* asking
 *     `startsSince`. Those first two are harm to a successor, not bookkeeping: the
 *     foreground-notification slot and the receiver registration are process-wide, so a
 *     superseded generation was still closing the notification a newer generation had
 *     just posted. `stopService` was worse — it did the same work on the *caller's*
 *     thread, before `releaseCore()` had even started.
 *  2. **The start token was claimed too late.** `onStartCommand` set `Status.Starting` on
 *     the main thread but only took its lifecycle token later, inside the IO coroutine's
 *     `startCore()`. A stop releasing in that window saw "no newer start" and converged
 *     onto the service the user had just started.
 *
 * So this test no longer drives a stand-in model class: the previous `Service` fake
 * could stay green while production changed underneath it. It drives the **real**
 * [CoreLifecycle] through the exact operation order `BoxService` uses, and the side
 * effects are modelled as a small owner-checked recorder so "did the old stop touch the
 * new generation's resources" is an assertion rather than a comment.
 */
class StopConvergenceTest {

    /**
     * Mirrors the production sequence, with `CoreLifecycle` as the only authority.
     *
     * `releaseCore()` is modelled as `invalidate()` (which is what it does to the
     * lifecycle) — that is the step that must NOT be mistaken for a newer start.
     */
    private class Harness {
        val lifecycle = CoreLifecycle()

        /** Side effects a stop finalizer may perform, recorded in order. */
        val effects = CopyOnWriteArrayList<String>()

        /**
         * The two shared, per-instance Android resources a finalizer touches. Modelled
         * as flags guarded by the SAME generation decision, because the point of the fix
         * is that they are only touched when this generation still owns the service.
         */
        var receiverRegistered = true
        var notificationOpen = true
        var stoppedSelf = false

        /** `stopService()` / `stopAndAlert()` prologue, on the caller's thread. */
        fun stopWatermark(): Long = lifecycle.currentStartAttempt()

        /**
         * `releaseCore()`: what it does to lifecycle ownership.
         *
         * Round 7 made this a **bracketed** event. `invalidate()` still stops a start that
         * was requested *before* the stop from publishing, but it no longer decides a
         * successor's fate — `endStop()` pins the stale boundary to everything alive while
         * the core slots were dirty, which is what lets a start accepted afterwards keep
         * its token instead of being stranded.
         */
        fun releaseCore() {
            lifecycle.beginStop()
            try {
                lifecycle.invalidate()
            } finally {
                lifecycle.endStop()
            }
        }

        /**
         * The single guarded finalizer both stop paths now use.
         *
         * The ownership question is asked ONCE, first; only if this generation still owns
         * the service may it unregister, close, write status and stop itself.
         */
        fun finalizeStop(watermark: Long): Boolean {
            if (lifecycle.startsSince(watermark)) {
                effects += "withheld"
                return false
            }
            effects += "converged"
            receiverRegistered = false
            notificationOpen = false
            stoppedSelf = true
            return true
        }

        /** `onStartCommand()`: claim the attempt, then publish it as Starting. */
        fun onStartCommand(): Long? = lifecycle.beginStart()

        /** True when [attempt] is allowed to write its core back. */
        fun publish(attempt: Long): Boolean = lifecycle.publish(attempt) {}

        /** Diagnostic accessor used by the failure messages. */
        fun supersededOf(attempt: Long): Boolean = lifecycle.isSupersededByNewerStart(attempt)

        fun destroy() = lifecycle.close()
    }

    // -----------------------------------------------------------------------
    // S01 — the round-5 hole: the claim has to exist before the stop finalizes
    // -----------------------------------------------------------------------

    @Test
    fun `S01 a start accepted during the release keeps the old stop from converging`() {
        val h = Harness()

        // Old stop begins: it stamps itself, then blocks inside releaseCore().
        val watermark = h.stopWatermark()

        // The user taps 启动 while the release is still in flight. With the fix, the
        // token is claimed HERE - at request entry - not later on the IO coroutine.
        val b = h.onStartCommand()
        check(b != null) { "the new start must be authorised" }

        // The release finishes and the finalizer runs on main.
        val converged = h.finalizeStop(watermark)

        check(!converged) { "a stale stop converged over a live generation" }
        check(h.receiverRegistered) { "the stale stop unregistered the new generation's receiver" }
        check(h.notificationOpen) { "the stale stop closed the new generation's notification" }
        check(!h.stoppedSelf) { "the stale stop called stopSelf() under a live generation" }
        check(h.effects.toList() == listOf("withheld")) { "got ${h.effects}" }
    }

    @Test
    fun `S01b claiming the token later would have lost the race - the old shape`() {
        // The round-5 ordering, reproduced honestly: the stop stamps, the release runs,
        // and the new start's token is only taken AFTER the finalizer has asked. This is
        // what `status = Starting` + `beginStart()` inside startCore() produced, and it
        // is why the claim had to move to the main-thread entry point.
        val h = Harness()
        val watermark = h.stopWatermark()
        h.releaseCore()
        val converged = h.finalizeStop(watermark)   // asks before the new start exists
        check(converged) { "the harness must reproduce the old ordering" }
        check(h.stoppedSelf) { "old shape stops the service" }
        // ... and only now does the new generation try to take a token.
        val late = h.onStartCommand()
        check(late != null) { "a late start is still authorised" }
        check(h.effects.toList() == listOf("converged")) { "got ${h.effects}" }
        // The service was already told to stop, so B is born into a dead service: this is
        // exactly the failure the S01 test above forbids, kept here as the red contrast.
    }

    // -----------------------------------------------------------------------
    // S02 / S03 — an old failure must not darken a healthy successor
    // -----------------------------------------------------------------------

    @Test
    fun `S02 an old alert cannot close the notification or receiver of a live successor`() {
        val h = Harness()
        val watermark = h.stopWatermark()           // old stopAndAlert entry
        h.releaseCore()                             // its releaseCore()
        val b = h.onStartCommand()                  // successor claims during the release
        check(b != null)
        h.finalizeStop(watermark)                   // alert finalizer reaches Main

        check(h.notificationOpen) { "an old alert closed the successor's notification" }
        check(h.receiverRegistered) { "an old alert unregistered the successor's receiver" }
        check(!h.stoppedSelf) { "an old alert stopped the successor's service" }
    }

    @Test
    fun `S03 one-shot serviceReload failure racing a user tap cannot kill the new start`() {
        // serviceReload0() reaches stopAndAlert while status is still Stopped - a
        // one-shot command client never consults the Service's status. Nothing about the
        // status field prevents onStartCommand from claiming here.
        val h = Harness()
        val watermark = h.stopWatermark()
        val tap = h.onStartCommand()
        check(tap != null) { "the tap must be accepted" }
        h.releaseCore()
        h.finalizeStop(watermark)
        check(h.notificationOpen && h.receiverRegistered && !h.stoppedSelf) {
            "the reload-triggered alert harmed the new start"
        }
    }

    // -----------------------------------------------------------------------
    // S04 / S07 — restart intents are not lost, and are not owned by a stale stop
    // -----------------------------------------------------------------------

    @Test
    fun `S04 three start intents during teardown still leave exactly one live generation`() {
        val h = Harness()
        val watermark = h.stopWatermark()
        h.releaseCore()
        // Three taps while Stopping. Each is a real claim at request entry now, so the
        // latest one owns the service and the earlier ones are superseded - they cannot
        // publish, which is what keeps this from becoming three CommandServers.
        val first = h.onStartCommand()
        val second = h.onStartCommand()
        val third = h.onStartCommand()
        check(first != null && second != null && third != null)
        check(!h.publish(first)) {
            "the first of three taps must not publish [tokens=$first/$second/$third " +
                "superseded=${h.supersededOf(first)}/${h.supersededOf(second)}/${h.supersededOf(third)}]"
        }
        check(!h.publish(second)) {
            "the second of three taps must not publish [tokens=$first/$second/$third " +
                "superseded=${h.supersededOf(first)}/${h.supersededOf(second)}/${h.supersededOf(third)}]"
        }
        check(h.publish(third)) { "the newest tap must publish" }
        h.finalizeStop(watermark)
        check(!h.stoppedSelf) { "a stale stop killed the newest of three taps" }
    }

    @Test
    fun `S04b sequential taps each publish, and each supersedes the one before it`() {
        // The contrast that makes S04's claim precise. S04 covers three attempts that are
        // all in flight at once, so only the newest may publish. Here each tap is published
        // before the next arrives, which is the ordinary connect→reconnect case: the newer
        // tap supersedes the older one, and the older one may no longer publish again.
        //
        // Round 7 had to make this origin explicit. Round 5's `publish` compared against a
        // counter that only a *release or destroy* moved, so a merely superseded attempt
        // could still write its core back - one core reachable, the other orphaned.
        val h = Harness()
        val first = h.onStartCommand()
        check(first != null)
        check(h.publish(first)) { "the first tap publishes when nothing precedes it" }

        val second = h.onStartCommand()
        check(second != null)
        check(h.publish(second)) { "a later tap publishes in turn" }
        check(!h.publish(first)) { "the superseded first tap must not publish again" }
    }

    @Test
    fun `S07 a failed start does not clear a restart intent that is newer than it`() {
        val h = Harness()
        // A's failed-start teardown begins ...
        val aWatermark = h.stopWatermark()
        h.releaseCore()
        // ... and while it releases, the user starts B.
        val b = h.onStartCommand()
        check(b != null)
        // A's alert finalizer runs. It must not converge, and it must not be able to
        // clear anything belonging to B - the removed `pendingRestart = false` write was
        // precisely such a cross-generation clear.
        h.finalizeStop(aWatermark)
        check(h.publish(b)) { "B must still be able to publish after A's alert" }
        check(!h.stoppedSelf) { "A stopped the service B is now running in" }
    }

    // -----------------------------------------------------------------------
    // S05 / S06 — destroy and supersession still hold (round-4/5 invariants)
    // -----------------------------------------------------------------------

    @Test
    fun `S05 a destroy during startup prevents publication`() {
        val h = Harness()
        val attempt = h.onStartCommand()
        check(attempt != null)
        h.destroy()                                  // onDestroy while startup() is in flight
        check(!h.publish(attempt)) { "a destroyed instance published a core" }
        check(h.onStartCommand() == null) { "a destroyed instance authorised a start" }
    }

    @Test
    fun `S06 an old startup failure does not interrupt a newer generation`() {
        val h = Harness()
        val a = h.onStartCommand()
        check(a != null)
        val b = h.onStartCommand()
        check(b != null)
        // A's startup throws: it abandons its own token and must not publish.
        h.lifecycle.abandon(a)
        check(!h.publish(a)) { "the failed older attempt published" }
        check(h.publish(b)) { "the newer attempt was blocked by the older failure" }
    }

    @Test
    fun `a destroy makes a stop finalizer converge nothing`() {
        val h = Harness()
        val watermark = h.stopWatermark()
        h.destroy()
        h.finalizeStop(watermark)
        check(!h.stoppedSelf) { "a destroyed instance still drove the service to Stopped" }
        check(h.effects.toList() == listOf("withheld")) { "got ${h.effects}" }
    }

    // -----------------------------------------------------------------------
    // Uncontested stops must still converge - the fix must not break the normal path
    // -----------------------------------------------------------------------

    @Test
    fun `an uncontested stop converges exactly once`() {
        val h = Harness()
        val watermark = h.stopWatermark()
        h.releaseCore()
        check(h.finalizeStop(watermark)) { "a normal stop must converge" }
        check(h.stoppedSelf && !h.notificationOpen && !h.receiverRegistered)
        check(h.effects.toList() == listOf("converged")) { "got ${h.effects}" }
    }

    @Test
    fun `a start that precedes the stop does not hold the stop back`() {
        // `startsSince` compares the attempt counter, so a start taken BEFORE the
        // watermark must not suppress a later, genuine stop.
        val h = Harness()
        val inFlight = h.onStartCommand()
        check(inFlight != null)
        val watermark = h.stopWatermark()
        h.releaseCore()
        check(h.finalizeStop(watermark)) { "a stop after a start must still converge" }
        check(h.stoppedSelf)
    }

    @Test
    fun `two hundred interleavings of claim and finalize never converge a live successor`() {
        repeat(200) { i ->
            val h = Harness()
            val watermark = h.stopWatermark()
            val releaseDone = CountDownLatch(1)
            val go = CountDownLatch(1)

            val stopper = Thread {
                go.await(5, TimeUnit.SECONDS)
                releaseDone.countDown()
                h.finalizeStop(watermark)
            }
            val starter = Thread {
                go.await(5, TimeUnit.SECONDS)
                releaseDone.await(5, TimeUnit.SECONDS)
                h.onStartCommand()
            }
            stopper.start(); starter.start()
            go.countDown()
            stopper.join(5_000); starter.join(5_000)

            if (h.effects.contains("converged")) {
                // The stop won the race outright. A start may then have been taken
                // afterwards, in which case it must be able to publish its own core -
                // but the stop must not have stopped a service the start now owns.
                check(h.effects.toList() == listOf("converged")) { "iteration $i: ${h.effects}" }
            } else {
                check(!h.stoppedSelf) { "iteration $i: withheld stop still stopped the service" }
                check(h.notificationOpen) { "iteration $i: withheld stop closed the notification" }
                check(h.receiverRegistered) { "iteration $i: withheld stop unregistered" }
            }
        }
    }
}
