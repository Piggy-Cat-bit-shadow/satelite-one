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
 * members only the running core can know.
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
 * The returned `selected` is the static one: this function only extends membership. Callers that
 * need runtime selection read it from the live snapshot, which is the authority while the core
 * runs.
 */
internal fun displayGroups(static: List<CoreGroup>, live: List<CoreGroup>): List<CoreGroup> {
    if (live.isEmpty()) return static
    val liveByTag = live.associateBy { it.tag }
    val merged = static.map { staticGroup ->
        val liveGroup = liveByTag[staticGroup.tag] ?: return@map staticGroup
        if (liveGroup.items.isEmpty()) return@map staticGroup
        val known = staticGroup.items.mapTo(HashSet()) { it.tag }
        val extra = liveGroup.items.filter { known.add(it.tag) }
        if (extra.isEmpty()) staticGroup else staticGroup.copy(items = staticGroup.items + extra)
    }
    val staticTags = static.mapTo(HashSet()) { it.tag }
    val runtimeOnly = live.filter { it.tag !in staticTags && it.items.isNotEmpty() }
    return if (runtimeOnly.isEmpty()) merged else merged + runtimeOnly
}
