package com.interstellar.proxy.data.config

import com.interstellar.proxy.data.RulesStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Feeds a retained raw subscription config to sing-box with minimal, surgical
 * edits — the original structure (outbounds, groups, DNS, rules) is preserved;
 * we only fix what the in-app service model depends on and prepend the built-in
 * rule sets the user enabled. Deliberately NOT a rewrite: upstream profiles
 * carry provider quirks and hand-tuned rules.
 *
 * Only sing-box bodies are handled (see [applySingbox]); Clash and Xray bodies
 * were pass-through only for the sidecar engines this client no longer ships.
 */
object RawConfigApplier {

    data class Options(
        val mode: ConfigBuilder.OutboundMode = ConfigBuilder.OutboundMode.RULE,
        val bypassLan: Boolean = true,
        val bypassCn: Boolean = true,
        val overseasProxy: Boolean = false,
        val fallbackDirect: Boolean = false,
        val adBlock: Boolean = true,
        val mixedPort: Int = 2080,
        // loopback port for sing-box's clash_api (mode switching goes through it)
        val apiPort: Int = 19090,
        val apiSecret: String = "",
    )

    private val json = Json { prettyPrint = false }

    private fun modeOf(mode: ConfigBuilder.OutboundMode) = when (mode) {
        ConfigBuilder.OutboundMode.GLOBAL -> "global"
        ConfigBuilder.OutboundMode.DIRECT -> "direct"
        else -> "rule"
    }

    fun applySingbox(raw: String, options: Options): String {
        val root = Json.parseToJsonElement(raw).jsonObject.toMutableMap()

        // outbounds: locate (or append) the direct / block targets and the
        // first selector/urltest group as the overseas proxy target
        val outbounds = (root["outbounds"] as? JsonArray ?: JsonArray(emptyList())).toMutableList()
        var directTag = outbounds.firstOrNull {
            (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == "direct"
        }?.let { (it as JsonObject)["tag"]?.jsonPrimitive?.content }
        if (directTag == null) {
            directTag = "__interstellar_direct"
            outbounds.add(buildJsonObject { put("type", "direct"); put("tag", directTag) })
        }
        var blockTag = outbounds.firstOrNull {
            (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == "block"
        }?.let { (it as JsonObject)["tag"]?.jsonPrimitive?.content }
        if (blockTag == null) {
            blockTag = "__interstellar_block"
            outbounds.add(buildJsonObject { put("type", "block"); put("tag", blockTag) })
        }
        val proxyTag = outbounds.firstOrNull {
            val type = (it as? JsonObject)?.get("type")?.jsonPrimitive?.content
            type == "selector" || type == "urltest"
        }?.let { (it as JsonObject)["tag"]?.jsonPrimitive?.content }
        root["outbounds"] = JsonArray(outbounds)

        // inbounds: keep the original tun (forcing auto_route — the app's VPN
        // model depends on it), add one when missing, same for the mixed port
        val inbounds = (root["inbounds"] as? JsonArray ?: JsonArray(emptyList())).toMutableList()
        val tunIdx = inbounds.indexOfFirst { (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == "tun" }
        if (tunIdx >= 0) {
            val tun = (inbounds[tunIdx] as JsonObject).toMutableMap()
            tun["auto_route"] = kotlinx.serialization.json.JsonPrimitive(true)
            inbounds[tunIdx] = JsonObject(tun)
        } else {
            inbounds.add(
                buildJsonObject {
                    put("type", "tun")
                    put("tag", "tun-in")
                    putJsonArray("address") { add("172.19.0.1/30") }
                    put("mtu", 9000)
                    put("auto_route", true)
                    put("stack", "mixed")
                    if (options.bypassLan) {
                        putJsonArray("route_exclude_address") {
                            add("10.0.0.0/8"); add("172.16.0.0/12"); add("192.168.0.0/16")
                        }
                    }
                },
            )
        }
        val hasMixed = inbounds.any {
            val obj = it as? JsonObject ?: return@any false
            obj["type"]?.jsonPrimitive?.content == "mixed" &&
                obj["listen_port"]?.jsonPrimitive?.content?.toIntOrNull() == options.mixedPort
        }
        if (!hasMixed) {
            inbounds.add(
                buildJsonObject {
                    put("type", "mixed")
                    put("tag", "mixed-in")
                    put("listen", "127.0.0.1")
                    put("listen_port", options.mixedPort)
                },
            )
        }
        root["inbounds"] = JsonArray(inbounds)

        // clash_api: the command socket + mode switching depend on it
        val experimental = (root["experimental"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        experimental["clash_api"] = buildJsonObject {
            put("external_controller", "127.0.0.1:${options.apiPort}")
            if (options.apiSecret.isNotBlank()) put("secret", options.apiSecret)
            put("default_mode", modeOf(options.mode))
        }
        root["experimental"] = JsonObject(experimental)

        // route: prepend the enabled built-in rules + their rule-set declarations
        val route = (root["route"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val rules = (route["rules"] as? JsonArray ?: JsonArray(emptyList())).toMutableList()
        val usedSets = linkedMapOf<String, RulesStore.RuleAsset>()
        val prepend = buildList<JsonElement> {
            if (options.bypassLan) {
                add(buildJsonObject { put("ip_is_private", true); put("outbound", directTag) })
            }
            if (options.adBlock) {
                usedSets["category-ads-all"] = RulesStore.adsAll
                add(buildJsonObject { putJsonArray("rule_set") { add("category-ads-all") }; put("outbound", blockTag) })
            }
            if (options.overseasProxy && proxyTag != null) {
                usedSets["geosite-geolocation-!cn"] = RulesStore.geolocationNotCn
                add(
                    buildJsonObject {
                        putJsonArray("rule_set") { add("geosite-geolocation-!cn") }
                        put("outbound", proxyTag)
                    },
                )
            }
            if (options.bypassCn) {
                usedSets["geosite-cn"] = RulesStore.geositeCn
                usedSets["geoip-cn"] = RulesStore.geoipCn
                add(
                    buildJsonObject {
                        putJsonArray("rule_set") { add("geosite-cn"); add("geoip-cn") }
                        put("outbound", directTag)
                    },
                )
            }
        }
        rules.addAll(0, prepend)
        route["rules"] = JsonArray(rules)
        val declared = (route["rule_set"] as? JsonArray ?: JsonArray(emptyList()))
            .mapNotNull { (it as? JsonObject)?.get("tag")?.jsonPrimitive?.content }
            .toSet()
        val sets = (route["rule_set"] as? JsonArray ?: JsonArray(emptyList())).toMutableList()
        for ((tag, asset) in usedSets) {
            if (tag !in declared) sets.add(ConfigBuilder.ruleSetJson(tag, asset))
        }
        route["rule_set"] = JsonArray(sets)
        if (options.fallbackDirect) route["final"] = kotlinx.serialization.json.JsonPrimitive(directTag)
        root["route"] = JsonObject(route)

        return json.encodeToString(JsonObject.serializer(), JsonObject(root))
    }

}
