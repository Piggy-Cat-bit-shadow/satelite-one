package com.interstellar.proxy.data.subscription

import com.interstellar.proxy.data.config.MinimalConfigBuilder
import com.interstellar.proxy.data.model.NodeType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * sing-box outbound → node model. These are the two silent-drop defects the
 * audit found: uTLS was read as a String and then cast to JsonObject (always
 * null), and SSH was mapped to NodeType.SSH but had no conversion branch.
 */
class SingboxOutboundConverterTest {

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `utls fingerprint is read from the object and written back`() {
        val node = SingboxOutboundConverter.toNode(
            obj(
                """
                {"type":"vmess","tag":"hk","server":"a.example.com","server_port":443,
                 "uuid":"u","tls":{"enabled":true,"server_name":"a.example.com",
                 "utls":{"enabled":true,"fingerprint":"chrome"}}}
                """.trimIndent(),
            ),
            null,
        )
        check(node != null) { "vmess node must convert" }
        check(node!!.fingerprint == "chrome") { "fingerprint was dropped: ${node.fingerprint}" }
        val outbound = MinimalConfigBuilder.nodeToOutbound(node, "hk")
        val fingerprint = outbound["tls"]!!.jsonObject["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content
        check(fingerprint == "chrome") { "fingerprint did not round-trip: $fingerprint" }
    }

    @Test
    fun `ssh outbound becomes an SSH node instead of being dropped`() {
        val node = SingboxOutboundConverter.toNode(
            obj(
                """
                {"type":"ssh","tag":"shell","server":"ssh.example.com","server_port":22,
                 "user":"root","private_key":"test-private-key-material"}
                """.trimIndent(),
            ),
            null,
        )
        check(node != null) { "ssh node must convert" }
        check(node!!.type == NodeType.SSH)
        check(node.sshUser == "root")
        check(node.sshKey == "test-private-key-material")
    }

    @Test
    fun `hysteria one is not silently reported as a supported node`() {
        val node = SingboxOutboundConverter.toNode(
            obj("""{"type":"hysteria","tag":"hy1","server":"h.example.com","server_port":443}"""),
            null,
        )
        check(node == null) { "hysteria 1 has no node model and must not be converted" }
    }

    @Test
    fun `tls enabled false stays disabled in the node and the generated config`() {
        val node = SingboxOutboundConverter.toNode(
            obj(
                """
                {"type":"vmess","tag":"plain","server":"a.example.com","server_port":80,
                 "uuid":"u","tls":{"enabled":false,"server_name":"a.example.com"}}
                """.trimIndent(),
            ),
            null,
        )
        check(node != null) { "vmess node must convert" }
        check(!node!!.tls) { "tls.enabled=false must not become enabled" }
        val outbound = MinimalConfigBuilder.nodeToOutbound(node, "plain")
        check("tls" !in outbound) { "a disabled TLS node must not generate a tls block: $outbound" }
    }

    @Test
    fun `trojan still defaults to tls when no tls object exists`() {
        val node = SingboxOutboundConverter.toNode(
            obj("""{"type":"trojan","tag":"t","server":"t.example.com","server_port":443,"password":"p"}"""),
            null,
        )
        check(node != null)
        check(node!!.tls) { "trojan requires TLS even when the block is omitted" }
    }
}
