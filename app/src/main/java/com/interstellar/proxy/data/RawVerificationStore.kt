package com.interstellar.proxy.data

import com.interstellar.proxy.InterstellarApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * Durable proof of which retained raw body a subscription's metadata describes.
 *
 * # The gap this closes
 *
 * A subscription's retained `.raw` file used to be trusted from two things that are not durable
 * identity: `subscriptions.json.configFormat == "singbox"` (written in a separate, unchecked step
 * from the raw bytes) and a fresh `Libbox.checkConfig` on whatever the file holds now. Neither can
 * tell "this is the body that was committed for this profile" from "this is a structurally valid
 * body that replaced it". On restart the raw could therefore be installed and started as the old
 * profile's configuration even though the commit that wrote it never finished, or a recovery that
 * failed had deliberately left it in place.
 *
 * # What is stored
 *
 * One record per subscription, in `filesDir/raw_verification.json`:
 *
 *   - `sha256`: the digest of the raw bytes the record describes;
 *   - `format`: the detected wire format at commit time;
 *   - `state`: `VERIFIED` (metadata now describes these bytes), `PENDING` (bytes written, metadata
 *     not committed yet - never usable), or `UNVERIFIED` (a tombstone: a recovery failed, a delete
 *     did not complete, or the metadata no longer matches);
 *   - `reason`: why an unverified record exists, for the report and for logs.
 *
 * A record is written through a temp file + fsync + rename, so a process death cannot leave a
 * half-written record that decodes as VERIFIED. A corrupt or missing file is "nothing is verified",
 * never "everything is fine": the reader returns no record, and every consumer treats that as
 * unverified.
 */
object RawVerificationStore {
    private const val FILE_NAME = "raw_verification.json"
    const val STATE_VERIFIED = "VERIFIED"
    const val STATE_PENDING = "PENDING"
    const val STATE_UNVERIFIED = "UNVERIFIED"

    @Serializable
    data class Record(
        val sha256: String = "",
        val format: String? = null,
        val state: String = STATE_UNVERIFIED,
        val reason: String? = null,
        val updatedAt: Long = 0,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val lock = Any()

    /** Test/embedding seam; null means the application files directory. */
    @Volatile
    var fileOverride: File? = null

    private var loaded = false
    private val records = linkedMapOf<String, Record>()

    fun file(): File = fileOverride ?: File(InterstellarApplication.application.filesDir, FILE_NAME)

    /** Test seam: point the store at [file] and drop anything cached. */
    fun useFileForTest(file: File?) {
        synchronized(lock) {
            fileOverride = file
            loaded = false
            records.clear()
        }
    }

    fun record(subscriptionId: String): Record? = synchronized(lock) {
        ensureLoaded()
        records[subscriptionId]
    }

    /** Bytes written for [subscriptionId], metadata not committed yet: not usable. */
    fun markPending(subscriptionId: String, sha256: String, format: String?) {
        put(subscriptionId, Record(sha256 = sha256, format = format, state = STATE_PENDING, updatedAt = now()))
    }

    /**
     * Marks the bytes verified only while the stored record still describes the same digest.
     *
     * A metadata save that lands after another commit must not bless bytes it did not write: the
     * comparison is the same test-and-set the raw file itself uses.
     */
    fun markVerifiedIfCurrent(subscriptionId: String, sha256: String, format: String?): Boolean = synchronized(lock) {
        ensureLoaded()
        val current = records[subscriptionId]
        if (current != null && current.sha256.isNotEmpty() && current.sha256 != sha256) {
            return@synchronized false
        }
        records[subscriptionId] = Record(
            sha256 = sha256,
            format = format,
            state = STATE_VERIFIED,
            updatedAt = now(),
        )
        persist()
        true
    }

    /**
     * Marks bytes verified unconditionally.
     *
     * Only a restore of a body this process itself knows (the previous body of a failed update)
     * may use this: the caller is replaying a commit, so the digest it names is the one it wrote.
     */
    fun markVerified(subscriptionId: String, sha256: String, format: String?) {
        put(
            subscriptionId,
            Record(sha256 = sha256, format = format, state = STATE_VERIFIED, updatedAt = now()),
        )
    }

    /** A tombstone: the raw must not be applied as this subscription's configuration. */
    fun markUnverified(subscriptionId: String, sha256: String?, reason: String) {
        put(
            subscriptionId,
            Record(
                sha256 = sha256.orEmpty(),
                state = STATE_UNVERIFIED,
                reason = reason,
                updatedAt = now(),
            ),
        )
    }

    fun remove(subscriptionId: String) {
        synchronized(lock) {
            ensureLoaded()
            if (records.remove(subscriptionId) != null) {
                persist()
            }
        }
    }

    /** True only when the record is VERIFIED and describes exactly [sha256]. */
    fun isVerified(subscriptionId: String, sha256: String): Boolean = synchronized(lock) {
        ensureLoaded()
        val current = records[subscriptionId] ?: return@synchronized false
        current.state == STATE_VERIFIED && current.sha256.isNotEmpty() && current.sha256 == sha256
    }

    private fun put(subscriptionId: String, record: Record) {
        synchronized(lock) {
            ensureLoaded()
            records[subscriptionId] = record
            persist()
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        records.clear()
        val file = file()
        val decoded = runCatching {
            if (file.isFile) json.decodeFromString<Map<String, Record>>(file.readText()) else emptyMap()
        }.getOrDefault(emptyMap())
        records.putAll(decoded)
        loaded = true
    }

    /**
     * Writes the whole map through a temp file, fsync and rename.
     *
     * The rename is checked: a false return means the previous record file is still what a restart
     * reads, so the caller must treat the commit as not recorded. There is no fallback write to the
     * final path - a direct write is exactly the torn-record case this exists to avoid.
     */
    private fun persist(): Boolean {
        val file = file()
        val parent = file.parentFile ?: return false
        parent.mkdirs()
        val temp = File(parent, "${file.name}.tmp")
        return runCatching {
            FileOutputStream(temp).use { stream ->
                stream.write(json.encodeToString(records.toMap()).toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            if (temp.renameTo(file)) {
                true
            } else {
                // Not reached on the Android filesystem, where rename replaces atomically and is
                // the whole point of the temp file. A host that refuses to replace an existing
                // target (Windows) gets a two-step fallback so the unit tests exercise the same
                // persist/restart path; the caller is told the truth if even that fails.
                if (file.delete() && temp.renameTo(file)) {
                    true
                } else {
                    temp.delete()
                    false
                }
            }
        }.getOrDefault(false)
    }

    private fun now(): Long = System.currentTimeMillis()
}
