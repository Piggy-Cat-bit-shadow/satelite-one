package com.interstellar.proxy.data

import com.interstellar.proxy.InterstellarApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * The active sing-box configuration, plus the identity of what it belongs to.
 *
 * A config file alone cannot answer "whose config is this?". That question is what the
 * subscription layer needs to answer when a fetched body is refused: the previous config may
 * still be serving, but only if it belongs to the subscription the user is now pointing at.
 * Handing one subscription's last-good config to another is exactly how the UI, the file and the
 * running Box end up naming different profiles, so the owner and a monotonic revision are stored
 * next to the bytes instead of being inferred.
 *
 * The revision only advances when the bytes or the owner actually change. Callers use it as the
 * generation of the active config: "cancel this result if the config moved past the revision my
 * operation committed".
 */
object ConfigStore {
    private const val ACTIVE_CONFIG = "active.json"
    private const val ACTIVE_META = "active.meta.json"

    val activeFile: File
        get() = File(InterstellarApplication.application.filesDir, ACTIVE_CONFIG)

    private val metaFile: File
        get() = File(InterstellarApplication.application.filesDir, ACTIVE_META)

    /** Identity of the bytes on disk: which subscription they belong to and which write they are. */
    @Serializable
    data class Meta(
        val ownerSubscriptionId: String? = null,
        val revision: Long = 0,
        val sha256: String = "",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Volatile
    private var meta: Meta? = null

    fun readActiveConfig(): String? {
        val file = activeFile
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Owner of the active config, or null when nothing usable is on disk. */
    fun readActiveConfigOwner(): String? = readMeta().ownerSubscriptionId

    /** Monotonic write generation of the active config. */
    fun activeConfigRevision(): Long = readMeta().revision

    fun readActiveConfigMeta(): Meta = readMeta()

    /**
     * Writes [content] and records [ownerSubscriptionId] as the subscription it belongs to.
     *
     * Byte-identical content under the same owner is not a new generation: returning the previous
     * revision keeps "the config changed" meaningful for reload decisions and for every in-flight
     * operation that compares revisions. [ownerSubscriptionId] null keeps the current owner, which
     * is only correct for callers that are restoring bytes they read from this store.
     *
     * @return the revision the store is now at.
     */
    @Synchronized
    fun writeActiveConfig(content: String, ownerSubscriptionId: String? = null): Long {
        val previous = readMeta()
        val sameBytes = previous.sha256.isNotEmpty() && previous.sha256 == sha256(content)
        val sameOwner = ownerSubscriptionId == null || ownerSubscriptionId == previous.ownerSubscriptionId
        if (sameBytes && sameOwner && activeFile.isFile) {
            return previous.revision
        }
        val owner = ownerSubscriptionId ?: previous.ownerSubscriptionId
        activeFile.parentFile?.mkdirs()
        activeFile.writeText(content)
        val next = Meta(ownerSubscriptionId = owner, revision = previous.revision + 1, sha256 = sha256(content))
        meta = next
        runCatching { metaFile.writeText(json.encodeToString(next)) }
        return next.revision
    }

    /** Drop the active config (all subscriptions removed — nothing to run). */
    @Synchronized
    fun clear() {
        runCatching { activeFile.delete() }
        runCatching { metaFile.delete() }
        meta = Meta()
    }

    /** Stable digest of a config document, for ownership/identity checks in reports. */
    fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun readMeta(): Meta {
        meta?.let { return it }
        val loaded = runCatching {
            if (metaFile.isFile) json.decodeFromString<Meta>(metaFile.readText()) else Meta()
        }.getOrDefault(Meta())
        // A metadata file from an older build (or a torn write) must not claim an owner it never
        // recorded: with no bytes on disk the identity is simply empty.
        val resolved = if (activeFile.isFile) loaded else Meta()
        meta = resolved
        return resolved
    }
}
