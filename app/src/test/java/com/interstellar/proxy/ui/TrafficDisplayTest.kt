package com.interstellar.proxy.ui

import org.junit.Test

/**
 * The statistics regression was not only "counters are 0" — it was that the UI had
 * no way to say "this config has no traffic manager at all". A config that never
 * enables one measures nothing, and rendering that as `0 kB/s` claims a
 * measurement that never happened.
 *
 * These lock the three states apart and pin the byte-rendering policy.
 */
class TrafficDisplayTest {

    // ---- trafficDisplay ----

    @Test
    fun `core stopped is idle even if statistics are available`() {
        check(trafficDisplay(running = false, trafficAvailable = true) == TrafficDisplay.Idle)
        check(trafficDisplay(running = false, trafficAvailable = false) == TrafficDisplay.Idle)
    }

    @Test
    fun `running with statistics is live`() {
        check(trafficDisplay(running = true, trafficAvailable = true) == TrafficDisplay.Live)
    }

    @Test
    fun `running without statistics is unavailable and never live`() {
        val state = trafficDisplay(running = true, trafficAvailable = false)
        check(state == TrafficDisplay.Unavailable) { "expected Unavailable, got $state" }
        check(state != TrafficDisplay.Live) { "a config with no traffic manager cannot be Live" }
    }

    // ---- bytesOrUnknown ----

    @Test
    fun `unavailable byte counters render as unknown and never as zero`() {
        val rendered = bytesOrUnknown(0, trafficAvailable = false) { "0 B" }
        check(rendered == "—") { "unavailable must render as unknown, got '$rendered'" }
        check(rendered != "0 B") { "'not measured' must not be spelled '0 B'" }
    }

    @Test
    fun `unavailable rendering ignores the value entirely`() {
        // Even a stale non-zero value must not leak through as if it were current.
        check(bytesOrUnknown(123_456_789, trafficAvailable = false) { "117 MB" } == "—")
    }

    @Test
    fun `available byte counters delegate to the formatter`() {
        check(bytesOrUnknown(2048, trafficAvailable = true) { "$it bytes" } == "2048 bytes")
    }

    @Test
    fun `available zero is a real measured zero and is shown as such`() {
        // The opposite mistake: a genuine 0 from a working traffic manager must be
        // reported, not hidden behind the unknown marker.
        val rendered = bytesOrUnknown(0, trafficAvailable = true) { "0 B" }
        check(rendered == "0 B") { "a measured zero must be shown, got '$rendered'" }
    }
}
