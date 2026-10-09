package com.interstellar.proxy.ui

import com.interstellar.proxy.core.LogRingBuffer
import org.junit.Test

/**
 * The Logs UI list is coalesced, not sampled: every line still reaches the ring
 * (see LogRingBufferTest), and this gate only decides how often the visible list is
 * rebuilt.
 *
 * Two behaviours matter: "one publish per window no matter how many lines arrive"
 * and "a quiet buffer publishes nothing". A third one is a correctness bug rather
 * than a policy: a line that arrives *while* a snapshot is being published must not
 * be marked as already shown.
 */
class LogUiPublishGateTest {

    @Test
    fun `nothing appended means nothing is due`() {
        val gate = LogUiPublishGate(minIntervalMs = 75)
        check(!gate.isDue(0, 0)) { "an untouched buffer must not publish" }
        check(!gate.isDue(0, 10_000))
    }

    @Test
    fun `a newer version publishes immediately`() {
        val gate = LogUiPublishGate(minIntervalMs = 75)
        check(gate.isDue(1, 1_000)) { "the first snapshot must not wait for a window" }
    }

    @Test
    fun `the same version is never published twice`() {
        val gate = LogUiPublishGate(minIntervalMs = 75)
        check(gate.isDue(1, 1_000))
        gate.markPublished(1, 1_000)
        check(!gate.isDue(1, 2_000)) { "unchanged version must stay quiet" }
        check(!gate.isDue(1, 60_000))
    }

    @Test
    fun `appends inside one window coalesce into a single publish`() {
        val gate = LogUiPublishGate(minIntervalMs = 75)
        check(gate.isDue(1, 1_000))
        gate.markPublished(1, 1_000)

        check(!gate.isDue(101, 1_020)) { "must not publish again inside the window" }
        check(!gate.isDue(101, 1_074)) { "still inside the window" }
        check(gate.isDue(101, 1_075)) { "the next window must publish the accumulated lines" }
    }

    @Test
    fun `each window publishes once for a steady stream`() {
        val gate = LogUiPublishGate(minIntervalMs = 75)
        var version = 0L
        var now = 0L
        var publishes = 0
        repeat(12) {
            version += 25
            now += 25
            if (gate.isDue(version, now)) {
                gate.markPublished(version, now)
                publishes++
            }
        }
        check(publishes in 1..5) { "expected a handful of publishes, got $publishes" }
    }

    @Test
    fun `reset makes the gate behave like a fresh one`() {
        val gate = LogUiPublishGate(minIntervalMs = 75)
        gate.markPublished(1, 1_000)
        gate.reset()
        check(!gate.isDue(0, 1_010)) { "after reset a clean buffer publishes nothing" }
        check(gate.isDue(1, 1_010)) { "and the next line publishes immediately again" }
    }

    @Test
    fun `a line appended during a publish is not marked as shown`() {
        // Deterministic reproduction of the lost-tail race: the append lands between
        // taking the snapshot and recording the watermark. Marking "whatever is
        // current now" (as the old gate did) would mark this line as displayed even
        // though it is not in the list, and with no further traffic it would never
        // appear. Marking the snapshot's own version leaves the buffer ahead, so the
        // next pass is still due.
        val ring = LogRingBuffer<String>(3000)
        val gate = LogUiPublishGate(minIntervalMs = 0)

        ring.add("first")
        val (snapshot, snapshotVersion) = ring.snapshotWithVersion()
        check(snapshot == listOf("first"))

        ring.add("arrived-during-publish") // the interleaving that used to be lost
        gate.markPublished(snapshotVersion, 1_000)

        check(gate.isDue(ring.currentVersion, 2_000)) {
            "the line appended during the publish must still be published"
        }
        // and publishing it makes the gate quiet again
        val (next, nextVersion) = ring.snapshotWithVersion()
        check(next == listOf("first", "arrived-during-publish"))
        gate.markPublished(nextVersion, 2_000)
        check(!gate.isDue(ring.currentVersion, 3_000))
    }

    @Test
    fun `an append during a publish survives a saturating buffer`() {
        // Same race, but at capacity: the size does not move at all, so only the
        // version can reveal that something new arrived.
        val ring = LogRingBuffer<String>(3)
        repeat(3) { ring.add("old-$it") }
        val (snapshot, snapshotVersion) = ring.snapshotWithVersion()
        run {
            val gate = LogUiPublishGate(minIntervalMs = 0)
            ring.add("new") // evicts the oldest AND keeps size == 3
            check(snapshot.size == 3)
            check(ring.size == 3) { "the buffer is saturated; size cannot signal the change" }
            gate.markPublished(snapshotVersion, 1_000)
            check(gate.isDue(ring.currentVersion, 2_000)) {
                "a saturated buffer must still publish the replacement line"
            }
        }
    }
}
