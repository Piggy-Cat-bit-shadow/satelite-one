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

    /**
     * Identity of the running instance and of the config generation it was asked for.
     *
     * The subscription id alone cannot identify a Box: A → B → A, "stop and start the same
     * subscription", and a changed mix pool all leave the id equal while the instance (or the
     * config it runs) is a different one. The engine generation and the config revision are what
     * make those three cases distinguishable, and they are captured together, before a request is
     * sent, so every later boundary can compare against a real identity instead of a name.
     */
    data class InstanceSnapshot(
        val activeSubscriptionId: String,
        val mixEnabled: Boolean,
        val mixSubscriptionIds: Set<String>,
        val engineGeneration: Long,
        val configRevision: Long,
    )

    /**
     * Whether the instance captured in [start] is still the one that owns this operation's result.
     *
     * [expectedRevision] is the config revision this operation itself committed (or the start
     * revision when it committed nothing). A revision that moved past it was written by someone
     * else, so the result is stale even when the subscription id and the engine are the same - for
     * example a mix membership change while the fetch was in flight. A blank active id is never an
     * owner, and a new Box (generation bump) invalidates every result that outlived it.
     */
    fun stillOwns(
        start: InstanceSnapshot,
        current: InstanceSnapshot,
        expectedRevision: Long = start.configRevision,
    ): Boolean {
        if (start.activeSubscriptionId.isBlank()) return false
        if (start.activeSubscriptionId != current.activeSubscriptionId) return false
        if (start.mixEnabled != current.mixEnabled) return false
        if (start.mixSubscriptionIds != current.mixSubscriptionIds) return false
        if (start.engineGeneration != current.engineGeneration) return false
        return current.configRevision == expectedRevision
    }

    /**
     * [stillOwns] plus "this attempt concerned the active pool at all".
     *
     * An update that never targeted the active pool reports nothing, no matter how the user
     * switched profiles while it was in flight.
     */
    fun ownsInstance(
        affectedAtStart: Boolean,
        start: InstanceSnapshot,
        current: InstanceSnapshot,
        expectedRevision: Long = start.configRevision,
    ): Boolean = affectedAtStart && stillOwns(start, current, expectedRevision)

    /** Which halves of an update result are reported. */
    data class UpdateReport(
        /** The config half: apply/reload what the fetch regenerated. */
        val config: Boolean,
        /** The provider half: refresh the live instance's remote node sources. */
        val providers: Boolean,
        /**
         * Why the fetched body was refused before it could become the active config, or null.
         *
         * The repository validates the candidate with libbox and keeps the previous body and
         * active config when it is refused; this carries that verdict to the initiator so a
         * download that "succeeded" but produced an unusable body is not reported as a config
         * result.
         */
        val configError: String? = null,
    )

    /**
     * The two results of one update attempt, decided independently.
     *
     * The halves are deliberately not tied together: [affected] decides whether this attempt
     * concerns the running instance at all, while [configUpdated] only decides whether there is a
     * config to apply. A download that failed, or a body that could not be parsed, leaves the
     * running instance on its previous config — and that instance's remote providers must still be
     * refreshed, because the node sources and the subscription download are different failures.
     * An attempt that cannot affect the active pool reports nothing.
     *
     * [configError] does not suppress the config half: in a batch, one subscription's body can be
     * refused while another one's bytes were committed, and that committed config still has to be
     * applied. The error is a separate fact about one candidate, not a statement that nothing
     * changed.
     */
    fun report(
        affected: Boolean,
        configUpdated: Boolean,
        configError: String? = null,
    ): UpdateReport = UpdateReport(
        config = affected && configUpdated,
        providers = affected,
        configError = configError,
    )

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
