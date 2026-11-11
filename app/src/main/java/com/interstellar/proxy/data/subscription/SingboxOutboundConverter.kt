package com.interstellar.proxy.data.subscription

import com.interstellar.proxy.data.model.NodeType
import com.interstellar.proxy.data.model.ProxyNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** Converts sing-box outbound JSON objects back into the unified node model. */
object SingboxOutboundConverter {

    fun toNode(outbound: JsonObject, subId: String?): ProxyNode? {
        val type = outbound.str("type")?.lowercase() ?: return null
        val tag = outbound.str("tag") ?: return null
        val server = outbound.str("server") ?: return null
        val port = outbound.num("server_port") ?: return null

        val nodeType = when (type) {
            "shadowsocks" -> NodeType.SHADOWSOCKS
            "vmess" -> NodeType.VMESS
            "vless" -> NodeType.VLESS
            "trojan" -> NodeType.TROJAN
            "hysteria2" -> NodeType.HYSTERIA2
            "tuic" -> NodeType.TUIC
            "socks" -> NodeType.SOCKS
            "http" -> NodeType.HTTP
            "wireguard" -> NodeType.WIREGUARD
            "anytls" -> NodeType.ANYTLS
            "ssh" -> NodeType.SSH
            else -> return null
        }

        val node = ProxyNode(
            id = UUID.randomUUID().toString(),
            name = tag,
            type = nodeType,
            server = server,
            port = port,
            udp = outbound.bool("udp"),
            subscriptionId = subId,
        )

        return when (nodeType) {
            NodeType.SHADOWSOCKS -> node.copy(
                method = outbound.str("method"),
                password = outbound.str("password"),
                plugin = outbound.str("plugin")?.substringBefore(';')?.substringBefore(':')?.takeIf { it.isNotBlank() },
            )

            NodeType.VMESS -> node.copy(
                uuid = outbound.str("uuid"),
                alterId = outbound.num("alter_id") ?: 0,
                security = outbound.str("security"),
            ).withTlsAndTransport(outbound)

            NodeType.VLESS -> node.copy(
                uuid = outbound.str("uuid"),
                flow = outbound.str("flow"),
            ).withTlsAndTransport(outbound)

            NodeType.TROJAN -> node.copy(
                password = outbound.str("password"),
            ).withTlsAndTransport(outbound, defaultTls = true)

            NodeType.HYSTERIA2 -> node.copy(
                password = outbound.str("password"),
                hy2ObfsPassword = outbound.obj("obfs")?.str("password"),
                upMbps = outbound.num("up_mbps"),
                downMbps = outbound.num("down_mbps"),
                sni = outbound.obj("tls")?.str("server_name"),
                insecure = outbound.obj("tls")?.bool("insecure"),
                alpn = outbound.obj("tls")?.strList("alpn"),
                tls = true,
            )

            NodeType.TUIC -> node.copy(
                uuid = outbound.str("uuid"),
                password = outbound.str("password"),
                congestionControl = outbound.str("congestion_control"),
                udpRelayMode = outbound.str("udp_relay_mode"),
                reduceRtt = outbound.str("reduce_rtt")?.toBooleanStrictOrNull(),
                sni = outbound.obj("tls")?.str("server_name"),
                insecure = outbound.obj("tls")?.bool("insecure"),
                alpn = outbound.obj("tls")?.strList("alpn"),
                tls = true,
            )

            NodeType.SOCKS, NodeType.HTTP -> node.copy(
                username = outbound.str("username"),
                password = outbound.str("password"),
                tls = outbound.obj("tls") != null,
                sni = outbound.obj("tls")?.str("server_name"),
            )

            NodeType.WIREGUARD -> {
                val wgLocal = outbound.arr("local_address")?.mapNotNull {
                    (it as? JsonPrimitive)?.content
                }?.filter { it.isNotBlank() } ?: return null
                node.copy(
                    wireguard = ProxyNode.WireGuardParams(
                        localAddress = wgLocal,
                        privateKey = outbound.str("private_key") ?: return null,
                        peerPublicKey = outbound.obj("peer")?.str("public_key") ?: outbound.str("peer_public_key"),
                        preSharedKey = outbound.str("pre_shared_key"),
                        reserved = outbound.arr("reserved")?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() },
                        mtu = outbound.num("mtu"),
                    ),
                )
            }

            NodeType.ANYTLS -> node.copy(
                password = outbound.str("password"),
                sni = outbound.obj("tls")?.str("server_name"),
                insecure = outbound.obj("tls")?.bool("insecure"),
                tls = true,
            )

            // SSH is a first-class node type in this client (ProxyNode.sshUser /
            // sshKey, generated back by MinimalConfigBuilder). Without this branch
            // the type was mapped above and then fell into `else -> null`, so
            // `mapNotNull` dropped every SSH node while the refresh still reported
            // success.
            NodeType.SSH -> node.copy(
                sshUser = outbound.str("user"),
                sshKey = outbound.str("private_key"),
                password = outbound.str("password"),
            )

            else -> null
        }
    }

    private fun ProxyNode.withTlsAndTransport(outbound: JsonObject, defaultTls: Boolean = false): ProxyNode {
        val tls = outbound.obj("tls")
        var node = copy(
            tls = tls != null || defaultTls,
            sni = tls?.str("server_name"),
            alpn = tls?.strList("alpn"),
            insecure = tls?.bool("insecure"),
            fingerprint = tls?.obj("utls")?.str("fingerprint")?.takeIf { it.isNotBlank() },
            reality = tls?.obj("reality")?.let { reality ->
                ProxyNode.RealityParams(
                    publicKey = reality.str("public_key") ?: "",
                    shortId = reality.str("short_id"),
                )
            },
        )
        val transport = outbound.obj("transport")
        if (transport != null) {
            val type = transport.str("type") ?: return node
            node = node.copy(network = type)
            when (type) {
                "ws" -> node = node.copy(
                    wsPath = transport.str("path"),
                    headers = transport.obj("headers")?.entries?.mapNotNull { (k, v) ->
                        (v as? JsonPrimitive)?.content?.let { k to it }
                    }?.toMap(),
                )

                "grpc" -> node = node.copy(grpcServiceName = transport.str("service_name"))
                "http" -> node = node.copy(
                    httpPath = transport.str("path"),
                    httpHost = transport.strList("host"),
                )
            }
        }
        return node
    }
}
