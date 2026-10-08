package com.interstellar.proxy.data

import android.content.Context
import com.interstellar.proxy.InterstellarApplication
import java.io.File
import java.util.Properties

/**
 * Simple synchronous key-value settings persisted to filesDir/settings.properties.
 * Mirrors the subset of SFA Settings used by the service layer.
 */
object Settings {
    private const val FILE_NAME = "settings.properties"

    const val PER_APP_PROXY_INCLUDE = 0
    const val PER_APP_PROXY_EXCLUDE = 1

    private val properties = Properties()
    private val file: File
        get() = File(InterstellarApplication.application.filesDir, FILE_NAME)

    init {
        load()
    }

    @Synchronized
    fun reload() {
        load()
    }

    private fun load() {
        properties.clear()
        runCatching {
            file.inputStream().use { properties.load(it) }
        }
    }

    @Synchronized
    private fun commit() {
        runCatching {
            file.outputStream().use { properties.store(it, null) }
        }
    }

    var allowBypass: Boolean
        get() = properties.getProperty("allowBypass", "false").toBoolean()
        set(value) {
            properties.setProperty("allowBypass", value.toString())
            commit()
        }

    var autoRedirect: Boolean
        get() = properties.getProperty("autoRedirect", "false").toBoolean()
        set(value) {
            properties.setProperty("autoRedirect", value.toString())
            commit()
        }

    var dynamicNotification: Boolean
        get() = properties.getProperty("dynamicNotification", "true").toBoolean()
        set(value) {
            properties.setProperty("dynamicNotification", value.toString())
            commit()
        }

    var systemProxyEnabled: Boolean
        get() = properties.getProperty("systemProxyEnabled", "false").toBoolean()
        set(value) {
            properties.setProperty("systemProxyEnabled", value.toString())
            commit()
        }

    var perAppProxyEnabled: Boolean
        get() = properties.getProperty("perAppProxyEnabled", "false").toBoolean()
        set(value) {
            properties.setProperty("perAppProxyEnabled", value.toString())
            commit()
        }

    var perAppProxyMode: Int
        get() = properties.getProperty("perAppProxyMode", "$PER_APP_PROXY_INCLUDE")?.toIntOrNull()
            ?: PER_APP_PROXY_INCLUDE
        set(value) {
            properties.setProperty("perAppProxyMode", value.toString())
            commit()
        }

    var perAppProxyList: Set<String>
        get() = properties.getProperty("perAppProxyList", "")
            .split('\n')
            .filter { it.isNotBlank() }
            .toSet()
        set(value) {
            properties.setProperty("perAppProxyList", value.joinToString("\n"))
            commit()
        }

    var perAppProxyShowSystemApps: Boolean
        get() = properties.getProperty("perAppProxyShowSystemApps", "false").toBoolean()
        set(value) {
            properties.setProperty("perAppProxyShowSystemApps", value.toString())
            commit()
        }

    var activeSubscriptionId: String
        get() = properties.getProperty("activeSubscriptionId", "")
        set(value) {
            properties.setProperty("activeSubscriptionId", value)
            commit()
        }

    var selectedNodeId: String
        get() = properties.getProperty("selectedNodeId", "")
        set(value) {
            properties.setProperty("selectedNodeId", value)
            commit()
        }

    /**
     * Mix mode: the node pool is the union of all checked subscriptions
     * instead of the single active one. Traffic/expiry stay per-subscription.
     */
    var mixEnabled: Boolean
        get() = properties.getProperty("mixEnabled", "false").toBoolean()
        set(value) {
            properties.setProperty("mixEnabled", value.toString())
            commit()
        }

    /**
     * Feed the active subscription's retained raw config (when its format
     * matches the running core) instead of the rewritten one. Incompatible
     * with mix mode — enabling this forces mix off.
     */
    var useRawConfigEnabled: Boolean
        get() = properties.getProperty("useRawConfigEnabled", "false").toBoolean()
        set(value) {
            properties.setProperty("useRawConfigEnabled", value.toString())
            commit()
        }

    var mixSubscriptionIds: Set<String>
        get() = properties.getProperty("mixSubscriptionIds", "")
            .split('\n')
            .filter { it.isNotBlank() }
            .toSet()
        set(value) {
            properties.setProperty("mixSubscriptionIds", value.joinToString("\n"))
            commit()
        }

    /**
     * When true, the config includes one urltest group per detected country
     * (香港 / 新加坡 / …). Off by default: only 自动 and manual node pick.
     */
    var regionGroupsEnabled: Boolean
        get() = properties.getProperty("regionGroupsEnabled", "false").toBoolean()
        set(value) {
            properties.setProperty("regionGroupsEnabled", value.toString())
            commit()
        }

    /** Last selected outbound tag of the main selector (`auto`, a group, or a node). */
    var selectedOutboundTag: String
        get() = properties.getProperty("selectedOutboundTag", "")
        set(value) {
            properties.setProperty("selectedOutboundTag", value)
            commit()
        }


    /** Nodes page layout: grid (default) or list. */
    var nodesGridView: Boolean
        get() = properties.getProperty("nodesGridView", "true").toBoolean()
        set(value) {
            properties.setProperty("nodesGridView", value.toString())
            commit()
        }

    var outboundMode: com.interstellar.proxy.data.config.ConfigBuilder.OutboundMode
        get() = when (properties.getProperty("outboundMode", "rule")) {
            "global" -> com.interstellar.proxy.data.config.ConfigBuilder.OutboundMode.GLOBAL
            "direct" -> com.interstellar.proxy.data.config.ConfigBuilder.OutboundMode.DIRECT
            else -> com.interstellar.proxy.data.config.ConfigBuilder.OutboundMode.RULE
        }
        set(value) {
            properties.setProperty(
                "outboundMode",
                when (value) {
                    com.interstellar.proxy.data.config.ConfigBuilder.OutboundMode.GLOBAL -> "global"
                    com.interstellar.proxy.data.config.ConfigBuilder.OutboundMode.DIRECT -> "direct"
                    com.interstellar.proxy.data.config.ConfigBuilder.OutboundMode.RULE -> "rule"
                },
            )
            commit()
        }

    var themeMode: String
        get() = properties.getProperty("themeMode", "light")
        set(value) {
            properties.setProperty("themeMode", value)
            commit()
        }

    var accentId: String
        get() = properties.getProperty("accentId", "green")
        set(value) {
            properties.setProperty("accentId", value)
            commit()
        }

    /** Homepage hero visual: "smiley" (FaceMark, default) or "orbit". */
    var heroStyle: String
        get() = properties.getProperty("heroStyle", "smiley")
        set(value) {
            properties.setProperty("heroStyle", value)
            commit()
        }

    /** App language: "system" (default) | "zh" | "en"; attachBaseContext re-reads after invalidate. */
    var appLanguage: String
        get() = properties.getProperty("appLanguage", com.interstellar.proxy.ktx.AppLanguage.SYSTEM)
        set(value) {
            properties.setProperty("appLanguage", value)
            commit()
            com.interstellar.proxy.ktx.AppLanguage.invalidate()
        }

    /** Last-known core state, keeps the quick-settings tile truthful. */
    var tileActive: Boolean
        get() = properties.getProperty("tileActive", "false").toBoolean()
        set(value) {
            properties.setProperty("tileActive", value.toString())
            commit()
        }

    /** Route RFC1918 / ULA / link-local directly (and exclude them from TUN). */
    var bypassLanEnabled: Boolean
        get() = properties.getProperty("bypassLanEnabled", "true").toBoolean()
        set(value) {
            properties.setProperty("bypassLanEnabled", value.toString())
            commit()
        }

    /** Route CN domains/IPs direct (built-in geo rule sets). */
    var bypassCnEnabled: Boolean
        get() = properties.getProperty("bypassCnEnabled", "true").toBoolean()
        set(value) {
            properties.setProperty("bypassCnEnabled", value.toString())
            commit()
        }

    /** Route geolocation-!cn (overseas) domains through the proxy (rule mode). */
    var overseasProxyEnabled: Boolean
        get() = properties.getProperty("overseasProxyEnabled", "false").toBoolean()
        set(value) {
            properties.setProperty("overseasProxyEnabled", value.toString())
            commit()
        }

    /** Rule-mode fallback for traffic no rule matched: true = direct, false = proxy. */
    var fallbackDirectEnabled: Boolean
        get() = properties.getProperty("fallbackDirectEnabled", "false").toBoolean()
        set(value) {
            properties.setProperty("fallbackDirectEnabled", value.toString())
            commit()
        }

    /** Epoch millis of the last successful rule/geodata file update (0 = never). */
    var ruleFilesUpdatedAt: Long
        get() = properties.getProperty("ruleFilesUpdatedAt", "0")?.toLongOrNull() ?: 0L
        set(value) {
            properties.setProperty("ruleFilesUpdatedAt", value.toString())
            commit()
        }

    /** Block ad/tracker domains (built-in category-ads-all rule set). */
    var adBlockEnabled: Boolean
        get() = properties.getProperty("adBlockEnabled", "true").toBoolean()
        set(value) {
            properties.setProperty("adBlockEnabled", value.toString())
            commit()
        }

    /** Subscription auto-update. */
    var autoUpdateEnabled: Boolean
        get() = properties.getProperty("autoUpdateEnabled", "true").toBoolean()
        set(value) {
            properties.setProperty("autoUpdateEnabled", value.toString())
            commit()
        }

    var autoUpdateIntervalHours: Int
        get() = properties.getProperty("autoUpdateIntervalHours", "6")?.toIntOrNull() ?: 6
        set(value) {
            properties.setProperty("autoUpdateIntervalHours", value.toString())
            commit()
        }

}
