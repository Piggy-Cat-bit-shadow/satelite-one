package com.interstellar.proxy.bg

import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import io.nekohasekai.libbox.InterfaceUpdateListener
import com.interstellar.proxy.InterstellarApplication
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.NetworkInterface

object DefaultNetworkMonitor {

    /**
     * Owns interface lookups and their retries.
     *
     * `SupervisorJob` so one failed resolution cannot cancel the monitor for the process
     * lifetime, and never `Dispatchers.Main`: the work runs on [resolveDispatcher].
     */
    private val monitorScope = CoroutineScope(SupervisorJob())

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
     * Monotonic id of the newest network event, and the authority on which resolve loop
     * may still publish. See [InterfaceResolutionEpoch] for the two defects this replaces.
     */
    private val resolution = InterfaceResolutionEpoch()

    /**
     * Where an interface lookup runs.
     *
     * **This must not be the main thread.** `DefaultNetworkListener` registers its
     * connectivity callbacks with `mainHandler`, so the whole resolve path — including up
     * to nine 100 ms retry pauses — used to execute on the main looper. A review measured
     * the budget at ~1 s of blocked main thread per unresolvable interface, which is an
     * ANR-shaped cost paid by the UI for a background bookkeeping task. It is a field so a
     * test can substitute a deterministic dispatcher.
     */
    @JvmField
    internal var resolveDispatcher: CoroutineDispatcher = Dispatchers.IO

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
     * ## Never blocks the caller, and never writes from a loser
     *
     * The lookup and its retries run on [resolveDispatcher]; this only takes a snapshot of
     * the listeners, claims an epoch, and launches. That makes the whole path safe to call
     * from the connectivity callback (which is on the main looper) and from
     * `start()`/`addListener`.
     *
     * ## A lost network also claims an epoch
     *
     * The "nothing to resolve" branch claims an epoch **before** announcing `""`/`-1`.
     * Round 6 announced the loss without doing so, which left a loop still running for the
     * previous network free to re-announce an interface the device had already left, after
     * the loss had been reported.
     *
     * ## Success exits the loop
     *
     * The loop used to run a fixed ten iterations and call `updateDefaultInterface` on
     * **every** one that got past the two guards, so a perfectly healthy network was
     * announced up to ten times per event — ten JNI crossings and ten writes into the
     * core's interface state for a single change. It now reports once and returns; only a
     * failed lookup retries.
     */
    internal fun checkDefaultInterfaceUpdate(newNetwork: Network?) {
        val targets = listeners.snapshot()
        if (targets.isEmpty()) return

        // Claimed for EVERY event, including the lost-network one. Everything older is now
        // a loser and must drop its result silently.
        val token = resolution.beginAttempt()

        if (newNetwork == null) {
            // No lookup to do, and no waiting: report the loss immediately, but only if
            // this event is still the newest one by the time the listeners are told.
            if (resolution.isWinner(token)) {
                targets.forEach { it.updateDefaultInterface("", -1, false, false) }
            }
            return
        }

        monitorScope.launch(resolveDispatcher) {
            var attempt = 0
            while (attempt < MAX_INTERFACE_LOOKUP_ATTEMPTS) {
                attempt++
                // Checked before every attempt AND before the publish below: a newer event
                // owns the truth now, and a loser's answer is not merely late, it is wrong.
                if (!resolution.isWinner(token)) return@launch

                val resolved = resolveInterface(newNetwork)
                if (resolved != null) {
                    // Re-checked after the lookup: the lookup itself takes time, and the
                    // device may have moved on while it ran. This is the ordering that
                    // round 6's self-captured epoch could not guarantee.
                    if (!resolution.isWinner(token)) return@launch
                    targets.forEach {
                        it.updateDefaultInterface(resolved.name, resolved.index, false, false)
                    }
                    return@launch
                }
                // An interface that is not up yet is the only reason to pause; giving up is
                // correct rather than harmful, because the next connectivity event re-runs
                // this. The pause is on `resolveDispatcher`, never on the caller's thread.
                if (attempt < MAX_INTERFACE_LOOKUP_ATTEMPTS) {
                    delay(INTERFACE_LOOKUP_RETRY_MS)
                }
            }
            android.util.Log.w(
                "InterstellarUI",
                "default interface for $newNetwork not resolvable after " +
                    "$MAX_INTERFACE_LOOKUP_ATTEMPTS attempts; waiting for the next connectivity event",
            )
        }
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
