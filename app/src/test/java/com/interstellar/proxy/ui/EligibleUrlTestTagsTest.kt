package com.interstellar.proxy.ui

import com.interstellar.proxy.core.CoreGroupItem
import org.junit.Test

/**
 * The single `eligibleNodes` source for a batch url-test.
 *
 * Both the progress denominator and the completion condition resolve from this list, so a
 * mistake here is not a cosmetic miscount: a tag that can never report a result keeps the run
 * from ever completing, which is exactly how every batch came to sit at "测速中" until its cap.
 *
 * The field config is what drives these cases: a raw sing-box config whose selectors hold five
 * real nodes and no `auto` group at all.
 */
class EligibleUrlTestTagsTest {

    private fun item(tag: String, type: String) = CoreGroupItem(tag, type)

    @Test
    fun `a selector's node members are all submitted`() {
        val items = listOf(
            item("🇺🇸 美国｜Vless Reality Vision", "vless"),
            item("🇺🇸 美国｜AnyTLS", "anytls"),
            item("🇺🇸 美国｜NaiveProxy", "naive"),
            item("🇺🇸 美国｜SS2022 ShadowTLS", "shadowsocks"),
            item("🇺🇸 美国｜MASQUE", "masque"),
        )
        check(eligibleUrlTestTags(items).size == 5) {
            "every node member must be counted exactly once"
        }
    }

    @Test
    fun `members that are themselves groups are not submitted`() {
        // The kernel's per-item pass skips group members, so they can never report a latency.
        // Counting one would make done >= total unreachable.
        val items = listOf(
            item("auto", "urltest"),
            item("🇭🇰 香港", "selector"),
            item("node-a", "vless"),
        )
        check(eligibleUrlTestTags(items) == listOf("node-a")) {
            "group members must not enter the denominator"
        }
    }

    @Test
    fun `group type match is case-insensitive across live snapshots`() {
        val items = listOf(item("g1", "Selector"), item("g2", "URLTest"), item("n", "vless"))
        check(eligibleUrlTestTags(items) == listOf("n"))
    }

    @Test
    fun `direct and block stay counted because the kernel does measure them`() {
        // Not a special case by accident: dropping endpoints would make N disagree with the set
        // actually submitted, and a real config can put them in a group.
        val items = listOf(item("direct", "direct"), item("block", "block"), item("n", "vless"))
        check(eligibleUrlTestTags(items) == listOf("direct", "block", "n")) {
            "endpoints are measurable, so they remain part of the submitted set"
        }
    }

    @Test
    fun `duplicate tags collapse to one submission`() {
        // A tag listed twice would otherwise ask for two results and accept one.
        val items = listOf(item("n", "vless"), item("n", "vless"), item("m", "anytls"))
        check(eligibleUrlTestTags(items) == listOf("n", "m")) {
            "a repeated tag must be submitted once"
        }
    }

    @Test
    fun `blank tags are dropped`() {
        val items = listOf(item("", "vless"), item("n", "vless"))
        check(eligibleUrlTestTags(items) == listOf("n"))
    }

    @Test
    fun `an empty or group-only group submits nothing rather than inventing a total`() {
        // The number that must never happen is a positive denominator with nothing able to
        // report against it; an empty run simply has no progress to show.
        check(eligibleUrlTestTags(emptyList()).isEmpty())
        check(eligibleUrlTestTags(listOf(item("auto", "urltest"))).isEmpty())
    }
}
