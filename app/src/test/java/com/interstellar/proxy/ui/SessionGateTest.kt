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
        val connected = CopyOnWriteArrayList<Boolean>()
        val gate = SessionGate<String>(
            publish = { published += it },
            publishConnected = { connected += it },
        )
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
    fun `stop clears what the session published and marks it disconnected`() {
        val r = Recorder()
        val g = r.gate.start()
        r.gate.commit(g, listOf("a", "b"))
        r.gate.commitConnected(g, true)
        r.gate.stop()
        check(r.published.last().isEmpty()) { "stop must leave the list empty: ${r.published}" }
        check(r.connected.last() == false) {
            "stop must leave the session disconnected, got ${r.connected}"
        }
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

    // ---- the connection flag is committed through the same gate ----

    @Test
    fun `a stale connected callback cannot revive a stopped session`() {
        val r = Recorder()
        val g = r.gate.start()
        r.gate.stop()
        // The old shape was `if (isCurrent(g)) _connected.value = true`: the check and
        // the write were two steps, so this write landed after the stop and left a dead
        // page showing "connected".
        r.gate.commitConnected(g, true)
        check(r.connected.last() == false) {
            "a stale onConnected() revived a stopped session: ${r.connected}"
        }
    }

    @Test
    fun `a delayed disconnect from the old session does not knock the new one offline`() {
        val r = Recorder()
        val old = r.gate.start()
        r.gate.commitConnected(old, true)
        r.gate.stop()
        val fresh = r.gate.start()
        r.gate.commitConnected(fresh, true)
        // The previous generation's client finally reports its disconnect.
        r.gate.commitConnected(old, false)
        check(r.connected.last() == true) {
            "an old disconnect took the live session offline: ${r.connected}"
        }
    }

    @Test
    fun `list and connection flag never cross generations under contention`() {
        repeat(200) {
            val r = Recorder()
            val old = r.gate.start()
            val fresh = r.gate.start()
            val go = CountDownLatch(1)
            val threads = listOf(
                Thread { go.await(5, TimeUnit.SECONDS); r.gate.commitConnected(old, true) },
                Thread { go.await(5, TimeUnit.SECONDS); r.gate.commit(old, listOf("stale")) },
                Thread { go.await(5, TimeUnit.SECONDS); r.gate.stop() },
                Thread { go.await(5, TimeUnit.SECONDS); r.gate.commitConnected(fresh, true) },
            )
            threads.forEach { it.start() }
            go.countDown()
            threads.forEach { it.join(5_000) }
            check(r.published.none { it == listOf("stale") }) {
                "a stale list was published: ${r.published}"
            }
        }
    }
}
