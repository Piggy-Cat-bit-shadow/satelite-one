package com.interstellar.proxy.data

import com.interstellar.proxy.data.ActiveConfigApply.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reload receipt is the only evidence a void `serviceReload()` RPC leaves behind.
 *
 * These cases are the difference between "my revision was refused and I may roll it back" and
 * "a receipt about someone else's write happens to be the last one recorded". Treating the latter as
 * a refusal is what let a late B roll the active config back over C.
 */
class ActiveConfigApplyTest {

    private val request = ReloadRequest(
        requestId = 7,
        revision = 12,
        engineGenerationBefore = 40,
    )

    private fun receipt(
        requestId: Long = 7,
        success: Boolean = true,
        revision: Long = 12,
        generation: Long = 41,
        error: String? = null,
    ) = ReloadReceipt(
        requestId = requestId,
        success = success,
        error = error,
        configRevision = revision,
        engineGeneration = generation,
        atMillis = 1,
    )

    @Test
    fun `the receipt of this exact request on the serving revision is applied`() {
        assertEquals(
            Verdict.APPLIED,
            ActiveConfigApply.classify(
                request = request,
                rpcSucceeded = true,
                receipt = receipt(),
                currentRevision = 12,
                currentGeneration = 41,
                appliedRevision = 12,
            ),
        )
    }

    @Test
    fun `a refusal whose RPC died is still a refusal of this request`() {
        // stopAndAlert() tears the command server down after recording the receipt, so the client
        // sees Unavailable/EOF instead of a returned RPC. The receipt is what makes it attributable.
        assertEquals(
            Verdict.REFUSED,
            ActiveConfigApply.classify(
                request = request,
                rpcSucceeded = false,
                receipt = receipt(success = false, error = "dependency[p-missing] not found"),
                currentRevision = 12,
                currentGeneration = 42,
                appliedRevision = -1,
            ),
        )
    }

    @Test
    fun `a receipt for another request is not evidence about this one`() {
        // A receipt that belongs to another request cannot confirm this one. The revision is still
        // current, so the honest verdict is unconfirmed (the old code reported superseded, which
        // looked like a newer operation owned the result).
        assertEquals(
            Verdict.UNCONFIRMED,
            ActiveConfigApply.classify(
                request = request,
                rpcSucceeded = true,
                receipt = receipt(requestId = 8, success = false),
                currentRevision = 12,
                currentGeneration = 42,
                appliedRevision = -1,
            ),
        )
    }

    @Test
    fun `a newer config revision supersedes the verdict`() {
        assertEquals(
            Verdict.SUPERSEDED,
            ActiveConfigApply.classify(
                request = request,
                rpcSucceeded = true,
                receipt = receipt(success = true),
                currentRevision = 13,
                currentGeneration = 41,
                appliedRevision = 12,
            ),
        )
        // Even a refusal of our revision must not be acted on once the store moved past it.
        assertEquals(
            Verdict.SUPERSEDED,
            ActiveConfigApply.classify(
                request = request,
                rpcSucceeded = false,
                receipt = receipt(success = false, revision = 12),
                currentRevision = 13,
                currentGeneration = 42,
                appliedRevision = -1,
            ),
        )
    }

    @Test
    fun `a success receipt on a newer engine is not this attempt`() {
        assertEquals(
            Verdict.SUPERSEDED,
            ActiveConfigApply.classify(
                request = request,
                rpcSucceeded = true,
                receipt = receipt(success = true),
                currentRevision = 12,
                currentGeneration = 43,
                appliedRevision = 12,
            ),
        )
    }

    @Test
    fun `a refusal followed by a newer Box serving the same revision is superseded`() {
        // A retry applied the same bytes after our refusal. Restoring the previous document then
        // would replace a config that is actually serving.
        assertEquals(
            Verdict.SUPERSEDED,
            ActiveConfigApply.classify(
                request = request,
                rpcSucceeded = false,
                receipt = receipt(success = false),
                currentRevision = 12,
                currentGeneration = 43,
                appliedRevision = 12,
            ),
        )
    }

    @Test
    fun `no receipt and a failed RPC is unconfirmed, never a failure to roll back`() {
        assertEquals(
            Verdict.UNCONFIRMED,
            ActiveConfigApply.classify(
                request = request,
                rpcSucceeded = false,
                receipt = null,
                currentRevision = 12,
                currentGeneration = 40,
                appliedRevision = -1,
            ),
        )
    }

    @Test
    fun `a returned RPC without our receipt is unconfirmed, not applied`() {
        assertEquals(
            Verdict.UNCONFIRMED,
            ActiveConfigApply.classify(
                request = request,
                rpcSucceeded = true,
                receipt = null,
                currentRevision = 12,
                currentGeneration = 40,
                appliedRevision = -1,
            ),
        )
        // A revision that moved meanwhile is still superseded: the verdict cannot be about us.
        assertEquals(
            Verdict.SUPERSEDED,
            ActiveConfigApply.classify(
                request = request,
                rpcSucceeded = true,
                receipt = null,
                currentRevision = 13,
                currentGeneration = 41,
                appliedRevision = -1,
            ),
        )
    }
}
