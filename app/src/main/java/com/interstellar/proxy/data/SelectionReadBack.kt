package com.interstellar.proxy.data

import kotlinx.serialization.Serializable

/**
 * The kernel's read-back for one group selection.
 *
 * `supported == false` is a capability answer from a core that predates the identity fields: the
 * selection was performed, but the answer cannot be attributed to this attempt. `appliedOutboundTag`
 * is the member the group HOLDS (not necessarily the one that was requested) and `appliedRevision`
 * is the state the next attempt is judged against.
 */
@Serializable
data class SelectOutboundReadBack(
    val supported: Boolean = false,
    val unconfirmed: Boolean = false,
    val requestId: Long = 0,
    val appliedOutboundTag: String? = null,
    val appliedRevision: Long = 0,
    val stale: Boolean = false,
)
