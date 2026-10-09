package com.interstellar.proxy.ui

import org.junit.Test

/**
 * The Logs UI list is coalesced, not sampled: every line still reaches the ring
 * (see LogRingBufferTest), and this gate only decides how often the visible list
 * is rebuilt. The two behaviours that matter are "one publish per window no
 * matter how many lines arrive" and "a quiet buffer publishes nothing".
 */
class LogUiPublishGateTest {

    @Test
    fun `nothing appended means nothing is due`() {
        val gate = LogUiPublishGate(minIntervalMs = 75)
        check(!gate.isDue(0)) { "an untouched buffer must not publish" }
        check(!gate.isDue(10_000))
    }

    @Test
    fun `first append publishes immediately`() {
        val gate = LogUiPublishGate(minIntervalMs = 75)
        gate.onAppend()
        check(gate.isDue(1_000)) { "the first snapshot must not wait for a window" }
    }

    @Test
    fun `appends inside one window coalesce into a single publish`() {
        val gate = LogUiPublishGate(minIntervalMs = 75)
        gate.onAppend()
        check(gate.isDue(1_000))
        gate.markPublished(1_000)

        // 100 lines arrive within the window
        repeat(100) { gate.onAppend() }
        check(!gate.isDue(1_020)) { "must not publish again inside the window" }
        check(!gate.isDue(1_074)) { "still inside the window" }
        check(gate.isDue(1_075)) { "the next window must publish the accumulated lines" }
    }

    @Test
    fun `no new lines after a publish means no further publishes`() {
        val gate = LogUiPublishGate(minIntervalMs = 75)
        gate.onAppend()
        gate.markPublished(1_000)
        check(!gate.isDue(2_000)) { "unchanged version must stay quiet" }
        check(!gate.isDue(60_000))
    }

    @Test
    fun `each window publishes once for a steady stream`() {
        val gate = LogUiPublishGate(minIntervalMs = 75)
        var now = 0L
        var publishes = 0
        // 300 lines over 300ms, polled every 25ms
        repeat(12) {
            repeat(25) { gate.onAppend() }
            now += 25
            if (gate.isDue(now)) {
                gate.markPublished(now)
                publishes++
            }
        }
        check(publishes in 1..5) { "expected a handful of publishes, got $publishes" }
    }

    @Test
    fun `reset makes the gate behave like a fresh one`() {
        val gate = LogUiPublishGate(minIntervalMs = 75)
        gate.onAppend()
        gate.markPublished(1_000)
        gate.reset()
        check(!gate.isDue(1_010)) { "after reset a clean buffer publishes nothing" }
        gate.onAppend()
        check(gate.isDue(1_010)) { "and the next line publishes immediately again" }
    }
}
