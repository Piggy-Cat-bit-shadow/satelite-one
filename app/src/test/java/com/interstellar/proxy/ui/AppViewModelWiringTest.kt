package com.interstellar.proxy.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * [body] with line comments and block comments removed, so positions and counts are about CODE.
     *
     * This is not cosmetic. `connect()`'s own documentation names `commandClient.connect()` while
     * explaining why it is gated, so a substring search finds the mention first; and a scan that
     * counts or positions occurrences is otherwise satisfied by prose. The production function is
     * heavily commented and commented *about this exact call*, which is what makes the strip load
     * bearing rather than tidy.
     */
    private fun code(body: String): String =
        body.replace(Regex("(?s)/\\*.*?\\*/"), " ").replace(Regex("//[^\n]*"), " ")

    /**
     * The statements inside the braced block that opens at the first occurrence of [opening].
     *
     * # Why the extent matters, and why substring searches were not enough
     *
     * Index comparisons against a whole function body cannot tell "inside the guard" from "after it".
     * An adversarial debug pass restored the P0-A defect by inserting two lines before the
     * suspended-verdict early return - an unconditional `commandClient.connect()` and
     * `registerStoppedReceiver()` - and every assertion in this file stayed green across all 196
     * tests: the inserted call sits before the poll, so a `lastIndexOf("commandClient.connect()")`
     * lands on the poll's call instead, and the presence checks are satisfied by the gated block that
     * is still there and still correct.
     *
     * A guard whose extent is not checked is a guard that can be walked around. So the gate is
     * extracted as a block and the assertions are made about what is INSIDE it.
     *
     * The block extraction alone does NOT close that hole, and this comment used to claim it did.
     * Extraction answers "is the gate still correct?", never "is the gate the only way in?", so an
     * ADDED unconditional call above the gate leaves the extraction green and the defect restored.
     * Re-measured: the whole 248-test suite passed with the P0-A defect back in place that way. What
     * closes it is the occurrence count in `connect is gated by the lifetime verdict read after the
     * subscription`, which is only meaningful on comment-stripped source ([code]).
     */
    private fun blockAfter(body: String, opening: String): String {
        val start = body.indexOf(opening)
        assertTrue(
            "the block `$opening` is gone from the function under test, so the wiring this test binds " +
                "has moved or been renamed and the guard it checks is no longer being verified",
            start >= 0,
        )
        val open = body.indexOf('{', start)
        assertTrue("`$opening` has no block", open >= 0)
        var depth = 0
        var i = open
        while (i < body.length) {
            when (body[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return body.substring(open + 1, i)
                }
            }
            i++
        }
        throw AssertionError("unbalanced braces after `$opening`")
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

    // --- ownership: one socket, so every establish needs a verdict -------------------

    /**
     * The startup establish in `startProxy()` is a UI-client establish and must be gated too.
     *
     * `commandClient` is ONE socket. `startProxy()` reaches the same `CommandClient` the guarded
     * `connect()` hands to the UI, so its `commandClient.connect()` is not a separate, startup-only
     * client that could legitimately sit outside the lifetime — it is the UI's client, established
     * from a second place.
     *
     * That second place is reachable while suspended, which is what made this a real bypass rather
     * than a tidiness question. `startProxy()` is called from the Compose UI, so it starts in the
     * foreground, but everything after `delay(250)` runs on `Dispatchers.IO`: tapping start and
     * immediately backgrounding the app lets the background edge land first, and `suspendUiClient()`
     * has by then already disconnected the client and set the verdict to "not wanted" (measured on
     * the real [UiClientLifetime]: `onBackground` -> true, then `wantsClient` -> false). An
     * unguarded connect then rebuilt the client *after* that disconnect, with no later edge to
     * correct it - `resumeUiClient()` fires only when the user comes back, and `mayReconnect()`
     * gates new polls without ever closing an existing client.
     *
     * # Why this counts establishes instead of checking positions
     *
     * "There is an `if` before the connect" is the assertion that already failed this file once: the
     * gate can be left intact while a second, ungated establish is added beside it, and a positional
     * check cannot see that. What is asserted here is the ownership invariant itself - in this
     * function, the number of establish calls equals the number of establish calls that sit inside a
     * lifetime verdict. Equal counts can only hold when every one of them is gated.
     *
     * The two legitimate shapes are the only ones accepted: `connect()`'s
     * `if (wantsClient) { … }`, and `startProxy()`'s `if (uiLifetime.wantsClient()) { … }`. The
     * comment-stripped source is used because these functions discuss `commandClient.connect()` in
     * prose while explaining the gate, and a count taken over raw text includes the prose.
     */
    @Test
    fun `every establish in connect and startProxy is behind a lifetime verdict`() {
        val establishes = Regex("commandClient\\.connect\\(\\)")

        // Two functions, two allowed shapes. `connect()` legitimately has TWO establishes - the
        // immediate one inside `if (wantsClient) { … }` and the poll's inside
        // `if (!uiLifetime.mayReconnect()) continue` - so its poll establish is accounted for
        // separately rather than being counted as an ungated call. `startProxy()` gets one verdict
        // and no poll, so for it the two counts must be exactly equal.
        for ((signature, gate, allowPoll) in listOf(
            Triple("fun connect()", "if (wantsClient) {", true),
            Triple("fun startProxyInternal()", "if (uiLifetime.wantsClient()) {", false),
        )) {
            val body = bodyOf(signature)
            val total = establishes.findAll(code(body)).count()
            val gated = establishes.findAll(code(blockAfter(body, gate))).count()
            val inPoll = if (allowPoll) {
                establishes.findAll(blockAfter(body, "pollJob = viewModelScope.launch")).count()
            } else {
                0
            }
            assertTrue(
                "`$signature` no longer establishes the client at all; this test cannot bind the " +
                    "gate it exists for",
                total > 0,
            )
            assertEquals(
                "`$signature` establishes the UI command client outside every lifetime verdict " +
                    "($total establish call(s); $gated inside `$gate`, $inPoll inside the reconnect " +
                    "poll). `commandClient` is one socket shared with the UI, so a call outside a " +
                    "verdict builds the client the canonical lifetime has just refused - reachable " +
                    "by backgrounding the app during the startup coroutine, and permanent because no " +
                    "later edge corrects an already connected client.",
                total,
                gated + inPoll,
            )
        }
    }

    /**
     * The headless probe is the deliberate exception, and it must stay deliberate.
     *
     * `runDisconnectedUrlTest` drives this same client without any lifetime verdict, on purpose:
     * a url-test run from the 未连接 state has no UI to serve, signals readiness only through
     * `onConnected()`, and is bounded by the kernel's per-node timeout. Its own continuation is
     * `reapplyOwedSuspend()`, which applies a background edge that arrived mid-probe.
     *
     * Stating it as a test keeps the exception visible: the assertion above deliberately covers
     * only `connect()`/`startProxy()`, and a future edit that gated the probe too would make the
     * probe time out, while one that copied this shape into `startProxy()` would be caught there.
     */
    @Test
    fun `the headless probe establishes the client without a lifetime verdict, on purpose`() {
        val body = code(bodyOf("private suspend fun runDisconnectedUrlTest("))
        val total = Regex("commandClient\\.connect\\(\\)").findAll(body).count()
        assertTrue(
            "`runDisconnectedUrlTest` no longer establishes the client; the headless url-test would " +
                "wait on a socket nobody opened and time out",
            total > 0,
        )
        assertFalse(
            "`runDisconnectedUrlTest` now consults the lifetime verdict. The probe is not UI work: " +
                "it runs with no UI to serve and already handles a mid-probe background edge through " +
                "`reapplyOwedSuspend()`, so gating it here would only make the probe time out.",
            body.contains("wantsClient()"),
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
        // The verdict must actually gate the establish calls, and the assertion is about what is
        // INSIDE the gate rather than about positions in the function. Index comparisons cannot tell
        // "inside the guard" from "after it", and an adversarial pass proved that by restoring the
        // P0-A defect - two lines inserted before the early return, an unconditional
        // `commandClient.connect()` and `registerStoppedReceiver()` - with every assertion here green
        // across all 196 tests. The inserted call sits before the poll, so a
        // `lastIndexOf("commandClient.connect()")` landed on the poll's call and never saw it.
        //
        // Two further traps this assertion has already fallen into, recorded because the extraction is
        // by substring and the production function is heavily commented:
        //
        //  * `"if (wantsClient)"` without the brace also matches the negated early return, so the
        //    "gate" was located at `if (!wantsClient) return` and the test failed against correct
        //    code. Hence the brace: it distinguishes a conditional from its negation.
        //  * `indexOf("commandClient.connect()")` matches the DOCUMENTATION first - the gate comment
        //    names the call while explaining why it is gated - so the establish position landed inside
        //    a comment, before the gate. Extracting the block sidesteps both: a comment is outside the
        //    braces.
        val gate = blockAfter(body, "if (wantsClient) {")
        assertTrue(
            "`connect()` no longer establishes the client inside the verdict gate. The canonical " +
                "foreground replay that `subscribeForeground()` performs is then undone by a connect " +
                "that runs regardless of the verdict: backgrounding the app no longer stops the UI " +
                "CommandClient, which is the P0-A defect.",
            gate.contains("commandClient.connect()"),
        )
        assertTrue(
            "`connect()` no longer registers the SERVICE_STOPPED receiver inside the verdict gate, so " +
                "a suspended entry arms a receiver for a client it deliberately did not create.",
            gate.contains("registerStoppedReceiver()"),
        )
        // The gate must be fed by the LIFETIME, not by a constant. Presence alone is not the guard:
        // `val wantsClient = true` keeps every other assertion satisfied while restoring the P0-A
        // defect exactly, and an earlier version of this test was green against precisely that
        // mutation. A wiring test that passes when the verdict is stubbed out is not binding the
        // verdict.
        assertInBody(
            "fun connect()",
            "val wantsClient = uiLifetime.wantsClient()",
            "`connect()`'s gate is not fed by the lifetime verdict; the establish decision is made " +
                "without consulting whether the UI client is supposed to exist, which is the P0-A " +
                "defect with a gate-shaped disguise.",
        )
        assertTrue("`connect()` no longer establishes the client", body.contains("commandClient.connect()"))
        // The gate must also be the ONLY way in, which the assertions above cannot see: they check
        // that the gate still CONTAINS a connect call, never that no connect call exists OUTSIDE it.
        //
        // An adversarial pass proved that gap is reachable, not theoretical. Adding one unconditional
        // `commandClient.connect()` (plus `registerStoppedReceiver()`) above the gate leaves the gate
        // intact and every other assertion here green - all 248 tests passed - while restoring the
        // P0-A defect exactly: a backgrounded entry, whose canonical replay has just suspended the
        // client, builds one anyway.
        //
        // The invariant that closes it is about the path that RUNS WHEN THE VERDICT REFUSES, so the
        // scan is scoped to the statements before the poll. The poll's own `commandClient.connect()`
        // is deliberately excluded: that one is guarded by `mayReconnect()`, and counting it would
        // make a correct `connect()` fail this check.
        val stripped = code(body)
        val beforePoll = stripped.substring(0, stripped.indexOf("pollJob = viewModelScope.launch"))
        val gatedCode = code(blockAfter(body, "if (wantsClient) {"))
        val establishing = Regex("commandClient\\.connect\\(\\)|registerStoppedReceiver\\(\\)")
        assertEquals(
            "`connect()` establishes the client somewhere other than inside the `if (wantsClient)` " +
                "gate, on the path that runs when the verdict has just refused it. A backgrounded " +
                "entry then builds the client the canonical lifetime said must not exist - the P0-A " +
                "defect, whether the stray call was moved above the gate or added alongside it.",
            establishing.findAll(beforePoll).count(),
            establishing.findAll(gatedCode).count(),
        )
        assertTrue(
            "`connect()` starts the reconnect poll before the suspended-verdict early return, so a " +
                "backgrounded app keeps a 2 s poll running against a lifetime that must not reconnect.",
            body.indexOf("if (!wantsClient) return") < body.indexOf("pollJob = viewModelScope.launch"),
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

    /**
     * A refresh the live graph only partly accepted must not be shown as a clean success.
     *
     * The core reports that case as `published` plus `pendingTags`/`restartRequired`, so a client
     * that only counts statuses prints "1 published, 0 failed" for a document whose group kept its
     * previous members — invisible failure, which the original acceptance forbids.
     */
    @Test
    fun `a partly applied provider refresh is reported before the plain summary`() {
        val body = code(bodyOf("private fun providerMessage("))
        val partial = body.indexOf("partlyApplied(")
        val summary = body.indexOf("vm_provider_summary")
        assertTrue(
            "`providerMessage()` no longer checks whether the refresh was only partly applied",
            partial >= 0,
        )
        assertTrue("`providerMessage()` no longer prints the plain provider summary", summary >= 0)
        assertTrue(
            "`providerMessage()` prints the plain summary before checking the partial case, so a " +
                "document whose membership the live graph refused is presented as a clean success.",
            partial < summary,
        )
        assertTrue(
            "`providerMessage()` no longer reports the partial case as an error; its result would read " +
                "like any other successful refresh.",
            body.substring(partial).contains("UiToast.Kind.Error"),
        )
    }

    /**
     * A fetch that outlives the profile it was started for must not act on the new profile.
     *
     * The defect this binds: the pool decision was the only guard, so an update started on A and
     * landing after the user activated B reloaded B (the active config "changed") and refreshed
     * B's providers, attributing B's state to A's operation. The owner of the live instance has to
     * be captured before the request and re-checked before the result is applied.
     *
     * The id alone is NOT that identity (A -> B -> A, a restart of the same profile and a mix
     * change all leave it equal), so the capture is an instance snapshot: pool + engine generation
     * + config revision.
     */
    @Test
    fun `a late update result is dropped when another profile became active`() {
        val body = bodyOf("fun refreshSubscription(")
        val capture = body.indexOf("val start = instanceSnapshot()")
        val decide = body.indexOf("affectsActiveConfig(")
        val fetch = body.indexOf("SubscriptionFetcher.fetch(")
        val owns = body.indexOf("ownsInstance(")
        assertTrue("`refreshSubscription()` no longer captures the instance identity before the fetch", capture >= 0)
        assertTrue("`refreshSubscription()` no longer decides pool membership", decide >= 0)
        assertTrue("`refreshSubscription()` no longer fetches the subscription", fetch >= 0)
        assertTrue(
            "`refreshSubscription()` captures the instance identity after the pool decision or after the " +
                "fetch, so the owner of the live instance is not known when the request starts",
            capture < decide && decide < fetch,
        )
        assertTrue(
            "`refreshSubscription()` no longer re-checks instance ownership before applying the result, so a " +
                "result that lands after another profile became active reloads that profile and refreshes its " +
                "providers - the late-result misattribution.",
            owns > fetch,
        )
        assertInBody(
            "fun refreshSubscription(",
            "vm_update_superseded",
            "`refreshSubscription()` no longer reports the superseded case, so the user sees the other " +
                "profile's result as this subscription's, or sees nothing at all.",
        )
    }

    @Test
    fun `a batch that outlives its profile is dropped the same way`() {
        val body = bodyOf("fun refreshAll()")
        val capture = body.indexOf("val start = instanceSnapshot()")
        val launch = body.indexOf("coroutineScope {")
        val owns = body.indexOf("ownsInstance(")
        assertTrue("`refreshAll()` no longer captures the instance identity before the batch", capture >= 0)
        assertTrue("`refreshAll()` captures the instance identity after the batch started", capture < launch)
        assertTrue(
            "`refreshAll()` no longer re-checks instance ownership before applying the batch result, so a " +
                "batch that lands after another profile became active reloads and refreshes that profile.",
            owns > launch,
        )
        assertInBody(
            "fun refreshAll()",
            "vm_update_all_superseded",
            "`refreshAll()` no longer reports the superseded batch, so a profile switch during the batch is " +
                "invisible and its result is attributed to the wrong instances.",
        )
    }

    // --- the two results of an update are independent -----------------------------------------------

    /**
     * A body that downloaded fine but was refused as a config must reach the initiator as that
     * refusal, and it must not have replaced the subscription's previous body.
     *
     * The repository now refuses the candidate BEFORE it touches the disk (`commitFetchedBody`), so
     * the refusal travels in the update's own result instead of a global error slot another
     * operation can steal.
     */
    @Test
    fun `a refused body is reported instead of a config result`() {
        val signature = "private suspend fun reportManualUpdateOutcome("
        val body = code(bodyOf(signature))
        assertTrue(
            "`reportManualUpdateOutcome()` no longer looks at `report.configError`, so a refused body is " +
                "reported as a config result the running instance never applied.",
            body.contains("report.configError"),
        )
        assertInBody(
            "fun refreshSubscription(",
            "when (val commit = SubscriptionRepository.commitFetchedBody(",
            "`refreshSubscription()` no longer validates the downloaded body before it is stored, so a body " +
                "the core refuses can overwrite the subscription's last good one.",
        )
        assertInBody(
            "fun refreshSubscription(",
            "is SubscriptionRepository.BodyCommit.Refused -> {",
            "`refreshSubscription()` no longer handles the refused candidate as its own result.",
        )
        assertInBody(
            "fun refreshSubscription(",
            "configError = commitError,",
            "`refreshSubscription()` no longer carries the refusal into the report.",
        )
        assertInBody(
            "fun refreshAll()",
            "is SubscriptionRepository.BodyCommit.Refused -> {",
            "`refreshAll()` no longer keeps a refused candidate out of the subscription's stored body.",
        )
        assertInBody(
            "fun refreshAll()",
            "configError = configError,",
            "`refreshAll()` no longer carries the refusal into the report.",
        )
        // The write must come AFTER the ownership check: writing first is what let a late result
        // leave the file (and the next start) describing a different profile than the live Box.
        val single = bodyOf("fun refreshSubscription(")
        val ownsSingle = single.indexOf("ownsInstance(")
        val commitSingle = single.indexOf("SubscriptionRepository.regenerateActiveConfig()")
        assertTrue("`refreshSubscription()` no longer defers the active-config commit", commitSingle >= 0)
        assertTrue(
            "`refreshSubscription()` commits the active config before it checks ownership, so a late " +
                "result rewrites active.json for an instance it no longer owns.",
            ownsSingle < commitSingle,
        )
        val batch = bodyOf("fun refreshAll()")
        val ownsBatch = batch.indexOf("ownsInstance(")
        val commitBatch = batch.indexOf("SubscriptionRepository.regenerateActiveConfig()")
        assertTrue("`refreshAll()` no longer defers the active-config commit", commitBatch >= 0)
        assertTrue(
            "`refreshAll()` commits the active config before it checks ownership.",
            ownsBatch < commitBatch,
        )
    }

    /**
     * A subscription whose download fails must still refresh the running instance's providers.
     *
     * The defect this binds, measured on the emulator before the fix: `refreshSubscription` guarded
     * the whole report on `updated`, so `HTTP 500` produced one error toast, no provider request
     * reached the old provider's address, and the node-source half of the update disappeared
     * entirely. The decision is therefore taken BEFORE the fetch, and the report takes it from
     * [com.interstellar.proxy.data.SubscriptionUpdateCoordinator.report] rather than from `updated`.
     */
    @Test
    fun `a failed download still reaches the provider half of a manual update`() {
        val body = bodyOf("fun refreshSubscription(")
        val decide = body.indexOf("affectsActiveConfig(")
        val fetch = body.indexOf("SubscriptionFetcher.fetch(")
        assertTrue("`refreshSubscription()` no longer decides whether the update affects the active pool", decide >= 0)
        assertTrue("`refreshSubscription()` no longer fetches the subscription", fetch >= 0)
        assertTrue(
            "`refreshSubscription()` decides whether the update affects the active pool AFTER the fetch, " +
                "so the decision can only be taken from a successful download - the failed-download " +
                "branch returns without refreshing the instance that is still running.",
            decide < fetch,
        )
        assertInBody(
            "fun refreshSubscription(",
            "configUpdated = updated,",
            "`refreshSubscription()` no longer derives the reported halves from both the pool decision and " +
                "the download result; tying them back to `updated` alone silences the provider refresh " +
                "whenever the subscription download fails.",
        )
    }

    @Test
    fun `a batch in which every download failed still reports the provider half`() {
        val body = bodyOf("fun refreshAll()")
        val decide = body.indexOf("affectsActiveConfig(")
        val launch = body.indexOf("coroutineScope {")
        assertTrue("`refreshAll()` no longer decides whether the batch affects the active pool", decide >= 0)
        assertTrue("`refreshAll()` no longer runs the batch", launch >= 0)
        assertTrue(
            "`refreshAll()` decides whether the batch affects the active pool after the fetches, so a " +
                "batch whose downloads all failed is indistinguishable from one that touched nothing.",
            decide < launch,
        )
        assertInBody(
            "fun refreshAll()",
            "configUpdated = ok > 0,",
            "`refreshAll()` no longer separates the pool decision from the batch's success count, so a " +
                "failed batch skips the provider refresh of the instance that is still running.",
        )
    }

    /**
     * The helper must keep the halves independent; collapsing them re-introduces the same defect one
     * call deeper, where the call-site assertions above cannot see it.
     */
    @Test
    fun `the outcome helper gates the config and provider halves separately`() {
        val signature = "private suspend fun reportManualUpdateOutcome("
        assertInBody(
            signature,
            "if (report.config)",
            "the config half is reported unconditionally, so a failed download now claims a config " +
                "result it never produced.",
        )
        assertInBody(
            signature,
            "if (report.providers)",
            "the provider half is gated by something else than the pool decision - the failed-download " +
                "branch stops refreshing the running instance's remote node sources.",
        )
    }

    // --- A2: activation and application belong to the profile the UI names ---------------------------

    /**
     * The active profile may only be renamed AFTER its config was built and validated.
     *
     * The defect this binds: `activateSubscription` set the new id first and regenerated second; a
     * subscription whose raw body the core refuses left the card pointing at B while the file and
     * the Box still served A's config. The candidate is now built first, and a refused candidate
     * rolls the id back.
     */
    @Test
    fun `a subscription is only activated after its config was built and validated`() {
        val body = bodyOf("fun activateSubscription(")
        val build = body.indexOf("SubscriptionRepository.buildActiveConfig(")
        val commitId = body.indexOf("SubscriptionRepository.activeSubscriptionId = id")
        val refuse = body.indexOf("if (candidate.content == null)")
        val rollback = body.indexOf("SubscriptionRepository.activeSubscriptionId = previousId")
        assertTrue("`activateSubscription()` no longer builds the candidate config", build >= 0)
        assertTrue("`activateSubscription()` no longer checks whether a candidate was produced", refuse >= 0)
        assertTrue(
            "`activateSubscription()` names the new profile before building its config, so a refused " +
                "candidate leaves the UI, the file and the Box naming different profiles.",
            build < commitId && refuse < commitId,
        )
        assertTrue(
            "`activateSubscription()` does not put the previous profile back when the core refuses the " +
                "candidate, so the UI keeps claiming a profile the Box never ran.",
            rollback > commitId,
        )
        assertTrue(
            "`activateSubscription()` no longer reports why the activation failed.",
            body.contains("vm_activate_refused"),
        )
    }

    /**
     * "Applied" must be an observation of the core, not the return of a void RPC.
     *
     * `CommandClient.serviceReload()` returns void; the handler stops the service on failure. The
     * receipt carries the request intent, the revision the Box read and the engine generation of
     * the attempt, and the ViewModel only reports Reloaded when that receipt is this request's and
     * the live instance is serving this revision.
     */
    @Test
    fun `the reload verdict is bound to this request and revision`() {
        val body = code(bodyOf("private fun reloadVerdict("))
        assertTrue(
            "`reloadVerdict()` no longer binds the reload to a request intent, so it can read a " +
                "verdict recorded for some other request as its own.",
            body.contains("registerReloadIntent("),
        )
        assertTrue(
            "`reloadVerdict()` no longer classifies the receipt against this request, revision and " +
                "engine.",
            body.contains("ActiveConfigApply.classify("),
        )
        assertTrue(
            "`reloadVerdict()` no longer reads the current revision it must match.",
            body.contains("currentRevision = ConfigStore.activeConfigRevision()"),
        )
        assertTrue(
            "`reloadVerdict()` no longer distinguishes a superseded result from a refusal.",
            body.contains("ReloadOutcome.Superseded"),
        )
        val apply = code(bodyOf("private suspend fun applyActiveConfigIfChanged("))
        assertTrue(
            "`applyActiveConfigIfChanged()` no longer treats a refused reload as a recovery case.",
            apply.contains("ActiveConfigRecovery.FailedCandidate("),
        )
        assertTrue(
            "`applyActiveConfigIfChanged()` no longer refuses to apply a config it does not own.",
            apply.contains("ActiveConfigOutcome.Superseded"),
        )
    }

    @Test
    fun `a refused config restores only while it still owns the revision`() {
        val body = code(bodyOf("private suspend fun recoverRefusedReload("))
        assertTrue(
            "`recoverRefusedReload()` no longer runs the CAS-guarded recovery instead of writing " +
                "the previous document unconditionally.",
            body.contains("ActiveConfigRecovery.restore("),
        )
        assertTrue(
            "`recoverRefusedReload()` no longer brings a core up on the restored config, so a " +
                "refused reload leaves the user disconnected even though the old config is known good.",
            body.contains("startStoredConfig()"),
        )
        assertTrue(
            "`recoverRefusedReload()` no longer reports the case where a newer commit owns the " +
                "config, so that case would be reported as a failed restore.",
            body.contains("RecoveryOutcome.Superseded"),
        )
        val recovery = File("src/main/java/com/interstellar/proxy/data/ActiveConfigApply.kt").readText()
        assertTrue(
            "the recovery no longer compares the committed revision before restoring.",
            recovery.contains("before.meta.revision != candidate.committedRevision"),
        )
        assertTrue(
            "the recovery no longer restores the active config with a compare-and-swap.",
            recovery.contains("restoreIfRevision("),
        )
        assertTrue(
            "the recovery no longer guards the retained body against a newer commit.",
            recovery.contains("restoreRawBodyIfCurrent("),
        )
    }

    // --- one operation, one pill with both results ---------------------------------------------------

    /**
     * The two halves of one update are shown in ONE pill.
     *
     * Measured before the fix: the refusal toast and the provider summary were two `showToast` calls
     * in a row, and the single toast slot meant the second always replaced the first. A two-line
     * pill (`UiToast.secondary`) is what makes both results of one operation readable together.
     */
    @Test
    fun `the two results of one update are shown in a single pill`() {
        val body = code(bodyOf("private suspend fun reportManualUpdateOutcome("))
        assertTrue(
            "`reportManualUpdateOutcome()` no longer combines the halves into one pill.",
            body.contains("showUpdateToast("),
        )
        assertTrue(
            "`reportManualUpdateOutcome()` still fires separate toasts, so the later one replaces the other.",
            !body.contains("showToast("),
        )
        assertTrue(
            "the toast model no longer carries a second line, so both results cannot be shown together.",
            source.contains("data class UiToast(val text: String, val kind: Kind, val secondary: String? = null)"),
        )
    }

    /**
     * A double tap is coalesced into the request already in flight, not sent twice.
     */
    @Test
    fun `a double tap is coalesced instead of queued`() {
        val body = bodyOf("fun refreshSubscription(")
        val guard = body.indexOf("if (!beginUpdate(id))")
        val launch = body.indexOf("viewModelScope.launch(")
        assertTrue("`refreshSubscription()` no longer guards a subscription that is already updating", guard >= 0)
        assertTrue(
            "`refreshSubscription()` starts the second fetch before it checks whether one is already in flight, " +
                "so a double tap issues two requests and two commits for one user action.",
            guard < launch,
        )
        assertInBody(
            "fun refreshSubscription(",
            "endUpdate(id)",
            "`refreshSubscription()` never releases the in-flight slot, so the subscription cannot be updated again.",
        )
        assertInBody(
            "fun refreshSubscription(",
            "vm_update_in_progress",
            "the coalesced tap is swallowed silently; the user cannot tell why nothing happened.",
        )
    }

    /**
     * The periodic worker applies the same ownership rules and the same recovery path as a manual
     * update.
     */
    @Test
    fun `the worker re-checks ownership and shares the reload and recovery path`() {
        val worker = File("src/main/java/com/interstellar/proxy/data/UpdateWorker.kt").readText()
        assertTrue(
            "UpdateWorker no longer captures the instance identity before its batch, so a scheduled run that " +
                "outlives its profile can reload another one.",
            worker.contains("val start = instanceSnapshot()"),
        )
        assertTrue(
            "UpdateWorker no longer re-checks ownership before it applies or refreshes.",
            worker.contains("stillOwns(start, instanceSnapshot(), expectedRevision)"),
        )
        assertTrue(
            "UpdateWorker no longer binds its reload to a receipt, so it cannot tell its own failure " +
                "from a newer operation's.",
            worker.contains("registerReloadIntent(expectedRevision)"),
        )
        assertTrue(
            "UpdateWorker no longer classifies the receipt against the revision it committed.",
            worker.contains("ActiveConfigApply.classify("),
        )
        assertTrue(
            "UpdateWorker no longer restores a refused candidate through the shared recovery.",
            worker.contains("ActiveConfigRecovery.restore("),
        )
        assertTrue(
            "UpdateWorker no longer contains the failed-candidate description the shared recovery needs.",
            worker.contains("ActiveConfigRecovery.FailedCandidate("),
        )
        assertTrue(
            "UpdateWorker no longer attempts to bring a Box back from the restored config.",
            worker.contains("restartStoredConfig()"),
        )
        assertTrue(
            "UpdateWorker no longer proves the restored revision is serving before it refreshes the " +
                "instance's providers.",
            worker.contains("servesRestoredInstance(") &&
                worker.contains("restored instance serving="),
        )
        assertTrue(
            "UpdateWorker reads the body from disk after the failure instead of using the identity it " +
                "committed; a newer raw body can then be rolled back by the old attempt.",
            worker.contains("batch.committedRawBodies") &&
                !worker.contains("committedRawBody = activeIdBefore"),
        )
        val viewModel = File("src/main/java/com/interstellar/proxy/ui/AppViewModel.kt").readText()
        assertTrue(
            "the manual path and the worker no longer share the failed-application recovery.",
            viewModel.contains("ActiveConfigRecovery.restore(") && worker.contains("ActiveConfigRecovery.restore("),
        )
        assertTrue(
            "the worker no longer journals the last-good document before an offline refresh, so a bad " +
                "candidate committed while the tunnel is off cannot be rolled back at the next start.",
            worker.contains("StartupRecovery.rememberBeforeOfflineRefresh(") &&
                worker.indexOf("rememberBeforeOfflineRefresh(") < worker.indexOf("refreshAll(regenerate = false)"),
        )
        val boxService = File("src/main/java/com/interstellar/proxy/bg/BoxService.kt").readText()
        assertTrue(
            "BoxService no longer records the failed start's revision/owner, so the UI cannot tell the " +
                "offline candidate apart from an unrelated start failure.",
            boxService.contains("lastStartFailure = StartFailure(") &&
                boxService.contains("lastStartFailure = null"),
        )
        assertTrue(
            "BoxService no longer confirms a successful start, so the last-good journal would survive a " +
                "successful start and could be replayed later.",
            boxService.contains("StartupRecovery.confirmStarted("),
        )
        assertTrue(
            "the ViewModel no longer performs the bounded startup recovery/retry.",
            viewModel.contains("watchStartupFailure(") &&
                viewModel.contains("StartupRecovery.restore(") &&
                viewModel.contains("startupRecoveryAttempted"),
        )
    }
}
