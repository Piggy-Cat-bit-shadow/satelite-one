package com.interstellar.proxy.data.config

import com.interstellar.proxy.data.model.NodeType
import com.interstellar.proxy.data.model.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Locks the thin-client contract of the generated config.
 *
 * Two jobs:
 *  1. assert the minimal shape sing-box needs to run a node subscription
 *  2. assert that app-side network POLICY stays gone — no geo rule sets, no
 *     ad blocking, no CN/overseas split, no region groups, no DNS hosts, no
 *     clash_api, no smart group. A future change that quietly re-adds client
 *     policy to the generator must fail here.
 */
class ConfigBuilderTest {

    private val nodes = listOf(
        ProxyNode(
            id = "1", name = "🇭🇰 香港 01", type = NodeType.VMESS,
            server = "hk1.example.com", port = 443, uuid = "uuid-1",
            tls = true, sni = "hk1.example.com",
        ),
        ProxyNode(
            id = "2", name = "🇺🇸 洛杉矶 01", type = NodeType.VLESS,
            server = "us1.example.com", port = 443, uuid = "uuid-2",
            tls = true, sni = "us1.example.com",
        ),
    )

    private fun config(options: ConfigBuilder.BuildOptions = ConfigBuilder.BuildOptions()): JsonObject =
        Json.parseToJsonElement(ConfigBuilder.build(nodes, options)).jsonObject

    private fun objs(json: JsonObject, key: String): List<JsonObject> =
        json[key]!!.jsonArray.map { it.jsonObject }

    // ---- minimal shape ----

    @Test
    fun `outbounds are selector urltest nodes and direct - nothing else`() {
        val outbounds = objs(config(), "outbounds")
        val types = outbounds.map { it["type"]!!.jsonPrimitive.content }
        check(types.count { it == "selector" } == 1) { "exactly one selector" }
        check(types.count { it == "urltest" } == 1) { "exactly one urltest (auto)" }
        check(types.none { it == "block" }) { "no block outbound: ad blocking is gone" }

        val selector = outbounds.first { it["type"]!!.jsonPrimitive.content == "selector" }
        check(selector["tag"]!!.jsonPrimitive.content == ConfigBuilder.GROUP_TAG)
        val members = selector["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content }
        check(members.first() == ConfigBuilder.AUTO_TAG) { "auto first" }
        check(members.containsAll(listOf("🇭🇰 香港 01", "🇺🇸 洛杉矶 01"))) { "every node reachable" }
        check(members.size == 3) { "auto + 2 nodes, no derived region groups: $members" }
    }

    @Test
    fun `route is sniff hijack-dns and LAN only with the selector as final`() {
        val route = config()["route"]!!.jsonObject
        val rules = route["rules"]!!.jsonArray.map { it.jsonObject }
        check(rules.size == 3) { "exactly sniff + hijack-dns + LAN: $rules" }
        check(rules[0]["action"]!!.jsonPrimitive.content == "sniff")
        check(rules[1]["protocol"]!!.jsonPrimitive.content == "dns")
        check(rules[1]["action"]!!.jsonPrimitive.content == "hijack-dns")
        check(rules[2]["ip_is_private"]!!.jsonPrimitive.content == "true")
        check(rules[2]["outbound"]!!.jsonPrimitive.content == ConfigBuilder.DIRECT_TAG)
        check(route["final"]!!.jsonPrimitive.content == ConfigBuilder.GROUP_TAG) { "everything else rides the selector" }
    }

    @Test
    fun `dns is the system resolver for outbound dials plus one remote DoH`() {
        val dns = config()["dns"]!!.jsonObject
        val servers = dns["servers"]!!.jsonArray.map { it.jsonObject }
        check(servers.size == 2) { "no CN/hosts/extra servers: $servers" }
        check(servers[0]["tag"]!!.jsonPrimitive.content == "dns-local")
        check(servers[0]["type"]!!.jsonPrimitive.content == "local")
        val remote = servers[1]
        check(remote["tag"]!!.jsonPrimitive.content == "dns-remote")
        check(remote["type"]!!.jsonPrimitive.content == "https")
        check(remote["detour"]!!.jsonPrimitive.content == ConfigBuilder.GROUP_TAG) { "app DNS rides the selector" }

        val rules = dns["rules"]!!.jsonArray.map { it.jsonObject }
        check(rules.size == 1) { "only the outbound-dial bootstrap rule: $rules" }
        check(rules[0]["outbound"]!!.jsonPrimitive.content == "any")
        check(rules[0]["server"]!!.jsonPrimitive.content == "dns-local")
        check(dns["final"]!!.jsonPrimitive.content == "dns-remote")
    }

    @Test
    fun `tun keeps the LAN out of the tunnel and the mixed port serves subscription refresh`() {
        val json = config()
        val inbounds = objs(json, "inbounds")
        val tun = inbounds.first { it["type"]!!.jsonPrimitive.content == "tun" }
        val excluded = tun["route_exclude_address"]!!.jsonArray.map { it.jsonPrimitive.content }
        check(excluded == listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")) { "LAN bypass: $excluded" }
        check(tun["auto_route"]!!.jsonPrimitive.content == "true")
        val mixed = inbounds.first { it["type"]!!.jsonPrimitive.content == "mixed" }
        check(mixed["listen_port"]!!.jsonPrimitive.content == "2080")
    }

    @Test
    fun `includeTun false drops the tun so url-test can run without the VPN`() {
        val types = objs(config(ConfigBuilder.BuildOptions(includeTun = false)), "inbounds")
            .map { it["type"]!!.jsonPrimitive.content }
        check("tun" !in types) { "tun skipped for a headless url-test" }
        check("mixed" in types)
    }

    @Test
    fun `selected node tag becomes the selector default`() {
        val selector = objs(config(ConfigBuilder.BuildOptions(selectedNodeTag = "🇺🇸 洛杉矶 01")), "outbounds")
            .first { it["type"]!!.jsonPrimitive.content == "selector" }
        check(selector["default"]!!.jsonPrimitive.content == "🇺🇸 洛杉矶 01")
        // unknown tag falls back to auto rather than producing an invalid default
        val fallback = objs(config(ConfigBuilder.BuildOptions(selectedNodeTag = "nope")), "outbounds")
            .first { it["type"]!!.jsonPrimitive.content == "selector" }
        check(fallback["default"]!!.jsonPrimitive.content == ConfigBuilder.AUTO_TAG)
    }

    // ---- no app-side policy ----

    @Test
    fun `no app-side network policy is injected`() {
        val text = ConfigBuilder.build(nodes, ConfigBuilder.BuildOptions())
        for (forbidden in listOf(
            "rule_set", "geosite", "geoip", "category-ads-all", "geolocation",
            "clash_api", "smart", "adblock", "dns-hosts", "block",
        )) {
            check(!text.contains(forbidden)) { "generated config must not contain '$forbidden'" }
        }
        // the only route rules are the three minimal ones
        val rules = config()["route"]!!.jsonObject["rules"]!!.jsonArray
        check(rules.size == 3)
        check("rule_set" !in config()["route"]!!.jsonObject) { "no rule-set declarations" }
    }

    @Test
    fun `experimental carries only the cache file - the command channel is libbox's own`() {
        val experimental = config()["experimental"]!!.jsonObject
        check(experimental.keys == setOf("cache_file")) { "unexpected experimental keys: ${experimental.keys}" }
    }

    @Test
    fun `node names that collide with reserved tags are renamed`() {
        val clash = listOf(
            ProxyNode(id = "1", name = "auto", type = NodeType.VMESS, server = "a", port = 1, uuid = "u"),
            ProxyNode(id = "2", name = "direct", type = NodeType.VMESS, server = "b", port = 1, uuid = "u"),
        )
        val selector = objs(
            Json.parseToJsonElement(ConfigBuilder.build(clash, ConfigBuilder.BuildOptions())).jsonObject,
            "outbounds",
        ).first { it["type"]!!.jsonPrimitive.content == "selector" }
        val members = selector["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content }
        check(members.count { it == ConfigBuilder.AUTO_TAG } == 1) { "one real auto only" }
        check(members.count { it == ConfigBuilder.DIRECT_TAG } == 0) { "no node claims direct" }
    }
}
