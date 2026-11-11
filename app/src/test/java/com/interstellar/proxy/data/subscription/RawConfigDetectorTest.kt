package com.interstellar.proxy.data.subscription

import org.junit.Test

/**
 * Raw-config format sniffing: the retained body decides whether a
 * subscription can be fed to a core verbatim.
 *
 * A body with `outbounds` alone is a node list, not a runnable profile. The
 * detector must only report SINGBOX when the document also brings its own TUN
 * inbound, because the app hands a raw body to the core unchanged (no generated
 * TUN is added). This is the exact confusion the old test encoded.
 */
class RawConfigDetectorTest {

    @Test
    fun `clash yaml is detected`() {
        val yaml = """
            port: 7890
            proxies:
              - name: "hk"
                type: ss
                server: hk.example.com
                port: 443
                cipher: aes-256-gcm
                password: pw
            proxy-groups:
              - name: "PROXY"
                type: select
                proxies: ["hk"]
            rules:
              - MATCH,PROXY
        """.trimIndent()
        check(RawConfigDetector.detect(yaml) == RawConfigFormat.CLASH)
    }

    @Test
    fun `selector and proxy outbounds without an inbound are a node list`() {
        val json = """
            {
              "outbounds": [
                {"type": "selector", "tag": "proxy", "outbounds": ["auto"]},
                {"type": "vmess", "tag": "hk", "server": "hk.example.com", "server_port": 443, "uuid": "u"}
              ]
            }
        """.trimIndent()
        check(RawConfigDetector.detect(json) == null) { "node JSON must not be a raw config" }
    }

    @Test
    fun `complete singbox config with tun inbound is detected`() {
        val json = """
            {
              "inbounds": [{"type": "tun", "tag": "tun-in", "address": ["172.19.0.1/30"]}],
              "outbounds": [{"type": "direct", "tag": "direct"}]
            }
        """.trimIndent()
        check(RawConfigDetector.detect(json) == RawConfigFormat.SINGBOX)
    }

    @Test
    fun `config with inbounds but no tun is not a runnable profile for this app`() {
        val json = """
            {
              "inbounds": [{"type": "mixed", "tag": "mixed-in", "listen": "127.0.0.1", "listen_port": 2080}],
              "outbounds": [{"type": "direct", "tag": "direct"}]
            }
        """.trimIndent()
        check(RawConfigDetector.detect(json) == null)
    }

    @Test
    fun `xray json with inbounds is detected so the import can refuse it explicitly`() {
        val json = """
            {
              "inbounds": [{"tag": "socks-in", "protocol": "socks", "port": 1080}],
              "outbounds": [
                {"tag": "proxy", "protocol": "vless", "settings": {}},
                {"tag": "direct", "protocol": "freedom"}
              ],
              "routing": {"rules": []}
            }
        """.trimIndent()
        check(RawConfigDetector.detect(json) == RawConfigFormat.XRAY)
    }

    @Test
    fun `xray outbounds without inbounds are not a complete config`() {
        val json = """
            {
              "outbounds": [
                {"tag": "proxy", "protocol": "vless", "settings": {}},
                {"tag": "direct", "protocol": "freedom"}
              ],
              "routing": {"rules": []}
            }
        """.trimIndent()
        check(RawConfigDetector.detect(json) == null)
    }

    @Test
    fun `base64-wrapped clash config is detected`() {
        val yaml = "proxies:\n  - name: a\n    type: ss\n    server: s\n    port: 1\n    cipher: x\n    password: p\n"
        val b64 = java.util.Base64.getEncoder().encodeToString(yaml.toByteArray())
        check(RawConfigDetector.detect(b64) == RawConfigFormat.CLASH)
    }

    @Test
    fun `plain uri node list is not a raw config`() {
        check(RawConfigDetector.detect("vmess://eyJ2IjoiMiIsInBzIjoibiIsImFkZCI6ImEuY29tIn0=\n") == null)
        check(RawConfigDetector.detect("ss://YWVzLTI1Ni1nY206cHdAaC5leGFtcGxlLmNvbTo0NDM#node\n") == null)
    }

    @Test
    fun `json without outbounds is not a raw config`() {
        check(RawConfigDetector.detect("""{"foo": "bar"}""") == null)
    }
}
