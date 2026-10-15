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

    var mixSubscriptionIds: Set<String>
        get() = properties.getProperty("mixSubscriptionIds", "")
            .split('\n')
            .filter { it.isNotBlank() }
            .toSet()
        set(value) {
            properties.setProperty("mixSubscriptionIds", value.joinToString("\n"))
            commit()
        }

    /** Last selected outbound tag of the main selector (`auto`, a group, or a node). */
    var selectedOutboundTag: String
        get() = properties.getProperty("selectedOutboundTag", "")
        set(value) {
            properties.setProperty("selectedOutboundTag", value)
            commit()
        }

    /**
     * The group [selectedOutboundTag] was picked in.
     *
     * Stored alongside the tag because a tag alone does not identify a choice: a config may
     * hold several selectors, and the same item can be a member of more than one of them.
     * Restoring a pick by searching for "the group that contains this tag" would then be a
     * guess, and a wrong guess silently moves a selector the user never touched.
     *
     * Empty means no group is known for the stored tag - a value written before this key
     * existed, or a pick made against a config that is no longer active. Nothing is
     * restored from an unknown group; the config's own `default` stays in force.
     */
    var selectedOutboundGroup: String
        get() = properties.getProperty("selectedOutboundGroup", "")
        set(value) {
            properties.setProperty("selectedOutboundGroup", value)
            commit()
        }


    /**
     * Routing mode: Rule / Global / Direct. NEVER a node pick - see
     * [selectedOutboundTag] for that. The two coexist.
     *
     * Stored as the kernel's own spelling ([com.interstellar.proxy.data.model.RoutingMode.wire]),
     * so the persisted value is exactly what goes on the wire and what a route
     * rule compares against. Only a recognised spelling is ever written: the
     * getter cannot fail, and an unrecognised value left behind by a downgrade
     * falls back to Rule rather than propagating an unknown mode into the core.
     *
     * Default Rule matches the kernel's own default (`clashmode.NewManager` maps
     * an empty `default_mode` to "Rule"), so an existing install that has never
     * touched this control keeps the behaviour it already had.
     */
    var routingMode: String
        get() = com.interstellar.proxy.data.model.RoutingMode
            .fromWire(properties.getProperty("routingMode"))
            ?.wire
            ?: com.interstellar.proxy.data.model.RoutingMode.DEFAULT.wire
        set(value) {
            val mode = com.interstellar.proxy.data.model.RoutingMode.fromWire(value)
                ?: com.interstellar.proxy.data.model.RoutingMode.DEFAULT
            properties.setProperty("routingMode", mode.wire)
            commit()
        }

    /** Nodes page layout: grid (default) or list. */
    var nodesGridView: Boolean
        get() = properties.getProperty("nodesGridView", "true").toBoolean()
        set(value) {
            properties.setProperty("nodesGridView", value.toString())
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
