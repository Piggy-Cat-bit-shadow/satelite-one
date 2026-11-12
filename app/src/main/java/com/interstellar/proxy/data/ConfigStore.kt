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

    /**
     * Test/embedding seam: run the store against [root] instead of the application files dir.
     *
     * The real store is process-wide Android state, but the invariants this class owns — "bytes and
     * revision are one snapshot" and "restore only while the store is still at the failed revision" —
     * are exactly the ones a late failure used to break. They need a JVM test that can commit two
     * writes from different threads, which is what this seam enables.
     */
    @Volatile
    private var rootOverride: File? = null

    internal fun useRootForTest(root: File?) {
        synchronized(this) {
            rootOverride = root
            meta = null
        }
    }

    private fun root(): File = rootOverride ?: InterstellarApplication.application.filesDir

    val activeFile: File
        get() = File(root(), ACTIVE_CONFIG)

    private val metaFile: File
        get() = File(root(), ACTIVE_META)

    /** Identity of the bytes on disk: which subscription they belong to and which write they are. */
    @Serializable
    data class Meta(
        val ownerSubscriptionId: String? = null,
        val revision: Long = 0,
        val sha256: String = "",
        /**
         * Whether these bytes were verified at the moment they were written.
         *
         * `false` means the store knowingly wrote bytes it cannot attest (the raw they came from is
         * no longer verified). `null` is a record from an older build; the start gate treats it as
         * legacy and keeps the old behaviour rather than bricking an upgrade.
         */
        val verified: Boolean? = null,
    )

    /** Config bytes and the identity of exactly those bytes. */
    data class Snapshot(
        val content: String?,
        val meta: Meta,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Volatile
    private var meta: Meta? = null

    @Synchronized
    fun readActiveConfig(): String? {
        val file = activeFile
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * The config bytes and the revision that identifies them, read under one lock.
     *
     * Reading them separately is what lets a writer slip between the two reads: the reader then
     * applies the old bytes while recording the new revision (or serves a revision that does not
     * describe the bytes it ran). Every consumer that needs both — the Box start/reload path and the
     * receipt it records — must take this snapshot instead.
     */
    @Synchronized
    fun snapshot(): Snapshot {
        val content = activeFile.takeIf { it.isFile }?.let { file ->
            runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        val current = readMeta()
        // A metadata file without bytes (or a torn write) is not an identity.
        return Snapshot(content, if (content == null) Meta() else current)
    }

    /** Owner of the active config, or null when nothing usable is on disk. */
    @Synchronized
    fun readActiveConfigOwner(): String? = readMeta().ownerSubscriptionId

    /** Monotonic write generation of the active config. */
    @Synchronized
    fun activeConfigRevision(): Long = readMeta().revision

    @Synchronized
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
    fun writeActiveConfig(content: String, ownerSubscriptionId: String? = null, verified: Boolean = true): Long {
        val previous = readMeta()
        val sameBytes = previous.sha256.isNotEmpty() && previous.sha256 == sha256(content)
        val sameOwner = ownerSubscriptionId == null || ownerSubscriptionId == previous.ownerSubscriptionId
        if (sameBytes && sameOwner && activeFile.isFile) {
            // The bytes are unchanged, but the verification verdict may have moved (a recovery that
            // failed after the write, a raw that was re-verified). Recording it is not a content
            // commit, so the revision stays where it is.
            if (previous.verified != verified) {
                val next = previous.copy(verified = verified)
                meta = next
                runCatching { metaFile.writeText(json.encodeToString(next)) }
            }
            return previous.revision
        }
        val owner = ownerSubscriptionId ?: previous.ownerSubscriptionId
        activeFile.parentFile?.mkdirs()
        activeFile.writeText(content)
        val next = Meta(ownerSubscriptionId = owner, revision = previous.revision + 1, sha256 = sha256(content), verified = verified)
        meta = next
        runCatching { metaFile.writeText(json.encodeToString(next)) }
        return next.revision
    }

    /**
     * Puts [content] back only while the store is still at [expectedRevision].
     *
     * This is the compare-and-swap a failed reload needs. Checking the revision and then calling
     * [writeActiveConfig] is two operations: a newer commit (C) can land between the check and the
     * write, and the failure of the older attempt (B) then rolls C back. Here both happen under one
     * lock, so B has no way to overwrite a write it no longer owns.
     *
     * @return the restoring revision, or null when a newer write owns the store (nothing written).
     */
    @Synchronized
    fun restoreIfRevision(
        expectedRevision: Long,
        content: String,
        ownerSubscriptionId: String?,
        verified: Boolean = true,
    ): Long? {
        val previous = readMeta()
        if (previous.revision != expectedRevision) return null
        if (!activeFile.isFile) return null
        val next = Meta(
            ownerSubscriptionId = ownerSubscriptionId,
            revision = previous.revision + 1,
            sha256 = sha256(content),
            verified = verified,
        )
        activeFile.writeText(content)
        meta = next
        runCatching { metaFile.writeText(json.encodeToString(next)) }
        return next.revision
    }

    /**
     * Records whether the bytes already on disk are verified, without touching them or their
     * revision.
     *
     * A refused raw is the case this exists for: the previous config may still be serving, but if
     * the raw it came from can no longer be verified, the next cold start must not present those
     * bytes as a checked configuration. The bytes stay for the running Box; the marker makes them
     * unstartable until the repository regenerates from verified inputs.
     */
    @Synchronized
    fun markActiveVerification(verified: Boolean) {
        val previous = readMeta()
        if (previous.verified == verified) return
        val next = previous.copy(verified = verified)
        meta = next
        runCatching { metaFile.writeText(json.encodeToString(next)) }
    }

    /** Drop the active config (all subscriptions removed — nothing to run). */    @Synchronized
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

    @Synchronized
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
