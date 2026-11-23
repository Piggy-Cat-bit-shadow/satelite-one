package com.interstellar.proxy.core

/**
 * One COMPLETE url-test outcome, exactly as the kernel reports it.
 *
 * The delay and [completedAt] belong to the SAME probe and are only ever copied out of one
 * snapshot entry. Keeping the two together is what makes "tested and failed" distinguishable
 * from "never tested" without a `-1` sentinel or a magic threshold. The kernel reuses the
 * existing `urlTestDelay` / `urlTestTime` wire fields:
 *
 *  - `delay > 0`, valid time: the last probe succeeded - show the delay;
 *  - `delay == 0`, valid time: the last probe genuinely failed - show the localized "timeout";
 *  - no entry at all: never probed - keep the existing untested placeholder.
 *
 * A zero time means "no result", never the epoch: the raw proto field is a Unix second count
 * whose absence is 0, so [isTerminal] tests it explicitly instead of treating any object as
 * tested.
 */
data class UrlTestResult(val delay: Int, val completedAt: Long) {
    /** A finished probe, success or failure. */
    val isTerminal: Boolean get() = completedAt > 0L

    val isSuccess: Boolean get() = isTerminal && delay > 0

    /** A finished probe that produced no delay: the unified "timeout" display. */
    val isTimeout: Boolean get() = isTerminal && delay <= 0
}

/** The complete result this member carries, or null when it was never probed. */
fun CoreGroupItem.urlTestResult(): UrlTestResult? =
    if (urlTestTime > 0L) UrlTestResult(urlTestDelay, urlTestTime) else null

/**
 * The tag -> complete result map of ONE full outbounds snapshot.
 *
 * A member without a terminal result is ABSENT rather than mapped to zero, so "never tested"
 * and "tested and failed" stay distinguishable at every layer. Every kernel push carries the
 * whole outbound list (`readOutbounds` in `daemon/started_service.go`), so replacing the map
 * with this value also bounds it to the current outbounds and drops tags that are gone from
 * the snapshot - there is no second, ever-growing cache.
 */
fun urlTestResultsOf(snapshot: List<CoreGroupItem>): Map<String, UrlTestResult> =
    snapshot.mapNotNull { item ->
        item.urlTestResult()?.let { result -> item.tag to result }
    }.toMap()

/**
 * The replacement result map for one outbounds snapshot, or null when the snapshot carries no
 * measurement information and the previous map must be kept.
 *
 * `SubscribeOutbounds` sends a zero-length list when the service is no longer STARTED, and a
 * running config always has at least one outbound. Treating that stop frame as a measurement
 * would erase the results the user just obtained - including an offline test's result at the
 * moment its temporary core stops, which would make the wait pointless. A non-empty snapshot,
 * however, is authoritative: a tag it lists with no result has none on this instance, and a tag
 * it no longer lists is gone.
 */
fun urlTestResultsUpdate(snapshot: List<CoreGroupItem>): Map<String, UrlTestResult>? =
    if (snapshot.isEmpty()) null else urlTestResultsOf(snapshot)

/**
 * The one display decision shared by the node badge, the detail sheet and the dashboard.
 *
 * Kept as a pure three-way value (and not as strings) so the policy can be unit-tested without
 * Compose, resources or a core. The caller only maps it to its already-localized labels.
 */
enum class UrlTestDisplay { Untested, Timeout, Delay }

fun urlTestDisplay(result: UrlTestResult?): UrlTestDisplay = when {
    result == null || !result.isTerminal -> UrlTestDisplay.Untested
    result.delay > 0 -> UrlTestDisplay.Delay
    else -> UrlTestDisplay.Timeout
}

/** Same decision for the two raw fields, for callers that hold them separately. */
fun urlTestDisplay(delay: Int, completedAt: Long): UrlTestDisplay =
    urlTestDisplay(if (completedAt > 0L) UrlTestResult(delay, completedAt) else null)

/**
 * Pick the result that describes one member of a group with type [groupType].
 *
 * A URLTest group measures every member against its OWN target, and the kernel puts that scoped
 * pair on the group's member. A tag-level entry may have been written by a probe against another
 * target, so the scoped pair always wins and the tag-level one is only a fallback. Every other
 * group has no target of its own; both halves describe the same tag-level result, so the newer
 * COMPLETE pair wins (a tie keeps the group snapshot). Comparing completion times here - rather
 * than testing `delay > 0` - is what stops a fresh failure from being replaced by an older
 * success.
 */
fun resultForMember(
    groupType: String,
    scoped: UrlTestResult?,
    global: UrlTestResult?,
): UrlTestResult? = if (groupType.equals("urltest", ignoreCase = true)) {
    scoped ?: global
} else {
    when {
        scoped == null -> global
        global == null -> scoped
        global.completedAt > scoped.completedAt -> global
        else -> scoped
    }
}

/**
 * What ONE test run has actually learned, counted over the nodes it submitted.
 *
 * [failed] counts only members that returned a terminal failure this round. A member that has
 * not reported yet is [pending], not failed: the old `total - successes` arithmetic painted
 * every still-running node red and also counted nodes the kernel had merely skipped.
 */
data class UrlTestSummary(
    val total: Int,
    /** Successful delays in milliseconds, ascending. */
    val delays: List<Int>,
    val failed: Int,
) {
    val ok: Int get() = delays.size

    val pending: Int get() = (total - ok - failed).coerceAtLeast(0)

    val minMs: Int get() = delays.firstOrNull() ?: 0

    val maxMs: Int get() = delays.lastOrNull() ?: 0

    val p50: Int get() = percentile(0.50)

    val p95: Int get() = percentile(0.95)

    private fun percentile(p: Double): Int =
        if (delays.isEmpty()) 0 else delays[((delays.size - 1) * p).toInt()]
}

/**
 * Count one run's outcomes from its submitted tags and the complete results received so far.
 *
 * [results] is the run's own outcome map (`AppViewModel.testResult`), not the app-wide cache,
 * so a previous run's successes can neither be counted as this run's nor hide a new failure.
 */
fun summarizeUrlTest(tags: List<String>, results: Map<String, UrlTestResult>): UrlTestSummary {
    val members = tags.distinct()
    val delays = members.mapNotNull { tag -> results[tag]?.takeIf { it.isSuccess }?.delay }.sorted()
    val failed = members.count { tag -> results[tag]?.isTimeout == true }
    return UrlTestSummary(total = members.size, delays = delays, failed = failed)
}
