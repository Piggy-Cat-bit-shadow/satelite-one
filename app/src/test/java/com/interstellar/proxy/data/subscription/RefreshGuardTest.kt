package com.interstellar.proxy.data.subscription

import com.interstellar.proxy.data.model.NodeType
import com.interstellar.proxy.data.model.ProxyNode
import org.junit.Test

/** Bodies that must be stopped before commitFetchedBody touches the old raw file. */
class RefreshGuardTest {

    private fun node() = ProxyNode(id = "1", name = "n", type = NodeType.VMESS, server = "s", port = 443)

    @Test
    fun `unsupported and oversized bodies are rejected before commit`() {
        check(
            RefreshGuard.decide(
                null,
                SubscriptionParser.Result.Unsupported("xray"),
            ) == RefreshGuard.Decision.UNSUPPORTED,
        )
        check(
            RefreshGuard.decide(
                null,
                SubscriptionParser.Result.TooManyNodes(10000),
            ) == RefreshGuard.Decision.TOO_MANY_NODES,
        )
    }

    @Test
    fun `an empty body is only acceptable as a raw singbox profile`() {
        check(
            RefreshGuard.decide(
                RawConfigFormat.SINGBOX,
                SubscriptionParser.Result.Empty,
            ) == RefreshGuard.Decision.ACCEPT,
        )
        check(
            RefreshGuard.decide(
                null,
                SubscriptionParser.Result.Empty,
            ) == RefreshGuard.Decision.EMPTY_UNPARSABLE,
        )
        check(
            RefreshGuard.decide(
                RawConfigFormat.CLASH,
                SubscriptionParser.Result.Empty,
            ) == RefreshGuard.Decision.EMPTY_UNPARSABLE,
        )
    }

    @Test
    fun `parsed nodes are always accepted`() {
        check(
            RefreshGuard.decide(
                null,
                SubscriptionParser.Result.Nodes(listOf(node())),
            ) == RefreshGuard.Decision.ACCEPT,
        )
        check(
            RefreshGuard.decide(
                RawConfigFormat.SINGBOX,
                SubscriptionParser.Result.Nodes(listOf(node())),
            ) == RefreshGuard.Decision.ACCEPT,
        )
    }
}
