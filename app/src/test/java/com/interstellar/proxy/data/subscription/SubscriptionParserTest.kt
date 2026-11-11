package com.interstellar.proxy.data.subscription

import com.interstellar.proxy.data.model.NodeType
import org.junit.Test

/** Node-list parsing limits and the protocol whitelist. */
class SubscriptionParserTest {

    @Test
    fun `ssh outbound is imported as a node`() {
        val json = """
            {"outbounds":[
              {"type":"ssh","tag":"shell","server":"ssh.example.com","server_port":22,"user":"root"}
            ]}
        """.trimIndent()
        val result = SubscriptionParser.parse(json, "sub")
        check(result is SubscriptionParser.Result.Nodes) { "expected nodes, got $result" }
        val nodes = (result as SubscriptionParser.Result.Nodes).nodes
        check(nodes.size == 1)
        check(nodes.single().type == NodeType.SSH)
        check(nodes.single().sshUser == "root")
    }

    @Test
    fun `hysteria one and bare shadowtls are rejected explicitly, not silently dropped`() {
        val json = """
            {"outbounds":[
              {"type":"hysteria","tag":"hy1","server":"h.example.com","server_port":443},
              {"type":"shadowtls","tag":"st","server":"st.example.com","server_port":443}
            ]}
        """.trimIndent()
        val result = SubscriptionParser.parse(json, "sub")
        check(result is SubscriptionParser.Result.Unsupported) { "unsupported outbounds must not disappear: $result" }
        check((result as SubscriptionParser.Result.Unsupported).reason == "unsupported_protocol:hysteria")
    }

    @Test
    fun `node count above the cap is a hard failure, not a truncated list`() {
        val builder = StringBuilder("""{"outbounds":[""")
        repeat(10001) { index ->
            if (index > 0) builder.append(',')
            builder.append(
                """{"type":"vmess","tag":"n$index","server":"s.example.com","server_port":443,"uuid":"u"}""",
            )
        }
        builder.append("]}")
        val result = SubscriptionParser.parse(builder.toString(), "sub")
        check(result is SubscriptionParser.Result.TooManyNodes) { "expected TooManyNodes, got $result" }
        check((result as SubscriptionParser.Result.TooManyNodes).limit == 10000)
    }

    @Test
    fun `a config without a tun inbound is rejected instead of split into nodes`() {
        val json = """
            {"inbounds":[{"type":"mixed","tag":"mixed-in","listen_port":2080}],
             "outbounds":[{"type":"vmess","tag":"hk","server":"s.example.com","server_port":443,"uuid":"u"}]}
        """.trimIndent()
        val result = SubscriptionParser.parse(json, "sub")
        check(result is SubscriptionParser.Result.Unsupported) { "expected Unsupported, got $result" }
        check((result as SubscriptionParser.Result.Unsupported).reason == "no_tun")
    }

    @Test
    fun `an xray document is rejected explicitly`() {
        val json = """
            {"inbounds":[{"protocol":"socks","port":1080}],
             "outbounds":[{"tag":"proxy","protocol":"vless","settings":{}}]}
        """.trimIndent()
        val result = SubscriptionParser.parse(json, "sub")
        check(result is SubscriptionParser.Result.Unsupported) { "expected Unsupported, got $result" }
        check((result as SubscriptionParser.Result.Unsupported).reason == "xray")
    }

    @Test
    fun `base64-wrapped xray is rejected exactly like plain xray`() {
        val json = """
            {"inbounds":[{"protocol":"socks","port":1080}],
             "outbounds":[{"tag":"proxy","protocol":"vless","settings":{}}]}
        """.trimIndent()
        val b64 = java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        val result = SubscriptionParser.parse(b64, "sub")
        check(result is SubscriptionParser.Result.Unsupported) { "expected Unsupported, got $result" }
        check((result as SubscriptionParser.Result.Unsupported).reason == "xray")
    }

    @Test
    fun `base64-wrapped config without tun is rejected exactly like plain json`() {
        val json = """
            {"inbounds":[{"type":"mixed","tag":"mixed-in","listen_port":2080}],
             "outbounds":[{"type":"vmess","tag":"hk","server":"s.example.com","server_port":443,"uuid":"u"}]}
        """.trimIndent()
        val b64 = java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        val result = SubscriptionParser.parse(b64, "sub")
        check(result is SubscriptionParser.Result.Unsupported) { "expected Unsupported, got $result" }
        check((result as SubscriptionParser.Result.Unsupported).reason == "no_tun")
    }

    @Test
    fun `base64-wrapped unsupported protocol is rejected exactly like plain json`() {
        val json = """
            {"outbounds":[{"type":"hysteria","tag":"hy1","server":"h.example.com","server_port":443}]}
        """.trimIndent()
        val b64 = java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        val result = SubscriptionParser.parse(b64, "sub")
        check(result is SubscriptionParser.Result.Unsupported) { "expected Unsupported, got $result" }
        check((result as SubscriptionParser.Result.Unsupported).reason == "unsupported_protocol:hysteria")
    }
}
