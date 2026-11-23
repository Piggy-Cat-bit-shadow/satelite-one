package com.interstellar.proxy.core

/**
 * Neutral outbound-group DTOs consumed by the UI (nodes page, dashboard).
 * Filled from libbox's CommandClient snapshots — the UI never touches
 * engine types.
 */
data class CoreGroup(
    val tag: String,
    val type: String,
    val selected: String?,
    val items: List<CoreGroupItem>,
)

data class CoreGroupItem(
    val tag: String,
    val type: String,
    val urlTestDelay: Int = 0,
    val urlTestTime: Long = 0L,
)

/**
 * Outbound types the core reports for a GROUP rather than a leaf node.
 *
 * `loadbalance` is a group too; libbox reports it exactly like the other two, so a page that
 * only knew `selector`/`urltest` rendered a load-balance member as if it were a node.
 */
private val GROUP_TYPES = setOf("selector", "urltest", "loadbalance")

internal fun isGroupType(type: String): Boolean = type.lowercase() in GROUP_TYPES

/**
 * The groups the node list shows: the structure read from the active config, extended with the
 * members only the running core can know and with the latest complete url-test result.
 *
 * # Why the static half cannot be the whole answer
 *
 * A group declared with `providers` has no members in the config file. The core downloads the
 * provider's document at runtime and publishes each node as `<provider tag>/<node tag>`; the
 * file on disk never contains those tags, so a page built from the file alone shows the group
 * as empty and the nodes as missing. The same holds for a group a provider document itself
 * carries: it exists only in the core's snapshot.
 *
 * # Why live cannot replace the static half
 *
 * The file is the structure that survives a stopped or restarting core, and switching
 * subscriptions must take effect before the new core has reported anything. So static stays the
 * base: its order, its declared members and their types are kept, and live entries are appended
 * only when their tag is not already present. A declared member the core has not (yet) reported
 * is still shown, because the file says the group has it.
 *
 * Runtime-only groups (tag absent from the file) are appended after the static ones. An empty
 * runtime-only group adds nothing to display, so it is not given a tab of its own.
 *
 * # Why results are overlaid instead of replaced
 *
 * The live snapshot of a declared group also carries the members' latest url-test results, but
 * the static object is what the page keeps for a declared member - so a result reported only on
 * the live half used to be dropped. Worse, the page combined its delay from a separate delay
 * cache with the time from the static item, which could compose a "0 / no time" pair out of two
 * different results and leave a failed node looking untested.
 *
 * [results] is therefore overlaid onto the members as a COMPLETE pair (delay + completion time)
 * during projection; the member's identity, type and position still come from the file. The
 * group-scope result carried by the live item wins over the tag-only global cache: a URLTest
 * group measures its members against its own target, and a value measured against another target
 * must not overwrite it. The global entry is used only when the group itself has no result.
 *
 * The returned `selected` is the static one: this function only extends membership and overlays
 * results. Callers that need runtime selection read it from the live snapshot, which is the
 * authority while the core runs.
 */
internal fun displayGroups(
    static: List<CoreGroup>,
    live: List<CoreGroup>,
    results: Map<String, UrlTestResult> = emptyMap(),
): List<CoreGroup> {
    if (live.isEmpty()) {
        // The core is stopped (or has not reported yet): the file keeps the structure, and the
        // last complete results are still valid facts about the nodes.
        if (results.isEmpty()) return static
        return static.map { group ->
            val items = projectMembers(group, liveGroup = null, results)
            if (items == group.items) group else group.copy(items = items)
        }
    }
    val liveByTag = live.associateBy { it.tag }
    val merged = static.map { staticGroup ->
        val liveGroup = liveByTag[staticGroup.tag]
        if (liveGroup == null || liveGroup.items.isEmpty()) {
            val items = projectMembers(staticGroup, liveGroup = null, results)
            if (items == staticGroup.items) staticGroup else staticGroup.copy(items = items)
        } else {
            staticGroup.copy(items = projectMembers(staticGroup, liveGroup, results))
        }
    }
    val staticTags = static.mapTo(HashSet()) { it.tag }
    val runtimeOnly = live
        .filter { it.tag !in staticTags && it.items.isNotEmpty() }
        .map { group ->
            group.copy(items = projectMembers(group, liveGroup = group, results))
        }
    return if (runtimeOnly.isEmpty()) merged else merged + runtimeOnly
}

/**
 * The file's members, in the file's order and with the file's types, followed by the members
 * only the core knows. Each produced item carries the newest complete result known for it.
 */
private fun projectMembers(
    group: CoreGroup,
    liveGroup: CoreGroup?,
    results: Map<String, UrlTestResult>,
): List<CoreGroupItem> {
    val liveByTag = liveGroup?.items.orEmpty().associateBy { it.tag }
    val seen = HashSet<String>(group.items.size)
    val declared = group.items.map { item ->
        seen.add(item.tag)
        item.withRuntimeResult(group.type, liveByTag[item.tag], results)
    }
    val extra = liveGroup?.items.orEmpty()
        .filter { seen.add(it.tag) }
        .map { item -> item.withRuntimeResult(group.type, item, results) }
    return if (extra.isEmpty()) declared else declared + extra
}

/**
 * This item with the complete runtime result for its tag attached.
 *
 * The pair is copied from ONE source, never merged field by field. [resultForMember] decides
 * which source is authoritative for this group: the live item's scoped pair for a URLTest group,
 * the newer complete pair otherwise.
 */
private fun CoreGroupItem.withRuntimeResult(
    groupType: String,
    liveItem: CoreGroupItem?,
    results: Map<String, UrlTestResult>,
): CoreGroupItem {
    val runtime = resultForMember(groupType, liveItem?.urlTestResult(), results[tag]) ?: return this
    return copy(urlTestDelay = runtime.delay, urlTestTime = runtime.completedAt)
}
