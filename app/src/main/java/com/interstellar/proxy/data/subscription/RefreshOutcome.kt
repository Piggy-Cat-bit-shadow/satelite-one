package com.interstellar.proxy.data.subscription

import com.interstellar.proxy.data.SubscriptionRepository

/**
 * Maps the repository's [SubscriptionRepository.RefreshCommit] results onto the
 * UI's success accounting. A result that did not commit (validation refusal,
 * persistence failure, accounting-only update) is never counted as success.
 *
 * Pure so the single and batch rules can be tested without an Android runtime.
 */
object RefreshOutcome {
    data class Single(val updated: Boolean, val error: String?)

    data class Batch(val succeeded: Int, val errors: List<String>)

    fun single(commit: SubscriptionRepository.RefreshCommit?): Single = when {
        commit == null -> Single(updated = false, error = null)
        commit.committed -> Single(updated = true, error = null)
        else -> Single(updated = false, error = commit.message)
    }

    fun batch(commits: List<SubscriptionRepository.RefreshCommit?>): Batch {
        val errors = commits.mapNotNull { commit ->
            if (commit == null || commit.committed) null else commit.message
        }
        return Batch(
            succeeded = commits.count { it?.committed == true },
            errors = errors,
        )
    }
}
