package com.interstellar.proxy.data.subscription

import com.interstellar.proxy.data.SubscriptionRepository
import org.junit.Test

/** Success accounting must follow the commit flag, never the presence of a message. */
class RefreshOutcomeTest {

    private fun commit(committed: Boolean, message: String? = null) =
        SubscriptionRepository.RefreshCommit(message = message, rawBody = null, committed = committed)

    @Test
    fun `a committed single refresh is a success`() {
        val outcome = RefreshOutcome.single(commit(committed = true, message = "updated"))
        check(outcome.updated)
        check(outcome.error == null)
    }

    @Test
    fun `a persistence failure is not a single-refresh success`() {
        val outcome = RefreshOutcome.single(
            commit(committed = false, message = "could not persist"),
        )
        check(!outcome.updated)
        check(outcome.error == "could not persist")
    }

    @Test
    fun `a missing result is not a success`() {
        check(!RefreshOutcome.single(null).updated)
    }

    @Test
    fun `batch counts only committed refreshes`() {
        val outcome = RefreshOutcome.batch(
            listOf(
                commit(committed = true),
                commit(committed = false, message = "refused"),
                null,
                commit(committed = true),
            ),
        )
        check(outcome.succeeded == 2)
        check(outcome.errors == listOf("refused"))
    }

    @Test
    fun `persistence failures are reported but not counted`() {
        val outcome = RefreshOutcome.batch(
            listOf(
                commit(committed = false, message = "could not persist"),
                commit(committed = true),
            ),
        )
        check(outcome.succeeded == 1)
        check(outcome.errors == listOf("could not persist"))
    }
}
