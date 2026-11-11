package com.interstellar.proxy.data

import org.junit.Test

/** The disk-full case: restore failure must still remove/quarantine the new body. */
class RawRecoveryPolicyTest {

    @Test
    fun `a mismatched current body is superseded and left untouched`() {
        check(
            RawRecoveryPolicy.decide(
                currentMatches = false,
                restored = false,
                removed = false,
            ) == RawRestoreResult.SUPERSEDED,
        )
    }

    @Test
    fun `a successful restore wins`() {
        check(
            RawRecoveryPolicy.decide(
                currentMatches = true,
                restored = true,
                removed = false,
            ) == RawRestoreResult.RESTORED,
        )
    }

    @Test
    fun `a failed restore removes the unverified body`() {
        check(
            RawRecoveryPolicy.decide(
                currentMatches = true,
                restored = false,
                removed = true,
            ) == RawRestoreResult.BODY_REMOVED,
        )
    }

    @Test
    fun `a failed restore and a failed removal are reported as stuck`() {
        check(
            RawRecoveryPolicy.decide(
                currentMatches = true,
                restored = false,
                removed = false,
            ) == RawRestoreResult.BODY_STUCK,
        )
    }
}
