package com.interstellar.proxy.data.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * RawConfigApplier.applySingbox keeps the original structure and only patches
 * what the in-app service model needs — these tests lock that contract down.
 */
class RawConfigApplierTest {

    private val options = RawConfigApplier.Options(
        mode = ConfigBuilder.OutboundMode.RULE,
        bypassLan = true,
        bypassCn = true,
        overseasProxy = true,
        fallbackDirect = true,
        adBlock = true,
        apiSecret = "sec",
    )

    @Test
    fun `singbox - clash api, tun, mixed and rule injection`() {
        val raw = """
            {
              "outbounds": [
                {"type": "selector", "tag": "选择", "outbounds": ["hk"]},
                {"type": "vmess", "tag": "hk", "server": "hk.example.com"}
              ],
              "route": {"rules": [{"domain": ["a.com"], "outbound": "hk"}]}
            }
        """.trimIndent()
        val out = RawConfigApplier.applySingbox(raw, options)
        val json = Json.parseToJsonElement(out).jsonObject

        val clashApi = json["experimental"]!!.jsonObject["clash_api"]!!.jsonObject
        check(clashApi["external_controller"]!!.jsonPrimitive.content == "127.0.0.1:19090")
        check(clashApi["secret"]!!.jsonPrimitive.content == "sec")
        check(clashApi["default_mode"]!!.jsonPrimitive.content == "rule")

        val inbounds = json["inbounds"]!!.jsonArray
        check(inbounds.any { it.jsonObject["type"]!!.jsonPrimitive.content == "tun" })
        check(inbounds.any {
            it.jsonObject["type"]!!.jsonPrimitive.content == "mixed" &&
                it.jsonObject["listen_port"]!!.jsonPrimitive.content == "2080"
        })

        val outbounds = json["outbounds"]!!.jsonArray.map { it.jsonObject }
        check(outbounds.any { it["type"]!!.jsonPrimitive.content == "direct" }) { "direct outbound appended" }
        check(outbounds.any { it["type"]!!.jsonPrimitive.content == "block" }) { "block outbound appended" }

        val rules = json["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val cnRule = rules.first { r -> r["rule_set"]?.jsonArray?.toString()?.contains("geosite-cn") == true }
        check(cnRule["outbound"]!!.jsonPrimitive.content == "__interstellar_direct")
        val overseas = rules.first { r -> r["rule_set"]?.jsonArray?.toString()?.contains("geolocation-!cn") == true }
        check(overseas["outbound"]!!.jsonPrimitive.content == "选择") { "overseas rides first selector" }
        // injected rules precede the original one
        check(rules.indexOf(cnRule) < rules.indexOfFirst { it["domain"] != null })
        // fallback flips route.final to the direct outbound
        check(json["route"]!!.jsonObject["final"]!!.jsonPrimitive.content == "__interstellar_direct")

        val sets = json["route"]!!.jsonObject["rule_set"]!!.jsonArray.map { it.jsonObject }
        check(sets.any { it["tag"]!!.jsonPrimitive.content == "geosite-geolocation-!cn" })
    }

    @Test
    fun `singbox - existing tun keeps its identity but gains auto_route`() {
        val raw = """
            {
              "inbounds": [{"type": "tun", "tag": "my-tun", "address": ["10.0.0.1/30"], "auto_route": false}],
              "outbounds": [{"type": "direct", "tag": "direct"}]
            }
        """.trimIndent()
        val out = RawConfigApplier.applySingbox(raw, options)
        val tun = Json.parseToJsonElement(out).jsonObject["inbounds"]!!.jsonArray
            .map { it.jsonObject }.first { it["type"]!!.jsonPrimitive.content == "tun" }
        check(tun["tag"]!!.jsonPrimitive.content == "my-tun") { "original tun kept" }
        check(tun["auto_route"]!!.jsonPrimitive.content == "true") { "auto_route forced" }
    }
}
