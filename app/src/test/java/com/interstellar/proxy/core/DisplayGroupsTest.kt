package com.interstellar.proxy.core

import com.interstellar.proxy.data.config.parseStaticGroups
import org.junit.Test

/**
 * The node page draws its groups from active.json, but a provider's nodes are not in that file:
 * the core downloads them at runtime and publishes them as `<provider>/<node>`. Before
 * [displayGroups] existed, such a group was displayed as an empty one on every page read, so
 * the provider's nodes could not be shown at all.
 *
 * These cases pin the split of authority: the file keeps the structure (order, declared members,
 * types, the stopped-core view) and the live snapshot only adds what the file cannot know.
 */
class DisplayGroupsTest {

    private fun item(tag: String, type: String = "vless", delay: Int = 0, testedAt: Long = 0) =
        CoreGroupItem(tag, type, delay, testedAt)

    private fun group(
        tag: String,
        type: String = "selector",
        selected: String? = null,
        items: List<CoreGroupItem> = emptyList(),
    ) = CoreGroup(tag, type, selected, items)

    @Test
    fun `a provider-fed group takes the members only the core knows`() {
        val static = listOf(group("MAIN"))
        val live = listOf(
            group(
                "MAIN",
                items = listOf(item("air/HK-01", delay = 38, testedAt = 1_700_000_000), item("air/JP-01", delay = 52)),
            ),
        )

        val displayed = displayGroups(static, live)

        check(displayed.size == 1) { "the file still defines the only group" }
        check(displayed[0].tag == "MAIN")
        check(displayed[0].type == "selector") { "the declared type is the structure" }
        check(displayed[0].items.map { it.tag } == listOf("air/HK-01", "air/JP-01")) {
            "provider nodes must reach the page in the core's order"
        }
        check(displayed[0].items[0].urlTestDelay == 38 && displayed[0].items[0].urlTestTime == 1_700_000_000L) {
            "the core's delay stamp has to survive the merge, or the node renders untested"
        }
    }

    @Test
    fun `declared members keep their place and their type, provider nodes are appended`() {
        val static = listOf(
            group(
                "MAIN",
                selected = "hk",
                items = listOf(item("direct", "direct"), item("hk", "trojan")),
            ),
        )
        // A provider publication may put its own nodes first; the page must not reorder the
        // members the user wrote, and a tag that exists in both halves is the declared node.
        val live = listOf(
            group(
                "MAIN",
                selected = "air/HK-01",
                items = listOf(item("air/HK-01", delay = 30), item("hk", "vless"), item("direct")),
            ),
        )

        val displayed = displayGroups(static, live)

        check(displayed[0].items.map { it.tag } == listOf("direct", "hk", "air/HK-01")) {
            "declared order is the page's structure; runtime-only members land after it"
        }
        check(displayed[0].items[1].type == "trojan") { "the live snapshot must not retype a declared member" }
        check(displayed[0].selected == "hk") { "selection is not membership; the caller overlays runtime selection" }
    }

    @Test
    fun `a declared member the core has not reported is still shown`() {
        val static = listOf(group("MAIN", items = listOf(item("a"), item("b"))))
        val live = listOf(group("MAIN", items = listOf(item("a"))))

        check(displayGroups(static, live)[0].items.map { it.tag } == listOf("a", "b")) {
            "the file says the group has b; a snapshot that lost it must not hide it"
        }
    }

    @Test
    fun `a provider config plus the core snapshot is what the page shows`() {
        // The whole data path of the reported defect: the file declares a provider-fed group, so
        // the static half has no members; the core reports the published tags; the page must end
        // up with the nodes, not with an empty group.
        val static = parseStaticGroups(
            """
            {
              "providers": [{"type": "remote", "tag": "air", "url": "https://provider.example/sub"}],
              "outbounds": [{"type": "selector", "tag": "proxy", "providers": ["air"]}]
            }
            """.trimIndent(),
        )
        val live = listOf(
            group(
                "proxy",
                items = listOf(item("air/HK-01", delay = 41), item("air/JP-01", delay = 88)),
            ),
        )

        val displayed = displayGroups(static, live)

        check(displayed.single().tag == "proxy")
        check(displayed.single().items.map { it.tag } == listOf("air/HK-01", "air/JP-01")) {
            "the provider's nodes must be displayed, not filtered out by an empty static half"
        }
    }

    @Test
    fun `a stopped core leaves the static structure untouched`() {
        val static = listOf(group("MAIN", items = listOf(item("a"), item("b"))))

        check(displayGroups(static, emptyList()) == static) {
            "with no live snapshot the file is still the page"
        }
    }

    @Test
    fun `a group a provider publishes gets a tab of its own`() {
        val static = listOf(group("MAIN"))
        val live = listOf(
            group("MAIN", items = listOf(item("air/group"))),
            group("air/group", "urltest", items = listOf(item("air/HK-01"), item("air/JP-01"))),
        )

        val displayed = displayGroups(static, live)

        check(displayed.map { it.tag } == listOf("MAIN", "air/group")) {
            "a runtime-only group is real and displayable, and it belongs after the file's groups"
        }
        check(displayed[1].type == "urltest")
        check(displayed[1].items.map { it.tag } == listOf("air/HK-01", "air/JP-01"))
    }

    @Test
    fun `an empty runtime-only group does not become an empty tab`() {
        val static = listOf(group("MAIN"))
        val live = listOf(group("MAIN"), group("air/empty", "urltest"))

        check(displayGroups(static, live).map { it.tag } == listOf("MAIN")) {
            "a tab with nothing in it would be a dead end"
        }
    }

    @Test
    fun `a duplicated live member is listed once`() {
        val static = listOf(group("MAIN"))
        val live = listOf(group("MAIN", items = listOf(item("air/HK-01"), item("air/HK-01", delay = 30))))

        check(displayGroups(static, live)[0].items.map { it.tag } == listOf("air/HK-01")) {
            "the list is keyed by tag; a duplicate would break the lazy-list keys"
        }
    }

    @Test
    fun `load-balance groups are groups, not nodes`() {
        check(isGroupType("loadbalance")) { "libbox reports this type for a real group" }
        check(isGroupType("LoadBalance")) { "the check is case-insensitive like the other group types" }
        check(isGroupType("selector") && isGroupType("urltest"))
        check(!isGroupType("vless") && !isGroupType("direct")) { "a node type must not be rendered as a group" }
    }
}
