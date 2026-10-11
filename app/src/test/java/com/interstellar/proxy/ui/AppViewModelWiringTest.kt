package com.interstellar.proxy.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The wiring `AppViewModel` performs around [UiClientLifetime], pinned against the production file.
 *
 * # Why this file exists, and what it is not
 *
 * `UiClientLifetimeTest` proves the *rules*. It cannot prove that the ViewModel consults them, and an
 * independent red-team run measured that gap rather than assuming it: **twelve minimal wiring breaks in
 * `AppViewModel.kt` each produced zero red on the 230-test suite**, including this one —
 *
 * ```kotlin
 * // onDisconnected, reverted to the pre-fix body:
 * if (!probing) applySocketDrop()
 * ```
 *
 * — which restores the exact defect the whole round exists to remove. That defect is not cosmetic:
 * `applySocketDrop()` answers a deliberate disconnect by arming a 400 ms watchdog that calls
 * `markStopped()` **and `BoxService.stop()`**, so the user pressing Home would stop the running tunnel
 * 400 ms later. A guard whose removal is invisible to the suite is not guarded.
 *
 * The ViewModel cannot be constructed in a plain JVM test here: it is an `AndroidViewModel`, there is no
 * Robolectric on this host, and there is no `androidTest` source set. So these tests read the production
 * source and assert the *shape* of the wiring — the same technique `ScreenFactOrderingTest` already uses in
 * this project for `B02`/`E02`, and for the same reason: to bind a call site that no behavioural test can
 * reach.
 *
 * # Stated limits, so nobody reads more into a green run than is there
 *
 * * This proves the calls exist in the right order in the source. It does not prove they have the right
 *   effect at runtime — an end-to-end proof needs a device or an injectable client seam.
 * * It is sensitive to renames and to moving a call into a helper. If a check here fails after a
 *   deliberate refactor, the correct response is to confirm the guard is still wired somewhere and update
 *   the test — not to delete it. Each failure message says what breaks in production, so that judgement
 *   can be made quickly.
 */
class AppViewModelWiringTest {

    private val source: String =
        File("src/main/java/com/interstellar/proxy/ui/AppViewModel.kt").readText()

    /**
     * The body of the function whose declaration contains [signature], delimited by brace balance.
     *
     * Brace balancing rather than "up to the next `fun`" is deliberate, and the first version of this
     * test got it wrong: the ViewModel's handler is an anonymous object whose methods are indented eight
     * spaces, so a search for the next top-level `fun` ran past the end of `onDisconnected()`, swallowed
     * `onConnectionError` and thousands of characters after it, and made every check below pass because
     * some *other* function still contained the string. A wiring test that cannot fail is worse than no
     * wiring test, so the extraction is now exact and the destructive control below is what proved it.
     */
    private fun bodyOf(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(
            "AppViewModel no longer declares `$signature`; the wiring this test binds has moved or been " +
                "renamed, so the guard it checks is no longer being verified",
            start >= 0,
        )
        val open = source.indexOf('{', start + signature.length)
        assertTrue("`$signature` has no body", open >= 0)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
            i++
        }
        throw AssertionError("unbalanced braces after `$signature`")
    }

    private fun assertInBody(signature: String, needle: String, why: String) {
        assertTrue(
            "`$signature` no longer performs `$needle`.\n\n$why",
            bodyOf(signature).contains(needle),
        )
    }

    // --- the 400 ms fuse: a deliberate disconnect must not be reported as a socket loss ---------------

    @Test
    fun `onDisconnected consults the gate before reporting a socket loss`() {
        // The defect this binds: `CommandClient.disconnect()` notifies handlers on the deliberate path,
        // and an unguarded body here turns "the user pressed Home" into "the socket died" -> 400 ms
        // later markStopped() + BoxService.stop() kills a healthy tunnel.
        assertInBody(
            "override fun onDisconnected()",
            "mayReportSocketLoss",
            "A deliberate suspend would be reported as a socket loss, arming the 400 ms watchdog that " +
                "calls markStopped() and BoxService.stop() - backgrounding the app would stop the VPN.",
        )
    }

    @Test
    fun `onConnectionError consults the gate before reporting a socket loss`() {
        assertInBody(
            "override fun onConnectionError(",
            "mayReportSocketLoss",
            "While suspended there is no UI to inform and no session to defend, so a failure to start a " +
                "client nobody asked for must not be treated as the tunnel failing.",
        )
    }

    @Test
    fun `disconnect declares the teardown before it disconnects the client`() {
        // `disconnect()` cancels startingWatchdog and THEN calls commandClient.disconnect(), whose
        // onDisconnected() calls applySocketDrop() -> Started -> armStartingWatchdog(400ms). Cancelling
        // before the callback that re-arms it is not a guard; the teardown flag has to be set first.
        val body = bodyOf("fun disconnect()")
        val teardown = body.indexOf("onTearingDown()")
        val disconnect = body.indexOf("commandClient.disconnect()")
        assertTrue("`disconnect()` no longer declares its teardown", teardown >= 0)
        assertTrue("`disconnect()` no longer disconnects the client", disconnect >= 0)
        assertTrue(
            "`disconnect()` disconnects the client before declaring the teardown, so the handler re-arms " +
                "the watchdog this function just cancelled: destroying the Activity or switching theme " +
                "would stop the tunnel 400 ms later.",
            teardown < disconnect,
        )
    }

    // --- the P0 regression: resume must re-establish the client --------------------------------------

    @Test
    fun `connect establishes the client rather than deferring to the poll`() {
        // The regression this binds: `connect()` lost its `commandClient.connect()`, leaving the 2 s poll
        // as the only connect - and the poll fires only for Starting/Stopped. On a resume with the core
        // already Started the socket was never reopened, so speed, memory, goroutines, history and node
        // delays froze and were rendered as live, and the 400 ms dead-core detector could never fire
        // again because this client's callbacks drive it. Silent and permanent.
        //
        // A plain `contains("commandClient.connect()")` does NOT bind this, and the first version of this
        // test was fooled by exactly that: the call also appears inside the poll, so deleting the
        // unconditional one still left a match. The assertion is therefore positional - the call must
        // appear BEFORE the poll is created. That is precise rather than brittle: it does not care what
        // the call looks like syntactically, only that establishing the client is not left to the poll.
        val body = bodyOf("fun connect()")
        val establish = body.indexOf("commandClient.connect()")
        val poll = body.indexOf("pollJob")
        assertTrue(
            "`connect()` no longer establishes the client at all: resuming with the core already Started " +
                "would never reopen the socket, freezing the dashboard while it still looked live and " +
                "disabling the dead-core detector.",
            establish >= 0,
        )
        assertTrue("`connect()` no longer starts a reconnect poll", poll >= 0)
        assertTrue(
            "`connect()` only establishes the client from inside the poll. The poll fires solely when " +
                "the status is Starting or Stopped, so a resume with the core already Started would " +
                "leave the UI frozen with no error and no way to recover.",
            establish < poll,
        )
    }

    @Test
    fun `the reconnect poll respects the suspension gate`() {
        assertInBody(
            "fun connect()",
            "mayReconnect()",
            "The poll runs on viewModelScope, which outlives backgrounding, so it would resurrect the UI " +
                "client within 2 s of the user leaving the app.",
        )
    }

    // --- the foreground subscription, and the probe's owed suspend -----------------------------------

    @Test
    fun `the foreground listener is subscribed and unsubscribed through helpers`() {
        assertInBody("fun connect()", "subscribeForeground()", "The UI would never learn it is in front.")
        assertInBody(
            "fun disconnect()",
            "unsubscribeForeground()",
            "Every relaunch would leave a listener copy behind, keeping a dead ViewModel reachable and " +
                "letting a foreground edge run side effects on it.",
        )
    }

    @Test
    fun `the probe rebuilds the owed suspend after clearing the flag`() {
        // Adjacency rather than a substring search, deliberately. The source contains four occurrences of
        // `probing = false` (the field declaration, the startProxy probe reset, this comment, and the
        // url-test's exit path) and two of `reapplyOwedSuspend()` (the declaration and this call), so
        // `indexOf` on either one matched the wrong site and the check stayed green when the CALL was
        // deleted - which is how the first version of this test failed its own destructive control.
        val source = this.source.replace("\r\n", "\n")
        assertTrue(
            "`reapplyOwedSuspend()` no longer immediately follows the probe flag being cleared in the " +
                "url-test exit path. The probe keeps the socket by design, so a background edge that " +
                "arrived during it stays un-applied: the UI client would keep running in the background " +
                "for the rest of the session.",
            source.contains("            probing = false\n            reapplyOwedSuspend()\n"),
        )
    }

    @Test
    fun `connect clears the previous teardown so a new session is not muted`() {
        // Without this the teardown flag set by `disconnect()` survives into the next session, so the
        // first real socket loss after a resume would be swallowed and the UI would keep showing a
        // connection that had already gone.
        assertInBody(
            "fun connect()",
            "onClientConnected()",
            "A session started after a teardown would inherit that teardown's muting, so a genuine " +
                "socket loss would be ignored and the UI would keep claiming the core is up.",
        )
    }

    @Test
    fun `resume is gated on the gate's own verdict`() {
        // `resumeUiClient()` must ask the gate whether a resume is owed. Calling `connect()`
        // unconditionally would fire on every foreground edge - including the immediate canonical value
        // `addForegroundListener` delivers on subscribe - and each redundant `connect()` makes
        // `CommandClient` notify `onDisconnected()` on the client it replaces, which is the same 400 ms
        // fuse reached from the resume side.
        val body = bodyOf("fun resumeUiClient()")
        assertTrue(
            "`resumeUiClient()` no longer consults the gate, so every foreground edge (including the " +
                "subscribe-time replay) would ask for a connection sequence, and a redundant `connect()` " +
                "reaches the 400 ms watchdog through `onDisconnected()`.",
            body.contains("onForeground()"),
        )
        assertTrue(
            "`resumeUiClient()` consults the gate but no longer acts on its answer.",
            body.contains("return"),
        )
    }

    @Test
    fun `suspend asks the gate before touching the client`() {
        // Symmetrically, `suspendUiClient()` must let the gate decide: it is the gate that knows a
        // headless probe holds the socket, and that a background edge arriving mid-probe must be
        // remembered rather than dropped.
        val body = bodyOf("fun suspendUiClient()")
        assertTrue(
            "`suspendUiClient()` no longer consults the gate, so it would disconnect a client a headless " +
                "url-test is driving and make the probe time out.",
            body.contains("onBackground("),
        )
        assertTrue("`suspendUiClient()` consults the gate but no longer acts on its answer.", body.contains("return"))
    }
}
