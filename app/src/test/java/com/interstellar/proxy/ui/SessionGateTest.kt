package com.interstellar.proxy.ui

import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The connections stream is opened and closed by page visibility while events arrive
 * from the libbox callback thread. The dangerous ordering is:
 *
 *   callback checks "am I still current?" → page closes and clears the list →
 *   callback writes its (now stale) snapshot
 *
 * which resurrects a closed page's list. [SessionGate] exists to make that
 * impossible, and these tests pin both halves of the guarantee.
 */
class SessionGateTest {

    private class Recorder {
        val published = CopyOnWriteArrayList<List<String>>()
        val gate = SessionGate<String> { published += it }
    }

    @Test
    fun `a commit for the live session is published`() {
        val r = Recorder()
        val g = r.gate.start()
        r.gate.commit(g, listOf("a"))
        check(r.published.toList() == listOf(listOf("a"))) { "got ${r.published}" }
    }

    @Test
    fun `a commit for a superseded session is dropped`() {
        val r = Recorder()
        val first = r.gate.start()
        val second = r.gate.start()
        r.gate.commit(first, listOf("stale"))
        check(r.published.isEmpty()) { "an old session must not publish: ${r.published}" }
        r.gate.commit(second, listOf("fresh"))
        check(r.published.toList() == listOf(listOf("fresh"))) { "got ${r.published}" }
    }

    @Test
    fun `stop clears what the session published`() {
        val r = Recorder()
        val g = r.gate.start()
        r.gate.commit(g, listOf("a", "b"))
        r.gate.stop()
        check(r.published.last().isEmpty()) { "stop must leave the list empty: ${r.published}" }
    }

    @Test
    fun `a commit after stop is dropped, not resurrected`() {
        val r = Recorder()
        val g = r.gate.start()
        r.gate.stop()
        r.gate.commit(g, listOf("resurrected"))
        check(r.published.last().isEmpty()) {
            "a post-stop commit resurrected a snapshot: ${r.published}"
        }
    }

    @Test
    fun `a commit racing stop can never be the last word`() {
        // The interleaving that matters, run for real: a commit and a stop from two
        // threads. Whichever wins the lock, the sequence must end empty — the commit
        // either lands first (and is then cleared) or is rejected by the generation
        // check inside the same lock.
        repeat(200) {
            val r = Recorder()
            val g = r.gate.start()
            val go = CountDownLatch(1)
            val committer = Thread {
                go.await(5, TimeUnit.SECONDS)
                r.gate.commit(g, listOf("race"))
            }
            val stopper = Thread {
                go.await(5, TimeUnit.SECONDS)
                r.gate.stop()
            }
            committer.start(); stopper.start()
            go.countDown()
            committer.join(5_000); stopper.join(5_000)
            check(r.published.isNotEmpty()) { "no publish happened at all" }
            check(r.published.last().isEmpty()) {
                "iteration $it ended with a resurrected snapshot: ${r.published}"
            }
        }
    }

    @Test
    fun `a session reopened after stop still publishes`() {
        val r = Recorder()
        val g1 = r.gate.start()
        r.gate.commit(g1, listOf("old"))
        r.gate.stop()
        val g2 = r.gate.start()
        r.gate.commit(g2, listOf("new"))
        check(r.published.last() == listOf("new")) { "got ${r.published}" }
    }

    @Test
    fun `the generation is visible across threads`() {
        val r = Recorder()
        val g = r.gate.start()
        var seen = -1
        val t = Thread { seen = r.gate.generationValue }
        t.start(); t.join(5_000)
        check(seen == g) { "another thread saw generation $seen, expected $g" }
    }
}
