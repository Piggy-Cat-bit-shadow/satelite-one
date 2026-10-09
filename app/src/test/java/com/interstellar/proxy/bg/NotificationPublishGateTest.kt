package com.interstellar.proxy.bg

import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The service notification's publish/terminate order.
 *
 * The shape this replaces was `if (released) return` followed by
 * `NotificationManager.notify(...)`. `released` was `@Volatile`, so each individual
 * read was fine — but the *pair* was not atomic, so `close()` could cancel between
 * them and the stale update would re-post a notification that outlived the foreground
 * service (surviving `stopSelf()` as a phantom "still connected").
 *
 * `NotificationPublishGate` makes the pair one step; these tests pin the resulting
 * ordering, including a deterministic reproduction of the old race.
 */
class NotificationPublishGateTest {

    private class Recorder {
        val posted = CopyOnWriteArrayList<String>()
        val gate = NotificationPublishGate()
    }

    @Test
    fun `a publish inside an open generation runs`() {
        val r = Recorder()
        val gen = r.gate.open()
        val ran = r.gate.publish(gen) { r.posted += "traffic" }
        check(ran) { "a publish in an open generation must run" }
        check(r.posted.toList() == listOf("traffic")) { "got ${r.posted}" }
    }

    @Test
    fun `a publish on a closed generation does nothing`() {
        val r = Recorder()
        val gen = r.gate.open()
        r.gate.close()
        val ran = r.gate.publish(gen) { r.posted += "traffic" }
        check(!ran) { "a publish after close must be refused" }
        check(r.posted.isEmpty()) { "a closed generation posted: ${r.posted}" }
    }

    @Test
    fun `a publish from a superseded generation does nothing`() {
        val r = Recorder()
        val first = r.gate.open()
        val second = r.gate.open()
        check(!r.gate.publish(first) { r.posted += "stale" }) { "an old generation published" }
        check(r.gate.publish(second) { r.posted += "live" })
        check(r.posted.toList() == listOf("live")) { "got ${r.posted}" }
    }

    @Test
    fun `close is idempotent and reports the generation it closed`() {
        val r = Recorder()
        val gen = r.gate.open()
        check(r.gate.close() == gen)
        check(r.gate.close() == null) { "closing twice must not report another generation" }
    }

    @Test
    fun `a publish already inside its block still completes before close is observed`() {
        // Deterministic ordering proof: the gate holds its lock for the whole
        // check-and-post, so a close() that arrives while a publish is executing cannot
        // interleave *inside* it. The publish therefore either finishes entirely before
        // close (and the caller's cancel removes what it posted), or never starts.
        val r = Recorder()
        val gen = r.gate.open()
        val entering = CountDownLatch(1)
        val hold = CountDownLatch(1)
        val publisher = Thread {
            r.gate.publish(gen) {
                entering.countDown()
                hold.await(5, TimeUnit.SECONDS)
                r.posted += "in-flight"
            }
        }
        publisher.start()
        check(entering.await(5, TimeUnit.SECONDS)) { "the publish never started" }

        val closer = Thread { r.gate.close() }
        closer.start()
        // close() must be blocked behind the in-flight publish, not cancelling under it.
        closer.join(300)
        check(closer.isAlive) { "close() interleaved with an in-flight publish" }

        hold.countDown()
        publisher.join(5_000)
        closer.join(5_000)
        check(r.posted.toList() == listOf("in-flight")) { "got ${r.posted}" }
        check(!r.gate.isOpen(gen)) { "the generation must be closed" }
        // and nothing may follow it
        check(!r.gate.publish(gen) { r.posted += "after-close" })
        check(r.posted.toList() == listOf("in-flight")) { "a post escaped close: ${r.posted}" }
    }

    @Test
    fun `the old unchecked shape is what this gate prevents`() {
        // Mutation-style contrast: doing the check and the post as two separate steps
        // (the previous implementation) lets a post slip through after close. This
        // asserts the hazard is real, so the gate is not guarding a hypothetical.
        val r = Recorder()
        val gen = r.gate.open()
        val checkedOpen = r.gate.isOpen(gen) // step 1: check
        check(checkedOpen)
        r.gate.close() // step 2 happens concurrently, i.e. here, before the post
        if (checkedOpen) r.posted += "slipped-through" // step 3: the old code posted here
        check(r.posted.toList() == listOf("slipped-through")) {
            "the two-step shape did not reproduce the hazard"
        }
        // The guarded shape refuses it.
        val r2 = Recorder()
        val gen2 = r2.gate.open()
        r2.gate.close()
        check(!r2.gate.publish(gen2) { r2.posted += "slipped-through" })
        check(r2.posted.isEmpty())
    }

    // ---- P0-B: one session, one token ----

    @Test
    fun `a text refresh does not open a second generation`() {
        // show(Starting) followed by show(Started) must stay in the same session. The old
        // call site opened unconditionally on every show(), so the second call minted a
        // new token - and the *previous* client's token stopped being current, while any
        // callback still in flight would then be judged against a token that had moved on.
        val r = Recorder()
        val starting = r.gate.beginOrRefreshSession()
        val started = r.gate.beginOrRefreshSession()
        check(started == starting) {
            "a text refresh must not open a new generation: $starting -> $started"
        }
        check(r.gate.publish(starting) { r.posted += "traffic" }) {
            "the single session token must still be able to publish"
        }
        check(r.posted.toList() == listOf("traffic"))
    }

    @Test
    fun `a restart after close does open a new generation, and the old token stays dead`() {
        val r = Recorder()
        val first = r.gate.beginOrRefreshSession()
        r.gate.close()
        val second = r.gate.beginOrRefreshSession()
        check(second != first) { "a Stop->Start must open a new generation" }
        check(r.gate.publish(second) { r.posted += "new" })
        check(!r.gate.publish(first) { r.posted += "old" }) {
            "the previous session's token must stay dead"
        }
        check(r.posted.toList() == listOf("new")) { "got ${r.posted}" }
    }

    @Test
    fun `a late traffic callback from the previous client is refused by the successor`() {
        // The P0-B hazard, stated as a test: client A is mid-callback when the user hits
        // Stop and immediately reconnects, so B owns the live token by the time A's
        // callback runs. A must be refused - the old shared-handler shape read the live
        // `generation` field and would have been authorised as B.
        val r = Recorder()
        val tokenA = r.gate.beginOrRefreshSession()
        r.gate.close()                      // Stop
        val tokenB = r.gate.beginOrRefreshSession()   // immediate reconnect
        check(tokenB != tokenA)
        // A's callback finally runs, carrying its own (dead) token.
        check(!r.gate.publish(tokenA) { r.posted += "stale-A" }) {
            "a previous client's callback must not publish under the successor"
        }
        check(r.gate.publish(tokenB) { r.posted += "B" })
        check(r.posted.toList() == listOf("B")) { "got ${r.posted}" }
    }
}
