package com.interstellar.proxy.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One provider entry of `CommandClient.refreshRemoteProviders`.
 *
 * The wire shape is PP01's `ProviderRefreshResult`: `status` is one of
 * `published`, `unchanged`, `failed`, `skipped`, `busy`, `cancelled`.
 */
@Serializable
data class ProviderRefreshResult(
    val tag: String = "",
    val status: String = "",
    val reason: String = "",
)

/** The `ProviderRefreshSummary` JSON returned by libbox. */
@Serializable
data class ProviderRefreshSummary(
    val results: List<ProviderRefreshResult> = emptyList(),
    val cancelled: Boolean = false,
    val boxChanged: Boolean = false,
)

data class ProviderRefreshCounts(
    val published: Int = 0,
    val unchanged: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val busy: Int = 0,
    /** Every reported entry, including statuses this client does not know. */
    val total: Int = 0,
)

/**
 * Parses and summarizes the manual provider refresh report.
 *
 * The parser is deliberately tolerant: an unknown status still counts toward
 * [ProviderRefreshCounts.total], so a newer core cannot make the UI claim "no
 * providers" by adding a status this client does not know yet.
 */
object ProviderRefresh {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(value: String?): ProviderRefreshSummary? {
        if (value.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<ProviderRefreshSummary>(value) }.getOrNull()
    }

    fun counts(summary: ProviderRefreshSummary): ProviderRefreshCounts {
        val byStatus = summary.results.groupingBy { it.status }.eachCount()
        return ProviderRefreshCounts(
            published = byStatus["published"] ?: 0,
            unchanged = byStatus["unchanged"] ?: 0,
            failed = byStatus["failed"] ?: 0,
            skipped = byStatus["skipped"] ?: 0,
            busy = byStatus["busy"] ?: 0,
            total = summary.results.size,
        )
    }
}
