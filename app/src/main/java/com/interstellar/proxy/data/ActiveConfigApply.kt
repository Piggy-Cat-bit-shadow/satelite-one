package com.interstellar.proxy.data

/**
 * Receipt the Box recorded for the reload request it actually served.
 *
 * `serviceReload()` is void on the wire, so a caller cannot learn from the RPC alone whether its
 * revision was applied, refused, or never even read. The Box records this receipt for every reload
 * it performs; [requestId] ties it to the client request that caused it, [configRevision] to the
 * exact config bytes it read, and [engineGeneration] to the instance the attempt belonged to.
 */
data class ReloadReceipt(
    val requestId: Long,
    val success: Boolean,
    val error: String?,
    val configRevision: Long,
    val engineGeneration: Long,
    val atMillis: Long,
)

/**
 * A reload this client is about to request, bound before the request is sent.
 *
 * The revision is the one this operation itself committed. A receipt whose request id, revision or
 * engine generation does not match this request is not evidence about this operation, no matter how
 * recently it was recorded.
 */
data class ReloadRequest(
    val requestId: Long,
    val revision: Long,
    val engineGenerationBefore: Long,
)

/**
 * Classifies the reload of one committed revision.
 *
 * The distinction that matters for safety is [Verdict.REFUSED] versus everything else: only a
 * refusal of *this* request, on a revision the store still owns, may restore the previous document.
 * A verdict that belongs to a newer write (superseded) must never be allowed to roll that write
 * back, and an unconfirmed RPC must not be treated as an apply failure either.
 */
object ActiveConfigApply {

    enum class Verdict {
        /** The Box is serving exactly this request's revision on this attempt's instance. */
        APPLIED,

        /** The Box received this request and refused the document; the old Box is gone. */
        REFUSED,

        /** A newer write, reload or instance owns the active config; this result changes nothing. */
        SUPERSEDED,

        /** The RPC failed without a matching receipt; what the Box did is unknown. */
        UNCONFIRMED,
    }

    fun classify(
        request: ReloadRequest,
        rpcSucceeded: Boolean,
        receipt: ReloadReceipt?,
        currentRevision: Long,
        currentGeneration: Long,
        appliedRevision: Long,
    ): Verdict {
        val ownReceipt = receipt?.takeIf {
            it.requestId == request.requestId && it.configRevision == request.revision
        }
        if (ownReceipt != null) {
            if (currentRevision != request.revision) return Verdict.SUPERSEDED
            if (ownReceipt.success) {
                val served = appliedRevision == request.revision &&
                    currentGeneration == ownReceipt.engineGeneration &&
                    currentGeneration > request.engineGenerationBefore
                return if (served) Verdict.APPLIED else Verdict.SUPERSEDED
            }
            // A refused document is only ours to undo while nobody has since applied the same
            // revision on a newer instance (a retry that succeeded, or another writer's restart).
            return if (appliedRevision == request.revision) Verdict.SUPERSEDED else Verdict.REFUSED
        }
        if (!rpcSucceeded) return Verdict.UNCONFIRMED
        // The RPC returned but no receipt carries this request id. Either the Box consumed a
        // different caller's intent (the reload RPC has no request payload) or another write/reload
        // moved past us. When the revision is still current we cannot tell whether it applied; that
        // is an unconfirmed result, not a clean success and not a refusal to roll back.
        if (currentRevision != request.revision) return Verdict.SUPERSEDED
        return Verdict.UNCONFIRMED
    }
}

/**
 * The store operations a failed-reload recovery needs, as an interface.
 *
 * Production is [AndroidActiveConfigStorage]; tests use a fake whose commit is deliberately
 * interleaved with the recovery to prove that a late failure cannot write over a newer config.
 */
interface ActiveConfigStorage {
    fun snapshot(): ConfigStore.Snapshot
    fun restoreIfRevision(expectedRevision: Long, content: String, ownerSubscriptionId: String?): Long?
    fun readRawBody(subscriptionId: String): String?
    fun restoreRawBodyIfCurrent(subscriptionId: String, currentBody: String?, previousBody: String?): Boolean
}

object AndroidActiveConfigStorage : ActiveConfigStorage {
    override fun snapshot(): ConfigStore.Snapshot = ConfigStore.snapshot()

    override fun restoreIfRevision(
        expectedRevision: Long,
        content: String,
        ownerSubscriptionId: String?,
    ): Long? = ConfigStore.restoreIfRevision(expectedRevision, content, ownerSubscriptionId)

    override fun readRawBody(subscriptionId: String): String? =
        SubscriptionRepository.readRawBody(subscriptionId)

    override fun restoreRawBodyIfCurrent(
        subscriptionId: String,
        currentBody: String?,
        previousBody: String?,
    ): Boolean = SubscriptionRepository.restoreRawBodyIfCurrent(subscriptionId, currentBody, previousBody)
}

/**
 * Rolls back one failed application, but only while the candidate it committed is still current.
 *
 * This is the single recovery path shared by a manual update and the periodic worker. Both commit a
 * candidate body through the repository, apply it as one config revision, and may learn much later
 * that the core refused it. The shared rule is: the failure may undo the candidate only if the
 * active config is still exactly the revision that operation committed and no newer write (C) has
 * replaced it. The revision comparison and the write are one compare-and-swap in [ConfigStore];
 * checking first and writing after is the race that let B's late failure restore A over C.
 */
object ActiveConfigRecovery {

    /**
     * True only when the live Box is serving the revision this recovery restored.
     *
     * Comparing "applied revision" with "the revision currently on disk" would also be true when
     * another operation (C) committed and applied while an old worker was waiting; the worker
     * would then refresh C's instance and log it as its own recovery. The expected revision is the
     * one [restore] returned, and it is the only instance this worker may act on.
     */
    fun servesRestoredInstance(restoredRevision: Long, appliedRevision: Long): Boolean =
        appliedRevision == restoredRevision

    /** What the failed attempt committed, and what was there before it. */
    data class FailedCandidate(
        /** Revision this attempt committed (the one the refused reload was asked for). */
        val committedRevision: Long,
        /** Owner of the committed candidate (used for diagnostics; revision implies the write). */
        val committedOwner: String?,
        /** Config bytes that served before this attempt, or null when there were none. */
        val previousConfig: String?,
        /** Owner of [previousConfig]. */
        val previousOwner: String?,
        /** Subscription whose retained raw body this attempt replaced, when it replaced one. */
        val rawOwnerId: String?,
        /** Raw body this attempt stored (null = it removed the file), read after the commit. */
        val committedRawBody: String?,
        /** Raw body to put back. */
        val previousRawBody: String?,
    )

    sealed interface Result {
        /** The previous document is back (revision [revision]); a Box must be started from it. */
        data class Restored(val revision: Long, val rawRestored: Boolean) : Result

        /** A newer write/revision owns the store; nothing was written or restarted. */
        data object Superseded : Result

        /** There is no previous document to restore; the store was left untouched. */
        data object NothingToRestore : Result
    }

    fun restore(
        candidate: FailedCandidate,
        storage: ActiveConfigStorage = AndroidActiveConfigStorage,
    ): Result {
        val before = storage.snapshot()
        if (before.meta.revision != candidate.committedRevision) return Result.Superseded
        if (before.meta.ownerSubscriptionId != candidate.committedOwner) return Result.Superseded
        val previous = candidate.previousConfig ?: return Result.NothingToRestore
        val revision = storage.restoreIfRevision(
            expectedRevision = candidate.committedRevision,
            content = previous,
            ownerSubscriptionId = candidate.previousOwner,
        ) ?: return Result.Superseded
        var rawRestored = false
        if (candidate.rawOwnerId != null) {
            rawRestored = storage.restoreRawBodyIfCurrent(
                subscriptionId = candidate.rawOwnerId,
                currentBody = candidate.committedRawBody,
                previousBody = candidate.previousRawBody,
            )
        }
        return Result.Restored(revision, rawRestored)
    }
}
