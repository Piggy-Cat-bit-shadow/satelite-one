package com.interstellar.proxy.ui

import com.interstellar.proxy.core.CoreGroup
import com.interstellar.proxy.core.CoreGroupItem
import com.interstellar.proxy.data.config.ConfigBuilder
import org.junit.Test

/**
 * A manual url-test must target the group the user is looking at.
 *
 * Regression: `urlTest(groupTag)` accepted a tag but always sent `auto` to the
 * kernel, so tapping 测速 on a 香港/美国/新加坡 tab silently tested a different
 * group. The main entry is a selector (mixed group+node members, which the
 * kernel's per-item pass skips), so it still falls back to `auto`.
 */
class UrlTestTargetTest {

    private fun group(tag: String, type: String) = CoreGroup(
        tag = tag,
        type = type,
        selected = null,
        items = listOf(CoreGroupItem(tag = "$tag-node", type = "vless")),
    )

    private val groups = listOf(
        group(ConfigBuilder.GROUP_TAG, "selector"),
        group(ConfigBuilder.AUTO_TAG, "urltest"),
        group("🇭🇰 香港", "urltest"),
        group("🇺🇸 美国", "urltest"),
    )

    @Test
    fun `auto tab tests auto`() {
        check(resolveUrlTestTarget(ConfigBuilder.AUTO_TAG, groups) == ConfigBuilder.AUTO_TAG)
    }

    @Test
    fun `region tab tests that region group, not auto`() {
        check(resolveUrlTestTarget("🇭🇰 香港", groups) == "🇭🇰 香港")
        check(resolveUrlTestTarget("🇺🇸 美国", groups) == "🇺🇸 美国")
    }

    @Test
    fun `selector falls back to auto because the kernel skips its mixed members`() {
        check(resolveUrlTestTarget(ConfigBuilder.GROUP_TAG, groups) == ConfigBuilder.AUTO_TAG)
    }

    @Test
    fun `blank and unknown tags fall back to auto`() {
        check(resolveUrlTestTarget("", groups) == ConfigBuilder.AUTO_TAG)
        check(resolveUrlTestTarget("nope", groups) == ConfigBuilder.AUTO_TAG)
    }

    @Test
    fun `group type match is case-insensitive so live snapshots still resolve`() {
        check(resolveUrlTestTarget("🇭🇰 香港", listOf(group("🇭🇰 香港", "URLTest"))) == "🇭🇰 香港")
    }
}
