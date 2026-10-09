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

    /**
     * Close the platform-events bridge. Idempotent, and safe to call twice.
     *
     * Split out of [shutdown] deliberately, because closing this object while a fact
     * call is still executing on its session's lane is a native use-after-close. The
     * service layer therefore calls this **only after a proven drain**
     * (`PlatformFacts.detachAndDrain` returning true). When the drain cannot be
     * proven the caller must leave the bridge open: that leaks one bounded Go object
     * which can no longer be reached (the session is unbound), which is strictly
     * better than closing it under a running call.
     */
    fun closePlatformEvents()

    /**
     * Tear the engine down. Does **not** touch the platform-events bridge — call
     * [closePlatformEvents] first. If it is still open here, the caller did not prove
     * a drain; that is logged loudly rather than papered over.
     */
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

/**
 * Run a synchronous teardown step, logging its failure instead of swallowing it.
 *
 * Two things this does NOT do, deliberately:
 *
 *  - it does not swallow [kotlinx.coroutines.CancellationException]. `runCatching` would,
 *    and then a cancelled teardown would look like a completed one. These native calls
 *    are synchronous, so a cancellation can only arrive *before* them — but if a binding
 *    ever throws one from inside, it must propagate rather than be logged as success.
 *  - it does not hide the difference between "closed" and "failed to close": the caller
 *    decides, and [SingBoxCore] records the failure.
 *
 * @return the block's value, or null when it failed (the failure is logged).
 */
internal inline fun <T> closeReportingFailure(what: String, block: () -> T): T? {
    return try {
        block()
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        android.util.Log.w("InterstellarUI", "core teardown step failed: $what", error)
        null
    }
}
