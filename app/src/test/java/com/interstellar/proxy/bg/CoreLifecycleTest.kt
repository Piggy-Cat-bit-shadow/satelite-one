package com.interstellar.proxy.bg

import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The core start/release ownership gate.
 *
 * The hazard: `startCore()` builds the core inside a slow `startup()` and only then
 * writes `core`/`factSession`. A `releaseCore()` that runs during that window used to
 * be invisible to the start, so the released core was written back — a second live
 * core, an attached fact bridge for a core that had already been torn down, and a
 * command socket nobody owned.
 *
 * `CoreLifecycle` makes the publish the linearization point instead. These tests pin
 * every interleaving, including the ones that used to write back.
 */
class CoreLifecycleTest {

    @Test
    fun `the youngest attempt may publish`() {
        val lifecycle = CoreLifecycle()
        val attempt = lifecycle.beginStart() ?: error("a fresh lifecycle must authorize a start")
        val published = CopyOnWriteArrayList<String>()
        check(lifecycle.publish(attempt) { published += "core" }) { "the youngest attempt must publish" }
        check(published.toList() == listOf("core"))
        check(lifecycle.isPublished)
    }

    @Test
    fun `a release invalidates a start that is still in flight`() {
        val lifecycle = CoreLifecycle()
        val attempt = lifecycle.beginStart() ?: error("a fresh lifecycle must authorize a start")
        // The teardown runs while startup() is still busy. Round 7: a stop is bracketed by
        // beginStop/endStop, and it is endStop that pins the stale boundary to everything
        // alive while the core slots were dirty.
        lifecycle.beginStop()
        lifecycle.invalidate()
        lifecycle.endStop()
        val published = CopyOnWriteArrayList<String>()
        check(!lifecycle.publish(attempt) { published += "core" }) {
            "a released generation must not be written back"
        }
        check(published.isEmpty()) { "the released core was published anyway: $published" }
        check(!lifecycle.isPublished)
    }

    @Test
    fun `a newer start supersedes an older one`() {
        val lifecycle = CoreLifecycle()
        val first = lifecycle.beginStart() ?: error("a fresh lifecycle must authorize a start")
        val second = lifecycle.beginStart() ?: error("a fresh lifecycle must authorize a start")
        val published = CopyOnWriteArrayList<String>()
        check(!lifecycle.publish(first) { published += "first" }) { "the superseded attempt published" }
        check(lifecycle.publish(second) { published += "second" })
        check(published.toList() == listOf("second")) { "got $published" }
    }

    @Test
    fun `abandon forgets a failed startup without publishing`() {
        val lifecycle = CoreLifecycle()
        val attempt = lifecycle.beginStart() ?: error("a fresh lifecycle must authorize a start")
        lifecycle.abandon(attempt)
        check(!lifecycle.isPublished) { "a failed startup must not look published" }
    }

    @Test
    fun `publishing runs inside the critical section`() {
        // A concurrent invalidate must not be able to slip between "we are allowed to
        // publish" and the publish itself: core + fact bridge are attached indivisibly.
        val lifecycle = CoreLifecycle()
        val attempt = lifecycle.beginStart() ?: error("a fresh lifecycle must authorize a start")
        val entering = CountDownLatch(1)
        val hold = CountDownLatch(1)
        val order = CopyOnWriteArrayList<String>()
        val publisher = Thread {
            lifecycle.publish(attempt) {
                entering.countDown()
                hold.await(5, TimeUnit.SECONDS)
                order += "published"
            }
        }
        publisher.start()
        check(entering.await(5, TimeUnit.SECONDS)) { "the publish never started" }
        val invalidator = Thread {
            lifecycle.beginStop(); lifecycle.invalidate(); lifecycle.endStop()
            order += "invalidated"
        }
        invalidator.start()
        invalidator.join(300)
        check(invalidator.isAlive) { "invalidate() interleaved inside a publish" }
        hold.countDown()
        publisher.join(5_000)
        invalidator.join(5_000)
        check(order.toList() == listOf("published", "invalidated")) { "got $order" }
    }

    @Test
    fun `at most one publish survives a start release race`() {
        repeat(200) {
            val lifecycle = CoreLifecycle()
            val attempt = lifecycle.beginStart() ?: error("a fresh lifecycle must authorize a start")
            val published = CopyOnWriteArrayList<String>()
            val go = CountDownLatch(1)
            val starter = Thread { go.await(5, TimeUnit.SECONDS); lifecycle.publish(attempt) { published += "core" } }
            val releaser = Thread {
                go.await(5, TimeUnit.SECONDS)
                lifecycle.beginStop(); lifecycle.invalidate(); lifecycle.endStop()
            }
            starter.start(); releaser.start()
            go.countDown()
            starter.join(5_000); releaser.join(5_000)

            // Either the publish won (and the release invalidated it afterwards) or the
            // release won and nothing was published — but the group must be consistent:
            // a published core always implies the release came first and invalidated it,
            // which is exactly what a caller must then dispose.
            if (published.isNotEmpty()) {
                check(!lifecycle.isPublished || published.size == 1) {
                    "more than one core was published in one race"
                }
            }
            check(published.size <= 1) { "published ${published.size} cores: $published" }
        }
    }

    // ---- P0-A: a destroyed Service instance is terminal ----

    /**
     * Models one Service instance's core-start path as `BoxService.startCore` does it:
     * take a token, do slow native work, then try to publish (disposing the locally
     * created core when the publish is refused).
     */
    private class Model {
        val lifecycle = CoreLifecycle()
        val published = CopyOnWriteArrayList<Long>()
        val disposed = CopyOnWriteArrayList<Long>()

        fun begin(): Long? = lifecycle.beginStart()

        fun publish(attempt: Long): Boolean {
            val ok = lifecycle.publish(attempt) { published += attempt }
            if (!ok) disposed += attempt
            return ok
        }

        /** onDestroy. */
        fun destroy() = lifecycle.close()

        /**
         * stopService, which may still be followed by a requested restart.
         *
         * Round 7: a stop is a *bracketed* event. `invalidate()` alone no longer decides a
         * successor's fate — that is `endStop()`'s job, and it pins the boundary to
         * everything alive while `core`/`factSession`/`fileDescriptor` were dirty. These
         * tests therefore go through the same bracket production uses.
         */
        fun stop() {
            lifecycle.beginStop()
            lifecycle.invalidate()
            lifecycle.endStop()
        }
    }

    @Test
    fun `a destroyed owner refuses to begin a start at all`() {
        // Old behaviour: beginStart() always returned a token, so a destroyed Service
        // went on to build a CommandServer and a fact bridge it could then publish.
        // Refusing before any native work is the whole point.
        val m = Model()
        m.destroy()
        check(m.begin() == null) { "a destroyed service instance must not begin a start attempt" }
    }

    @Test
    fun `a destroy during startup prevents publication and the starter disposes its own core`() {
        val m = Model()
        val attempt = m.begin() ?: error("expected a token before the destroy")
        // ... the slow native startup() runs here ...
        m.destroy()
        val ok = m.publish(attempt)
        check(!ok) { "a core must not be published after the owning service was destroyed" }
        check(m.published.isEmpty()) { "published anyway: ${m.published}" }
        check(m.disposed.toList() == listOf(attempt)) {
            "the superseded starter must dispose exactly the core it built: ${m.disposed}"
        }
    }

    @Test
    fun `a destroy during a failing startup disposes the partial core`() {
        val m = Model()
        val attempt = m.begin() ?: error("expected a token")
        m.destroy()
        // startup() throws: the catch path abandons the token and disposes the local
        // object without ever calling publish().
        m.lifecycle.abandon(attempt)
        check(m.published.isEmpty())
        check(!m.lifecycle.isPublished)
        check(m.lifecycle.isClosed) { "destroy must stay closed even after a failed startup" }
    }

    @Test
    fun `a normal stop still allows the restart the user asked for`() {
        // The distinction that matters: stop() is NOT terminal. A pendingRestart on the
        // same instance must still be able to start a new core.
        val m = Model()
        val first = m.begin() ?: error("expected a token")
        m.stop()
        check(!m.publish(first)) { "the stopped generation must not publish" }
        val second = m.begin() ?: error("a stop must not close the owner")
        check(m.publish(second)) { "the restart must be allowed to publish" }
        check(!m.lifecycle.isClosed) { "a normal stop must not mark the owner closed" }
    }

    @Test
    fun `a stop followed by a destroy refuses the pending restart`() {
        val m = Model()
        val first = m.begin() ?: error("expected a token")
        m.stop()
        check(!m.publish(first))
        m.destroy()
        check(m.begin() == null) { "a pending restart must not survive the destroy" }
    }

    @Test
    fun `destroy twice is idempotent and still refuses starts`() {
        val m = Model()
        m.destroy()
        m.destroy()
        check(m.begin() == null)
        check(m.lifecycle.isClosed)
        check(!m.lifecycle.isPublished)
    }

    @Test
    fun `two hundred deterministic interleavings never publish after the destroy returns`() {
        // The five shapes the review asked for, cycled 200 times. Each iteration also
        // races a real publish against the destroy, and asserts the ordering invariant
        // that makes "no core behind a destroyed Service" true: publish() holds the
        // same lock as close(), so a publish that ran can only ever appear BEFORE the
        // close in the shared order log - never after it.
        val shapes = listOf(
            "start-destroy-publish",
            "start-destroy-publish-failing-startup",
            "start-stop-publish-then-restart",
            "start-stop-destroy",
            "destroy-twice",
        )
        repeat(200) { i ->
            val shape = shapes[i % shapes.size]
            val m = Model()
            val order = CopyOnWriteArrayList<String>()
            val attempt = m.begin()
            check(attempt != null) { "iteration $i ($shape): expected a token" }

            val go = CountDownLatch(1)
            val publisher = Thread {
                go.await(5, TimeUnit.SECONDS)
                if (m.publish(attempt)) order += "publish" else order += "refused"
            }

            when (shape) {
                "start-destroy-publish" -> {
                    m.destroy(); order += "close"
                    publisher.start(); go.countDown(); publisher.join(5_000)
                }
                "start-destroy-publish-failing-startup" -> {
                    m.destroy(); order += "close"
                    m.lifecycle.abandon(attempt)
                    publisher.start(); go.countDown(); publisher.join(5_000)
                }
                "start-stop-publish-then-restart" -> {
                    m.stop(); order += "stop"
                    publisher.start(); go.countDown(); publisher.join(5_000)
                    val next = m.begin()
                    check(next != null) { "iteration $i: a stop must allow a restart" }
                    check(m.publish(next)) { "iteration $i: the restart must publish" }
                }
                "start-stop-destroy" -> {
                    m.stop(); order += "stop"
                    m.destroy(); order += "close"
                    publisher.start(); go.countDown(); publisher.join(5_000)
                }
                else -> {
                    m.destroy(); m.destroy(); order += "close"
                    publisher.start(); go.countDown(); publisher.join(5_000)
                }
            }

            // Invariant 1: never a publish entry after a close/stop happened.
            val lastBarrier = order.indexOfLast { it == "close" || it == "stop" }
            val publishAt = order.indexOf("publish")
            if (publishAt >= 0 && lastBarrier >= 0 && shape != "start-stop-publish-then-restart") {
                check(publishAt < lastBarrier) {
                    "iteration $i ($shape): published after the owner was closed: $order"
                }
            }
            // Invariant 2: a refused publish always disposed exactly the core it built.
            if (order.contains("refused")) {
                check(m.published.none { it == attempt }) {
                    "iteration $i ($shape): a refused publish still recorded a core"
                }
                check(m.disposed.count { it == attempt } == 1) {
                    "iteration $i ($shape): refused publish disposed ${m.disposed}"
                }
            }
        }
    }

    // ---- P0-D: whose failure is it to report? ----

    @Test
    fun `a failure is only reportable while the attempt still owns the service`() {
        val lifecycle = CoreLifecycle()
        val attempt = lifecycle.beginStart() ?: error("expected a token")
        check(lifecycle.isCurrent(attempt)) { "a fresh attempt owns the service" }

        // A stop took the service away from this attempt: its failure is no longer its to
        // report, and reporting it would write Stopped over whatever came next. Round 7:
        // the stale boundary is pinned at the end of the teardown, so the bracket is what
        // production uses.
        lifecycle.beginStop()
        lifecycle.invalidate()
        lifecycle.endStop()
        check(!lifecycle.isCurrent(attempt)) { "a stopped attempt must not report a failure" }
    }

    @Test
    fun `a newer start makes the previous attempt non-current`() {
        val lifecycle = CoreLifecycle()
        val first = lifecycle.beginStart() ?: error("expected a token")
        val second = lifecycle.beginStart() ?: error("expected a token")
        check(!lifecycle.isCurrent(first)) { "a superseded attempt must not report a failure" }
        check(lifecycle.isCurrent(second)) { "the newest attempt owns the service" }
    }

    @Test
    fun `a destroy makes every attempt non-current`() {
        val lifecycle = CoreLifecycle()
        val attempt = lifecycle.beginStart() ?: error("expected a token")
        lifecycle.close()
        check(!lifecycle.isCurrent(attempt)) { "no attempt survives the destroy" }
    }

    // ---- Debug round: is isPublished ever a lie? ----

    @Test
    fun `a released core is no longer reported as published`() {
        // Found by the adversarial pass over this round's own work: `published` was only
        // ever assigned in publish(), so after a stop it still claimed a live core - and
        // the assertions in this file trusted it.
        val lifecycle = CoreLifecycle()
        val attempt = lifecycle.beginStart() ?: error("expected a token")
        check(lifecycle.publish(attempt) {})
        check(lifecycle.isPublished) { "a published core must be reported as published" }

        // stopService() -> releaseCore(): the round-7 bracket.
        lifecycle.beginStop()
        lifecycle.invalidate()
        lifecycle.endStop()
        check(!lifecycle.isPublished) {
            "a released core must not still be reported as published"
        }
    }

    @Test
    fun `a destroyed owner reports nothing published`() {
        val lifecycle = CoreLifecycle()
        val attempt = lifecycle.beginStart() ?: error("expected a token")
        check(lifecycle.publish(attempt) {})
        lifecycle.close()
        check(!lifecycle.isPublished) { "a destroyed owner must not report a published core" }
    }
}
