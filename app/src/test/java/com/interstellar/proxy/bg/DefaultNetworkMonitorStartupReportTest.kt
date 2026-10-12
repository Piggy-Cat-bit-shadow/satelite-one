package com.interstellar.proxy.bg

import java.io.File
import org.junit.Test

/**
 * The startup report must be delivered before `addListener` returns.
 *
 * ## The defect this pins
 *
 * `DefaultNetworkMonitor.addListener` is called from the core's *own* start. The core starts its
 * interface monitor in the Initialize stage (`NetworkManager.Start` → `interfaceMonitor.Start()`)
 * and initialises rule-sets in the following Start stage (`Router.Start`). A rule-set with a remote
 * URL dials during that initialisation whenever it has nothing cached — a first start after install,
 * or after the cache is cleared.
 *
 * The core treats the first network-environment observation it ever sees as a transition: it claims
 * an epoch and runs the reset body, which cancels every dial already in flight ("network changed
 * while dialling"). While registration routed its report through `checkDefaultInterfaceUpdate`, that
 * report was launched on a coroutine and could land in the middle of the rule-set initialisation, so
 * a start cancelled its own resource fetch:
 *
 *     start router: initialize rule-set[N]: initial rule-set: <tag>: network changed while dialling
 *
 * `Router.Start` then fails, the box start fails, and the service stops. Retrying could not help:
 * nothing had been cached, so every attempt re-fetched and re-raced.
 *
 * ## Why this is asserted at the source
 *
 * `DefaultNetworkMonitor` is an Android `object`: reaching `addListener` in a JVM test would mean
 * standing up `InterstellarApplication.connectivity`, a `ConnectivityManager` and a `Network`, none
 * of which exist here. The property that has to hold is *where the report is issued*, and that is
 * readable in the source, so the source is what is asserted — the same trade
 * `PlatformFactsListenerLockBoundaryTest` documents for the watermark's initial value.
 */
class DefaultNetworkMonitorStartupReportTest {

    private val source: String by lazy {
        File("src/main/java/com/interstellar/proxy/bg/DefaultNetworkMonitor.kt").readText()
    }

    private fun bodyOf(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "could not find `$signature` in DefaultNetworkMonitor.kt" }
        val open = source.indexOf('{', start)
        check(open >= 0) { "`$signature` has no body" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
        }
        throw AssertionError("`$signature` is not brace-balanced")
    }

    @Test
    fun `registration reports the known interface before it returns`() {
        val body = bodyOf("fun addListener(")
        check(body.contains("deliverKnownInterfaceNow(listener)")) {
            "addListener no longer reports the known interface synchronously. Registration runs " +
                "inside the core's Initialize stage, one stage before the router dials for its " +
                "remote rule-sets; a report that is merely handed to a coroutine can land during " +
                "that initialisation, and the core's first environment observation then cancels " +
                "the fetch as 'network changed while dialling' - which fails the whole start."
        }
        check(body.contains("if (deliverKnownInterfaceNow(listener)) return")) {
            "addListener does not stop once the interface has been reported. Falling through to " +
                "the asynchronous path would report the same interface twice and, worse, would " +
                "make the synchronous report look optional."
        }
    }

    @Test
    fun `the synchronous report is issued directly, not through the coroutine path`() {
        val body = bodyOf("fun deliverKnownInterfaceNow(")
        check(body.contains("listener.updateDefaultInterface(")) {
            "deliverKnownInterfaceNow does not call updateDefaultInterface itself, so the report " +
                "still happens somewhere else - which is exactly the shape that let it land after " +
                "the router had started dialling."
        }
        check(!body.contains("launch(")) {
            "deliverKnownInterfaceNow launches its report instead of issuing it. A launched report " +
                "does not happen before this call returns, which is the only property that keeps " +
                "it ahead of the router's rule-set initialisation."
        }
        check(body.contains("resolveInterface(network)")) {
            "deliverKnownInterfaceNow does not resolve the interface it is about to report."
        }
    }

    @Test
    fun `the synchronous report still claims the resolution epoch`() {
        val body = bodyOf("fun deliverKnownInterfaceNow(")
        check(body.contains("resolution.beginAttempt()")) {
            "deliverKnownInterfaceNow publishes an answer without claiming an attempt, so a resolve " +
                "already in flight for an older observation could overwrite it afterwards."
        }
        check(body.contains("resolution.isWinner(token)")) {
            "deliverKnownInterfaceNow publishes without checking that its answer is still the " +
                "newest, which is the stale-writer rule InterfaceResolutionEpoch exists to enforce."
        }
    }

    @Test
    fun `registration keeps the asynchronous path for an interface that is not resolvable yet`() {
        val body = bodyOf("fun addListener(")
        check(body.contains("checkDefaultInterfaceUpdate(defaultNetwork)")) {
            "addListener dropped its fallback, so a core that starts before the interface can be " +
                "resolved is never told about it - the retry loop is what covers that case."
        }
        val sync = bodyOf("fun deliverKnownInterfaceNow(")
        check(sync.contains("?: return false")) {
            "deliverKnownInterfaceNow has no 'no answer yet' exit, so it cannot hand back to the " +
                "asynchronous path."
        }
    }
}
