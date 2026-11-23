package com.interstellar.proxy.ui.pages

import com.interstellar.proxy.core.CoreGroup
import com.interstellar.proxy.core.CoreGroupItem
import com.interstellar.proxy.core.UrlTestResult
import com.interstellar.proxy.data.config.MinimalConfigBuilder
import com.interstellar.proxy.core.UrlTestDisplay
import com.interstellar.proxy.core.urlTestDisplay
import org.junit.Test

/**
 * The dashboard's leaf walk must carry the leaf's COMPLETE result.
 *
 * The old helpers returned only a delay and used `takeIf { it > 0 }` when falling back, so a fresh
 * failure (0 with a completion time) could be replaced by an older positive delay, and the
 * composition could not tell "finished and failed" from "still testing". These cases pin the
 * replacement rule: the pair moves together and the newer result wins.
 */
class DashboardNodeSelectionTest {

    private fun item(
        tag: String,
        type: String = "vless",
        delay: Int = 0,
        testedAt: Long = 0,
    ) = CoreGroupItem(tag, type, delay, testedAt)

    private fun group(
        tag: String,
        type: String = "selector",
        selected: String? = null,
        items: List<CoreGroupItem> = emptyList(),
    ) = CoreGroup(tag, type, selected, items)

    private fun node(tag: String, results: Map<String, UrlTestResult>): CoreGroupItem {
        val result = results[tag] ?: return item(tag)
        return item(tag, delay = result.delay, testedAt = result.completedAt)
    }

    @Test
    fun `a selected leaf returns its complete timeout result`() {
        val results = mapOf("hk" to UrlTestResult(0, 1_700_000_000L))
        val main = group(
            MinimalConfigBuilder.GROUP_TAG,
            selected = "hk",
            items = listOf(node("hk", results)),
        )

        val selection = resolveNow(listOf(main), results, MinimalConfigBuilder.GROUP_TAG)

        check(selection.tag == "hk")
        check(selection.result == UrlTestResult(0, 1_700_000_000L)) {
            "the selected path must return the failure's own delay and time, not just a positive delay"
        }
        check(selectedResult(listOf(main), results, main, "hk")?.isTimeout == true)
    }

    @Test
    fun `a new failure is not replaced by an older success of the same member`() {
        // The group snapshot still shows the previous success; the tag-level stream has the new
        // failure. The newer complete pair must win even though its delay is zero.
        val results = mapOf("hk" to UrlTestResult(0, 2_000L))
        val main = group(
            MinimalConfigBuilder.GROUP_TAG,
            selected = "hk",
            items = listOf(item("hk", delay = 42, testedAt = 1_000L)),
        )

        val result = selectedResult(listOf(main), results, main, "hk")

        check(result == UrlTestResult(0, 2_000L)) {
            "the failure must not be discarded because its delay is not positive"
        }
    }

    @Test
    fun `a newer success replaces an older failure`() {
        val results = mapOf("hk" to UrlTestResult(55, 2_000L))
        val main = group(
            MinimalConfigBuilder.GROUP_TAG,
            selected = "hk",
            items = listOf(item("hk", delay = 0, testedAt = 1_000L)),
        )

        check(selectedResult(listOf(main), results, main, "hk") == UrlTestResult(55, 2_000L))
    }

    @Test
    fun `a URLTest group's selected leaf keeps its scoped result`() {
        // auto selected hk: its group snapshot says 80 ms against the group's own target, while
        // the tag-level cache holds a newer 0 against another target. The group's own result is
        // what its selection used, so the dashboard shows it.
        val results = mapOf("hk" to UrlTestResult(0, 3_000L))
        val auto = group(
            MinimalConfigBuilder.AUTO_TAG,
            type = "urltest",
            selected = "hk",
            items = listOf(item("hk", delay = 80, testedAt = 1_000L)),
        )
        val main = group(
            MinimalConfigBuilder.GROUP_TAG,
            selected = MinimalConfigBuilder.AUTO_TAG,
            items = listOf(item(MinimalConfigBuilder.AUTO_TAG, "urltest")),
        )

        val selection = resolveNow(listOf(main, auto), results, MinimalConfigBuilder.GROUP_TAG)

        check(selection.tag == "hk")
        check(selection.result == UrlTestResult(80, 1_000L)) {
            "the group-scope pair must not be overwritten by a tag-only cache entry"
        }
    }

    @Test
    fun `an untested selected leaf returns no result`() {
        val main = group(
            MinimalConfigBuilder.GROUP_TAG,
            selected = "hk",
            items = listOf(item("hk")),
        )

        check(selectedResult(listOf(main), emptyMap(), main, "hk") == null)
    }

    @Test
    fun `an urltest group without a selection shows its fastest successful member`() {
        val results = mapOf(
            "slow" to UrlTestResult(90, 1_000L),
            "fast" to UrlTestResult(30, 1_000L),
            "failed" to UrlTestResult(0, 1_000L),
        )
        val auto = group(
            MinimalConfigBuilder.AUTO_TAG,
            type = "urltest",
            items = listOf(node("slow", results), node("fast", results), node("failed", results)),
        )

        val selection = resolveNow(listOf(auto), results, MinimalConfigBuilder.AUTO_TAG)

        check(selection.tag == "fast") { "only a success may rank as fastest; a 0 delay must not win" }
        check(selection.result?.delay == 30)
    }

    @Test
    fun `a group whose members all failed does not present the zero as the fastest`() {
        val results = mapOf(
            "a" to UrlTestResult(0, 1_000L),
            "b" to UrlTestResult(0, 1_000L),
        )
        val auto = group(
            MinimalConfigBuilder.AUTO_TAG,
            type = "urltest",
            items = listOf(node("a", results), node("b", results)),
        )

        val selection = resolveNow(listOf(auto), results, MinimalConfigBuilder.AUTO_TAG)

        check(selection.tag == "a") { "with no success, the group's first member is the honest fallback" }
        check(selection.result?.isTimeout == true)
    }

    @Test
    fun `the row label and leaf tag follow the same walk as the result`() {
        val results = mapOf("hk" to UrlTestResult(12, 1_000L))
        val auto = group(
            MinimalConfigBuilder.AUTO_TAG,
            type = "urltest",
            selected = "hk",
            items = listOf(item("hk", delay = 12, testedAt = 1_000L)),
        )
        val main = group(
            MinimalConfigBuilder.GROUP_TAG,
            selected = MinimalConfigBuilder.AUTO_TAG,
            items = listOf(item(MinimalConfigBuilder.AUTO_TAG, "urltest")),
        )
        val groups = listOf(main, auto)

        check(nodeRowValue(groups, results, main, "hk") == "hk")
        check(currentLeafTag(groups, results, main, "hk") == "hk")
        check(selectedResult(groups, results, main, "hk") == UrlTestResult(12, 1_000L))
    }

    @Test
    fun `a stopped core falls back to the persisted pick and still returns its result`() {
        val results = mapOf("hk" to UrlTestResult(0, 5_000L))
        val main = group(
            MinimalConfigBuilder.GROUP_TAG,
            selected = null,
            items = listOf(item("hk", delay = 0, testedAt = 5_000L)),
        )

        val selection = resolveNow(listOf(main), results, "hk")

        check(selection.tag == "hk")
        check(selection.result == UrlTestResult(0, 5_000L))
        check(nodeRowValue(listOf(main), results, main, "hk") == "hk")
    }

    @Test
    fun `a cycle is bounded and returns the last tag`() {
        val a = group("a", selected = "b", items = listOf(item("b", "selector")))
        val b = group("b", selected = "a", items = listOf(item("a", "selector")))

        val selection = resolveNow(listOf(a, b), emptyMap(), "a")

        check(selection.tag.isNotBlank())
    }
}

// ---- the differential the two screens never had ------------------------------------------------

/**
 * The dashboard's delay line and the node badge derive their text from the same terminal state, and
 * before this test they could disagree: the dashboard rendered `delay <= 0` as "testing" while the
 * badge rendered a finished failure as a timeout. Both decisions are now values, so they can be
 * compared without a device.
 */
class DashboardAndBadgeAgreeTest {
    private fun allStates(): List<Pair<String, UrlTestResult?>> = listOf(
        "never tested" to null,
        "timeout" to UrlTestResult(0, 1_700_000_000L),
        "success" to UrlTestResult(37, 1_700_000_000L),
        "delay without time" to UrlTestResult(37, 0L),
        "zero delay and zero time" to UrlTestResult(0, 0L),
    )

    @Test
    fun `the dashboard and the badge agree on every terminal state`() {
        for ((name, result) in allStates()) {
            val badge = urlTestDisplay(result)
            val dashboard = dashboardDelayDisplay(result, memberPending = false)
            val expected = when (badge) {
                UrlTestDisplay.Delay -> DashboardDelayDisplay.Delay
                UrlTestDisplay.Timeout -> DashboardDelayDisplay.Timeout
                UrlTestDisplay.Untested -> DashboardDelayDisplay.Untested
            }
            check(dashboard == expected) {
                "$name: the badge says $badge but the dashboard says $dashboard"
            }
        }
    }

    @Test
    fun `only a pending member that has no result shows the loading state`() {
        check(dashboardDelayDisplay(null, memberPending = true) == DashboardDelayDisplay.Testing)
        // A failure that already arrived ends the loading state, even inside the run that asked for it.
        check(dashboardDelayDisplay(UrlTestResult(0, 10L), memberPending = true) == DashboardDelayDisplay.Timeout)
        check(dashboardDelayDisplay(UrlTestResult(9, 10L), memberPending = true) == DashboardDelayDisplay.Delay)
        // And nothing outside a run may claim to be loading.
        check(dashboardDelayDisplay(null, memberPending = false) == DashboardDelayDisplay.Untested)
    }
}