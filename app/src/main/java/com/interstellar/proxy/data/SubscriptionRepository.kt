package com.interstellar.proxy.data

import com.interstellar.proxy.InterstellarApplication
import com.interstellar.proxy.data.config.ConfigBuilder
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
 * Regenerates the active sing-box config on every mutation.
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

    private val indexFile: File
        get() = File(InterstellarApplication.application.filesDir, "subscriptions.json")

    /** Retained raw subscription body on disk (kept when it is a full core config). */
    fun rawFileOf(subscriptionId: String): File = File(dir, "$subscriptionId.raw")

    /**
     * Persists the raw body and returns its detected format (null = plain
     * node list, raw file removed). Airport configs use YAML anchors and
     * provider-specific quirks, so the file is stored byte-for-byte (no
     * re-serialization) and only fed to the matching core.
     */
    fun saveRawBody(subscriptionId: String, body: String): String? {
        val format = com.interstellar.proxy.data.subscription.RawConfigDetector.detect(body) ?: run {
            rawFileOf(subscriptionId).delete()
            return null
        }
        runCatching { rawFileOf(subscriptionId).writeText(body) }
        return format.wire
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

    @Synchronized
    fun upsert(subscription: Subscription) {
        val index = subscriptions.indexOfFirst { it.id == subscription.id }
        if (index >= 0) {
            subscriptions[index] = subscription
        } else {
            subscriptions.add(subscription)
        }
        if (activeSubscriptionId.isBlank()) activeSubscriptionId = subscription.id
        save()
        regenerateActiveConfig()
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
    fun poolOf(
        subscriptions: List<Subscription>,
        activeSubscriptionId: String,
        mixEnabled: Boolean,
        mixSubscriptionIds: Set<String>,
    ): List<ProxyNode> = if (mixEnabled) {
        subscriptions.filter { it.id in mixSubscriptionIds }.flatMap { it.nodes }
    } else {
        subscriptions.find { it.id == activeSubscriptionId }?.nodes ?: emptyList()
    }

    /** Subscriptions checked into the mix pool (store order). */
    fun mixedSubscriptions(): List<Subscription> =
        subscriptions.filter { it.id in Settings.mixSubscriptionIds }

    fun activeNodes(): List<ProxyNode> =
        poolOf(subscriptions, activeSubscriptionId, Settings.mixEnabled, Settings.mixSubscriptionIds)

    fun newSubscriptionId(): String = UUID.randomUUID().toString()

    /** Last config generation/check failure, for UI diagnostics. */
    @Volatile
    var lastConfigError: String? = null
        private set

    /**
     * Regenerates filesDir/active.json from the current node pool (the active
     * subscription, or the mix union when Mix is on).
     * Returns null when there is nothing to run.
     */
    @Synchronized
    fun regenerateActiveConfig(includeTun: Boolean = true): String? {
        // built-in rule sets must exist on disk before local rule-set paths
        // are embedded into the config
        RulesStore.ensureRules(InterstellarApplication.application)

        // raw-config path: the active subscription IS a full config for the
        // running core — feed it through with compatibility shims + built-in
        // rule injection instead of rewriting. Mix and custom rules do not
        // apply here; format mismatch / missing file falls through to rewrite.
        val activeSub = activeSubscription()
        if (Settings.useRawConfigEnabled && activeSub != null) {
            val rawApplied = applyRawConfigIfMatching(activeSub)
            if (rawApplied != null) return rawApplied
        }

        val mix = Settings.mixEnabled
        if (!mix && activeSubscription() == null) {
            lastConfigError = str(com.interstellar.proxy.R.string.repo_no_subscription_selected)
            return null
        }

        val nodes = activeNodes()
        if (nodes.isEmpty()) {
            lastConfigError = when {
                mix && mixedSubscriptions().isEmpty() -> str(com.interstellar.proxy.R.string.repo_mix_none_checked)
                mix -> str(com.interstellar.proxy.R.string.repo_selected_no_nodes)
                else -> str(com.interstellar.proxy.R.string.repo_no_nodes)
            }
            return null
        }

        var selectedTag = Settings.selectedOutboundTag.takeIf { it.isNotBlank() }
            ?: ConfigBuilder.tagFor(nodes, selectedNodeId)
            ?: ConfigBuilder.AUTO_TAG
        // smart mode: bake in the engine's current pick (fall back to auto
        // on a cold start); the engine refines it via hot-switches later
        if (selectedTag == com.interstellar.proxy.data.config.ConfigBuilder.SMART_TAG) {
            selectedTag = Settings.smartActiveTag.takeIf { it.isNotBlank() }
                ?: ConfigBuilder.AUTO_TAG
        }
        val regionGroups = Settings.regionGroupsEnabled
        if (!regionGroups &&
            selectedTag != ConfigBuilder.AUTO_TAG &&
            ConfigBuilder.nodeFilterRulesActive(selectedTag, nodes, regionGroupsEnabled = true)
        ) {
            selectedTag = ConfigBuilder.AUTO_TAG
            Settings.selectedOutboundTag = ConfigBuilder.AUTO_TAG
        }
        val opts = ConfigBuilder.BuildOptions(
            mode = Settings.outboundMode,
            bypassLan = Settings.bypassLanEnabled,
            bypassCn = Settings.bypassCnEnabled,
            overseasProxy = Settings.overseasProxyEnabled,
            fallbackDirect = Settings.fallbackDirectEnabled,
            adBlock = Settings.adBlockEnabled,
            selectedNodeTag = selectedTag,
            dnsOverrides = DnsOverridesStore.enabled(),
            regionGroupsEnabled = regionGroups,
            includeTun = includeTun,
            simpleRules = com.interstellar.proxy.data.SimpleRulesStore.enabled(),
        )
        val content = ConfigBuilder.build(nodes, opts)
        return try {
            Libbox.checkConfig(content)
            ConfigStore.writeActiveConfig(content)
            lastConfigError = null
            content
        } catch (e: Exception) {
            lastConfigError = str(com.interstellar.proxy.R.string.repo_config_check_failed, e.message ?: "")
            android.util.Log.e(TAG, "config check failed: ${e.message}\n$content", e)
            null
        }
    }

    suspend fun regenerateActiveConfigAsync(): String? = withContext(Dispatchers.IO) {
        regenerateActiveConfig()
    }

    /** Raw path of [regenerateActiveConfig]; null = not applicable, use the rewrite. */
    private fun applyRawConfigIfMatching(sub: Subscription): String? {
        val format = com.interstellar.proxy.data.subscription.RawConfigFormat.from(sub.configFormat)
        // Only a sing-box subscription body can be handed to this core verbatim;
        // Clash/Xray bodies were only ever pass-through for the removed sidecars.
        if (format != com.interstellar.proxy.data.subscription.RawConfigFormat.SINGBOX) return null
        val raw = runCatching { rawFileOf(sub.id).takeIf { it.isFile }?.readText() }.getOrNull() ?: return null
        val options = com.interstellar.proxy.data.config.RawConfigApplier.Options(
            mode = Settings.outboundMode,
            bypassLan = Settings.bypassLanEnabled,
            bypassCn = Settings.bypassCnEnabled,
            overseasProxy = Settings.overseasProxyEnabled,
            fallbackDirect = Settings.fallbackDirectEnabled,
            adBlock = Settings.adBlockEnabled,
        )
        val content = com.interstellar.proxy.data.config.RawConfigApplier.applySingbox(raw, options)
        return try {
            Libbox.checkConfig(content)
            ConfigStore.writeActiveConfig(content)
            lastConfigError = null
            content
        } catch (e: Exception) {
            lastConfigError = str(com.interstellar.proxy.R.string.repo_raw_check_failed, e.message ?: "")
            android.util.Log.e(TAG, "raw config check failed: ${e.message}\n$content", e)
            null
        }
    }

    /**
     * Fetches a URL subscription and updates the store.
     * Returns a user-facing message, or null when the sub is not refreshable.
     */
    suspend fun refresh(id: String): String? = withContext(Dispatchers.IO) {
        val sub = get(id) ?: return@withContext null
        val url = sub.url ?: return@withContext str(com.interstellar.proxy.R.string.vm_local_no_refresh)
        try {
            val result = com.interstellar.proxy.data.net.SubscriptionFetcher.fetch(url)
            val format = saveRawBody(id, result.body)
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
        } catch (e: Exception) {
            str(com.interstellar.proxy.R.string.repo_refresh_failed, sub.name, e.message ?: "")
        }
    }

    /** Refreshes every URL subscription, keeping the selected node when possible. */
    suspend fun refreshAll(): List<String> = withContext(Dispatchers.IO) {
        subscriptions.filter { it.url != null }.map { refresh(it.id) }.filterNotNull()
    }

    fun selectNode(nodeId: String) {
        selectedNodeId = nodeId
        regenerateActiveConfig()
    }
}
