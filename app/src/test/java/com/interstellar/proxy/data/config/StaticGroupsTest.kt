package com.interstellar.proxy.data.config

import org.junit.Test

/**
 * The static half of the node page: what the active config file declares.
 *
 * A provider-fed group parses as a group with ZERO members here, and that is correct - the
 * provider's nodes are published by the core at runtime under `<provider>/<node>` tags. Pinning
 * it stops a future "fix" from trying to make the file contain runtime state, and documents the
 * gap the live merge in `displayGroups` exists to fill.
 */
class StaticGroupsTest {

    @Test
    fun `a provider-fed selector has no static members`() {
        val config = """
            {
              "providers": [
                {"type": "remote", "tag": "air", "url": "https://provider.example/sub"}
              ],
              "outbounds": [
                {"type": "selector", "tag": "proxy", "providers": ["air"], "default": "air/HK-01"}
              ]
            }
        """.trimIndent()

        val groups = parseStaticGroups(config)

        check(groups.size == 1)
        check(groups[0].tag == "proxy" && groups[0].type == "selector")
        check(groups[0].items.isEmpty()) {
            "the file names no provider node: the core publishes them at runtime"
        }
        check(groups[0].selected == null) {
            "a default naming a provider node is not a static member; the core's selection is the truth"
        }
    }

    @Test
    fun `use_all_providers parses the same way`() {
        val config = """
            {
              "providers": [{"type": "local", "tag": "pool", "path": "nodes.json"}],
              "outbounds": [{"type": "urltest", "tag": "auto", "use_all_providers": true}]
            }
        """.trimIndent()

        val groups = parseStaticGroups(config)

        check(groups.single().tag == "auto" && groups.single().type == "urltest")
        check(groups.single().items.isEmpty())
    }

    @Test
    fun `declared members keep their wire types`() {
        val config = """
            {
              "outbounds": [
                {"type": "direct", "tag": "direct"},
                {"type": "vless", "tag": "hk", "server": "127.0.0.1", "server_port": 443},
                {"type": "selector", "tag": "proxy", "outbounds": ["direct", "hk"], "default": "hk"}
              ]
            }
        """.trimIndent()

        val proxy = parseStaticGroups(config).single { it.tag == "proxy" }

        check(proxy.items.map { it.tag } == listOf("direct", "hk"))
        check(proxy.items.map { it.type } == listOf("direct", "vless")) {
            "a declared member's type is what its card renders"
        }
        check(proxy.selected == "hk")
    }

    @Test
    fun `loadbalance is a group too`() {
        val config = """
            {
              "outbounds": [
                {"type": "vless", "tag": "a", "server": "127.0.0.1", "server_port": 443},
                {"type": "loadbalance", "tag": "lb", "outbounds": ["a"]}
              ]
            }
        """.trimIndent()

        val lb = parseStaticGroups(config).single()

        check(lb.tag == "lb" && lb.type == "loadbalance")
        check(lb.items.single().tag == "a")
    }

    @Test
    fun `leaf outbounds do not become groups`() {
        val config = """
            {
              "outbounds": [
                {"type": "vless", "tag": "a", "server": "127.0.0.1", "server_port": 443},
                {"type": "direct", "tag": "direct"}
              ]
            }
        """.trimIndent()

        check(parseStaticGroups(config).isEmpty())
    }

    @Test
    fun `group order follows the file`() {
        val config = """
            {
              "outbounds": [
                {"type": "selector", "tag": "second", "outbounds": []},
                {"type": "urltest", "tag": "first", "outbounds": []}
              ]
            }
        """.trimIndent()

        check(parseStaticGroups(config).map { it.tag } == listOf("second", "first")) {
            "the page's tab order is the config's order"
        }
    }

    @Test
    fun `a config without outbounds yields no groups`() {
        check(parseStaticGroups("""{"providers": [{"type": "remote", "tag": "air", "url": "https://x"}]}""").isEmpty())
        check(parseStaticGroups("not json").isEmpty())
    }
}
