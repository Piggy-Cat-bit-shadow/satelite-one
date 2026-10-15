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
class MinimalConfigBuilderTest {

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

    private fun config(options: MinimalConfigBuilder.BuildOptions = MinimalConfigBuilder.BuildOptions()): JsonObject =
        Json.parseToJsonElement(MinimalConfigBuilder.build(nodes, options)).jsonObject

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
        check(selector["tag"]!!.jsonPrimitive.content == MinimalConfigBuilder.GROUP_TAG)
        val members = selector["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content }
        check(members.first() == MinimalConfigBuilder.AUTO_TAG) { "auto first" }
        check(members.containsAll(listOf("🇭🇰 香港 01", "🇺🇸 洛杉矶 01"))) { "every node reachable" }
        check(members.size == 3) { "auto + 2 nodes, no derived region groups: $members" }
    }

    @Test
    fun `route is sniff hijack-dns the two mode rules and LAN with the selector as final`() {
        val route = config()["route"]!!.jsonObject
        val rules = route["rules"]!!.jsonArray.map { it.jsonObject }
        check(rules.size == 5) { "exactly sniff + hijack-dns + Direct + Global + LAN: $rules" }
        check(rules[0]["action"]!!.jsonPrimitive.content == "sniff")
        check(rules[1]["protocol"]!!.jsonPrimitive.content == "dns")
        check(rules[1]["action"]!!.jsonPrimitive.content == "hijack-dns")
        check(rules[2]["clash_mode"]!!.jsonPrimitive.content == "Direct")
        check(rules[2]["outbound"]!!.jsonPrimitive.content == MinimalConfigBuilder.DIRECT_TAG)
        check(rules[3]["clash_mode"]!!.jsonPrimitive.content == "Global")
        check(rules[3]["outbound"]!!.jsonPrimitive.content == MinimalConfigBuilder.GROUP_TAG)
        check(rules[4]["ip_is_private"]!!.jsonPrimitive.content == "true")
        check(rules[4]["outbound"]!!.jsonPrimitive.content == MinimalConfigBuilder.DIRECT_TAG)
        check(route["final"]!!.jsonPrimitive.content == MinimalConfigBuilder.GROUP_TAG) { "everything else rides the selector" }
    }

    // ---- routing mode ----

    /**
     * The mode rules are what make Rule/Global/Direct mean anything.
     *
     * The kernel's mode is bare state that only changes traffic because a rule matches on
     * it, and a mode's rules are inert while another mode is current. Without these two
     * rules `SetClashMode` succeeds and changes nothing - the exact failure this feature
     * exists to avoid - so their absence must be a red build, not a silent regression.
     */
    @Test
    fun `both runtime routing modes are declared as clash_mode route rules`() {
        val rules = config()["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val modes = rules.mapNotNull { it["clash_mode"]?.jsonPrimitive?.content }
        check(modes == listOf("Direct", "Global")) {
            "the kernel derives its mode list from these: $modes"
        }
        // Rule is deliberately NOT declared: clashmode.NewManager always prepends the
        // default mode ("Rule"), so declaring it would only add a redundant rule.
        check("Rule" !in modes) { "Rule is the implicit default and needs no rule" }
    }

    /**
     * Order is load-bearing, so it is pinned rather than described.
     *
     * `route/route.go` continues the rule walk after a `sniff`/`resolve` action but STOPS at
     * `route`, `reject` or `hijack-dns`. Two consequences decide the layout:
     * the mode rules must come after `hijack-dns` (or DNS is hijacked by TUN before the
     * rules run and mode-independent DNS policy stops working), and before the LAN rule
     * (whose unconditional `ip_is_private` match would otherwise consume the connection and
     * Direct could never route anything).
     */
    @Test
    fun `the mode rules sit between the dns hijack and the lan bypass`() {
        val rules = config()["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val sniff = rules.indexOfFirst { it["action"]?.jsonPrimitive?.content == "sniff" }
        val hijack = rules.indexOfFirst { it["action"]?.jsonPrimitive?.content == "hijack-dns" }
        val direct = rules.indexOfFirst { it["clash_mode"]?.jsonPrimitive?.content == "Direct" }
        val global = rules.indexOfFirst { it["clash_mode"]?.jsonPrimitive?.content == "Global" }
        val lan = rules.indexOfFirst { it["ip_is_private"]?.jsonPrimitive?.content == "true" }
        check(sniff in 0..<hijack) { "sniff must run before everything: $sniff/$hijack" }
        check(hijack < global) { "a route action stops the walk, so Global must follow hijack-dns" }
        check(direct < global) { "the narrower override (Direct) must be decided before the broader one (Global)" }
        check(direct < lan) { "the LAN rule is unconditional and terminal: the mode rules must precede it" }
    }

    /**
     * The mode rules may only name outbounds the config already defines.
     *
     * A `clash_mode` rule with no outbound decodes as an implicit `route` action with an empty
     * tag; it passes config validation and then fails at match time with
     * "outbound not found". Every referenced tag is therefore checked against the real outbound
     * list, which is the check the kernel's own validator does NOT perform.
     */
    @Test
    fun `every outbound the mode rules reference exists`() {
        val json = config()
        val tags = objs(json, "outbounds").map { it["tag"]!!.jsonPrimitive.content }.toSet()
        val referenced = json["route"]!!.jsonObject["rules"]!!.jsonArray
            .map { it.jsonObject }
            .mapNotNull { it["outbound"]?.jsonPrimitive?.content }
        check(referenced.isNotEmpty()) { "the route still routes something" }
        referenced.forEach { tag ->
            check(tag in tags) { "route rule references '$tag', which is not an outbound: $tags" }
        }
    }

    /**
     * Direct mode must not resolve through the proxy.
     *
     * `dns-remote` detours to the selector and `dns.final` is `dns-remote`, so without this
     * rule every lookup would still leave through the proxy node after the user chose 直连 -
     * and dialling the DoH server through the selector is by itself enough to keep a node
     * connection alive. DNS rules support `clash_mode` in this kernel, so the detour is
     * overridden per query instead of rewriting the server (which would need a config reload
     * on every mode change).
     */
    @Test
    fun `direct mode sends dns out directly instead of through the selector`() {
        val dns = config()["dns"]!!.jsonObject
        val rules = dns["rules"]!!.jsonArray.map { it.jsonObject }
        check(rules.size == 2) { "bootstrap + direct-mode override: $rules" }
        // Bootstrap stays first: a DoH server named by a domain must still resolve locally.
        check(rules[0]["outbound"]!!.jsonPrimitive.content == "any")
        check(rules[0]["server"]!!.jsonPrimitive.content == "dns-local")
        check(rules[1]["clash_mode"]!!.jsonPrimitive.content == "Direct")
        check(rules[1]["server"]!!.jsonPrimitive.content == "dns-remote")
        val overrideOutbound = rules[1]["outbound"]!!.jsonArray.map { it.jsonPrimitive.content }
        check(overrideOutbound == listOf(MinimalConfigBuilder.DIRECT_TAG)) {
            "the direct override must use the direct outbound, not the selector: $overrideOutbound"
        }
        // The transport is reused, so Rule/Global DNS policy is untouched.
        val servers = dns["servers"]!!.jsonArray.map { it.jsonObject }
        check(servers[1]["detour"]!!.jsonPrimitive.content == MinimalConfigBuilder.GROUP_TAG) {
            "dns-remote itself still detours to the selector"
        }
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
        // The netstack must be sing-box's choice: writing "stack" here is what
        // used to force every Android libbox to ship gVisor.
        check("stack" !in tun) { "the app must not pin a tun netstack: $tun" }
        check("mtu" !in tun) { "the app must not pin a tun mtu: $tun" }
        val mixed = inbounds.first { it["type"]!!.jsonPrimitive.content == "mixed" }
        check(mixed["listen_port"]!!.jsonPrimitive.content == "2080")
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
        check(remote["detour"]!!.jsonPrimitive.content == MinimalConfigBuilder.GROUP_TAG) { "app DNS rides the selector" }

        val rules = dns["rules"]!!.jsonArray.map { it.jsonObject }
        check(rules.size == 2) { "the outbound-dial bootstrap rule plus the Direct-mode override: $rules" }
        check(rules[0]["outbound"]!!.jsonPrimitive.content == "any")
        check(rules[0]["server"]!!.jsonPrimitive.content == "dns-local")
        check(dns["final"]!!.jsonPrimitive.content == "dns-remote")
    }

    @Test
    fun `includeTun false drops the tun so url-test can run without the VPN`() {
        val types = objs(config(MinimalConfigBuilder.BuildOptions(includeTun = false)), "inbounds")
            .map { it["type"]!!.jsonPrimitive.content }
        check("tun" !in types) { "tun skipped for a headless url-test" }
        check("mixed" in types)
    }

    @Test
    fun `selected node tag becomes the selector default`() {
        val selector = objs(config(MinimalConfigBuilder.BuildOptions(selectedNodeTag = "🇺🇸 洛杉矶 01")), "outbounds")
            .first { it["type"]!!.jsonPrimitive.content == "selector" }
        check(selector["default"]!!.jsonPrimitive.content == "🇺🇸 洛杉矶 01")
        // unknown tag falls back to auto rather than producing an invalid default
        val fallback = objs(config(MinimalConfigBuilder.BuildOptions(selectedNodeTag = "nope")), "outbounds")
            .first { it["type"]!!.jsonPrimitive.content == "selector" }
        check(fallback["default"]!!.jsonPrimitive.content == MinimalConfigBuilder.AUTO_TAG)
    }

    @Test
    fun `generated config is well-formed json and round-trips`() {        // A cheap second pair of eyes on the shape: the assertions above index into parsed
        // objects, so a change that produced, say, two `route` keys or a trailing comma would
        // be reported by the parser as a confusing failure elsewhere rather than as itself.
        // Re-serialising must also succeed - the config is consumed as text by the core.
        val text = MinimalConfigBuilder.build(nodes, MinimalConfigBuilder.BuildOptions())
        val parsed = Json.parseToJsonElement(text).jsonObject
        check(parsed.keys.containsAll(listOf("log", "dns", "inbounds", "outbounds", "route", "experimental"))) {
            "generated config lost a top-level section: ${parsed.keys}"
        }
        val again = Json.parseToJsonElement(Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), parsed))
        check(again == Json.parseToJsonElement(text)) { "the generated config does not round-trip" }
    }

    // ---- no app-side policy ----

    @Test
    fun `no app-side network policy is injected`() {
        val text = MinimalConfigBuilder.build(nodes, MinimalConfigBuilder.BuildOptions())
        for (forbidden in listOf(
            "rule_set", "geosite", "geoip", "category-ads-all", "geolocation",
            "smart", "adblock", "dns-hosts", "block",
            "gvisor", "stack", "outbound_mode", "bypass_cn", "overseas",
            // the statistics switch below is explicitly empty; any of these would
            // turn it into a real control surface or inject mode/DNS policy
            "external_controller", "secret", "default_mode", "store_selected",
            "store_fakeip", "external_ui",
        )) {
            check(!text.contains(forbidden)) { "generated config must not contain '$forbidden'" }
        }
        // the only route rules are the five minimal ones: sniff, hijack-dns, the two routing
        // modes, and the LAN bypass. `clash_mode` is a ROUTING-mode override, not app-side
        // policy (it carries no geo/domain knowledge and adds no outbound), which is why the
        // forbidden-string list above still holds unchanged.
        val rules = config()["route"]!!.jsonObject["rules"]!!.jsonArray
        check(rules.size == 5)
        check("rule_set" !in config()["route"]!!.jsonObject) { "no rule-set declarations" }
    }

    @Test
    fun `experimental carries only the cache file - the command channel is libbox's own`() {
        val experimental = config()["experimental"]!!.jsonObject
        check(experimental.keys == setOf("cache_file")) { "unexpected experimental keys: ${experimental.keys}" }
        check(experimental["cache_file"]!!.jsonObject["enabled"]!!.jsonPrimitive.content == "true") {
            "cache_file must stay enabled"
        }
    }

    @Test
    fun `no control port is configured or implied`() {
        // Statistics do NOT need clash_api: the kernel creates its traffic manager
        // because libbox always passes a PlatformLogWriter
        // (daemon/instance.go: `PlatformLogWriter: s`). Configuring an empty
        // clash_api "to enable statistics" would therefore be inert, and adding an
        // external_controller would open a real listening control port — the one
        // thing this thin client must not grow. Lock both out.
        val text = MinimalConfigBuilder.build(nodes, MinimalConfigBuilder.BuildOptions())
        for (forbidden in listOf(
            "clash_api", "external_controller", "external_ui", "19090", "secret",
        )) {
            check(!text.contains(forbidden)) { "generated config must not contain '$forbidden'" }
        }
    }

    @Test
    fun `node names that collide with reserved tags are renamed`() {
        val clash = listOf(
            ProxyNode(id = "1", name = "auto", type = NodeType.VMESS, server = "a", port = 1, uuid = "u"),
            ProxyNode(id = "2", name = "direct", type = NodeType.VMESS, server = "b", port = 1, uuid = "u"),
        )
        val selector = objs(
            Json.parseToJsonElement(MinimalConfigBuilder.build(clash, MinimalConfigBuilder.BuildOptions())).jsonObject,
            "outbounds",
        ).first { it["type"]!!.jsonPrimitive.content == "selector" }
        val members = selector["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content }
        check(members.count { it == MinimalConfigBuilder.AUTO_TAG } == 1) { "one real auto only" }
        check(members.count { it == MinimalConfigBuilder.DIRECT_TAG } == 0) { "no node claims direct" }
    }
}
