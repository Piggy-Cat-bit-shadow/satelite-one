package com.interstellar.proxy.data.subscription

import com.interstellar.proxy.data.model.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Subscription sniffer: node lists only (Clash YAML, share URIs, Base64).
 * JSON with outbounds is extracted as nodes, never run as a raw config.
 */
object SubscriptionParser {
    private const val MAX_NODES = 10000

    sealed class Result {
        data class Nodes(val nodes: List<ProxyNode>) : Result()

        data object Empty : Result()

        /**
         * The body parsed into more nodes than this app will hold. Returning a
         * truncated list would present a partial subscription as if it were
         * complete, so the limit is a hard failure instead.
         */
        data class TooManyNodes(val limit: Int) : Result()

        /**
         * The body is a JSON core config this app cannot run: no TUN inbound
         * (the raw path would run nothing) or an Xray document (this core cannot
         * execute it). Reported explicitly instead of falling back to a node
         * list or a silent "unparsable".
         */
        data class Unsupported(val reason: String) : Result()
    }

    fun parse(content: String, subscriptionId: String? = null): Result {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return Result.Empty

        if (trimmed.startsWith("{")) {
            jsonUnsupportedReason(trimmed)?.let { return Result.Unsupported(it) }
            extractNodesFromSingbox(trimmed, subscriptionId)?.let {
                if (it.isNotEmpty()) return resultOf(it)
            }
        }

        if (isClashLike(trimmed)) {
            val nodes = ClashParser.parseClashYaml(trimmed, subscriptionId)
            if (nodes.isNotEmpty()) return resultOf(nodes)
        }

        val direct = UriParser.parseUriList(trimmed, subscriptionId)
        if (direct.isNotEmpty()) return resultOf(direct)

        val decoded = decodeBase64(trimmed)
        if (decoded != null) {
            if (decoded.trimStart().startsWith("{")) {
                // Base64 must not be a way around the JSON shape checks: an Xray or
                // incomplete config wrapped in base64 has to fail exactly like the
                // same bytes pasted directly.
                jsonUnsupportedReason(decoded)?.let { return Result.Unsupported(it) }
                extractNodesFromSingbox(decoded, subscriptionId)?.let {
                    if (it.isNotEmpty()) return resultOf(it)
                }
            }
            val nodes = UriParser.parseUriList(decoded, subscriptionId)
            if (nodes.isNotEmpty()) return resultOf(nodes)
            if (isClashLike(decoded)) {
                val clashNodes = ClashParser.parseClashYaml(decoded, subscriptionId)
                if (clashNodes.isNotEmpty()) return resultOf(clashNodes)
            }
        }

        return Result.Empty
    }

    /**
     * Reject a JSON body that is a core config of the wrong shape, instead of
     * letting the node extractor turn it into a different subscription. A plain
     * node JSON has no inbounds, so it is not affected.
     */
    private fun jsonUnsupportedReason(text: String): String? = runCatching {
        val json = Json.parseToJsonElement(text).jsonObject
        val outbounds = json["outbounds"]?.jsonArray
        if (outbounds != null && outbounds.any { (it as? JsonObject)?.get("protocol") != null }) {
            return@runCatching "xray"
        }
        val inbounds = json["inbounds"]?.jsonArray
        if (inbounds != null && inbounds.isNotEmpty()) {
            val hasTun = inbounds.any {
                (it as? JsonObject)?.get("type")?.jsonPrimitive?.content?.equals("tun", ignoreCase = true) == true
            }
            // A full config with its own TUN is handed to the core verbatim; the
            // core validates it, and it may legitimately use outbound types this
            // node model does not have.
            if (hasTun) return@runCatching null
            return@runCatching "no_tun"
        }
        if (outbounds != null) {
            // Node JSON: an outbound this client cannot turn into a node would
            // otherwise be dropped by mapNotNull while refresh reported success.
            val nonNodeTypes = setOf("selector", "urltest", "direct", "block", "dns")
            val unsupported = outbounds.asSequence()
                .mapNotNull { (it as? JsonObject)?.get("type")?.jsonPrimitive?.content?.lowercase() }
                .firstOrNull { it !in PROXY_OUTBOUND_TYPES && it !in nonNodeTypes }
            if (unsupported != null) return@runCatching "unsupported_protocol:$unsupported"
        }
        null
    }.getOrNull()

    /** Filters pseudo-nodes, then enforces [MAX_NODES] on the surviving list. */
    private fun resultOf(nodes: List<ProxyNode>): Result {
        val filtered = nodes.filterNot(::isInfoNode)
        if (filtered.size > MAX_NODES) return Result.TooManyNodes(MAX_NODES)
        return Result.Nodes(filtered)
    }

    /** Airports smuggle quota/expiry pseudo-nodes into the list — drop them. */
    private fun isInfoNode(node: com.interstellar.proxy.data.model.ProxyNode): Boolean {
        val n = node.name
        return n.contains("剩余") || n.contains("到期") || n.contains("过期") ||
            n.contains("官网") || n.contains("重置") || n.contains("公告") ||
            n.contains("套餐") || n.contains("订阅") && n.length < 16
    }

    /** Pull proxy outbounds out of a JSON object; inbounds/route are ignored. */
    private fun extractNodesFromSingbox(text: String, subId: String?): List<ProxyNode>? = runCatching {
        val json = Json.parseToJsonElement(text).jsonObject
        val outbounds = json["outbounds"]?.jsonArray ?: return@runCatching null
        outbounds.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val type = obj["type"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (type in PROXY_OUTBOUND_TYPES) {
                SingboxOutboundConverter.toNode(obj, subId)
            } else {
                null
            }
        }
    }.getOrNull()

    private fun isClashLike(text: String): Boolean = runCatching {
        ClashParser.isClashConfig(text) || text.contains("proxies:")
    }.getOrDefault(false)

    private fun decodeBase64(text: String): String? =
        // One decoder shared with RawConfigDetector: android.util.Base64 is a
        // stub in local JVM tests (returnDefaultValues), which made every
        // base64-wrapped JSON look unparsable under test while working on device.
        SubscriptionParserDecoded.base64Decode(text)

    /**
     * Outbound types this client can turn into a node. Hysteria 1 and a bare
     * `shadowtls` outbound are deliberately absent: the node model has no
     * Hysteria-1 type, and ShadowTLS is only a wrapper around another outbound
     * (handled from `ProxyNode.shadowTls`), so whitelisting them here would
     * advertise support the converter cannot provide.
     */
    private val PROXY_OUTBOUND_TYPES = setOf(
        "shadowsocks", "vmess", "vless", "trojan", "hysteria2",
        "tuic", "socks", "http", "wireguard", "anytls", "ssh",
    )
}
