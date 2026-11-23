package com.interstellar.proxy.core

import org.junit.Test

/**
 * The two-field terminal contract, exercised on the pure data layer.
 *
 * The kernel reuses `urlTestDelay` (int) and `urlTestTime` (seconds) with this meaning:
 * a positive delay is a success, a zero delay WITH a time is a genuine failure, and no time is
 * "never tested". No `-1` sentinel and no magic delay threshold exists in this model, so the only
 * way to get these wrong is to read the fields from different results or to treat zero as
 * "missing". Both are pinned here.
 */
class UrlTestResultTest {

    private fun item(tag: String, delay: Int = 0, completedAt: Long = 0L) =
        CoreGroupItem(tag, "vless", delay, completedAt)

    // ---- one snapshot -> one pair per tag -----------------------------------

    @Test
    fun `a snapshot entry keeps its own delay and completion time together`() {
        val results = urlTestResultsOf(
            listOf(
                item("ok", delay = 123, completedAt = 1_700_000_000L),
                item("failed", delay = 0, completedAt = 1_700_000_010L),
                item("untested", delay = 0, completedAt = 0L),
            ),
        )

        check(results["ok"] == UrlTestResult(123, 1_700_000_000L))
        check(results["failed"] == UrlTestResult(0, 1_700_000_010L)) {
            "a failure is a terminal result, not a missing one"
        }
        check("untested" !in results) {
            "0/0 means never tested; mapping it to a zero result would fake a timeout"
        }
    }

    @Test
    fun `a new failure carries only the new time, never an older success`() {
        val first = urlTestResultsOf(listOf(item("n", delay = 42, completedAt = 1_000L)))
        val second = urlTestResultsOf(listOf(item("n", delay = 0, completedAt = 2_000L)))

        check(first["n"]!!.isSuccess)
        check(second["n"] == UrlTestResult(0, 2_000L)) {
            "the failure's delay must be paired with the failure's own completion time"
        }
        check(second["n"]!!.isTimeout)
    }

    @Test
    fun `one snapshot replaces the map instead of accumulating old tags`() {
        val results = urlTestResultsOf(listOf(item("kept", delay = 5, completedAt = 10L)))
        check(results.keys == setOf("kept"))

        val next = urlTestResultsOf(listOf(item("other", delay = 7, completedAt = 20L)))
        check(next.keys == setOf("other")) {
            "a tag absent from the snapshot has no result any more; the map is bounded by it"
        }
    }

    @Test
    fun `the stop frame is not a measurement and must not erase the last results`() {
        // SubscribeOutbounds sends a zero-length list once the service is not STARTED. A running
        // config always has at least one outbound, so this frame carries no information - and
        // treating it as one would wipe an offline test's result when its temporary core stops.
        check(urlTestResultsUpdate(emptyList()) == null)

        // A real snapshot, even one with no results yet, is authoritative and clears the map.
        val freshInstance = urlTestResultsUpdate(listOf(item("n", delay = 0, completedAt = 0L)))
        check(freshInstance != null && freshInstance.isEmpty()) {
            "a running instance that has not measured anything has no results"
        }
    }

    // ---- the three-way display decision --------------------------------------

    @Test
    fun `the display decision is the two-field rule`() {
        check(urlTestDisplay(null) == UrlTestDisplay.Untested)
        check(urlTestDisplay(UrlTestResult(0, 0L)) == UrlTestDisplay.Untested)
        check(urlTestDisplay(UrlTestResult(0, 1_700_000_000L)) == UrlTestDisplay.Timeout)
        check(urlTestDisplay(UrlTestResult(1, 1_700_000_000L)) == UrlTestDisplay.Delay)
        check(urlTestDisplay(UrlTestResult(120, 1_700_000_000L)) == UrlTestDisplay.Delay)
    }

    @Test
    fun `a legitimate large delay is a success, not a timeout`() {
        // 65000/65535 used to be hard-coded failure sentinels in the UI. The kernel caps a positive
        // RTT at 65535, and with a completion time it is still a successful measurement.
        check(urlTestDisplay(UrlTestResult(65_535, 1_700_000_000L)) == UrlTestDisplay.Delay)
        check(urlTestDisplay(65_535, 1_700_000_000L) != UrlTestDisplay.Timeout)
    }

    @Test
    fun `the raw-field overload agrees with the result overload`() {
        check(urlTestDisplay(0, 0L) == UrlTestDisplay.Untested)
        check(urlTestDisplay(0, 10L) == UrlTestDisplay.Timeout)
        check(urlTestDisplay(12, 10L) == UrlTestDisplay.Delay)
    }

    // ---- summary counting ----------------------------------------------------

    @Test
    fun `the summary counts only terminal outcomes as ok or failed`() {
        val results = mapOf(
            "ok-1" to UrlTestResult(20, 100L),
            "ok-2" to UrlTestResult(90, 100L),
            "failed" to UrlTestResult(0, 100L),
        )
        val summary = summarizeUrlTest(
            tags = listOf("ok-1", "ok-2", "failed", "pending"),
            results = results,
        )

        check(summary.total == 4)
        check(summary.ok == 2)
        check(summary.failed == 1) {
            "only a member with a terminal failure may be counted as failed"
        }
        check(summary.pending == 1) {
            "a member that has not returned yet is pending, never failed"
        }
        check(summary.delays == listOf(20, 90))
        check(summary.minMs == 20 && summary.maxMs == 90)
        // The pre-existing nearest-lower-index percentile rule is preserved; with two values both
        // ranks land on the first one. This test pins counting, not a new percentile definition.
        check(summary.p50 == 20 && summary.p95 == 20)
    }

    @Test
    fun `a previous run's success cannot hide this run's failure`() {
        // The run submitted only "n"; the map still has an old success for "gone" and a new
        // failure for "n". Counting over the submitted set keeps the old success out.
        val summary = summarizeUrlTest(
            tags = listOf("n"),
            results = mapOf(
                "gone" to UrlTestResult(10, 100L),
                "n" to UrlTestResult(0, 200L),
            ),
        )

        check(summary.ok == 0)
        check(summary.failed == 1)
        check(summary.pending == 0)
    }

    @Test
    fun `duplicate submitted tags cannot inflate the total`() {
        val summary = summarizeUrlTest(
            tags = listOf("n", "n", "m"),
            results = mapOf("n" to UrlTestResult(30, 100L)),
        )

        check(summary.total == 2)
        check(summary.ok == 1)
        check(summary.pending == 1)
    }

    @Test
    fun `an empty run has no numbers rather than a fabricated denominator`() {
        val summary = summarizeUrlTest(emptyList(), emptyMap())
        check(summary.total == 0 && summary.ok == 0 && summary.failed == 0 && summary.pending == 0)
        check(summary.minMs == 0 && summary.maxMs == 0 && summary.p50 == 0 && summary.p95 == 0)
    }
}
