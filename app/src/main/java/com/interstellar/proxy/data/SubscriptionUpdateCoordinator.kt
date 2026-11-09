package com.interstellar.proxy.data

/**
 * Decides what a subscription update means for the running core.
 *
 * The active config is derived from either the active subscription or, with Mix
 * on, the union of the checked subscriptions. Updating a subscription that is in
 * neither cannot change the bytes on disk; updating one that is in the pool can,
 * and the running core must then be hot-reloaded before the manual provider
 * refresh is asked to run.
 */
object SubscriptionUpdateCoordinator {

    /**
     * True when any of [updatedIds] participates in the active config.
     *
     * Mix on: the mix members are the pool. Mix off: only the active subscription.
     * A raw sing-box subscription is always applied through the active slot, so
     * the active-id check is kept even while Mix is on.
     */
    fun affectsActiveConfig(
        updatedIds: Collection<String>,
        activeSubscriptionId: String,
        mixEnabled: Boolean,
        mixSubscriptionIds: Set<String>,
    ): Boolean {
        if (updatedIds.isEmpty()) return false
        if (activeSubscriptionId.isNotBlank() && updatedIds.contains(activeSubscriptionId)) return true
        return mixEnabled && updatedIds.any { it in mixSubscriptionIds }
    }

    fun configChanged(before: String?, after: String?): Boolean = before != after

    enum class ActiveConfigAction {
        /** The bytes on disk did not change; nothing to reload. */
        None,

        /** The bytes changed and the core is running: hot-reload it. */
        Reload,

        /** The bytes changed but the core is not running; they apply on next start. */
        SavedOnly,
    }

    fun action(changed: Boolean, running: Boolean): ActiveConfigAction = when {
        !changed -> ActiveConfigAction.None
        running -> ActiveConfigAction.Reload
        else -> ActiveConfigAction.SavedOnly
    }
}
