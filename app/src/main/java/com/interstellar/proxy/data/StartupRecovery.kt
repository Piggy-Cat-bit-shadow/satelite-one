package com.interstellar.proxy.data

import com.interstellar.proxy.InterstellarApplication
import java.io.File
import java.util.Properties
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The last configuration that actually served before an offline worker replaced it.
 *
 * The worker updates subscriptions while the tunnel is off. The fetch is committed to the profile,
 * but the new document is not tried until the next `Start proxy`; if the core rejects it at startup,
 * the old configuration and the exact revision that served it are the only safe thing to go back
 * to. The journal is written *before* the offline refresh, so it names the previous document even
 * when the process dies during the commit.
 *
 * It is deliberately per active subscription and contains only one previous generation. A second
 * offline update of the same subscription keeps the first previous document: replacing it with the
 * bytes that just failed to start would turn "last good" into "last attempted".
 *
 * Both shapes are represented:
 * - a raw body is restored through the repository's raw CAS;
 * - a node-list profile is restored through the subscription-content CAS (nodes + format), while
 *   quota/name/url metadata is left alone.
 */
data class StartupCandidate(
    val subscriptionId: String,
    val previousRawBody: String?,
    val previousConfig: String,
    val previousOwner: String?,
    val previousRevision: Long,
    val previousContent: SubscriptionRepository.SubscriptionContent? = null,
    val candidateContent: SubscriptionRepository.SubscriptionContent? = null,
)

interface StartupRecoveryStorage {
    fun readJournal(): String?
    fun writeJournal(text: String)
    fun clearJournal()
    fun snapshot(): ConfigStore.Snapshot
    fun readRawBody(subscriptionId: String): String?
    fun restoreRawBodyIfCurrent(subscriptionId: String, currentBody: String?, previousBody: String?): Boolean
    fun restoreConfigIfRevision(expectedRevision: Long, content: String, ownerSubscriptionId: String?): Long?
    fun readSubscriptionContent(subscriptionId: String): SubscriptionRepository.SubscriptionContent?
    fun restoreSubscriptionContentIfCurrent(
        subscriptionId: String,
        expected: SubscriptionRepository.SubscriptionContent,
        previous: SubscriptionRepository.SubscriptionContent,
    ): Boolean
}

object AndroidStartupRecoveryStorage : StartupRecoveryStorage {
    private val journalFile: File
        get() = File(InterstellarApplication.application.filesDir, "startup_last_good.properties")

    override fun readJournal(): String? =
        journalFile.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    override fun writeJournal(text: String) {
        journalFile.parentFile?.mkdirs()
        journalFile.writeText(text)
    }

    override fun clearJournal() {
        runCatching { journalFile.delete() }
    }

    override fun snapshot(): ConfigStore.Snapshot = ConfigStore.snapshot()

    override fun readRawBody(subscriptionId: String): String? =
        SubscriptionRepository.readRawBody(subscriptionId)

    override fun restoreRawBodyIfCurrent(
        subscriptionId: String,
        currentBody: String?,
        previousBody: String?,
    ): Boolean = SubscriptionRepository.restoreRawBodyIfCurrent(subscriptionId, currentBody, previousBody)

    override fun restoreConfigIfRevision(
        expectedRevision: Long,
        content: String,
        ownerSubscriptionId: String?,
    ): Long? = ConfigStore.restoreIfRevision(expectedRevision, content, ownerSubscriptionId)

    override fun readSubscriptionContent(
        subscriptionId: String,
    ): SubscriptionRepository.SubscriptionContent? =
        SubscriptionRepository.readSubscriptionContent(subscriptionId)

    override fun restoreSubscriptionContentIfCurrent(
        subscriptionId: String,
        expected: SubscriptionRepository.SubscriptionContent,
        previous: SubscriptionRepository.SubscriptionContent,
    ): Boolean = SubscriptionRepository.restoreSubscriptionContentIfCurrent(
        subscriptionId,
        expected,
        previous,
    )
}

object StartupRecovery {
    private val journalJson = Json { ignoreUnknownKeys = true }

    sealed interface Result {
        /** The previous config and content are back; start again from [revision]. */
        data class Restored(val revision: Long) : Result

        /** There was no journal, or the candidate never changed the retained document. */
        data object Nothing : Result

        /** A newer revision/owner/content owns the state; nothing was written. */
        data object Superseded : Result
    }

    /**
     * Records the document that is serving now, before an offline refresh replaces it.
     *
     * Returns false when the active config does not belong to [subscriptionId]: there is no
     * same-subscription last-good to restore, and a startup failure must be reported rather than
     * silently falling back to another profile. A null [previousRawBody] is legitimate for a
     * node-list subscription; its nodes/format are journaled separately.
     */
    fun rememberBeforeOfflineRefresh(
        subscriptionId: String,
        previousRawBody: String?,
        snapshot: ConfigStore.Snapshot,
        storage: StartupRecoveryStorage = AndroidStartupRecoveryStorage,
    ): Boolean {
        if (snapshot.content == null) return false
        if (snapshot.meta.ownerSubscriptionId != subscriptionId) return false
        // Keep the oldest known good generation while an unconfirmed candidate is still pending.
        if (storage.readJournal() != null) return true
        val previousContent = storage.readSubscriptionContent(subscriptionId)
            ?: SubscriptionRepository.SubscriptionContent()
        val properties = Properties()
        properties["subscriptionId"] = subscriptionId
        previousRawBody?.let { properties["previousRawBody"] = toHex(it.toByteArray()) }
        properties["previousConfig"] = toHex(snapshot.content.toByteArray())
        properties["previousOwner"] = snapshot.meta.ownerSubscriptionId ?: ""
        properties["previousRevision"] = snapshot.meta.revision.toString()
        properties["previousContent"] = journalJson.encodeToString(previousContent)
        storage.writeJournal(propertiesText(properties))
        return true
    }

    /**
     * Records the candidate content after the offline fetch, without touching what serves.
     *
     * The previous generation is already in the journal. The candidate is what the next start may
     * reject; restore only applies it back to the previous nodes/format after checking that the
     * current content is still exactly this candidate.
     */
    fun rememberCandidate(
        subscriptionId: String,
        storage: StartupRecoveryStorage = AndroidStartupRecoveryStorage,
    ): Boolean {
        val candidate = read(storage.readJournal()) ?: return false
        if (candidate.subscriptionId != subscriptionId) return false
        val content = storage.readSubscriptionContent(subscriptionId) ?: return false
        write(candidate.copy(candidateContent = content), storage)
        return true
    }

    /**
     * Restores the journaled document if [candidateRevision] is still the failed candidate.
     *
     * Config CAS runs first. A raw body and a node-list content are then restored through their own
     * CAS; if either loses, the already-written pieces are undone so the newer winner is never
     * overwritten. The journal is consumed on every attempt so a bad candidate cannot restart the
     * service more than once.
     */
    fun restore(
        candidateRevision: Long,
        candidateOwner: String?,
        storage: StartupRecoveryStorage = AndroidStartupRecoveryStorage,
    ): Result {
        val candidate = read(storage.readJournal()) ?: return Result.Nothing
        val before = storage.snapshot()
        if (before.meta.revision != candidateRevision || before.meta.ownerSubscriptionId != candidateOwner) {
            storage.clearJournal()
            return Result.Superseded
        }
        val currentConfig = before.content ?: run {
            storage.clearJournal()
            return Result.Nothing
        }
        if (before.meta.ownerSubscriptionId != candidate.subscriptionId) {
            storage.clearJournal()
            return Result.Superseded
        }
        val currentRaw = storage.readRawBody(candidate.subscriptionId)
        val currentContent = storage.readSubscriptionContent(candidate.subscriptionId)
        val rawChanged = currentRaw != candidate.previousRawBody
        val contentChanged = candidate.previousRawBody == null &&
            candidate.previousContent != null &&
            candidate.candidateContent != null &&
            currentContent == candidate.candidateContent &&
            candidate.previousContent != candidate.candidateContent
        val contentMovedAway = candidate.previousRawBody == null &&
            candidate.candidateContent != null &&
            currentContent != null &&
            currentContent != candidate.candidateContent
        if (!rawChanged && !contentChanged) {
            // The offline refresh did not change the retained document (or another operation
            // already restored it); this failure is not the candidate's.
            storage.clearJournal()
            return if (contentMovedAway) Result.Superseded else Result.Nothing
        }
        val restoredRevision = storage.restoreConfigIfRevision(
            expectedRevision = candidateRevision,
            content = candidate.previousConfig,
            ownerSubscriptionId = candidate.previousOwner,
        ) ?: run {
            storage.clearJournal()
            return Result.Superseded
        }
        if (rawChanged) {
            val rawRestored = storage.restoreRawBodyIfCurrent(
                subscriptionId = candidate.subscriptionId,
                currentBody = currentRaw,
                previousBody = candidate.previousRawBody,
            )
            if (!rawRestored) {
                storage.restoreConfigIfRevision(restoredRevision, currentConfig, candidateOwner)
                storage.clearJournal()
                return Result.Superseded
            }
        }
        if (contentChanged) {
            val contentRestored = storage.restoreSubscriptionContentIfCurrent(
                subscriptionId = candidate.subscriptionId,
                expected = candidate.candidateContent,
                previous = candidate.previousContent,
            )
            if (!contentRestored) {
                if (rawChanged) {
                    storage.restoreRawBodyIfCurrent(
                        subscriptionId = candidate.subscriptionId,
                        currentBody = candidate.previousRawBody,
                        previousBody = currentRaw,
                    )
                }
                storage.restoreConfigIfRevision(restoredRevision, currentConfig, candidateOwner)
                storage.clearJournal()
                return Result.Superseded
            }
        }
        storage.clearJournal()
        return Result.Restored(restoredRevision)
    }

    /** Clears the journal once a start from the current bytes has succeeded. */
    fun confirmStarted(ownerSubscriptionId: String?, storage: StartupRecoveryStorage = AndroidStartupRecoveryStorage) {
        val candidate = read(storage.readJournal()) ?: return
        if (candidate.subscriptionId == ownerSubscriptionId) storage.clearJournal()
    }

    /** Drops an attempt that lost ownership before it could record a candidate. */
    fun discard(storage: StartupRecoveryStorage = AndroidStartupRecoveryStorage) {
        storage.clearJournal()
    }

    private fun write(candidate: StartupCandidate, storage: StartupRecoveryStorage) {
        val properties = Properties()
        properties["subscriptionId"] = candidate.subscriptionId
        candidate.previousRawBody?.let { properties["previousRawBody"] = toHex(it.toByteArray()) }
        properties["previousConfig"] = toHex(candidate.previousConfig.toByteArray())
        properties["previousOwner"] = candidate.previousOwner ?: ""
        properties["previousRevision"] = candidate.previousRevision.toString()
        candidate.previousContent?.let { properties["previousContent"] = journalJson.encodeToString(it) }
        candidate.candidateContent?.let { properties["candidateContent"] = journalJson.encodeToString(it) }
        storage.writeJournal(propertiesText(properties))
    }

    private fun propertiesText(properties: Properties): String =
        java.io.StringWriter().also { properties.store(it, "startup last good") }.toString()

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun fromHex(text: String): ByteArray {
        require(text.length % 2 == 0) { "hex body has an odd length" }
        return ByteArray(text.length / 2) { index ->
            text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun read(text: String?): StartupCandidate? {
        if (text == null) return null
        return runCatching {
            val properties = Properties()
            properties.load(text.reader())
            StartupCandidate(
                subscriptionId = properties.getProperty("subscriptionId") ?: return null,
                previousRawBody = properties.getProperty("previousRawBody")?.let {
                    String(fromHex(it))
                },
                previousConfig = String(fromHex(properties.getProperty("previousConfig"))),
                previousOwner = properties.getProperty("previousOwner")?.takeIf { it.isNotEmpty() },
                previousRevision = properties.getProperty("previousRevision")?.toLong() ?: 0L,
                previousContent = properties.getProperty("previousContent")?.let {
                    journalJson.decodeFromString(it)
                },
                candidateContent = properties.getProperty("candidateContent")?.let {
                    journalJson.decodeFromString(it)
                },
            )
        }.getOrNull()
    }
}
