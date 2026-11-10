package com.interstellar.proxy.data

import java.io.File
import java.util.Properties

import com.interstellar.proxy.InterstellarApplication

/**
 * The last configuration that actually served before an offline worker replaced its raw body.
 *
 * The worker updates subscriptions while the tunnel is off. The fetch is committed to the profile,
 * but the new bytes are not tried until the next `Start proxy`; if the core rejects them at startup,
 * the old bytes and the exact config revision that served them are the only safe thing to go back
 * to. The journal is written *before* the offline refresh, so it names the previous document even
 * when the process dies during the commit.
 *
 * It is deliberately per active subscription and contains only one previous generation. A second
 * offline update of the same subscription keeps the first previous document: replacing it with the
 * bytes that just failed to start would turn "last good" into "last attempted".
 */
data class StartupCandidate(
    val subscriptionId: String,
    val previousRawBody: String?,
    val previousConfig: String,
    val previousOwner: String?,
    val previousRevision: Long,
)

interface StartupRecoveryStorage {
    fun readJournal(): String?
    fun writeJournal(text: String)
    fun clearJournal()
    fun snapshot(): ConfigStore.Snapshot
    fun readRawBody(subscriptionId: String): String?
    fun restoreRawBodyIfCurrent(subscriptionId: String, currentBody: String?, previousBody: String?): Boolean
    fun restoreConfigIfRevision(expectedRevision: Long, content: String, ownerSubscriptionId: String?): Long?
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
}

object StartupRecovery {
    sealed interface Result {
        /** The previous config and raw body are back; start again from [revision]. */
        data class Restored(val revision: Long) : Result

        /** There was no journal, or the candidate never changed the raw body. */
        data object Nothing : Result

        /** A newer revision/owner or raw body owns the state; nothing was written. */
        data object Superseded : Result
    }

    /**
     * Records the document that is serving now, before an offline refresh replaces it.
     *
     * Returns false when there is no previous raw body or the active config does not belong to
     * [subscriptionId]: in that case there is no same-subscription last-good to restore, and a
     * startup failure must be reported rather than silently falling back to another profile.
     */
    fun rememberBeforeOfflineRefresh(
        subscriptionId: String,
        previousRawBody: String?,
        snapshot: ConfigStore.Snapshot,
        storage: StartupRecoveryStorage = AndroidStartupRecoveryStorage,
    ): Boolean {
        if (previousRawBody == null || snapshot.content == null) return false
        if (snapshot.meta.ownerSubscriptionId != subscriptionId) return false
        // Keep the oldest known good generation while an unconfirmed candidate is still pending.
        if (storage.readJournal() != null) return true
        val properties = Properties()
        properties["subscriptionId"] = subscriptionId
        properties["previousRawBody"] = toHex(previousRawBody.toByteArray())
        properties["previousConfig"] = toHex(snapshot.content.toByteArray())
        properties["previousOwner"] = snapshot.meta.ownerSubscriptionId ?: ""
        properties["previousRevision"] = snapshot.meta.revision.toString()
        val text = java.io.StringWriter().also { properties.store(it, "startup last good") }.toString()
        storage.writeJournal(text)
        return true
    }

    /**
     * Restores the journaled document if [candidateRevision] is still the failed candidate.
     *
     * The config CAS runs before the raw CAS, exactly like the reload recovery. If the raw body was
     * replaced by a newer operation in between, the config restore is undone and the result is
     * [Result.Superseded]; the newer raw is never overwritten. The journal is consumed on every
     * attempt so a bad candidate cannot restart the service more than once.
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
        val currentContent = before.content ?: run {
            storage.clearJournal()
            return Result.Nothing
        }
        if (before.meta.ownerSubscriptionId != candidate.subscriptionId) {
            storage.clearJournal()
            return Result.Superseded
        }
        val currentRaw = storage.readRawBody(candidate.subscriptionId)
        if (currentRaw == candidate.previousRawBody) {
            // The offline refresh did not change the body (or another operation already restored
            // it); this failure is not the candidate's and must be reported as-is.
            storage.clearJournal()
            return Result.Nothing
        }
        val restoredRevision = storage.restoreConfigIfRevision(
            expectedRevision = candidateRevision,
            content = candidate.previousConfig,
            ownerSubscriptionId = candidate.previousOwner,
        ) ?: run {
            storage.clearJournal()
            return Result.Superseded
        }
        val rawRestored = storage.restoreRawBodyIfCurrent(
            subscriptionId = candidate.subscriptionId,
            currentBody = currentRaw,
            previousBody = candidate.previousRawBody,
        )
        if (!rawRestored) {
            // A newer body won the CAS after the config was restored. Put the candidate config
            // back so the raw and config still describe the same generation; if that also lost,
            // report superseded and leave the newer owner untouched.
            storage.restoreConfigIfRevision(restoredRevision, currentContent, candidateOwner)
            storage.clearJournal()
            return Result.Superseded
        }
        storage.clearJournal()
        return Result.Restored(restoredRevision)
    }

    /** Clears the journal once a start from the current bytes has succeeded. */
    fun confirmStarted(ownerSubscriptionId: String?, storage: StartupRecoveryStorage = AndroidStartupRecoveryStorage) {
        val candidate = read(storage.readJournal()) ?: return
        if (candidate.subscriptionId == ownerSubscriptionId) storage.clearJournal()
    }

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
            )
        }.getOrNull()
    }
}
