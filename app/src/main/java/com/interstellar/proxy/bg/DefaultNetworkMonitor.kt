package com.interstellar.proxy.bg

import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import io.nekohasekai.libbox.InterfaceUpdateListener
import com.interstellar.proxy.InterstellarApplication
import java.net.NetworkInterface

object DefaultNetworkMonitor {

    var defaultNetwork: Network? = null
        private set

    /**
     * The cores currently listening for default-interface changes.
     *
     * Keyed by the listener object libbox hands us, so `closeDefaultInterfaceMonitor`
     * can only ever remove its own registration. See [InterfaceListenerRegistry] for the
     * handover failure a single mutable field produced.
     */
    private val listeners = InterfaceListenerRegistry<InterfaceUpdateListener>()

    /**
     * Monotonic id of the newest network change we were told about.
     *
     * A retry loop started for an older network must stop as soon as a newer event
     * arrives: otherwise a stale loop keeps re-announcing an interface the device has
     * already left, after the newer one was announced.
     */
    @Volatile
    private var epoch: Long = 0

    /**
     * The tracked network must always be the PHYSICAL one. Best-matching /
     * default-network callbacks deliver our own VPN once it is established —
     * binding dns-local (LocalResolver) or the auto-detect interface to it
     * loops every query back into the tunnel, killing resolution of node
     * server domains (all proxied traffic then dies with it).
     */
    private fun isVpn(network: Network): Boolean =
        InterstellarApplication.connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    private fun physicalNetwork(): Network? {
        val cm = InterstellarApplication.connectivity
        cm.activeNetwork?.let { if (!isVpn(it)) return it }
        return cm.allNetworks.firstOrNull { network ->
            !isVpn(network) &&
                cm.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    }

    /**
     * Register [owner] as a consumer of the tracked physical network.
     *
     * This layer is keyed by the Android Service instance that owns the core
     * (`DefaultNetworkListener`'s map), which is a *different* ownership axis from the
     * native listener above: a Service's registration and a core's listener are created
     * and destroyed at different moments, and conflating them was how one teardown came
     * to clear the other's tracking.
     */
    suspend fun start(owner: Any) {
        DefaultNetworkListener.start(owner) { network ->
            if (network != null && isVpn(network)) return@start
            publishDefaultNetwork(network ?: physicalNetwork())
        }
        defaultNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            physicalNetwork()
        } else {
            DefaultNetworkListener.get()
        }
    }

    /** Release [owner]'s registration. A stale owner's call cannot drop a live one. */
    suspend fun stop(owner: Any) {
        DefaultNetworkListener.stop(owner)
    }

    suspend fun require(): Network {
        val network = defaultNetwork
        if (network != null) {
            return network
        }
        return DefaultNetworkListener.get()
    }

    /**
     * A core registered its default-interface listener.
     *
     * `PlatformInterfaceWrapper.startDefaultInterfaceMonitor`. Registers only this
     * listener and immediately tells it the interface we already know about, so a core
     * that starts while the network is up does not have to wait for the next change.
     */
    fun addListener(listener: InterfaceUpdateListener) {
        listeners.add(listener)
        checkDefaultInterfaceUpdate(defaultNetwork)
    }

    /**
     * A core dropped its listener.
     *
     * `PlatformInterfaceWrapper.closeDefaultInterfaceMonitor`. Only the listener that was
     * actually passed is removed, so a core finishing its teardown cannot unsubscribe a
     * newer core that has already taken over.
     */
    fun removeListener(listener: InterfaceUpdateListener) {
        listeners.remove(listener)
    }

    /** How many cores are currently listening. Exists for tests and diagnostics. */
    internal val listenerCount: Int get() = listeners.size

    /** Records the newest tracked network and announces it to every live listener. */
    private fun publishDefaultNetwork(network: Network?) {
        defaultNetwork = network
        checkDefaultInterfaceUpdate(network)
    }

    /**
     * Tell every registered listener which interface the default network is on.
     *
     * ## Success exits the loop
     *
     * The loop used to run a fixed ten iterations and call
     * `updateDefaultInterface` on **every** one that got past the two guards, so a
     * perfectly healthy network was announced up to ten times per event — ten JNI
     * crossings and ten writes into the core's interface state for a single change. It
     * now reports once and returns; only a failed lookup retries.
     *
     * ## Bounded retry, cancellable by a newer event
     *
     * `getLinkProperties` returns null and `NetworkInterface.getByName` throws while the
     * interface is still coming up, which is the real reason a retry exists at all. The
     * retry stays bounded and gives up, and a newer network event supersedes an older
     * loop instead of letting both write.
     *
     * ## The sleep is bounded and small, and it is not on the main thread in practice
     *
     * This runs from the connectivity callback (`DefaultNetworkListener` registers with
     * `mainHandler`) and from `start()`/`addListener` on the service's IO coroutine. One
     * 100 ms pause after a failed lookup, at most nine times, only while the interface is
     * genuinely unavailable - the previous shape paid the same sleeps in the *success*
     * path too.
     */
    private fun checkDefaultInterfaceUpdate(newNetwork: Network?) {
        val targets = listeners.snapshot()
        if (targets.isEmpty()) return

        if (newNetwork == null) {
            targets.forEach { it.updateDefaultInterface("", -1, false, false) }
            return
        }

        val epochAtEntry = ++epoch
        var attempt = 0
        while (attempt < MAX_INTERFACE_LOOKUP_ATTEMPTS) {
            attempt++
            // A newer network event owns the truth now; this loop must not overwrite it.
            if (epochAtEntry != epoch) return

            val resolved = resolveInterface(newNetwork)
            if (resolved != null) {
                targets.forEach {
                    it.updateDefaultInterface(resolved.name, resolved.index, false, false)
                }
                return
            }
            // An interface that is not up yet is the only reason to pause; giving up is
            // correct rather than harmful, because the next connectivity event re-runs this.
            if (attempt < MAX_INTERFACE_LOOKUP_ATTEMPTS) Thread.sleep(INTERFACE_LOOKUP_RETRY_MS)
        }
        android.util.Log.w(
            "InterstellarUI",
            "default interface for $newNetwork not resolvable after $MAX_INTERFACE_LOOKUP_ATTEMPTS attempts; " +
                "waiting for the next connectivity event",
        )
    }

    private class ResolvedInterface(val name: String, val index: Int)

    private fun resolveInterface(network: Network): ResolvedInterface? {
        val linkProperties = InterstellarApplication.connectivity.getLinkProperties(network)
            ?: return null
        val name = linkProperties.interfaceName ?: return null
        return try {
            ResolvedInterface(name, NetworkInterface.getByName(name).index)
        } catch (e: Exception) {
            null
        }
    }

    private const val MAX_INTERFACE_LOOKUP_ATTEMPTS = 10
    private const val INTERFACE_LOOKUP_RETRY_MS = 100L
}
