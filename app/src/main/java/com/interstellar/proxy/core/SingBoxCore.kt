package com.interstellar.proxy.core

import android.util.Log
import com.interstellar.proxy.InterstellarApplication
import com.interstellar.proxy.ktx.StringArray
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.OverrideOptions
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

    override suspend fun startup() {
        val server = CommandServer(this, platformInterface)
        server.start()
        commandServer = server
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

    override fun needWifiState() = commandServer?.needWIFIState() ?: false

    override suspend fun shutdown() {
        val server = commandServer ?: return
        runCatching {
            server.closeService()
        }.onFailure {
            server.setError("android: close service: ${it.message}")
        }
        server.close()
        commandServer = null
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
