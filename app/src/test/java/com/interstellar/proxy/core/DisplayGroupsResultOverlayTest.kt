package com.interstellar.proxy.core

import org.junit.Test

/**
 * The projection overlays a COMPLETE runtime result onto the static structure.
 *
 * The regression this pins: `displayGroups` used to keep the static object for every declared
 * member and only append runtime-only ones, so a result reported on the live half was dropped.
 * The nodes page then combined a delay from a separate cache with the time from the static item,
 * which could assemble `0 / 0` out of two different results and leave a failed node looking
 * untested.
 */
class DisplayGroupsResultOverlayTest {

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
    fun `a declared member receives the complete runtime pair without changing its structure`() {
        val static = listOf(
            group("MAIN", selected = "hk", items = listOf(item("direct", "direct"), item("hk", "trojan"))),
        )
        // The live half has the members but no result for hk yet.
        val live = listOf(
            group("MAIN", items = listOf(item("direct", "direct"), item("hk", "vless"))),
        )
        val results = mapOf("hk" to UrlTestResult(0, 1_700_000_000L))

        val displayed = displayGroups(static, live, results)

        check(displayed.single().items.map { it.tag } == listOf("direct", "hk")) {
            "membership and order still come from the file"
        }
        check(displayed.single().items[1].type == "trojan") {
            "a declared member keeps its configured type"
        }
        val hk = displayed.single().items[1]
        check(hk.urlTestDelay == 0 && hk.urlTestTime == 1_700_000_000L) {
            "both fields must be overlaid from ONE result; 0/0 would render an untested node"
        }
    }

    @Test
    fun `a provider-only group member receives the overlay too`() {
        val static = listOf(group("MAIN"))
        val live = listOf(group("MAIN", items = listOf(item("air/HK-01", "vless"))))
        val results = mapOf("air/HK-01" to UrlTestResult(38, 1_700_000_000L))

        val hk = displayGroups(static, live, results).single().items.single()

        check(hk.tag == "air/HK-01" && hk.urlTestDelay == 38 && hk.urlTestTime == 1_700_000_000L)
    }

    @Test
    fun `a selector member takes the newer complete pair so a new failure replaces an old success`() {
        // Both halves are tag-level results; the group snapshot is simply one push behind.
        val static = listOf(
            group("MAIN", items = listOf(item("hk", delay = 42, testedAt = 1_000L))),
        )
        val live = listOf(
            group("MAIN", items = listOf(item("hk", delay = 42, testedAt = 1_000L))),
        )
        val results = mapOf("hk" to UrlTestResult(0, 2_000L))

        val hk = displayGroups(static, live, results).single().items.single()

        check(hk.urlTestDelay == 0 && hk.urlTestTime == 2_000L) {
            "the newer failure must replace the older success as a pair"
        }
    }

    @Test
    fun `a URLTest group's scoped result is not overwritten by a newer tag-only entry`() {
        // The group measured 80 ms against its own target; the tag-level cache says 0 against a
        // different target. The group's own selection used 80, so that is what its members show.
        val static = listOf(
            group("auto", "urltest", items = listOf(item("hk", delay = 80, testedAt = 1_000L))),
        )
        val live = listOf(
            group("auto", "urltest", items = listOf(item("hk", delay = 80, testedAt = 1_000L))),
        )
        val results = mapOf("hk" to UrlTestResult(0, 3_000L))

        val hk = displayGroups(static, live, results).single().items.single()

        check(hk.urlTestDelay == 80 && hk.urlTestTime == 1_000L) {
            "the group-scope pair must win over a tag-only cache entry"
        }
    }

    @Test
    fun `a URLTest group falls back to the tag-level entry only when it has no scoped result`() {
        val static = listOf(
            group("auto", "urltest", items = listOf(item("hk", delay = 0, testedAt = 0L))),
        )
        val live = listOf(
            group("auto", "urltest", items = listOf(item("hk", delay = 0, testedAt = 0L))),
        )
        val results = mapOf("hk" to UrlTestResult(0, 3_000L))

        val hk = displayGroups(static, live, results).single().items.single()

        check(hk.urlTestDelay == 0 && hk.urlTestTime == 3_000L)
    }

    @Test
    fun `a declared member the core has not reported still receives the last complete result`() {
        val static = listOf(group("MAIN", items = listOf(item("a"), item("b"))))
        val live = listOf(group("MAIN", items = listOf(item("a"))))
        val results = mapOf("b" to UrlTestResult(0, 4_000L))

        val displayed = displayGroups(static, live, results)

        check(displayed.single().items.map { it.tag } == listOf("a", "b"))
        check(displayed.single().items[1].urlTestTime == 4_000L)
    }

    @Test
    fun `a stopped core keeps the file's structure and still shows the last results`() {
        val static = listOf(group("MAIN", items = listOf(item("a"), item("b"))))
        val results = mapOf("a" to UrlTestResult(25, 5_000L))

        val displayed = displayGroups(static, emptyList(), results)

        check(displayed.single().items.map { it.tag } == listOf("a", "b"))
        check(displayed.single().items[0].urlTestDelay == 25 && displayed.single().items[0].urlTestTime == 5_000L)
        check(displayed.single().items[1].urlTestTime == 0L) { "b has no result and must stay untested" }
    }

    @Test
    fun `no live snapshot and no results is still the exact static list`() {
        val static = listOf(group("MAIN", items = listOf(item("a"), item("b"))))
        check(displayGroups(static, emptyList()) == static)
        check(displayGroups(static, emptyList(), emptyMap()) == static)
    }

    @Test
    fun `a stale success in either half never wins over a newer failure of the same tag`() {
        // The failure is in the group snapshot and the success is in the tag-level map.
        val scopedFailure = resultForMember(
            "selector",
            scoped = UrlTestResult(0, 2_000L),
            global = UrlTestResult(55, 1_000L),
        )
        check(scopedFailure == UrlTestResult(0, 2_000L))

        // ... and the other way round.
        val globalFailure = resultForMember(
            "selector",
            scoped = UrlTestResult(55, 1_000L),
            global = UrlTestResult(0, 2_000L),
        )
        check(globalFailure == UrlTestResult(0, 2_000L))

        // A URLTest group keeps its own scoped result regardless of the other entry's time.
        check(
            resultForMember(
                "urltest",
                scoped = UrlTestResult(55, 1_000L),
                global = UrlTestResult(0, 2_000L),
            ) == UrlTestResult(55, 1_000L),
        )
        // No scoped result: the tag-level entry answers.
        check(
            resultForMember("urltest", scoped = null, global = UrlTestResult(0, 2_000L)) ==
                UrlTestResult(0, 2_000L),
        )
    }
}
