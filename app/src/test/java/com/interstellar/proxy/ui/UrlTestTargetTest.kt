package com.interstellar.proxy.ui

import com.interstellar.proxy.core.CoreGroup
import com.interstellar.proxy.core.CoreGroupItem
import com.interstellar.proxy.data.config.MinimalConfigBuilder
import org.junit.Test

/**
 * A manual url-test must target the group the user is looking at.
 *
 * Regression: `urlTest(groupTag)` accepted a tag but always sent `auto` to the
 * kernel, so tapping 测速 on an airport/raw group tab silently tested a different
 * group. The generated config's single selector mixes group+node members, which
 * the kernel's per-item pass skips, so a selector keeps falling back to `auto`.
 */
class UrlTestTargetTest {

    private fun group(tag: String, type: String) = CoreGroup(
        tag = tag,
        type = type,
        selected = null,
        items = listOf(CoreGroupItem(tag = "$tag-node", type = "vless")),
    )

    private val groups = listOf(
        group(MinimalConfigBuilder.GROUP_TAG, "selector"),
        group(MinimalConfigBuilder.AUTO_TAG, "urltest"),
        group("🇭🇰 香港", "urltest"),
        group("🇺🇸 美国", "urltest"),
    )

    @Test
    fun `auto tab tests auto`() {
        check(resolveUrlTestTarget(MinimalConfigBuilder.AUTO_TAG, groups) == MinimalConfigBuilder.AUTO_TAG)
    }

    @Test
    fun `any real urltest group is tested by its own tag, not auto`() {
        // e.g. groups a raw config or an airport profile defines itself
        check(resolveUrlTestTarget("🇭🇰 香港", groups) == "🇭🇰 香港")
        check(resolveUrlTestTarget("🇺🇸 美国", groups) == "🇺🇸 美国")
    }

    @Test
    fun `selector falls back to auto because the kernel skips its mixed members`() {
        check(resolveUrlTestTarget(MinimalConfigBuilder.GROUP_TAG, groups) == MinimalConfigBuilder.AUTO_TAG)
    }

    @Test
    fun `blank and unknown tags fall back to auto`() {
        check(resolveUrlTestTarget("", groups) == MinimalConfigBuilder.AUTO_TAG)
        check(resolveUrlTestTarget("nope", groups) == MinimalConfigBuilder.AUTO_TAG)
    }

    @Test
    fun `group type match is case-insensitive so live snapshots still resolve`() {
        check(resolveUrlTestTarget("🇭🇰 香港", listOf(group("🇭🇰 香港", "URLTest"))) == "🇭🇰 香港")
    }

    // ---- a config with no `auto` at all (any user-supplied raw config) ----

    /**
     * The app's `auto` is its own generated group's name. When a raw config is running, the
     * old rule still returned `auto`, and the kernel answered
     * `NotFound: outbound not found: auto` - so 测速 on a fully populated group tested
     * nothing, and the UI showed only "could not send command".
     */
    private val rawGroups = listOf(
        CoreGroup(
            tag = "🌍 国外流量",
            type = "selector",
            selected = "🇺🇸 美国｜NaiveProxy",
            items = listOf(
                CoreGroupItem("🇺🇸 美国｜Vless Reality Vision", "vless"),
                CoreGroupItem("🇺🇸 美国｜NaiveProxy", "naive"),
            ),
        ),
        CoreGroup("🤖 AI", "selector", "🇺🇸 美国｜AnyTLS", listOf(CoreGroupItem("🇺🇸 美国｜AnyTLS", "anytls"))),
    )

    @Test
    fun `a selector is tested under its own tag when the config has no auto`() {
        check(resolveUrlTestTarget("🌍 国外流量", rawGroups) == "🌍 国外流量") {
            "a raw config's selector must be tested itself, not a tag nothing defines"
        }
        check(resolveUrlTestTarget("🤖 AI", rawGroups) == "🤖 AI")
    }

    @Test
    fun `nothing resolves to auto unless the config actually defines it`() {
        val resolved = listOf(
            resolveUrlTestTarget("", rawGroups),
            resolveUrlTestTarget("nope", rawGroups),
            resolveUrlTestTarget("🌍 国外流量", rawGroups),
        )
        check(resolved.none { it == MinimalConfigBuilder.AUTO_TAG }) {
            "auto was returned for a config that does not define it: $resolved"
        }
    }

    @Test
    fun `a blank tag falls back to a group the config really has`() {
        check(resolveUrlTestTarget("", rawGroups) == "🌍 国外流量") {
            "the fallback must name a live group"
        }
    }

    @Test
    fun `no groups at all still yields the historical default`() {
        // The pre-connect case: nothing is known yet, so the caller's own name is kept and
        // StartProxy's own gating decides whether a test is sent at all.
        check(resolveUrlTestTarget("whatever", emptyList()) == MinimalConfigBuilder.AUTO_TAG)
    }
}
