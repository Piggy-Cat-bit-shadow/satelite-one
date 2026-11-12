package com.interstellar.proxy.data

import com.interstellar.proxy.InterstellarApplication
import com.interstellar.proxy.data.config.MinimalConfigBuilder
import com.interstellar.proxy.data.model.ProxyNode
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * File-backed store for subscriptions, their nodes and the current selection.
 * Regenerates the active sing-box config on every mutation that can change it.
 */
object SubscriptionRepository {
    private const val TAG = "SubscriptionRepo"

    /** Localized string following the CURRENT language (live, no restart needed). */
    private fun str(id: Int): String =
        com.interstellar.proxy.ktx.AppLanguage.getString(InterstellarApplication.application, id)

    private fun str(id: Int, vararg formatArgs: Any): String =
        com.interstellar.proxy.ktx.AppLanguage.getString(InterstellarApplication.application, id, *formatArgs)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Serializable
    data class Subscription(
        val id: String,
        val name: String,
        val url: String? = null,
        val nodes: List<ProxyNode> = emptyList(),
        val uploadBytes: Long = 0,
        val downloadBytes: Long = 0,
        val totalBytes: Long = 0,
        val expireSeconds: Long = 0,
        val lastUpdated: Long = 0,
        /** Detected format of the retained raw body ("clash"/"singbox"/"xray"; null = plain node list). */
        val configFormat: String? = null,
    )

    /** The configuration-bearing part of a subscription, without quota/name/url metadata. */
    @Serializable
    data class SubscriptionContent(
        val nodes: List<ProxyNode> = emptyList(),
        val configFormat: String? = null,
    )

    private val contentLock = Any()

    fun readSubscriptionContent(subscriptionId: String): SubscriptionContent? = synchronized(contentLock) {
        get(subscriptionId)?.let { SubscriptionContent(it.nodes, it.configFormat) }
    }

    /**
     * Puts [previous] back only while the subscription's configuration content is still [expected].
     *
     * Quota, name and URL are deliberately not touched: an offline refresh updates those
     * independently, and reverting them would discard a newer accounting update. A concurrent
     * content commit makes the comparison fail, so the older operation never overwrites it.
     */
    fun restoreSubscriptionContentIfCurrent(
        subscriptionId: String,
        expected: SubscriptionContent,
        previous: SubscriptionContent,
    ): Boolean = synchronized(contentLock) {
        val index = subscriptions.indexOfFirst { it.id == subscriptionId }
        if (index < 0) return@synchronized false
        val current = subscriptions[index]
        if (current.nodes != expected.nodes || current.configFormat != expected.configFormat) {
            return@synchronized false
        }
        subscriptions[index] = current.copy(
            nodes = previous.nodes,
            configFormat = previous.configFormat,
        )
        if (!save()) {
            subscriptions[index] = current
            return@synchronized false
        }
        true
    }

    private val dir: File
        get() = File(InterstellarApplication.application.filesDir, "subscriptions").also { it.mkdirs() }

    /** Serializes raw-body reads/writes so a guarded restore cannot interleave a newer commit. */
    private val rawBodyLock = Any()

    /**
     * Raw bodies whose restore AND removal both failed. They must not be applied:
     * the metadata still describes the old body, while the file may hold the new
     * unverified one. This is in-process only; a restart after such a filesystem
     * failure needs the explicit check in the recovery report.
     */
    private val unverifiedRawIds: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val indexFile: File
        get() = File(InterstellarApplication.application.filesDir, "subscriptions.json")

    /** Retained raw subscription body on disk (kept when it is a full core config). */
    fun rawFileOf(subscriptionId: String): File = File(dir, "$subscriptionId.raw")

    /** Reads a subscription's retained raw body, or null when it has none / is unverified. */
    fun readRawBody(subscriptionId: String): String? = synchronized(rawBodyLock) {
        if (unverifiedRawIds.contains(subscriptionId)) return@synchronized null
        readRawBodyLocked(subscriptionId)
    }

    private fun readRawBodyLocked(subscriptionId: String): String? =
        runCatching { rawFileOf(subscriptionId).takeIf { it.isFile }?.readText() }.getOrNull()

    /** Puts a previously retained body back (used to undo a candidate the core refused at runtime). */
    fun restoreRawBody(subscriptionId: String, body: String?) {
        synchronized(rawBodyLock) { restoreRawBodyLocked(subscriptionId, body) }
    }

    /**
     * Puts [previousBody] back only while the retained body is still [currentBody].
     *
     * A late failure of an older update must not overwrite the body a newer update committed. The
     * comparison and the write share [rawBodyLock] with [commitFetchedBody], so a commit that lands
     * between them cannot be rolled back by the older attempt.
     *
     * When the restore write itself fails (disk full), the unverified new body is removed or
     * quarantined under the same lock, so it cannot be applied later under the old metadata.
     * [recoverRawBodyIfCurrent] exposes which of those happened.
     */
    fun restoreRawBodyIfCurrent(subscriptionId: String, currentBody: String?, previousBody: String?): Boolean =
        recoverRawBodyIfCurrent(subscriptionId, currentBody, previousBody) == RawRestoreResult.RESTORED

    /**
     * Restores [previousBody] and records what the verification store must say about the result.
     *
     * The body and its record move together: a restored body is VERIFIED against the exact bytes
     * this function wrote, a body it could not restore is UNVERIFIED with the digest of whatever is
     * still on disk, and a body that could neither be restored nor removed also leaves the
     * subscription blocked in memory, so neither the apply gate nor [isRawConfig] can reach it
     * before a restart.
     */
    fun recoverRawBodyIfCurrent(
        subscriptionId: String,
        currentBody: String?,
        previousBody: String?,
    ): RawRestoreResult = synchronized(rawBodyLock) {
        if (readRawBodyLocked(subscriptionId) != currentBody) {
            return@synchronized RawRestoreResult.SUPERSEDED
        }
        if (restoreRawBodyLocked(subscriptionId, previousBody)) {
            return@synchronized RawRestoreResult.RESTORED
        }
        val removed = removeRawBodyLocked(subscriptionId)
        val result = RawRecoveryPolicy.decide(
            currentMatches = true,
            restored = false,
            removed = removed,
        )
        if (result == RawRestoreResult.BODY_STUCK) unverifiedRawIds.add(subscriptionId)
        return@synchronized result
    }

    /**
     * Replays a known body: the bytes are written atomically, and the verification record names
     * exactly those bytes again.
     *
     * A write failure is a false return, never a silent success - a recovery that claims to have
     * restored a body it did not write is how an unverified raw becomes startable. The record is
     * tombstones for the bytes that are actually on disk, so a restart cannot disagree with it.
     */
    private fun restoreRawBodyLocked(subscriptionId: String, body: String?): Boolean {
        if (!writeRawBodyLocked(subscriptionId, body)) {
            RawVerificationStore.markUnverified(
                subscriptionId,
                body?.let { ConfigStore.sha256(it) },
                "the previous configuration could not be written back",
            )
            return false
        }
        recordBodyWritten(subscriptionId, body)
        return true
    }

    /** @return true only when the file now holds [body] (or is gone for null). */
    private fun writeRawBodyLocked(subscriptionId: String, body: String?): Boolean {
        if (body == null) return removeRawBodyLocked(subscriptionId)
        val persisted = AtomicFileWrite.writeText(rawFileOf(subscriptionId), body)
        if (persisted) unverifiedRawIds.remove(subscriptionId)
        return persisted
    }

    /**
     * Names the verification state of a body this repository just wrote.
     *
     * A body that is gone has no record; a body that is on disk is verified against the bytes that
     * were written, not against whatever a later read happens to find.
     */
    private fun recordBodyWritten(subscriptionId: String, body: String?) {
        if (body == null) {
            RawVerificationStore.remove(subscriptionId)
            return
        }
        val format = get(subscriptionId)?.configFormat
        RawVerificationStore.markVerified(subscriptionId, ConfigStore.sha256(body), format)
    }

    /**
     * Deletes the active raw body; if deletion is refused (read-only file), rename it out of the
     * active path instead. Rename needs no free space, which matters when the restore failed
     * because the disk is full.
     *
     * The verification record moves with the body: a body that is gone leaves no record, and a body
     * that could not be deleted is marked UNVERIFIED with its own digest, so the apply gate refuses
     * it even though the file is still there.
     */
    private fun removeRawBodyLocked(subscriptionId: String): Boolean {
        val file = rawFileOf(subscriptionId)
        if (!file.exists()) {
            unverifiedRawIds.remove(subscriptionId)
            RawVerificationStore.remove(subscriptionId)
            return true
        }
        if (runCatching { file.delete() }.getOrDefault(false) || !file.exists()) {
            unverifiedRawIds.remove(subscriptionId)
            RawVerificationStore.remove(subscriptionId)
            return true
        }
        val quarantine = File(file.parentFile, file.name + ".orphan")
        val moved = runCatching {
            if (quarantine.exists()) quarantine.delete()
            file.renameTo(quarantine)
        }.getOrDefault(false)
        if (moved || !file.exists()) {
            unverifiedRawIds.remove(subscriptionId)
            RawVerificationStore.remove(subscriptionId)
            return true
        }
        unverifiedRawIds.add(subscriptionId)
        RawVerificationStore.markUnverified(
            subscriptionId,
            runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()?.let { ConfigStore.sha256(it) },
            "the retained body could not be deleted",
        )
        return false
    }

    /** Outcome of deciding what to do with a downloaded subscription body. */
    sealed interface BodyCommit {
        /**
         * The body was kept (a raw config) or the subscription is a node list again.
         *
         * [rawBody] is the exact bytes this commit wrote as the retained body (null when the
         * candidate was a node list and the file was removed). A later recovery must roll back
         * *this* body, not whatever happens to be on disk when the failure arrives: another
         * operation can commit a newer body for the same subscription before the old reload
         * fails, and a late read would then name the newer body as its own.
         */
        data class Accepted(val format: String?, val rawBody: String? = null) : BodyCommit

        /** A sing-box body the core refuses; the subscription's previous body is still on disk. */
        data class Refused(val error: String) : BodyCommit
    }

    /**
     * Decides whether a downloaded body may replace the subscription's retained body.
     *
     * The candidate is validated with the same `Libbox.checkConfig` the apply path uses, BEFORE it
     * touches the disk. A refused candidate must not overwrite the body of a subscription that has
     * one: the previous body is that subscription's last good config, and replacing it with a body
     * the core refuses is what left the next start with nothing to fall back to. A body that is not
     * a raw core config (a node list) is accepted as "no raw body" and the stale file is dropped.
     */
    fun commitFetchedBody(subscriptionId: String, body: String): BodyCommit = synchronized(rawBodyLock) {
        val detected = com.interstellar.proxy.data.subscription.RawConfigDetector.detect(body)
        if (detected == null) {
            if (!removeRawBodyLocked(subscriptionId)) {
                return@synchronized BodyCommit.Refused(
                    str(com.interstellar.proxy.R.string.repo_raw_delete_failed),
                )
            }
            return@synchronized BodyCommit.Accepted(null, null)
        }
        when (com.interstellar.proxy.data.subscription.RawConfigCommitPolicy.decide(detected)) {
            // This core cannot execute an Xray body, and there is no Xray-to-node
            // conversion in this app. Accepting it used to write a file the apply
            // path never reads, so a refresh could report success while the
            // subscription could not run. Refuse before anything touches the disk.
            com.interstellar.proxy.data.subscription.RawConfigCommitPolicy.Decision.REJECT_XRAY -> {
                android.util.Log.e(TAG, "refusing Xray raw body: unsupported format")
                return@synchronized BodyCommit.Refused(str(com.interstellar.proxy.R.string.repo_raw_xray_unsupported))
            }

            // A Clash config is a node source, not a raw core config for this
            // client: dropping the stale raw file keeps the subscription on the
            // generated minimal config built from its parsed nodes.
            com.interstellar.proxy.data.subscription.RawConfigCommitPolicy.Decision.NODE_LIST -> {
                if (!removeRawBodyLocked(subscriptionId)) {
                    return@synchronized BodyCommit.Refused(
                        str(com.interstellar.proxy.R.string.repo_raw_delete_failed),
                    )
                }
                return@synchronized BodyCommit.Accepted(null, null)
            }

            com.interstellar.proxy.data.subscription.RawConfigCommitPolicy.Decision.SINGBOX -> {
                val failure = runCatching { Libbox.checkConfig(body) }.exceptionOrNull()
                if (failure != null) {
                    android.util.Log.e(TAG, "refusing fetched raw body: ${failure.message}")
                    return@synchronized BodyCommit.Refused(str(com.interstellar.proxy.R.string.repo_raw_check_failed, failure.message ?: ""))
                }
                // The bytes land first, atomically. The record says PENDING: the metadata that
                // describes them is saved by the caller (upsert), and only that save turns the
                // pair into a verified profile. Until then the apply gate refuses the body, so an
                // interrupted commit cannot become the config that starts on the next launch.
                if (!writeRawBodyLocked(subscriptionId, body)) {
                    RawVerificationStore.markUnverified(
                        subscriptionId,
                        ConfigStore.sha256(body),
                        "the fetched body could not be written",
                    )
                    return@synchronized BodyCommit.Refused(
                        str(com.interstellar.proxy.R.string.repo_raw_body_write_failed),
                    )
                }
                RawVerificationStore.markPending(subscriptionId, ConfigStore.sha256(body), detected.wire)
                BodyCommit.Accepted(detected.wire, body)
            }
        }
    }

    /**
     * Retains [body] as the subscription's raw body and returns its detected format (null = plain
     * node list). Kept for the import path; a body the core refuses is refused here too, so an
     * invalid document is never imported as if it were a working profile.
     */
    fun saveRawBody(subscriptionId: String, body: String): String? =
        when (val commit = commitFetchedBody(subscriptionId, body)) {
            is BodyCommit.Accepted -> commit.format
            is BodyCommit.Refused -> null
        }

    var subscriptions: MutableList<Subscription> = load()
        private set

    var activeSubscriptionId: String
        get() = Settings.activeSubscriptionId
        set(value) {
            Settings.activeSubscriptionId = value
        }

    var selectedNodeId: String
        get() = Settings.selectedNodeId
        set(value) {
            Settings.selectedNodeId = value
        }

    private fun load(): MutableList<Subscription> {
        val list = runCatching {
            if (indexFile.exists()) {
                json.decodeFromString<List<Subscription>>(indexFile.readText()).toMutableList()
            } else {
                mutableListOf()
            }
        }.getOrDefault(mutableListOf())
        // drop mix checks pointing at subscriptions that no longer exist
        val mixIds = Settings.mixSubscriptionIds
        if (mixIds.isNotEmpty()) {
            val pruned = mixIds.filterTo(mutableSetOf()) { id -> list.any { it.id == id } }
            if (pruned.size != mixIds.size) Settings.mixSubscriptionIds = pruned
        }
        return list
    }

    /**
     * Writes the metadata index, durably enough that callers can act on the result.
     *
     * @return true when the file was replaced. `writeText` used to be wrapped in `runCatching` and
     * the outcome discarded, so a commit could report success while the subscription metadata
     * still described the previous body - the exact state that makes a raw untrustworthy.
     */
    @Synchronized
    fun save(): Boolean {
        val text = runCatching { json.encodeToString(subscriptions.toList()) }.getOrNull()
        if (text == null) {
            android.util.Log.e(TAG, "failed to encode subscriptions.json")
            return false
        }
        val persisted = AtomicFileWrite.writeText(indexFile, text)
        if (!persisted) android.util.Log.e(TAG, "failed to persist subscriptions.json")
        return persisted
    }

    fun get(id: String): Subscription? = subscriptions.find { it.id == id }

    /**
     * True when [subscriptionId] participates in the config the active slot would run.
     *
     * Used by [upsert] to decide whether a mutation can change the active config at all. Updating a
     * subscription that is neither active nor a mix member must not rewrite — and therefore must
     * not re-validate or re-commit — the config of the subscription that IS active.
     */
    fun affectsActivePool(subscriptionId: String, activeId: String, mixEnabled: Boolean, mixIds: Set<String>): Boolean {
        if (subscriptionId.isBlank()) return false
        if (activeId.isNotBlank() && subscriptionId == activeId) return true
        return mixEnabled && subscriptionId in mixIds
    }

    @Synchronized
    fun upsert(subscription: Subscription, regenerate: Boolean = true): Boolean {
        var metadataSaved = false
        val shouldRegenerate = synchronized(contentLock) {
            val previous = subscriptions.toList()
            val previousActive = activeSubscriptionId
            val index = subscriptions.indexOfFirst { it.id == subscription.id }
            if (index >= 0) {
                subscriptions[index] = subscription
            } else {
                subscriptions.add(subscription)
            }
            if (activeSubscriptionId.isBlank()) activeSubscriptionId = subscription.id
            if (!save()) {
                // Keep memory and disk on the same (old) version rather than
                // regenerating a config from metadata that did not persist.
                subscriptions.clear()
                subscriptions.addAll(previous)
                activeSubscriptionId = previousActive
                return@synchronized false
            }
            metadataSaved = true
            regenerate &&
                affectsActivePool(subscription.id, activeSubscriptionId, Settings.mixEnabled, Settings.mixSubscriptionIds)
        }
        // The pair becomes trustworthy only now: the bytes were written by commitFetchedBody as
        // PENDING, and this save is the metadata they were waiting for. A failed save leaves a
        // tombstone instead of a profile that looks verified but is not.
        recordRawVerification(subscription, metadataSaved)
        if (metadataSaved && shouldRegenerate) {
            regenerateActiveConfig()
        }
        return metadataSaved
    }

    /**
     * Ties the verification record to what the metadata now says.
     *
     * Three outcomes: the raw and its metadata agree (VERIFIED), there is no raw body (the record is
     * dropped), or they do not agree (a tombstone the apply gate refuses).
     */
    private fun recordRawVerification(subscription: Subscription, metadataSaved: Boolean) {
        val rawBody = synchronized(rawBodyLock) { readRawBodyLocked(subscription.id) }
        val rawSha = rawBody?.let { ConfigStore.sha256(it) }
        if (!metadataSaved) {
            RawVerificationStore.markUnverified(subscription.id, rawSha, "the subscription metadata could not be saved")
            return
        }
        if (rawBody == null) {
            RawVerificationStore.remove(subscription.id)
            return
        }
        val format = com.interstellar.proxy.data.subscription.RawConfigFormat.from(subscription.configFormat)
        if (format != com.interstellar.proxy.data.subscription.RawConfigFormat.SINGBOX) {
            RawVerificationStore.markUnverified(
                subscription.id,
                rawSha,
                "the subscription no longer describes a raw configuration",
            )
            return
        }
        RawVerificationStore.markVerifiedIfCurrent(subscription.id, ConfigStore.sha256(rawBody), format.wire)
    }

    @Synchronized
    fun remove(id: String) {
        val empty = synchronized(contentLock) {
            val previous = subscriptions.toList()
            val previousActive = activeSubscriptionId
            val previousMix = Settings.mixSubscriptionIds
            subscriptions.removeAll { it.id == id }
            if (activeSubscriptionId == id) {
                activeSubscriptionId = subscriptions.firstOrNull()?.id ?: ""
            }
            val remaining = Settings.mixSubscriptionIds - id
            Settings.mixSubscriptionIds =
                if (Settings.mixEnabled && remaining.isEmpty() && subscriptions.isNotEmpty()) {
                    // keep the mix pool non-empty: re-check everything left
                    subscriptions.map { it.id }.toSet()
                } else {
                    remaining
                }
            if (!save()) {
                subscriptions.clear()
                subscriptions.addAll(previous)
                activeSubscriptionId = previousActive
                Settings.mixSubscriptionIds = previousMix
                return@synchronized null
            }
            // Delete under the same lock as the raw CAS: otherwise a restore that just checked
            // the old body could have the file removed between its check and write. The index is
            // already persisted, so a failed delete cannot resurrect the subscription, and the
            // verification record moves with the body: deleting the file without its record would
            // let the next profile that reuses the id trust a body nobody wrote.
            synchronized(rawBodyLock) { removeRawBodyLocked(id) }
            subscriptions.isEmpty()
        } ?: return
        // 删光订阅时把磁盘上的活动配置一并清掉,否则静态分组解析会把
        // 旧配置"复活"成节点列表
        if (empty) {
            ConfigStore.clear()
        }
        regenerateActiveConfig()
    }

    fun activeSubscription(): Subscription? = get(activeSubscriptionId)

    /**
     * The node pool the generated config runs on. Mix on = union of the
     * checked subscriptions (in store order), off = the active subscription.
     * Single derivation shared by the repository and the UI.
     */
    /**
     * True when this subscription is a complete sing-box config whose body is
     * still on disk — i.e. it will be handed to the core verbatim.
     *
     * Raw configs are STANDALONE: they bring their own selector/urltest/route,
     * so merging them into a node pool would mean rewriting them. The mix pool
     * therefore only ever contains plain node subscriptions.
     */
    fun isRawConfig(sub: Subscription): Boolean =
        !unverifiedRawIds.contains(sub.id) &&
            com.interstellar.proxy.data.subscription.RawConfigFormat.from(sub.configFormat) ==
            com.interstellar.proxy.data.subscription.RawConfigFormat.SINGBOX &&
            rawFileOf(sub.id).isFile

    fun poolOf(
        subscriptions: List<Subscription>,
        activeSubscriptionId: String,
        mixEnabled: Boolean,
        mixSubscriptionIds: Set<String>,
    ): List<ProxyNode> = if (mixEnabled) {
        subscriptions
            .filter { it.id in mixSubscriptionIds && !isRawConfig(it) }
            .flatMap { it.nodes }
    } else {
        subscriptions.find { it.id == activeSubscriptionId }?.nodes ?: emptyList()
    }

    /** Subscriptions checked into the mix pool (store order; raw configs excluded). */
    fun mixedSubscriptions(): List<Subscription> =
        subscriptions.filter { it.id in Settings.mixSubscriptionIds && !isRawConfig(it) }

    fun activeNodes(): List<ProxyNode> =
        poolOf(subscriptions, activeSubscriptionId, Settings.mixEnabled, Settings.mixSubscriptionIds)

    fun newSubscriptionId(): String = UUID.randomUUID().toString()

    /** Last config generation/check failure, for UI diagnostics. */
    @Volatile
    var lastConfigError: String? = null
        private set

    /**
     * Takes the last config refusal, so it is reported exactly once and by the operation that
     * caused it: the update paths call this right after the body was committed (or refused) and
     * carry the verdict into their result. A later unrelated operation must not inherit it.
     */
    fun takeConfigError(): String? {
        val error = lastConfigError
        lastConfigError = null
        return error
    }

    /** What one regeneration of the active config produced, and whose config it is. */
    data class ActiveConfigBuild(
        val content: String?,
        /** Subscription the produced config belongs to; null when nothing was produced. */
        val ownerId: String?,
        /** Why nothing was produced (or why a raw body was refused), or null. */
        val error: String?,
        /** True when [content] is a subscription's raw document rather than a generated node pool. */
        val raw: Boolean,
    )

    /**
     * Regenerates filesDir/active.json from the node pool (the active subscription, or
     * the mix union when Mix is on).
     * Returns null when there is nothing to run.
     */
    @Synchronized
    fun regenerateActiveConfig(includeTun: Boolean = true): String? =
        buildActiveConfig(
            activeId = activeSubscriptionId,
            mixEnabled = Settings.mixEnabled,
            mixIds = Settings.mixSubscriptionIds,
            includeTun = includeTun,
            commit = true,
        ).content

    /**
     * Builds (and, when [commit], installs) the config an active slot would run.
     *
     * A refused raw body is a terminal answer for THIS subscription only: the previous bytes stay on
     * disk and are returned when they belong to the same subscription (its own last good), and are
     * never returned when they belong to another one. That single rule is what keeps the UI, the
     * file and the running Box naming the same profile after an activation is refused.
     */
    @Synchronized
    fun buildActiveConfig(
        activeId: String = activeSubscriptionId,
        mixEnabled: Boolean = Settings.mixEnabled,
        mixIds: Set<String> = Settings.mixSubscriptionIds,
        includeTun: Boolean = true,
        commit: Boolean = true,
    ): ActiveConfigBuild {
        // Raw-config path — automatic, with no user switch to understand.
        //
        // If the active subscription's body is a complete sing-box config, it is
        // handed to libbox byte-for-byte: no injected rules, no DNS rewrite, no
        // outbound rebuild, no route.final override. The config decides the
        // behaviour. Anything else (share links, base64 node lists, Clash YAML)
        // is not a config, so it falls through to the minimal generator below.
        val activeSub = activeId.takeIf { it.isNotBlank() }?.let { get(it) }
        if (activeSub != null) {
            when (val rawApplied = applyRawConfigIfMatching(activeSub)) {
                is RawApply.Applied -> {
                    lastConfigError = null
                    if (commit) ConfigStore.writeActiveConfig(rawApplied.content, activeSub.id, verified = true)
                    return ActiveConfigBuild(rawApplied.content, activeSub.id, null, raw = true)
                }
                // A raw body the core refused is a terminal answer for this regeneration: falling
                // through to the node generator would overwrite the real reason with "no usable
                // nodes". Only this subscription's OWN previous config may keep serving - another
                // subscription's config must never be presented as this one's.
                is RawApply.Refused -> {
                    val lastGood = ConfigStore.readActiveConfig()
                    val lastGoodOwner = ConfigStore.readActiveConfigOwner()
                    lastConfigError = rawApplied.error
                    return if (lastGood != null && lastGoodOwner == activeSub.id) {
                        // It may keep serving the RUNNING Box, but the bytes now have no verified
                        // raw behind them. Marking them unstartable is what stops a cold start
                        // (or the quick-settings tile) from presenting them as a checked profile.
                        if (commit) ConfigStore.markActiveVerification(false)
                        ActiveConfigBuild(lastGood, activeSub.id, rawApplied.error, raw = true)
                    } else {
                        ActiveConfigBuild(null, null, rawApplied.error, raw = true)
                    }
                }
                RawApply.NotRaw -> Unit
            }
        }

        if (!mixEnabled && activeSub == null) {
            val error = str(com.interstellar.proxy.R.string.repo_no_subscription_selected)
            lastConfigError = error
            return ActiveConfigBuild(null, null, error, raw = false)
        }

        val nodes = poolOf(subscriptions, activeId, mixEnabled, mixIds)
        if (nodes.isEmpty()) {
            val error = when {
                mixEnabled && !hasUsableMixMember(mixIds) -> str(com.interstellar.proxy.R.string.repo_mix_none_checked)
                mixEnabled -> str(com.interstellar.proxy.R.string.repo_selected_no_nodes)
                else -> str(com.interstellar.proxy.R.string.repo_no_nodes)
            }
            lastConfigError = error
            return ActiveConfigBuild(null, null, error, raw = false)
        }

        val selectedTag = Settings.selectedOutboundTag.takeIf { it.isNotBlank() }
            ?: MinimalConfigBuilder.tagFor(nodes, selectedNodeId)
            ?: MinimalConfigBuilder.AUTO_TAG
        val opts = MinimalConfigBuilder.BuildOptions(
            selectedNodeTag = selectedTag,
            includeTun = includeTun,
        )
        val content = MinimalConfigBuilder.build(nodes, opts)
        return try {
            Libbox.checkConfig(content)
            lastConfigError = null
            if (commit) ConfigStore.writeActiveConfig(content, activeSub?.id ?: activeId.takeIf { it.isNotBlank() }, verified = true)
            ActiveConfigBuild(content, activeSub?.id ?: activeId.takeIf { it.isNotBlank() }, null, raw = false)
        } catch (e: Exception) {
            val error = str(com.interstellar.proxy.R.string.repo_config_check_failed, e.message ?: "")
            lastConfigError = error
            android.util.Log.e(TAG, "config check failed: ${e.message}\n$content", e)
            ActiveConfigBuild(null, null, error, raw = false)
        }
    }

    /** True when at least one checked mix member can contribute nodes (raw configs cannot). */
    private fun hasUsableMixMember(mixIds: Set<String>): Boolean =
        subscriptions.any { it.id in mixIds && !isRawConfig(it) }

    suspend fun regenerateActiveConfigAsync(): String? = withContext(Dispatchers.IO) {
        regenerateActiveConfig()
    }

    /**
     * Raw path of [buildActiveConfig]; [RawApply.NotRaw] = not applicable, use the rewrite.
     *
     * The subscription body is written out exactly as it was received: no
     * injected outbounds, no rule sets, no DNS rewrite, no route.final override.
     * The only thing the app does is ask libbox whether the config is valid.
     */
    private sealed interface RawApply {
        data class Applied(val content: String) : RawApply

        /** The body is a sing-box document and the core refused it; the previous config stands. */
        data class Refused(val error: String) : RawApply

        /** This subscription is not a raw sing-box body, or has none on disk yet. */
        object NotRaw : RawApply
    }

    private fun applyRawConfigIfMatching(sub: Subscription): RawApply {
        val format = com.interstellar.proxy.data.subscription.RawConfigFormat.from(sub.configFormat)
        // Only a sing-box body can be handed to this core verbatim; Clash/Xray
        // bodies were only ever pass-through for the sidecars this client no
        // longer ships.
        if (format != com.interstellar.proxy.data.subscription.RawConfigFormat.SINGBOX) return RawApply.NotRaw
        val raw = readRawBody(sub.id)?.takeIf { it.isNotBlank() } ?: return RawApply.NotRaw
        // "Structurally valid right now" is not "the body that was committed for this profile".
        // The durable record answers the second question; without a VERIFIED digest the raw is a
        // document nobody can attribute, and applying it is how an interrupted commit or a failed
        // recovery becomes the configuration that starts on the next launch.
        if (!RawVerificationStore.isVerified(sub.id, ConfigStore.sha256(raw))) {
            android.util.Log.e(TAG, "refusing raw body for ${sub.id}: no verified digest matches the file")
            return RawApply.Refused(str(com.interstellar.proxy.R.string.repo_raw_unverified))
        }
        return try {
            Libbox.checkConfig(raw)
            RawApply.Applied(raw)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "raw config check failed: ${e.message}", e)
            RawApply.Refused(str(com.interstellar.proxy.R.string.repo_raw_check_failed, e.message ?: ""))
        }
    }

    /**
     * Fetches a URL subscription and updates the store.
     * Returns a user-facing message, or null when the sub is not refreshable.
     */
    /** One subscription refresh: its user-facing message and the body this run committed. */
    data class RefreshCommit(
        val message: String?,
        val rawBody: String?,
        /** True only when this run actually replaced the retained body (Accepted). */
        val committed: Boolean = false,
        /** The nodes/format this run actually wrote; null when nothing was committed. */
        val committedContent: SubscriptionContent? = null,
    )

    /** The whole batch: messages, plus the exact body/content each subscription committed. */
    data class RefreshAllResult(
        val messages: List<String>,
        val committedRawBodies: Map<String, String?>,
        val committedContents: Map<String, SubscriptionContent>,
    )

    /**
     * Refreshes one subscription and returns the body it actually committed.
     *
     * The body is captured from the commit result inside this call, not read again later: a newer
     * operation can replace the same subscription's raw file before an older reload fails, and a
     * late read would then name the newer body as the old operation's own.
     */
    suspend fun refresh(id: String, regenerate: Boolean = true): RefreshCommit? = withContext(Dispatchers.IO) {
        val sub = get(id) ?: return@withContext null
        val url = sub.url ?: return@withContext RefreshCommit(str(com.interstellar.proxy.R.string.vm_local_no_refresh), null)
        try {
            val result = com.interstellar.proxy.data.net.SubscriptionFetcher.fetch(url)
            // Parse and classify BEFORE commitFetchedBody: that call is destructive
            // (it replaces or deletes the retained raw body). A body this app cannot
            // use must leave the old nodes, the old configFormat and the last-good
            // raw body exactly as they were.
            val detected = com.interstellar.proxy.data.subscription.RawConfigDetector.detect(result.body)
            val parsed = com.interstellar.proxy.data.subscription.SubscriptionParser.parse(result.body, id)
            val parsedError = when (
                com.interstellar.proxy.data.subscription.RefreshGuard.decide(detected, parsed)
            ) {
                com.interstellar.proxy.data.subscription.RefreshGuard.Decision.ACCEPT -> null
                com.interstellar.proxy.data.subscription.RefreshGuard.Decision.UNSUPPORTED ->
                    unsupportedConfigMessage(
                        (parsed as com.interstellar.proxy.data.subscription.SubscriptionParser.Result.Unsupported).reason,
                    )
                com.interstellar.proxy.data.subscription.RefreshGuard.Decision.TOO_MANY_NODES ->
                    str(
                        com.interstellar.proxy.R.string.repo_too_many_nodes,
                        (parsed as com.interstellar.proxy.data.subscription.SubscriptionParser.Result.TooManyNodes).limit,
                    )
                com.interstellar.proxy.data.subscription.RefreshGuard.Decision.EMPTY_UNPARSABLE ->
                    str(com.interstellar.proxy.R.string.repo_refresh_unparsable, sub.name)
            }
            if (parsedError != null) {
                // Accounting is real (the download happened), but nodes, format and
                // the retained raw body stay on the previous good version.
                if (
                    !upsert(
                        sub.copy(
                            uploadBytes = result.uploadBytes,
                            downloadBytes = result.downloadBytes,
                            totalBytes = result.totalBytes,
                            expireSeconds = result.expireSeconds,
                            lastUpdated = System.currentTimeMillis(),
                        ),
                        regenerate = regenerate,
                    )
                ) {
                    return@withContext RefreshCommit(str(com.interstellar.proxy.R.string.repo_raw_write_failed), null)
                }
                return@withContext RefreshCommit(parsedError, null)
            }

            val previousRaw = readRawBody(id)
            when (val commit = commitFetchedBody(id, result.body)) {
                is BodyCommit.Refused -> {
                    // The body is refused, but the download and its quota headers are real: keep the
                    // subscription's previous config and update only the accounting.
                    if (
                        !upsert(
                            sub.copy(
                                uploadBytes = result.uploadBytes,
                                downloadBytes = result.downloadBytes,
                                totalBytes = result.totalBytes,
                                expireSeconds = result.expireSeconds,
                                lastUpdated = System.currentTimeMillis(),
                            ),
                            regenerate = regenerate,
                        )
                    ) {
                        return@withContext RefreshCommit(str(com.interstellar.proxy.R.string.repo_raw_write_failed), null)
                    }
                    RefreshCommit(commit.error, null)
                }

                is BodyCommit.Accepted -> {
                    val format = commit.format
                    var committedContent = SubscriptionContent(sub.nodes, sub.configFormat)
                    val message = when (parsed) {
                        is com.interstellar.proxy.data.subscription.SubscriptionParser.Result.Nodes -> {
                            committedContent = SubscriptionContent(parsed.nodes, format)
                            if (
                                !upsert(
                                    sub.copy(
                                        nodes = parsed.nodes,
                                        configFormat = format,
                                        uploadBytes = result.uploadBytes,
                                        downloadBytes = result.downloadBytes,
                                        totalBytes = result.totalBytes,
                                        expireSeconds = result.expireSeconds,
                                        lastUpdated = System.currentTimeMillis(),
                                    ),
                                    regenerate = regenerate,
                                )
                            ) {
                                val recovery = recoverRawBodyIfCurrent(id, commit.rawBody, previousRaw)
                                return@withContext RefreshCommit(rawRecoveryMessage(recovery), null)
                            }
                            str(com.interstellar.proxy.R.string.repo_refreshed, sub.name, parsed.nodes.size)
                        }

                        com.interstellar.proxy.data.subscription.SubscriptionParser.Result.Empty -> {
                            // Reached only for a raw sing-box profile: no nodes to extract,
                            // but the retained body is what the core will run.
                            committedContent = SubscriptionContent(sub.nodes, format)
                            if (
                                !upsert(
                                    sub.copy(
                                        configFormat = format,
                                        uploadBytes = result.uploadBytes,
                                        downloadBytes = result.downloadBytes,
                                        totalBytes = result.totalBytes,
                                        expireSeconds = result.expireSeconds,
                                        lastUpdated = System.currentTimeMillis(),
                                    ),
                                    regenerate = regenerate,
                                )
                            ) {
                                val recovery = recoverRawBodyIfCurrent(id, commit.rawBody, previousRaw)
                                return@withContext RefreshCommit(rawRecoveryMessage(recovery), null)
                            }
                            str(
                                com.interstellar.proxy.R.string.repo_refreshed_raw,
                                sub.name,
                                com.interstellar.proxy.data.subscription.RawConfigFormat.from(format)?.label ?: "",
                            )
                        }

                        else -> error("parsed error result reached the commit branch")
                    }
                    RefreshCommit(message, commit.rawBody, committed = true, committedContent = committedContent)
                }
            }
        } catch (e: Exception) {
            RefreshCommit(str(com.interstellar.proxy.R.string.repo_refresh_failed, sub.name, e.message ?: ""), null)
        }
    }

    fun rawRecoveryMessage(result: RawRestoreResult): String = when (result) {
        RawRestoreResult.RESTORED, RawRestoreResult.SUPERSEDED ->
            str(com.interstellar.proxy.R.string.repo_raw_write_failed)
        RawRestoreResult.BODY_REMOVED -> str(com.interstellar.proxy.R.string.repo_raw_restore_failed)
        RawRestoreResult.BODY_STUCK -> str(com.interstellar.proxy.R.string.repo_raw_restore_stuck)
    }

    private fun unsupportedConfigMessage(reason: String): String = when {
        reason == "xray" -> str(com.interstellar.proxy.R.string.repo_unsupported_xray)
        reason.startsWith("unsupported_protocol:") -> str(
            com.interstellar.proxy.R.string.repo_unsupported_protocol,
            reason.substringAfter(':'),
        )
        else -> str(com.interstellar.proxy.R.string.repo_unsupported_no_tun)
    }

    /**
     * Refreshes every URL subscription, keeping the selected node when possible.
     *
     * The committed bodies travel with the messages so a caller that later has to roll one back
     * uses the bytes it actually wrote, not whatever is on disk at failure time.
     */
    suspend fun refreshAll(regenerate: Boolean = true): RefreshAllResult = withContext(Dispatchers.IO) {
        val commits = subscriptions.filter { it.url != null }.mapNotNull { sub ->
            refresh(sub.id, regenerate)?.let { sub.id to it }
        }
        RefreshAllResult(
            messages = commits.mapNotNull { it.second.message },
            committedRawBodies = commits.filter { it.second.committed }.associate { it.first to it.second.rawBody },
            committedContents = commits.filter { it.second.committed && it.second.committedContent != null }
                .associate { it.first to it.second.committedContent!! },
        )
    }

    fun selectNode(nodeId: String) {
        selectedNodeId = nodeId
        regenerateActiveConfig()
    }
}
