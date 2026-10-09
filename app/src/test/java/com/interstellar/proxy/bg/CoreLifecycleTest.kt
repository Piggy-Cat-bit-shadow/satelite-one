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
        val attempt = lifecycle.beginStart()
        val published = CopyOnWriteArrayList<String>()
        check(lifecycle.publish(attempt) { published += "core" }) { "the youngest attempt must publish" }
        check(published.toList() == listOf("core"))
        check(lifecycle.isPublished)
    }

    @Test
    fun `a release invalidates a start that is still in flight`() {
        val lifecycle = CoreLifecycle()
        val attempt = lifecycle.beginStart()
        // The teardown runs while startup() is still busy.
        lifecycle.invalidate()
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
        val first = lifecycle.beginStart()
        val second = lifecycle.beginStart()
        val published = CopyOnWriteArrayList<String>()
        check(!lifecycle.publish(first) { published += "first" }) { "the superseded attempt published" }
        check(lifecycle.publish(second) { published += "second" })
        check(published.toList() == listOf("second")) { "got $published" }
    }

    @Test
    fun `abandon forgets a failed startup without publishing`() {
        val lifecycle = CoreLifecycle()
        val attempt = lifecycle.beginStart()
        lifecycle.abandon(attempt)
        check(!lifecycle.isPublished) { "a failed startup must not look published" }
    }

    @Test
    fun `publishing runs inside the critical section`() {
        // A concurrent invalidate must not be able to slip between "we are allowed to
        // publish" and the publish itself: core + fact bridge are attached indivisibly.
        val lifecycle = CoreLifecycle()
        val attempt = lifecycle.beginStart()
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
        val invalidator = Thread { lifecycle.invalidate(); order += "invalidated" }
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
            val attempt = lifecycle.beginStart()
            val published = CopyOnWriteArrayList<String>()
            val go = CountDownLatch(1)
            val starter = Thread { go.await(5, TimeUnit.SECONDS); lifecycle.publish(attempt) { published += "core" } }
            val releaser = Thread { go.await(5, TimeUnit.SECONDS); lifecycle.invalidate() }
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
}
