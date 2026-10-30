/*
 *                                                                             *
 *  Copyright (C) 2019 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2019 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  Ported from sing-box-for-android (GPLv3) for interstellar-android.             *
 *                                                                             *
 *******************************************************************************/

package com.interstellar.proxy.bg

import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.interstellar.proxy.InterstellarApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.runBlocking

object DefaultNetworkListener {
    private sealed class NetworkMessage {
        class Start(val key: Any, val listener: (Network?) -> Unit) : NetworkMessage()

        class Get : NetworkMessage() {
            val response = CompletableDeferred<Network>()
        }

        class Stop(val key: Any) : NetworkMessage()

        class Put(val network: Network) : NetworkMessage()

        class Update(val network: Network) : NetworkMessage()

        class Lost(val network: Network) : NetworkMessage()
    }

    @OptIn(DelicateCoroutinesApi::class, ObsoleteCoroutinesApi::class)
    private val networkActor =
        GlobalScope.actor<NetworkMessage>(Dispatchers.Unconfined) {
            val listeners = mutableMapOf<Any, (Network?) -> Unit>()
            var network: Network? = null
            val pendingRequests = arrayListOf<NetworkMessage.Get>()
            for (message in channel) {
                when (message) {
                    is NetworkMessage.Start -> {
                        if (listeners.isEmpty()) register()
                        listeners[message.key] = message.listener
                        if (network != null) message.listener(network)
                    }

                    is NetworkMessage.Get -> {
                        check(listeners.isNotEmpty()) { "Getting network without any listeners is not supported" }
                        if (network == null) {
                            pendingRequests += message
                        } else {
                            message.response.complete(network)
                        }
                    }

                    is NetworkMessage.Stop ->
                        if (listeners.isNotEmpty() &&
                            listeners.remove(message.key) != null &&
                            listeners.isEmpty()
                        ) {
                            network = null
                            unregister()
                        }

                    is NetworkMessage.Put -> {
                        network = message.network
                        pendingRequests.forEach { it.response.complete(message.network) }
                        pendingRequests.clear()
                        listeners.values.forEach { it(network) }
                    }

                    is NetworkMessage.Update ->
                        if (network == message.network) {
                            listeners.values.forEach { it(network) }
                        }

                    is NetworkMessage.Lost ->
                        if (network == message.network) {
                            network = null
                            listeners.values.forEach { it(null) }
                        }
                }
            }
        }

    suspend fun start(key: Any, listener: (Network?) -> Unit) = networkActor.send(
        NetworkMessage.Start(key, listener),
    )

    suspend fun get(): Network = if (fallback) {
        @TargetApi(23)
        InterstellarApplication.connectivity.activeNetwork
            ?: error("missing default network")
    } else {
        NetworkMessage.Get().run {
            networkActor.send(this)
            response.await()
        }
    }

    suspend fun stop(key: Any) = networkActor.send(NetworkMessage.Stop(key))

    // NB: this runs in ConnectivityThread, and this behavior cannot be changed until API 26
    private object Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = runBlocking {
            networkActor.send(NetworkMessage.Put(network))
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            runBlocking { networkActor.send(NetworkMessage.Update(network)) }
        }

        /**
         * Address, route and DNS changes arrive here, not through onCapabilitiesChanged.
         *
         * A Wi-Fi that reassociates on the same interface name can keep the same Network object and
         * the same capabilities while its LinkProperties (IPv4 address, DNS servers, default route)
         * change. Without this override the core would keep the old index and the old environment
         * fingerprint until something unrelated happened to fire, so the transition would be missed
         * on exactly the roam this listener exists to follow.
         */
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            runBlocking { networkActor.send(NetworkMessage.Update(network)) }
        }

        override fun onLost(network: Network) = runBlocking {
            networkActor.send(NetworkMessage.Lost(network))
        }
    }

    private var fallback = false
    private val request =
        NetworkRequest.Builder().apply {
            DefaultNetworkRequestCapabilities.values.forEach { addCapability(it) }
        }.build()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Register the callback, and never let a refusal take the process down.
     *
     * This body runs inside [networkActor], which is a `GlobalScope` coroutine: an exception thrown
     * here is caught by the coroutine machinery and delivered to the uncaught-exception handler, not
     * to whichever caller happened to send the `Start` message. `BoxService.startService`'s
     * `try { ... } catch (e: Exception)` around `DefaultNetworkMonitor.start` therefore cannot see
     * it - the process is killed instead of the start being abandoned.
     *
     * That is exactly what a rejected request did: `ConnectivityService` refuses a request that names
     * a capability it reserves for its own classification, and the resulting
     * `IllegalArgumentException` crashed the app the moment the VPN was switched on. The request no
     * longer names one, and this catch is the second line of defence: an unexpected refusal degrades
     * the monitor to [fallback] (the active network) with a log line, and the tunnel still starts.
     */
    private fun register() {
        try {
            registerRequest()
        } catch (e: Exception) {
            fallback = true
            android.util.Log.e(
                "InterstellarUI",
                "default network registration failed; falling back to the active network",
                e,
            )
        }
    }

    private fun registerRequest() {
        when (Build.VERSION.SDK_INT) {
            in 31..Int.MAX_VALUE ->
                @TargetApi(31)
                {
                    InterstellarApplication.connectivity.registerBestMatchingNetworkCallback(
                        request,
                        Callback,
                        mainHandler,
                    )
                }

            in 28 until 31 ->
                @TargetApi(28)
                {
                    InterstellarApplication.connectivity.requestNetwork(request, Callback, mainHandler)
                }

            in 26 until 28 ->
                @TargetApi(26)
                {
                    InterstellarApplication.connectivity.registerDefaultNetworkCallback(Callback, mainHandler)
                }

            in 24 until 26 ->
                @TargetApi(24)
                {
                    InterstellarApplication.connectivity.registerDefaultNetworkCallback(Callback)
                }

            else ->
                try {
                    fallback = false
                    InterstellarApplication.connectivity.requestNetwork(request, Callback)
                } catch (e: RuntimeException) {
                    fallback = true
                }
        }
    }

    private fun unregister() {
        runCatching {
            InterstellarApplication.connectivity.unregisterNetworkCallback(Callback)
        }
    }
}

/**
 * The capabilities the default-network request is allowed to ask the framework for.
 *
 * ## A request is not a capability check
 *
 * `VALIDATED` is what separates "has an IP stack" from "reaches the internet", and the monitor still
 * requires it - but it must be *read from the callback* (`NetworkCapabilities.hasCapability`), never
 * *requested*. `ConnectivityService.ensureRequestableCapabilities` rejects a `NetworkRequest` that
 * names a capability reserved for the platform's own classification:
 *
 *     java.lang.IllegalArgumentException: Cannot request network with VALIDATED
 *         at android.net.IConnectivityManager$Stub$Proxy.requestNetwork
 *         at android.net.ConnectivityManager.registerBestMatchingNetworkCallback
 *
 * Requesting it crashed the process on every VPN start from 0.1.28 on, on every Android that takes
 * the request path (API 28+). `CAPTIVE_PORTAL` is reserved by the same rule, so it is pinned out here
 * too: the point is not the two names, it is that this list stays requestable.
 *
 * `INTERNET` keeps the callback on networks that have an IP stack at all; `NOT_RESTRICTED` keeps it on
 * networks the profile may use. Both are documented requestable capabilities.
 */
internal object DefaultNetworkRequestCapabilities {
    val values: IntArray =
        intArrayOf(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
        )
}
