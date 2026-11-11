package com.interstellar.proxy.data

/**
 * Outcome of undoing a raw-body commit after the metadata save failed.
 *
 * The restore itself can fail (for example the disk is full), so a failed
 * restore is not the end of the recovery: the unverified new body must be
 * removed/quarantined so it cannot be applied later under the old metadata.
 */
enum class RawRestoreResult {
    /** The previous body is back on disk (or correctly absent). */
    RESTORED,

    /** A newer commit owns the file; this attempt must touch nothing. */
    SUPERSEDED,

    /** Restore failed, but the unverified new body was removed/quarantined. */
    BODY_REMOVED,

    /** Restore and removal both failed; the new body is still on disk. */
    BODY_STUCK,
}

/** Pure state machine, testable without a filesystem. */
object RawRecoveryPolicy {
    fun decide(currentMatches: Boolean, restored: Boolean, removed: Boolean): RawRestoreResult = when {
        !currentMatches -> RawRestoreResult.SUPERSEDED
        restored -> RawRestoreResult.RESTORED
        removed -> RawRestoreResult.BODY_REMOVED
        else -> RawRestoreResult.BODY_STUCK
    }
}
