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
    fun `connect establishes the client immediately when the lifetime verdict allows it`() {
        // The regression this binds: `connect()` lost its `commandClient.connect()`, leaving the 2 s poll
        // as the only connect - and the poll fires only for Starting/Stopped. On a resume with the core
        // already Started the socket was never reopened, so speed, memory, goroutines, history and node
        // delays froze and were rendered as live, and the 400 ms dead-core detector could never fire
        // again because this client's callbacks drive it. Silent and permanent.
        //
        // A plain `contains("commandClient.connect()")` does NOT bind this, and the first version of this
        // test was fooled by exactly that: the call also appears inside the poll, so deleting the
        // immediate one still left a match. The assertion is therefore positional - the call must appear
        // BEFORE the poll is created. That is precise rather than brittle: it does not care what the call
        // looks like syntactically, only that establishing the client is not left to the poll.
        //
        // This test is deliberately NOT "connect() must connect unconditionally", which is what it used
        // to assert and what cost it its teeth: an unconditional connect is exactly the P0-A defect. The
        // constraint is that the verdict decides - see the three tests below for the other half.
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

    /**
     * The P0-A defect: the canonical verdict arrives DURING `connect()` and `connect()` undid it.
     *
     * `subscribeForeground()` replays the canonical foreground value synchronously, so a `false`
     * replay runs `suspendUiClient()` inside `connect()` — marking the lifetime suspended and
     * disconnecting the client — and `connect()` then returned and called `commandClient.connect()`
     * unconditionally. The canonical state said "the app is in the background and must not have a UI
     * client" and `connect()` built one anyway, which is precisely the behaviour the whole round
     * exists to remove.
     *
     * The gate must be the lifetime's own verdict, read AFTER the subscription. Both halves are
     * asserted, because a test that only checked the `false` case would pass against an
     * implementation that never connects at all.
     */
    @Test
    fun `connect is gated by the lifetime verdict read after the subscription`() {
        val body = bodyOf("fun connect()")
        val subscribe = body.indexOf("subscribeForeground()")
        val verdict = body.indexOf("wantsClient()")
        assertTrue("`connect()` no longer subscribes to the foreground fact", subscribe >= 0)
        assertTrue(
            "`connect()` no longer reads the lifetime verdict, so the canonical foreground replay that " +
                "`subscribeForeground()` performs is undone by the unconditional connect that follows: " +
                "backgrounding the app no longer stops the UI CommandClient.",
            verdict >= 0,
        )
        assertTrue(
            "`connect()` reads the lifetime verdict BEFORE subscribing and acting on it. " +
                "`subscribeForeground()` delivers the canonical value synchronously, so a verdict read " +
                "first can already be stale by the time it is used: a `false` replay suspends the " +
                "client inside the subscribe, and the connect that follows resurrects it - the P0-A " +
                "defect, narrowed but not fixed.",
            verdict > subscribe,
        )
        // The verdict must actually gate the establish calls, not merely be computed. If a call sat
        // outside the gate, the read would be decorative and the defect unchanged.
        //
        // Two traps this assertion has already fallen into, both recorded because the extraction is
        // by substring and the production function is heavily commented:
        //
        //  * `"if (wantsClient)"` without the brace also matches the negated early return below, so
        //    the "gate" was located at `if (!wantsClient) return` and the test failed against correct
        //    code. Hence the brace: it distinguishes a conditional from its negation.
        //  * `indexOf("commandClient.connect()")` matches the DOCUMENTATION first - the gate comment
        //    names the call while explaining why it is gated - so the establish position landed
        //    inside a comment, before the gate. Hence `lastIndexOf`, which is the last real call site
        //    in the function. Under the P0-A mutation (the gate removed, the call left unconditional)
        //    the call is before the block and the assertion is RED; it is the last occurrence either
        //    way, so the assertion is comparing the same site before and after.
        val gate = body.indexOf("if (wantsClient) {")
        assertTrue(
            "`connect()` computes the verdict but does not gate the establish call on it, so the " +
                "client is still established unconditionally after the subscription replayed a " +
                "background state.",
            gate >= 0,
        )
        // The gate must be fed by the LIFETIME, not by a constant. Presence alone is not the guard:
        // `val wantsClient = true` keeps every positional assertion above satisfied while restoring
        // the P0-A defect exactly, and the first version of this test was green against precisely
        // that mutation. A wiring test that passes when the verdict is stubbed out is not binding the
        // verdict.
        assertInBody(
            "fun connect()",
            "val wantsClient = uiLifetime.wantsClient()",
            "`connect()`'s gate is not fed by the lifetime verdict; the establish decision is made " +
                "without consulting whether the UI client is supposed to exist, which is the P0-A " +
                "defect with a gate-shaped disguise.",
        )
        val establish = body.lastIndexOf("commandClient.connect()")
        assertTrue("`connect()` no longer establishes the client", establish >= 0)
        assertTrue(
            "`connect()` establishes the client outside the verdict gate, which is the P0-A defect: " +
                "the canonical replay said the app is in the background and connect() built a client.",
            gate < establish,
        )
    }

    /**
     * The other half: a suspended lifetime must not be resurrected by `connect()` itself.
     *
     * This is the `background -> connect()` case. `resumeUiClient()` re-enters `connect()` on every
     * foreground edge, and `connect()` is also re-entered from the composition, so a `connect()` that
     * established the client regardless of the verdict would pull a deliberately suspended client
     * back up with no foreground edge at all — and with it the 1 Hz Status tick and the control plane
     * the suspension exists to stop.
     */
    @Test
    fun `connect does not resurrect a suspended client`() {
        val body = bodyOf("fun connect()")
        // The early return is what makes "no client, no poll" true rather than merely unlikely: the
        // poll block sits after it, so a suspended entry cannot start one.
        val earlyReturn = body.indexOf("if (!wantsClient) return")
        assertTrue(
            "`connect()` no longer returns early when the verdict says the UI client must not exist, " +
                "so a backgrounded app's connect() still creates the client and starts the reconnect " +
                "poll - the poll would then run for a client nobody asked for.",
            earlyReturn >= 0,
        )
        val poll = body.indexOf("pollJob = viewModelScope.launch")
        assertTrue("`connect()` no longer starts a reconnect poll", poll >= 0)
        assertTrue(
            "`connect()` starts the reconnect poll before/outside the suspended-verdict return, so " +
                "backgrounding the app leaves a 2 s poll running against a suspended lifetime.",
            earlyReturn < poll,
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
