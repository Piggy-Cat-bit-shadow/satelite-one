package com.interstellar.proxy.data.subscription

import org.junit.Test

/** The commit decision matrix: every accepted body has a real path. */
class RawConfigCommitPolicyTest {

    @Test
    fun `plain node bodies are the node-list path`() {
        check(RawConfigCommitPolicy.decide(null) == RawConfigCommitPolicy.Decision.NODE_LIST)
    }

    @Test
    fun `clash is a node source, not a raw core config`() {
        check(
            RawConfigCommitPolicy.decide(RawConfigFormat.CLASH) ==
                RawConfigCommitPolicy.Decision.NODE_LIST,
        )
    }

    @Test
    fun `singbox goes to checkConfig and the raw file`() {
        check(
            RawConfigCommitPolicy.decide(RawConfigFormat.SINGBOX) ==
                RawConfigCommitPolicy.Decision.SINGBOX,
        )
    }

    @Test
    fun `xray is rejected before it is written`() {
        check(
            RawConfigCommitPolicy.decide(RawConfigFormat.XRAY) ==
                RawConfigCommitPolicy.Decision.REJECT_XRAY,
        )
    }
}
