package com.interstellar.proxy.data.subscription

/**
 * The refresh/import gate that runs BEFORE the destructive raw-body commit.
 *
 * `commitFetchedBody` may replace or delete the retained raw body, so a body
 * that cannot be used must be classified here first. Keeping the matrix pure
 * lets tests pin that Unsupported/TooManyNodes/empty-node-list never reach the
 * commit, while a raw sing-box profile with no extractable nodes does.
 */
object RefreshGuard {
    enum class Decision { ACCEPT, UNSUPPORTED, TOO_MANY_NODES, EMPTY_UNPARSABLE }

    fun decide(detected: RawConfigFormat?, parsed: SubscriptionParser.Result): Decision = when (parsed) {
        is SubscriptionParser.Result.Unsupported -> Decision.UNSUPPORTED
        is SubscriptionParser.Result.TooManyNodes -> Decision.TOO_MANY_NODES
        SubscriptionParser.Result.Empty ->
            if (detected == RawConfigFormat.SINGBOX) Decision.ACCEPT else Decision.EMPTY_UNPARSABLE
        is SubscriptionParser.Result.Nodes -> Decision.ACCEPT
    }
}
