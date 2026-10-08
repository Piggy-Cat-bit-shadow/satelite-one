package com.interstellar.proxy.data.config

import com.interstellar.proxy.data.model.NodeType
import com.interstellar.proxy.data.model.ProxyNode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Minimal sing-box config generator for plain node subscriptions.
 *
 * Deliberately thin: it emits only what sing-box needs to run the
 * subscription's nodes behind a selector. Routing policy, geo rule sets, ad
 * blocking, DNS policy, latency ranking and region grouping are NOT the app's
 * business — they belong to sing-box itself, or to a raw sing-box config the
 * user supplies (which is passed through untouched, see
 * `SubscriptionRepository.regenerateActiveConfig`).
 *
 * Emits:
 *  - one `selector` (手动选择) holding `auto` + every node
 *  - one `urltest` (`auto`) over every node — the app's sole latency source
 *  - the node outbounds (protocol conversion only, see [nodeToOutbound])
 *  - a `direct` outbound (referenced by the LAN rule)
 *  - a TUN inbound, plus an optional loopback mixed port for subscription refresh
 *  - a minimal DNS block and a minimal route block
 */
object ConfigBuilder {

    const val GROUP_TAG = "手动选择"
    const val AUTO_TAG = "auto"
    const val DIRECT_TAG = "direct"

    /** Tags a node name must never collide with. */
    private val RESERVED_TAGS = setOf(GROUP_TAG, AUTO_TAG, DIRECT_TAG, "tun-in", "mixed-in")

    /**
     * Private ranges kept out of the tunnel. Android UX rather than routing
     * policy: without it the VPN swallows the LAN and local devices become
     * unreachable. Fixed, not a setting.
     */
    private val LAN_ROUTES = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")

    // urltest tuning pinned in one place instead of exposed as app settings.
    // These are sing-box's own knobs; no app-side algorithm sits on top.
    private const val URLTEST_URL = "https://www.gstatic.com/generate_204"
    private const val URLTEST_INTERVAL = "5m"
    private const val URLTEST_TOLERANCE = 50
    private const val URLTEST_IDLE_TIMEOUT = "30m"

    data class BuildOptions(
        /** Node/group tag to pre-select in the selector; falls back to `auto`. */
        val selectedNodeTag: String? = null,
        /** Loopback mixed port used by the app's own subscription refresh. */
        val mixedPortEnabled: Boolean = true,
        val mixedPort: Int = 2080,
        /** When false, skip TUN so url-test can run without claiming the VPN. */
        val includeTun: Boolean = true,
    )

    fun build(nodes: List<ProxyNode>, options: BuildOptions): String {
        val tags = dedupeTags(nodes)
        val json = buildJsonObject {
            putJsonObject("log") {
                put("level", "info")
                put("timestamp", true)
            }
            putJsonObject("dns") { buildDns() }
            putJsonArray("inbounds") { buildInbounds(options) }
            putJsonArray("outbounds") { buildOutbounds(nodes, tags, options) }
            putJsonObject("route") { buildRoute() }
            putJsonObject("experimental") {
                putJsonObject("cache_file") {
                    put("enabled", true)
                }
            }
        }
        return json.toString()
    }

    // ---- inbounds ----

    private fun kotlinx.serialization.json.JsonArrayBuilder.buildInbounds(options: BuildOptions) {
        if (options.includeTun) {
            add(
                buildJsonObject {
                    put("type", "tun")
                    put("tag", "tun-in")
                    putJsonArray("address") {
                        // v4-only TUN: the underlying network often has no IPv6
                        // exit, and a v6 tun address makes apps dial AAAA targets
                        // that can never be reached — those connections die with
                        // ERR_CONNECTION_RESET.
                        add("172.19.0.1/30")
                    }
                    put("mtu", 9000)
                    put("auto_route", true)
                    put("stack", "mixed")
                    putJsonArray("route_exclude_address") {
                        LAN_ROUTES.forEach { add(it) }
                    }
                },
            )
        }
        if (options.mixedPortEnabled) {
            add(
                buildJsonObject {
                    put("type", "mixed")
                    put("tag", "mixed-in")
                    put("listen", "127.0.0.1")
                    put("listen_port", options.mixedPort)
                },
            )
        }
    }

    // ---- outbounds ----

    private fun kotlinx.serialization.json.JsonArrayBuilder.buildOutbounds(
        nodes: List<ProxyNode>,
        tags: List<String>,
        options: BuildOptions,
    ) {
        val defaultTag = options.selectedNodeTag?.takeIf { it in tags || it == AUTO_TAG } ?: AUTO_TAG
        // the single selector: auto (urltest over every node) + every node
        add(
            buildJsonObject {
                put("type", "selector")
                put("tag", GROUP_TAG)
                putJsonArray("outbounds") {
                    add(AUTO_TAG)
                    tags.forEach { add(it) }
                }
                put("default", defaultTag)
                put("interrupt_exist_connections", false)
            },
        )
        if (nodes.isNotEmpty()) {
            add(urltestOutbound(AUTO_TAG, tags))
        }
        // node outbounds (shadow-tls detours referenced inline)
        val usedTags = mutableSetOf<String>()
        val extras = mutableListOf<JsonElement>()
        nodes.zip(tags).forEach { (node, tag) ->
            val detourTag: String?
            if (node.shadowTls != null && node.type == NodeType.VMESS) {
                var stTag = "$tag-shadowtls"
                var i = 2
                while (!usedTags.add(stTag)) {
                    stTag = "$tag-shadowtls-${i++}"
                }
                detourTag = stTag
                extras.add(buildShadowTlsOutbound(node, stTag))
            } else {
                detourTag = null
            }
            add(nodeToOutbound(node, tag, detourTag))
        }
        extras.forEach { add(it) }
        // referenced by the LAN route rule below
        add(buildJsonObject { put("type", "direct"); put("tag", DIRECT_TAG) })
    }

    private fun urltestOutbound(tag: String, members: List<String>): JsonObject = buildJsonObject {
        put("type", "urltest")
        put("tag", tag)
        putJsonArray("outbounds") { members.forEach { add(it) } }
        put("url", URLTEST_URL)
        put("interval", URLTEST_INTERVAL)
        put("tolerance", URLTEST_TOLERANCE)
        put("idle_timeout", URLTEST_IDLE_TIMEOUT)
    }

    // ---- dns / route ----

    /**
     * Minimal DNS — exactly enough for Android TUN to resolve reliably.
     *
     * - `dns-local` (system resolver) answers queries raised while dialling an
     *   outbound, i.e. resolving a node's own server domain. Without it a
     *   remote-only resolver would have to resolve the proxy through the proxy.
     * - `dns-remote` (DoH through the selector) answers application queries, so
     *   lookups are not leaked to the underlying network.
     *
     * No CN/overseas split, no geo rule sets, no ad blocking, no hosts entries.
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.buildDns() {
        putJsonArray("servers") {
            add(
                buildJsonObject {
                    put("tag", "dns-local")
                    put("type", "local")
                },
            )
            add(
                buildJsonObject {
                    put("tag", "dns-remote")
                    put("type", "https")
                    put("server", "1.1.1.1")
                    put("detour", GROUP_TAG)
                },
            )
        }
        putJsonArray("rules") {
            add(
                buildJsonObject {
                    put("outbound", "any")
                    put("server", "dns-local")
                },
            )
        }
        put("final", "dns-remote")
        put("strategy", "prefer_ipv4")
        put("independent_cache", true)
    }

    /**
     * Minimal route: sniff, hand DNS to the DNS module, keep the LAN out of the
     * tunnel, send everything else to the selector.
     *
     * No geo rule sets, no CN bypass, no overseas rule, no ad blocking, no
     * user-defined domain rules and no mode-dependent `final`.
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.buildRoute() {
        putJsonArray("rules") {
            add(buildJsonObject { put("action", "sniff") })
            add(
                buildJsonObject {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                },
            )
            add(
                buildJsonObject {
                    put("ip_is_private", true)
                    put("outbound", DIRECT_TAG)
                },
            )
        }
        put("final", GROUP_TAG)
        put("auto_detect_interface", true)
    }

    // ---- node → outbound (protocol conversion only) ----

    private fun buildShadowTlsOutbound(node: ProxyNode, tag: String): JsonObject = buildJsonObject {
        put("type", "shadowtls")
        put("tag", tag)
        put("server", node.server)
        put("server_port", node.port)
        put("version", node.shadowTls?.version ?: 3)
        node.shadowTls?.password?.let { put("password", it) }
        putJsonObject("tls") {
            put("enabled", true)
            put("server_name", node.shadowTls?.sni ?: node.sni ?: node.server)
        }
    }

    fun nodeToOutbound(node: ProxyNode, tag: String, shadowTlsDetour: String? = null): JsonObject = buildJsonObject {
        put("tag", tag)
        if (shadowTlsDetour != null) put("detour", shadowTlsDetour)
        when (node.type) {
            NodeType.SHADOWSOCKS -> {
                put("type", "shadowsocks")
                put("server", node.server)
                put("server_port", node.port)
                put("method", node.method ?: "aes-256-gcm")
                node.password?.let { put("password", it) }
                if (node.plugin != null) {
                    put("plugin", node.plugin)
                    node.pluginOpts?.let { opts ->
                        putJsonObject("plugin_opts") {
                            opts.forEach { (k, v) -> put(k, v) }
                        }
                    }
                }
            }

            NodeType.VMESS -> {
                put("type", "vmess")
                put("server", node.server)
                put("server_port", node.port)
                put("uuid", node.uuid ?: "")
                put("security", node.security ?: "auto")
                put("alter_id", node.alterId ?: 0)
                buildTls(this, node)
                buildTransport(this, node)
            }

            NodeType.VLESS -> {
                put("type", "vless")
                put("server", node.server)
                put("server_port", node.port)
                put("uuid", node.uuid ?: "")
                node.flow?.let { put("flow", it) }
                buildTls(this, node)
                buildTransport(this, node)
            }

            NodeType.TROJAN -> {
                put("type", "trojan")
                put("server", node.server)
                put("server_port", node.port)
                put("password", node.password ?: "")
                buildTls(this, node)
                buildTransport(this, node)
            }

            NodeType.HYSTERIA2 -> {
                put("type", "hysteria2")
                put("server", node.server)
                put("server_port", node.port)
                node.password?.let { put("password", it) }
                node.hy2ObfsPassword?.let {
                    putJsonObject("obfs") {
                        put("type", "salamander")
                        put("password", it)
                    }
                }
                node.upMbps?.let { put("up_mbps", it) }
                node.downMbps?.let { put("down_mbps", it) }
                putJsonObject("tls") {
                    put("enabled", true)
                    node.sni?.let { put("server_name", it) } ?: put("server_name", node.server)
                    put("insecure", node.insecure ?: false)
                    node.alpn?.let { alpn -> putJsonArray("alpn") { alpn.forEach { add(it) } } }
                }
            }

            NodeType.TUIC -> {
                put("type", "tuic")
                put("server", node.server)
                put("server_port", node.port)
                node.uuid?.let { put("uuid", it) }
                node.password?.let { put("password", it) }
                node.congestionControl?.let { put("congestion_control", it) }
                node.udpRelayMode?.let { put("udp_relay_mode", it) }
                node.reduceRtt?.let { put("reduce_rtt", it) }
                putJsonObject("tls") {
                    put("enabled", true)
                    node.sni?.let { put("server_name", it) } ?: put("server_name", node.server)
                    put("insecure", node.insecure ?: false)
                    node.alpn?.let { alpn -> putJsonArray("alpn") { alpn.forEach { add(it) } } }
                }
            }

            NodeType.SOCKS -> {
                put("type", "socks")
                put("server", node.server)
                put("server_port", node.port)
                put("version", "5")
                node.username?.let { put("username", it) }
                node.password?.let { put("password", it) }
                if (node.tls) buildTls(this, node)
            }

            NodeType.HTTP -> {
                put("type", "http")
                put("server", node.server)
                put("server_port", node.port)
                node.username?.let { put("username", it) }
                node.password?.let { put("password", it) }
                if (node.tls) buildTls(this, node)
            }

            NodeType.WIREGUARD -> {
                put("type", "wireguard")
                put("server", node.server)
                put("server_port", node.port)
                putJsonArray("local_address") {
                    (node.wireguard?.localAddress ?: listOf("172.16.0.2/32")).forEach { add(it) }
                }
                put("private_key", node.wireguard?.privateKey ?: "")
                node.wireguard?.peerPublicKey?.let { put("peer_public_key", it) }
                node.wireguard?.preSharedKey?.let { put("pre_shared_key", it) }
                node.wireguard?.reserved?.let { reserved ->
                    putJsonArray("reserved") { reserved.forEach { add(it) } }
                }
                node.wireguard?.mtu?.let { put("mtu", it) }
            }

            NodeType.ANYTLS -> {
                put("type", "anytls")
                put("server", node.server)
                put("server_port", node.port)
                node.password?.let { put("password", it) }
                putJsonObject("tls") {
                    put("enabled", true)
                    node.sni?.let { put("server_name", it) } ?: put("server_name", node.server)
                    put("insecure", node.insecure ?: false)
                }
            }

            NodeType.SSH -> {
                put("type", "ssh")
                put("server", node.server)
                put("server_port", node.port)
                node.sshUser?.let { put("user", it) }
                node.sshKey?.let { put("private_key", it) }
            }

            NodeType.UNKNOWN -> put("type", "direct")
        }
    }

    private fun buildTls(obj: kotlinx.serialization.json.JsonObjectBuilder, node: ProxyNode) {
        if (!node.tls && node.reality == null) return
        obj.putJsonObject("tls") {
            put("enabled", true)
            val sni = node.sni ?: node.server
            put("server_name", sni)
            put("insecure", node.insecure ?: false)
            node.alpn?.let { alpn -> putJsonArray("alpn") { alpn.forEach { add(it) } } }
            if (node.fingerprint != null) {
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", node.fingerprint)
                }
            }
            node.reality?.let { reality ->
                if (reality.publicKey.isNotBlank()) {
                    putJsonObject("reality") {
                        put("enabled", true)
                        put("public_key", reality.publicKey)
                        reality.shortId?.let { put("short_id", it) }
                    }
                }
            }
        }
    }

    private fun buildTransport(obj: kotlinx.serialization.json.JsonObjectBuilder, node: ProxyNode) {
        when (node.network) {
            "ws" -> obj.putJsonObject("transport") {
                put("type", "ws")
                put("path", node.wsPath ?: "/")
                node.headers?.let { headers ->
                    putJsonObject("headers") {
                        headers.forEach { (k, v) -> put(k, v) }
                    }
                }
            }

            "grpc" -> obj.putJsonObject("transport") {
                put("type", "grpc")
                node.grpcServiceName?.let { put("service_name", it) }
            }

            "h2", "http" -> obj.putJsonObject("transport") {
                put("type", "http")
                node.httpPath?.let { put("path", it) }
                node.httpHost?.let { hosts ->
                    putJsonArray("host") { hosts.forEach { add(it) } }
                }
            }
        }
    }

    // ---- helpers ----

    private fun dedupeTags(nodes: List<ProxyNode>): List<String> {
        val used = mutableSetOf<String>()
        return nodes.map { node ->
            var base = node.name.trim().ifBlank { "${node.server}:${node.port}" }
            base = base.replace(Regex("[\\r\\n\"\\\\]"), " ").trim()
            var tag = base
            var index = 2
            while (tag in used || tag in RESERVED_TAGS) {
                tag = "$base ($index)"
                index++
            }
            used.add(tag)
            tag
        }
    }

    fun tagsFor(nodes: List<ProxyNode>): List<String> = dedupeTags(nodes)

    fun tagFor(nodes: List<ProxyNode>, nodeId: String): String? {
        val tags = dedupeTags(nodes)
        val index = nodes.indexOfFirst { it.id == nodeId }
        return if (index >= 0) tags[index] else null
    }
}
