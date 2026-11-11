package com.interstellar.proxy.data.subscription

/**
 * What the commit path does with a detected body. Kept pure so the Xray/Clash
 * decisions are testable without an Android runtime, and so the three cases
 * cannot drift between the detector, the repository and the apply path.
 */
object RawConfigCommitPolicy {
    enum class Decision { NODE_LIST, SINGBOX, REJECT_XRAY }

    fun decide(format: RawConfigFormat?): Decision = when (format) {
        null, RawConfigFormat.CLASH -> Decision.NODE_LIST
        RawConfigFormat.SINGBOX -> Decision.SINGBOX
        RawConfigFormat.XRAY -> Decision.REJECT_XRAY
    }
}
