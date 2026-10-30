package com.interstellar.proxy.bg

import android.net.NetworkCapabilities
import org.junit.Test

/**
 * The default-network request must stay requestable.
 *
 * ## The defect this pins
 *
 * `DefaultNetworkListener` registers a `NetworkRequest` to follow the physical default network.
 * A request is not a capability check: `ConnectivityService.ensureRequestableCapabilities` throws
 *
 *     java.lang.IllegalArgumentException: Cannot request network with VALIDATED
 *
 * for any request that names a capability the platform reserves for its own classification, and
 * `NET_CAPABILITY_VALIDATED` is one of them. The request that added VALIDATED ("only a validated
 * physical network is usable", v0.1.28) therefore killed the process on every VPN start:
 *
 *     FATAL EXCEPTION: DefaultDispatcher-worker-4
 *     java.lang.IllegalArgumentException: Cannot request network with VALIDATED
 *         at android.net.ConnectivityManager.registerBestMatchingNetworkCallback
 *
 * The monitor still requires validation - `DefaultNetworkMonitor.isUsable` reads
 * `hasCapability(NET_CAPABILITY_VALIDATED)` from the delivered capabilities. That is a check on a
 * network the platform handed us, which is legal; naming it in the request is not.
 *
 * ## Why the list is asserted directly
 *
 * Unlike the source-text tests in this package, the capability list is a plain `IntArray` of
 * compile-time framework constants, so a JVM test can read the exact values the production code
 * hands to `NetworkRequest.Builder`. No `ConnectivityManager`, no `Network`, no Android runtime.
 */
class DefaultNetworkRequestCapabilitiesTest {

    private val requested get() = DefaultNetworkRequestCapabilities.values.toSet()

    @Test
    fun `the request keeps asking for the capabilities the callback needs`() {
        check(NetworkCapabilities.NET_CAPABILITY_INTERNET in requested) {
            "the default-network request no longer asks for INTERNET, so the callback would not " +
                "be registered for networks that have an IP stack at all."
        }
        check(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED in requested) {
            "the default-network request no longer asks for NOT_RESTRICTED."
        }
    }

    @Test
    fun `the request never names VALIDATED`() {
        check(NetworkCapabilities.NET_CAPABILITY_VALIDATED !in requested) {
            "the default-network request names NET_CAPABILITY_VALIDATED again. " +
                "ConnectivityService.ensureRequestableCapabilities rejects that request with " +
                "\"Cannot request network with VALIDATED\", and because registration runs inside a " +
                "GlobalScope actor the exception is not caught by the start path - it kills the " +
                "process the moment the VPN is switched on. Validation belongs in " +
                "DefaultNetworkMonitor.isUsable (hasCapability on the delivered network), not in " +
                "the request."
        }
    }

    @Test
    fun `the request never names a capability reserved for the platform`() {
        val reserved =
            setOf(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL,
            )
        val collisions = requested intersect reserved
        check(collisions.isEmpty()) {
            "the default-network request names capabilities reserved for the platform's own " +
                "classification: $collisions. Any of them is refused the same way VALIDATED was."
        }
    }
}
