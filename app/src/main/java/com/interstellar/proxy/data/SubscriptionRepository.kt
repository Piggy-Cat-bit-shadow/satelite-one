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
import java.util.UUID

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

    private val dir: File
        get() = File(InterstellarApplication.application.filesDir, "subscriptions").also { it.mkdirs() }

    /** Serializes raw-body reads/writes so a guarded restore cannot interleave a newer commit. */
    private val rawBodyLock = Any()

    private val indexFile: File
        get() = File(InterstellarApplication.application.filesDir, "subscriptions.json")

    /** Retained raw subscription body on disk (kept when it is a full core config). */
    fun rawFileOf(subscriptionId: String): File = File(dir, "$subscriptionId.raw")

    /** Reads a subscription's retained raw body, or null when it has none. */
    fun readRawBody(subscriptionId: String): String? = synchronized(rawBodyLock) {
        readRawBodyLocked(subscriptionId)
    }

    private fun readRawBodyLocked(subscriptionId: String): String? =
        runCatching { rawFileOf(subscriptionId).takeIf { it.isFile }?.readText() }.getOrNull()

    /** Puts a previously retained body back (used to undo a candidate the core refused at runtime). */
    fun restoreRawBody(subscriptionId: String, body: String?) {
        synchronized(rawBodyLock) { writeRawBodyLocked(subscriptionId, body) }
    }

    /**
     * Puts [previousBody] back only while the retained body is still [currentBody].
     *
     * A late failure of an older update must not overwrite the body a newer update committed. The
     * comparison and the write share [rawBodyLock] with [commitFetchedBody], so a commit that lands
     * between them cannot be rolled back by the older attempt.
     *
     * @return true when the older body was restored, false when a newer body owns the file.
     */
    fun restoreRawBodyIfCurrent(subscriptionId: String, currentBody: String?, previousBody: String?): Boolean =
        synchronized(rawBodyLock) {
            if (readRawBodyLocked(subscriptionId) != currentBody) return@synchronized false
            writeRawBodyLocked(subscriptionId, previousBody)
            true
        }

    private fun writeRawBodyLocked(subscriptionId: String, body: String?) {
        val file = rawFileOf(subscriptionId)
        if (body == null) {
            runCatching { file.delete() }
        } else {
            runCatching { file.writeText(body) }
        }
    }

    /** Outcome of deciding what to do with a downloaded subscription body. */
    sealed interface BodyCommit {
        /** The body was kept (a raw config) or the subscription is a node list again. */
        data class Accepted(val format: String?) : BodyCommit

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
            rawFileOf(subscriptionId).delete()
            return@synchronized BodyCommit.Accepted(null)
        }
        if (detected == com.interstellar.proxy.data.subscription.RawConfigFormat.SINGBOX) {
            val failure = runCatching { Libbox.checkConfig(body) }.exceptionOrNull()
            if (failure != null) {
                android.util.Log.e(TAG, "refusing fetched raw body: ${failure.message}")
                return@synchronized BodyCommit.Refused(str(com.interstellar.proxy.R.string.repo_raw_check_failed, failure.message ?: ""))
            }
        }
        runCatching { rawFileOf(subscriptionId).writeText(body) }
        BodyCommit.Accepted(detected.wire)
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

    @Synchronized
    fun save() {
        runCatching {
            indexFile.writeText(json.encodeToString(subscriptions.toList()))
        }
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
    fun upsert(subscription: Subscription, regenerate: Boolean = true) {
        val index = subscriptions.indexOfFirst { it.id == subscription.id }
        if (index >= 0) {
            subscriptions[index] = subscription
        } else {
            subscriptions.add(subscription)
        }
        if (activeSubscriptionId.isBlank()) activeSubscriptionId = subscription.id
        save()
        if (regenerate &&
            affectsActivePool(subscription.id, activeSubscriptionId, Settings.mixEnabled, Settings.mixSubscriptionIds)
        ) {
            regenerateActiveConfig()
        }
    }

    @Synchronized
    fun remove(id: String) {
        subscriptions.removeAll { it.id == id }
        rawFileOf(id).delete()
        if (activeSubscriptionId == id) {
            activeSubscriptionId = subscriptions.firstOrNull()?.id ?: ""
        }
        // 删光订阅时把磁盘上的活动配置一并清掉,否则静态分组解析会把
        // 旧配置"复活"成节点列表
        if (subscriptions.isEmpty()) {
            ConfigStore.clear()
        }
        val remaining = Settings.mixSubscriptionIds - id
        Settings.mixSubscriptionIds =
            if (Settings.mixEnabled && remaining.isEmpty() && subscriptions.isNotEmpty()) {
                // keep the mix pool non-empty: re-check everything left
                subscriptions.map { it.id }.toSet()
            } else {
                remaining
            }
        save()
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
                    if (commit) ConfigStore.writeActiveConfig(rawApplied.content, activeSub.id)
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
            if (commit) ConfigStore.writeActiveConfig(content, activeSub?.id ?: activeId.takeIf { it.isNotBlank() })
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
    suspend fun refresh(id: String, regenerate: Boolean = true): String? = withContext(Dispatchers.IO) {
        val sub = get(id) ?: return@withContext null
        val url = sub.url ?: return@withContext str(com.interstellar.proxy.R.string.vm_local_no_refresh)
        try {
            val result = com.interstellar.proxy.data.net.SubscriptionFetcher.fetch(url)
            when (val commit = commitFetchedBody(id, result.body)) {
                is BodyCommit.Refused -> {
                    // The body is refused, but the download and its quota headers are real: keep the
                    // subscription's previous config and update only the accounting.
                    upsert(
                        sub.copy(
                            uploadBytes = result.uploadBytes,
                            downloadBytes = result.downloadBytes,
                            totalBytes = result.totalBytes,
                            expireSeconds = result.expireSeconds,
                            lastUpdated = System.currentTimeMillis(),
                        ),
                        regenerate = regenerate,
                    )
                    commit.error
                }

                is BodyCommit.Accepted -> {
                    val format = commit.format
                    when (val parsed = com.interstellar.proxy.data.subscription.SubscriptionParser.parse(result.body, id)) {
                        is com.interstellar.proxy.data.subscription.SubscriptionParser.Result.Nodes -> {
                            upsert(
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
                            str(com.interstellar.proxy.R.string.repo_refreshed, sub.name, parsed.nodes.size)
                        }

                        com.interstellar.proxy.data.subscription.SubscriptionParser.Result.Empty ->
                            // e.g. a full sing-box profile: no nodes to extract, but the
                            // raw body is retained and usable in raw mode. Quota still
                            // belongs to this subscription, so keep it fresh too.
                            if (format != null) {
                                upsert(
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
                                str(
                                    com.interstellar.proxy.R.string.repo_refreshed_raw,
                                    sub.name,
                                    com.interstellar.proxy.data.subscription.RawConfigFormat.from(format)?.label ?: "",
                                )
                            } else {
                                str(com.interstellar.proxy.R.string.repo_refresh_unparsable, sub.name)
                            }
                    }
                }
            }
        } catch (e: Exception) {
            str(com.interstellar.proxy.R.string.repo_refresh_failed, sub.name, e.message ?: "")
        }
    }

    /** Refreshes every URL subscription, keeping the selected node when possible. */
    suspend fun refreshAll(regenerate: Boolean = true): List<String> = withContext(Dispatchers.IO) {
        subscriptions.filter { it.url != null }.map { refresh(it.id, regenerate) }.filterNotNull()
    }

    fun selectNode(nodeId: String) {
        selectedNodeId = nodeId
        regenerateActiveConfig()
    }
}
