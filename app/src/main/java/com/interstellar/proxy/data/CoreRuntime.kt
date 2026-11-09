package com.interstellar.proxy.data

/**
 * Process-wide identity of the live core and of the config generation it is serving.
 *
 * Two numbers decide whether a result that arrives late still belongs to the instance it was
 * started for:
 *
 *  - [engineGeneration] advances every time a Box is published or released. It is what separates
 *    A → B → A and "same subscription stopped and started again" from "nothing happened": the
 *    subscription id is identical again, but the Box is not.
 *  - [appliedConfigRevision] is the [ConfigStore] revision the live Box last applied successfully.
 *    A reload that the core refused leaves the Box gone (the kernel releases it before building the
 *    replacement), so a caller may only claim "applied" after this matches the revision it wrote.
 *
 * Deliberately plain memory, no Android dependencies: this is process state, and a process restart
 * starts a new engine by definition (the BoxService publishes a fresh generation).
 */
object CoreRuntime {
    /** Bumped whenever a Box is published or released. Never decreases. */
    @Volatile
    var engineGeneration: Long = 0L
        private set

    /** The active-config revision the live Box last applied, or -1 when no Box is running one. */
    @Volatile
    var appliedConfigRevision: Long = -1L
        private set

    /** Set when the core refused the last applied config; cleared by a successful apply. */
    @Volatile
    var lastApplyFailure: String? = null
        private set

    /** True when the last apply attempt finished on the success path. */
    @Volatile
    var lastApplySucceeded: Boolean = false
        private set

    @Synchronized
    fun onEngineStarted() {
        engineGeneration += 1
        appliedConfigRevision = -1L
    }

    @Synchronized
    fun onEngineReleased() {
        engineGeneration += 1
        appliedConfigRevision = -1L
    }

    /** The Box started or reloaded this exact config revision. */
    @Synchronized
    fun onConfigApplied(revision: Long) {
        appliedConfigRevision = revision
        lastApplySucceeded = true
        lastApplyFailure = null
    }

    /** The core refused a config; the Box that was serving it is gone. */
    @Synchronized
    fun onApplyFailed(error: String?) {
        appliedConfigRevision = -1L
        lastApplySucceeded = false
        lastApplyFailure = error
    }

    /** Test/embedding seam: returns the runtime to "nothing has started yet". */
    @Synchronized
    fun reset() {
        engineGeneration = 0L
        appliedConfigRevision = -1L
        lastApplySucceeded = false
        lastApplyFailure = null
    }
}
