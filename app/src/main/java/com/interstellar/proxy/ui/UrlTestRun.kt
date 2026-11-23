package com.interstellar.proxy.ui

import com.interstellar.proxy.core.CoreGroup
import com.interstellar.proxy.core.UrlTestResult
import com.interstellar.proxy.core.isGroupType
import com.interstellar.proxy.core.urlTestResult

/**
 * What ONE headless url-test round can prove from the snapshots it already receives.
 *
 * The kernel's `URLTest` RPC returns as soon as the round is scheduled, so nothing about the
 * response says the probes finished. The proof is the member's own terminal result: the complete
 * `delay + completion time` pair the kernel publishes for a success AND for a genuine failure.
 * This type is the pure projection of that proof, so the rule is testable without a core.
 */
internal data class HeadlessProbeProgress(
    /** The target group appeared in the probe core's group snapshot. */
    val targetPublished: Boolean,
    /** The member leaves the probe core says the round submitted. */
    val members: List<String>,
    /** Members with a terminal result completed at or after the request. */
    val completed: List<String>,
    /**
     * Members that could not even be enumerated.
     *
     * The kernel's snapshot omits a hidden group, and a group no provider has published yet is
     * absent too. Such a group's leaves were still submitted by the round, but the client cannot
     * name them, so completion cannot be proven. The caller reports this instead of stopping the
     * core on a guess.
     */
    val unprovable: List<String> = emptyList(),
) {
    val pending: List<String> get() = members.filterNot { it in completed }

    /** Done only when the member set is known, non-empty, provable, and every member reported. */
    val complete: Boolean
        get() = targetPublished && members.isNotEmpty() && unprovable.isEmpty() && pending.isEmpty()
}

/**
 * Project the headless wait's next state.
 *
 * # Which stream is authoritative
 *
 * Mirrors the kernel's own snapshot assembly (`readGroups` in `daemon/started_service.go`):
 *
 *  - a member the group SNAPSHOT lists is read from the group itself. For a URLTest group that is
 *    the pair measured against the group's own target, and a tag-level entry (which may have been
 *    written by a probe against another target) must not end the wait for it;
 *  - a nested group's leaves are not listed by the parent's snapshot at all: the kernel expands
 *    the nested group during the round and publishes each leaf's tag-level result. The outbounds
 *    stream is therefore the only place those leaves can be observed, and that pair is what the
 *    wait reads for them.
 *
 * A display-only batch (a selector target) has no configured target, so every member - direct or
 * nested - is read from the tag-level result, exactly as the node list reads it.
 *
 * `completedAt >= requestEpochSeconds` attributes the result to this request. Completion times are
 * whole epoch seconds, so a result finished in the request's own second is accepted (`>=`) instead
 * of being dropped as "older".
 */
internal fun headlessProbeProgress(
    target: String,
    requestEpochSeconds: Long,
    groups: List<CoreGroup>,
    results: Map<String, UrlTestResult>,
): HeadlessProbeProgress {
    val group = groups.find { it.tag == target }
        ?: return HeadlessProbeProgress(
            targetPublished = false,
            members = emptyList(),
            completed = emptyList(),
        )
    val scopedOnly = group.type.equals("urltest", ignoreCase = true)
    val direct = LinkedHashSet<String>()
    val nested = LinkedHashSet<String>()
    val unresolved = LinkedHashSet<String>()
    collectMembers(group, groups, depth = 0, direct = direct, nested = nested, unresolved = unresolved)
    val members = (direct + nested).toList()
    val directByTag = group.items.associateBy { it.tag }
    val completed = members.filter { tag ->
        val terminal = if (scopedOnly && tag in directByTag) {
            // A member the group itself lists carries the group's own target result. A tag-level
            // entry may belong to another target and must not end the wait for this one.
            directByTag.getValue(tag).urlTestResult()
        } else {
            // Nested leaves are not listed by the parent: the round publishes their tag-level
            // pair (or, for a display-only batch, every member is tag-level by definition).
            results[tag]
        }
        (terminal?.completedAt ?: 0L) >= requestEpochSeconds
    }
    return HeadlessProbeProgress(
        targetPublished = true,
        members = members,
        completed = completed,
        unprovable = unresolved.toList(),
    )
}

/**
 * Split a group into the leaves the round submitted: direct leaves, leaves reached through nested
 * groups (expanded the way `urlTestBatch.test` expands them), and nested groups that cannot be
 * expanded because the snapshot does not publish them.
 */
private fun collectMembers(
    group: CoreGroup,
    groups: List<CoreGroup>,
    depth: Int,
    direct: MutableSet<String>,
    nested: MutableSet<String>,
    unresolved: MutableSet<String>,
) {
    if (depth > 8) return
    for (item in group.items) {
        if (!isGroupType(item.type)) {
            if (item.tag.isBlank()) continue
            if (depth == 0) direct.add(item.tag) else nested.add(item.tag)
            continue
        }
        val child = groups.find { it.tag == item.tag }
        if (child == null) {
            unresolved.add(item.tag)
        } else {
            collectMembers(child, groups, depth + 1, direct, nested, unresolved)
        }
    }
}
