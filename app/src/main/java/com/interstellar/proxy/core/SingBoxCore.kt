package com.interstellar.proxy.core

import android.util.Log
import com.interstellar.proxy.InterstellarApplication
import com.interstellar.proxy.ktx.StringArray
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformEvents
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SystemProxyStatus

/**
 * The sing-box in-process engine. libbox.aar is built from
 * Piggy-Cat-bit-shadow/sing-box (branch testing) — the only core this
 * client ships.
 */
class SingBoxCore(
    private val platformInterface: PlatformInterface,
    private val host: CoreHost,
) : ProxyCore, CommandServerHandler {
    private var commandServer: CommandServer? = null
    private fun server(): CommandServer = checkNotNull(commandServer) { "core not started" }

    /**
     * Exactly one PlatformEvents per running CommandServer.
     *
     * Created right after the server starts and released in [shutdown] before the
     * server goes away. Deliberately NOT touched by [applyConfig]: a config reload
     * keeps the same Android session, so rebuilding it there would leak one bridge
     * per reload and let an old bridge's callbacks reach the new box.
     */
    private var platformEvents: PlatformEvents? = null

    override suspend fun startup() {
        val server = CommandServer(this, platformInterface)
        // Staged ownership: publish the reference BEFORE start() can fail part-way. The
        // previous order assigned `commandServer` only after a successful start(), so a
        // throw from inside start() left a half-started CommandServer (and its socket)
        // reachable from nothing at all - shutdown() saw a null field and returned.
        // With the reference stored first, every failure path still has something to
        // close, including the caller's `dispose after a partial start`.
        commandServer = server
        server.start()
        // A failure here leaves the server running but no bridge. That is a consistent
        // state: shutdown() closes the server, and no caller ever saw a fact bridge.
        platformEvents = Libbox.newPlatformEvents(server)
    }

    override suspend fun applyConfig(config: String, overrides: CoreOverrides) {
        server().startOrReloadService(config, overrides.toOverrideOptions())
    }

    override fun pause() {
        commandServer?.pause()
    }

    override fun wake() {
        commandServer?.wake()
    }

    // ---- platform facts → shared core policy ----

    override fun memoryTrim(level: Int) {
        platformEvents?.memoryTrim(level)
    }

    override fun setAppForeground(foreground: Boolean) {
        platformEvents?.setAppForeground(foreground)
    }

    override fun setScreenOn(on: Boolean) {
        platformEvents?.setScreenOn(on)
    }

    override fun reportDeviceWake() {
        platformEvents?.reportDeviceWake()
    }

    override fun needWifiState() = commandServer?.needWIFIState() ?: false

    override fun closePlatformEvents() {
        // Idempotent: a second call, or one after shutdown, is a no-op. The reference is
        // cleared even when close() fails, so a retry cannot re-close a handle that
        // already failed once and cannot keep it reachable from a dead generation.
        val events = platformEvents ?: return
        platformEvents = null
        closeReportingFailure("platformEvents.close") { events.close() }
    }

    override suspend fun shutdown() {
        // The bridge is closed by the caller *before* this, and only when it could
        // prove the fact lane had drained. Reaching here with it still open means that
        // proof was unavailable: leave it open (one unreachable Go object) instead of
        // closing it under a native call, and say so loudly.
        if (platformEvents != null) {
            android.util.Log.e(
                "InterstellarUI",
                "SingBoxCore.shutdown() with the platform-events bridge still open: drain was not proven",
            )
        }
        val server = commandServer ?: return
        // Clear the reference FIRST: whatever happens below, this generation is finished,
        // and a failure must not leave a dead native handle reachable from applyConfig()
        // or from a retry that would try to close it twice.
        commandServer = null
        // Keep the original diagnostic text: a closeService failure must still surface the
        // binding's message to the user-facing error path, not just to logcat.
        try {
            server.closeService()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            closeReportingFailure("setError") {
                server.setError("android: close service: ${error.message}")
            }
        }
        closeReportingFailure("server.close") { server.close() }
    }

    // ---- CommandServerHandler → CoreHost ----

    override fun serviceStop() = host.onCoreRequestStop()

    override fun serviceReload() = host.onCoreRequestReload()

    override fun getSystemProxyStatus(): SystemProxyStatus? =
        host.systemProxyState()?.let { state ->
            SystemProxyStatus().apply {
                available = state.available
                enabled = state.enabled
            }
        }

    override fun setSystemProxyEnabled(isEnabled: Boolean) = host.onSetSystemProxy(isEnabled)

    override fun triggerNativeCrash() {
        Thread {
            Thread.sleep(200)
            throw RuntimeException("debug native crash")
        }.start()
    }

    override fun writeDebugMessage(message: String?) {
        Log.d("interstellar", message!!)
    }

    override fun connectSSHAgent(): Int = -1
}

private fun CoreOverrides.toOverrideOptions() =
    OverrideOptions().apply {
        autoRedirect = this@toOverrideOptions.autoRedirect
        if (perAppEnabled) {
            val selfPackage = InterstellarApplication.application.packageName
            if (perAppInclude) {
                includePackage = StringArray((perAppPackages + selfPackage).iterator())
            } else {
                excludePackage = StringArray((perAppPackages - selfPackage).iterator())
            }
        }
    }
