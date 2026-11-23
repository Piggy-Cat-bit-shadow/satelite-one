package com.interstellar.proxy.ui.pages

import com.interstellar.proxy.core.CoreGroup
import com.interstellar.proxy.core.CoreGroupItem
import com.interstellar.proxy.core.UrlTestResult
import com.interstellar.proxy.core.resultForMember
import com.interstellar.proxy.core.urlTestResult
import com.interstellar.proxy.data.config.MinimalConfigBuilder

/**
 * A leaf the dashboard's group chain currently denotes, with the leaf's COMPLETE result.
 *
 * Keeping the tag and the result together is what lets the dashboard decide between a delay, a
 * timeout and a still-pending member without re-deriving the leaf in a second walk.
 */
internal data class NodeSelection(val tag: String, val result: UrlTestResult?)

/** The displayed node name: live selection first, then the persisted pick. */
internal fun nodeRowValue(
    groups: List<CoreGroup>,
    results: Map<String, UrlTestResult>,
    mainGroup: CoreGroup?,
    storedSelected: String,
): String {
    val selection = mainSelection(groups, results, mainGroup, storedSelected) ?: return "未选择"
    return if (isGroupAlias(selection.tag)) "自动" else selection.tag
}

/** The leaf node tag currently in use (null when it stays on a group / auto itself). */
internal fun currentLeafTag(
    groups: List<CoreGroup>,
    results: Map<String, UrlTestResult>,
    mainGroup: CoreGroup?,
    storedSelected: String,
): String? =
    mainSelection(groups, results, mainGroup, storedSelected)
        ?.tag
        ?.takeIf { !isGroupAlias(it) }

/** The complete result of the node the dashboard is showing, or null when it was never probed. */
internal fun selectedResult(
    groups: List<CoreGroup>,
    results: Map<String, UrlTestResult>,
    mainGroup: CoreGroup?,
    storedSelected: String,
): UrlTestResult? =
    mainSelection(groups, results, mainGroup, storedSelected)?.result

private fun isGroupAlias(tag: String): Boolean =
    tag == MinimalConfigBuilder.AUTO_TAG || tag == MinimalConfigBuilder.GROUP_TAG

private fun mainSelection(
    groups: List<CoreGroup>,
    results: Map<String, UrlTestResult>,
    mainGroup: CoreGroup?,
    storedSelected: String,
): NodeSelection? {
    // live core selection first; when 未连接 fall back to the persisted tag
    val selected = mainGroup?.selected?.takeIf { it.isNotBlank() }
        ?: storedSelected.takeIf { it.isNotBlank() }
        ?: return null
    return resolveNow(groups, results, selected)
}

/**
 * Walk selector / urltest until the leaf in use, carrying that leaf's result.
 *
 * Urltest's Now() is empty until the first full test finishes - fall back to the current fastest
 * (or first) member so the home row does not sit on "自动" for seconds. Only a SUCCESS can be the
 * fastest; a timeout has no latency to rank and must not win by being zero.
 */
internal fun resolveNow(
    groups: List<CoreGroup>,
    results: Map<String, UrlTestResult>,
    tag: String,
    depth: Int = 0,
): NodeSelection {
    if (depth > 5) return NodeSelection(tag, results[tag])
    val group = groups.find { it.tag == tag } ?: return NodeSelection(tag, results[tag])
    val next = group.selected
    if (!next.isNullOrBlank() && next != tag) {
        val member = group.items.find { it.tag == next }
        if (member != null) {
            if (groups.any { it.tag == next }) {
                // The selection names a nested group: its own result belongs to that group's
                // members, not to the group item.
                return resolveNow(groups, results, next, depth + 1)
            }
            return NodeSelection(next, memberResult(group, member, results))
        }
        return resolveNow(groups, results, next, depth + 1)
    }
    val items = group.items
    if (items.isEmpty()) return NodeSelection(tag, results[tag])
    val fastest = items
        .mapNotNull { item ->
            val result = memberResult(group, item, results)?.takeIf { it.isSuccess }
                ?: return@mapNotNull null
            item to result
        }
        .minByOrNull { (_, result) -> result.delay }
        ?.first
    val candidate = fastest ?: items.first()
    if (groups.any { it.tag == candidate.tag }) {
        return resolveNow(groups, results, candidate.tag, depth + 1)
    }
    return NodeSelection(candidate.tag, memberResult(group, candidate, results))
}

/**
 * The complete result of one member, from the source that is authoritative for its group.
 *
 * Delegates to the same [resultForMember] rule the node-page projection uses: a URLTest group's
 * scoped pair always wins (its members are measured against the group's own target), while any
 * other group takes the newer complete pair. Neither path ever turns "no positive delay" into a
 * fallback for an older success.
 */
internal fun memberResult(
    group: CoreGroup,
    item: CoreGroupItem,
    results: Map<String, UrlTestResult>,
): UrlTestResult? =
    resultForMember(group.type, item.urlTestResult(), results[item.tag])
