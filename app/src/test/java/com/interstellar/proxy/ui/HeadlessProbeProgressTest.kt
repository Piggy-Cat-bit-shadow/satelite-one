package com.interstellar.proxy.ui

import com.interstellar.proxy.core.CoreGroup
import com.interstellar.proxy.core.CoreGroupItem
import com.interstellar.proxy.core.UrlTestResult
import org.junit.Test

/**
 * What the headless wait is allowed to treat as completion.
 *
 * The kernel's `URLTest` RPC returns before the probes have necessarily run, so the temporary
 * core must not be stopped on the response. The only trustworthy signal is a member's COMPLETE
 * terminal result (a failure carries a completion time too) attributed to this request. These
 * cases pin that rule and the stream each group type must read, so nobody can replace it with a
 * fixed settle delay again.
 */
class HeadlessProbeProgressTest {

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

    @Test
    fun `an unpublished target proves nothing and must keep waiting`() {
        val progress = headlessProbeProgress("auto", 1_000L, emptyList(), emptyMap())

        check(!progress.targetPublished)
        check(!progress.complete) { "no member set is not completion" }
        check(progress.members.isEmpty())
    }

    @Test
    fun `a group with no measurable members proves nothing`() {
        val target = group("auto", "urltest", items = listOf(item("nested", "selector")))

        val progress = headlessProbeProgress("auto", 1_000L, listOf(target), emptyMap())

        check(progress.targetPublished)
        check(progress.members.isEmpty())
        check(progress.unprovable == listOf("nested")) {
            "a URLTest group's nested member has no scoped pair the parent snapshot can expose"
        }
        check(!progress.complete) {
            "an empty member set cannot satisfy 'every member reported'"
        }
    }

    @Test
    fun `a selector batch expands nested groups into the leaves it submitted`() {
        val target = group(
            "MAIN",
            items = listOf(
                item("auto", "urltest"),
                item("sub", "selector"),
                item("direct", "direct"),
                item("vless-node"),
            ),
        )
        val auto = group("auto", "urltest", items = listOf(item("auto-leaf")))
        val sub = group("sub", items = listOf(item("sub-leaf")))
        val groups = listOf(target, auto, sub)
        val results = mapOf(
            "auto-leaf" to UrlTestResult(10, 1_100L),
            "sub-leaf" to UrlTestResult(0, 1_200L),
            "direct" to UrlTestResult(0, 1_300L),
            "vless-node" to UrlTestResult(12, 1_400L),
        )

        val progress = headlessProbeProgress("MAIN", 1_000L, groups, results)

        check(progress.members.toSet() == setOf("direct", "vless-node", "auto-leaf", "sub-leaf")) {
            "the display-only batch expands nested groups the way urlTestBatch.test does"
        }
        check(progress.members.size == 4)
        check(progress.unprovable.isEmpty())
        check(progress.complete)
    }

    @Test
    fun `a selector batch with an unpublished nested group cannot be proven`() {
        // The core does not list `hidden` at all, so its leaves cannot even be enumerated.
        val target = group(
            "MAIN",
            items = listOf(item("hidden", "selector"), item("direct", "direct")),
        )
        val results = mapOf("direct" to UrlTestResult(0, 1_100L))

        val progress = headlessProbeProgress("MAIN", 1_000L, listOf(target), results)

        check(progress.members == listOf("direct"))
        check(progress.unprovable == listOf("hidden")) {
            "completion must not be declared for a member set that could not be enumerated"
        }
        check(!progress.complete)
    }

    @Test
    fun `a URLTest group awaits its nested leaves through their tag-level pairs`() {
        // The parent's snapshot lists only the nested group. The round expands it and publishes
        // each leaf's tag-level result, which is the only observable pair for those leaves.
        val target = group(
            "auto",
            "urltest",
            items = listOf(
                item("direct", "direct", delay = 5, testedAt = 1_100L),
                item("region", "selector"),
            ),
        )
        val region = group("region", items = listOf(item("region-leaf")))
        val results = mapOf("region-leaf" to UrlTestResult(9, 1_200L))

        val progress = headlessProbeProgress("auto", 1_000L, listOf(target, region), results)

        check(progress.members == listOf("direct", "region-leaf")) {
            "nested groups are expanded the way the round expands them"
        }
        check(progress.unprovable.isEmpty())
        check(progress.complete)
    }

    @Test
    fun `a hidden nested group makes completion unprovable`() {
        val target = group(
            "auto",
            "urltest",
            items = listOf(item("hidden", "selector")),
        )

        val progress = headlessProbeProgress("auto", 1_000L, listOf(target), emptyMap())

        check(progress.members.isEmpty())
        check(progress.unprovable == listOf("hidden")) {
            "a group the snapshot omits cannot be expanded into the leaves that were measured"
        }
        check(!progress.complete)
    }

    @Test
    fun `a selector batch completes only when every member reported`() {
        val target = group(
            "MAIN",
            items = listOf(item("ok"), item("bad"), item("pending")),
        )
        val results = mapOf(
            "ok" to UrlTestResult(30, 1_001L),
            "bad" to UrlTestResult(0, 1_002L),
        )

        val progress = headlessProbeProgress("MAIN", 1_000L, listOf(target), results)

        check(progress.members == listOf("ok", "bad", "pending"))
        check(progress.completed == listOf("ok", "bad")) {
            "a retained failure is a terminal outcome, so it counts as reported"
        }
        check(progress.pending == listOf("pending"))
        check(!progress.complete)

        val finished = headlessProbeProgress(
            "MAIN",
            1_000L,
            listOf(target),
            results + ("pending" to UrlTestResult(0, 1_003L)),
        )
        check(finished.complete) {
            "every submitted member reported, including the failures"
        }
    }

    @Test
    fun `a result completed before the request does not count`() {
        val target = group("MAIN", items = listOf(item("n")))
        val old = mapOf("n" to UrlTestResult(0, 999L))

        check(!headlessProbeProgress("MAIN", 1_000L, listOf(target), old).complete) {
            "a previous result must not be attributed to this request"
        }

        val sameSecond = mapOf("n" to UrlTestResult(0, 1_000L))
        check(headlessProbeProgress("MAIN", 1_000L, listOf(target), sameSecond).complete) {
            "completion times are whole seconds; the request's own second is the newest result"
        }
    }

    @Test
    fun `a URLTest group reads its own scoped result, not a newer tag-level entry`() {
        // The group's snapshot has no result for hk, but a tag-only probe against another target
        // finished during this request. It must not end the wait for the group's own round.
        val target = group("auto", "urltest", items = listOf(item("hk")))
        val otherTarget = mapOf("hk" to UrlTestResult(0, 2_000L))

        val progress = headlessProbeProgress("auto", 1_000L, listOf(target), otherTarget)

        check(progress.completed.isEmpty())
        check(!progress.complete) {
            "a tag-level result measured against a different target is not this group's outcome"
        }
    }

    @Test
    fun `a URLTest group completes from the result its snapshot carries`() {
        val target = group(
            "auto",
            "urltest",
            items = listOf(item("hk", delay = 80, testedAt = 1_500L)),
        )

        val progress = headlessProbeProgress("auto", 1_000L, listOf(target), emptyMap())

        check(progress.complete)
        check(progress.completed == listOf("hk"))
    }

    @Test
    fun `duplicate members are submitted and awaited once`() {
        val target = group("MAIN", items = listOf(item("n"), item("n")))
        val results = mapOf("n" to UrlTestResult(10, 1_100L))

        val progress = headlessProbeProgress("MAIN", 1_000L, listOf(target), results)

        check(progress.members == listOf("n"))
        check(progress.complete)
    }
}
