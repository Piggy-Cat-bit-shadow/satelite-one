package com.interstellar.proxy.core

import com.interstellar.proxy.InterstellarApplication
import io.nekohasekai.libbox.PlatformInterface

/**
 * Raw Android platform facts, forwarded to the core.
 *
 * Facts only — which trim level trims or releases memory, whether a wake resets
 * transports, whether screen-off pauses anything — all of that lives in sing-box's
 * shared mobile policy. No implementation of this interface may reinterpret a
 * level, map it to an action, or trigger a reconnect.
 */
interface PlatformFactSink {
    /** The raw Android `onTrimMemory` level, forwarded unchanged. */
    fun memoryTrim(level: Int)

    /** Jiejiebox's own UI process is in the foreground. Says nothing about the VPN. */
    fun setAppForeground(foreground: Boolean)

    fun setScreenOn(on: Boolean)

    /** The user actually unlocked / came back to the device — stronger than SCREEN_ON. */
    fun reportDeviceWake()
}

/**
 * Engine-agnostic operations the service layer (BoxService) invokes on the
 * core. There is exactly one implementation — [SingBoxCore] — because this
 * client ships one core only: Piggy-Cat-bit-shadow/sing-box via libbox.
 */
interface ProxyCore : PlatformFactSink {
    /** Create and start the engine itself (CommandServer). */
    suspend fun startup()

    /** Apply (first start or hot-reload) a generated config. */
    suspend fun applyConfig(config: String, overrides: CoreOverrides)

    /**
     * Doze pause / resume.
     *
     * Kept as an interface, but the Android device axis no longer drives it: the
     * old `ACTION_DEVICE_IDLE_MODE_CHANGED → pause()/wake()` path was retired when
     * the shared screen/device policy took over, because two writers of the same
     * device state fight each other (a Doze exit would wake a core whose screen is
     * still off).
     */
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
