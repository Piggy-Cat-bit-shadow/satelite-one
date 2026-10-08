package com.interstellar.proxy.core

import com.interstellar.proxy.InterstellarApplication
import io.nekohasekai.libbox.PlatformInterface

/**
 * Engine-agnostic operations the service layer (BoxService) invokes on the
 * core. There is exactly one implementation — [SingBoxCore] — because this
 * client ships one core only: Piggy-Cat-bit-shadow/sing-box via libbox.
 */
interface ProxyCore {
    /** Create and start the engine itself (CommandServer). */
    suspend fun startup()

    /** Apply (first start or hot-reload) a generated config. */
    suspend fun applyConfig(config: String, overrides: CoreOverrides)

    /** Doze pause / resume. */
    fun pause()

    fun wake()

    /** Tear the engine down completely before the Android service stops. */
    suspend fun shutdown()

    /** Whether the core needs WIFI-state location permission. */
    fun needWifiState(): Boolean
}

/**
 * Neutral start-time overrides, mapped onto sing-box's OverrideOptions.
 */
data class CoreOverrides(
    val autoRedirect: Boolean,
    val perAppEnabled: Boolean,
    val perAppInclude: Boolean,
    val perAppPackages: Set<String>,
    /** Tag the UI wants selected. */
    val selectedTag: String? = null,
)

/** Neutral system-proxy state (BoxService ↔ core, decoupled from libbox types). */
data class SystemProxyState(val available: Boolean, val enabled: Boolean)

/** Core → service callbacks. */
interface CoreHost {
    /** Core dropped the tun / crashed and wants the Android service torn down. */
    fun onCoreRequestStop()

    /** Core wants the active config re-applied (e.g. system proxy toggled). */
    fun onCoreRequestReload()

    fun systemProxyState(): SystemProxyState?

    fun onSetSystemProxy(enabled: Boolean)

    /** Per-second traffic sample for the persistent notification. */
    fun onCoreTraffic(upPerSecond: Long, downPerSecond: Long) {}
}

object CoreEngines {
    fun create(
        platformInterface: PlatformInterface,
        host: CoreHost,
    ): ProxyCore = SingBoxCore(platformInterface, host)
}
