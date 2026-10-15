package com.interstellar.proxy.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The wiring that makes Rule / Global / Direct a *real* control rather than three buttons.
 *
 * # Why this reads source instead of driving the ViewModel
 *
 * `AppViewModel` is an `AndroidViewModel`, there is no Robolectric on this host and no
 * `androidTest` source set, so it cannot be constructed in a plain JVM test. This is the same
 * technique — and the same stated limit — as [AppViewModelWiringTest]: it binds call sites
 * that no behavioural test on this host can reach. `RoutingModeTest` covers the pure logic;
 * `MinimalConfigBuilderTest` covers the config that makes a mode mean something; this file
 * covers only the connections between them.
 *
 * # The failure this file exists to prevent
 *
 * A previous generation of this app shipped exactly this feature and it did not work. The
 * commit that removed it recorded the root cause: the mode was written into the generated
 * config, so `setClashMode` changed a setting and nothing else — and the kernel's mode does
 * not alter routing at all unless a *rule* reads it. Two independent halves therefore have to
 * be present at once:
 *
 *  1. the generated config declares `clash_mode` rules (pinned in `MinimalConfigBuilderTest`), and
 *  2. the app actually calls the one-shot `setClashMode` RPC on the running core.
 *
 * Either half alone produces a UI that looks correct and traffic that does not change, so
 * each is asserted rather than assumed. The checks below are deliberately about ORDER and
 * GATING too, not mere presence: "the call exists somewhere in the file" was already fooled
 * once in this project by a call sitting in a different branch of the same function.
 */
class RoutingModeWiringTest {

    private val viewModelSource: String =
        File("src/main/java/com/interstellar/proxy/ui/AppViewModel.kt").readText()

    private val dashboardSource: String =
        File("src/main/java/com/interstellar/proxy/ui/pages/DashboardPage.kt").readText()

    /** Comment-stripped, so positions and counts are about CODE and not the prose about it. */
    private fun code(source: String): String =
        source.replace(Regex("(?s)/\\*.*?\\*/"), " ").replace(Regex("//[^\n]*"), " ")

    private val viewModelCode: String by lazy { code(viewModelSource) }
    private val dashboardCode: String by lazy { code(dashboardSource) }

    /** The body of the declaration containing [signature], delimited by brace balance. */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(
            "`$signature` is gone from the file under test, so the wiring this test binds has " +
                "moved or been renamed and is no longer verified",
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

    // --- the control chain: intent -> persistence -> runtime command ----------------------

    @Test
    fun `setRoutingMode persists the intent before it is shown or sent`() {
        // The order is the design, not an accident. A change made while the VPN is stopped is
        // applied at the next start, and the only thing that survives that gap is the stored
        // value - so if the setting is written after the (skipped) send, a stop/start between
        // the two loses the user's choice silently. Persisting first also means a failed send
        // leaves the intent intact for the reconcile to retry.
        val body = code(bodyOf(viewModelSource, "fun setRoutingMode("))
        val persist = body.indexOf("Settings.routingMode =")
        val publish = body.indexOf("_routingMode.value =")
        assertTrue("`setRoutingMode` no longer persists the mode", persist >= 0)
        assertTrue("`setRoutingMode` no longer publishes it to the UI", publish >= 0)
        assertTrue(
            "`setRoutingMode` publishes to the UI before it persists: a process death in that " +
                "window would leave the dashboard showing a mode the app never stored, and the " +
                "next start would silently disagree with what the user saw.",
            persist < publish,
        )
    }

    @Test
    fun `setRoutingMode sends the kernel the canonical wire spelling`() {
        // `setClashMode(mode.wire)` and not `mode.name` / `mode.toString()`: the kernel's
        // SetMode matches EXACT case first and only then falls back to an equal-fold search,
        // and the mode list it derives from the rules contains the canonical literal. Sending
        // the enum name would rely on the font of tolerance the enum exists to remove.
        val body = code(bodyOf(viewModelSource, "fun setRoutingMode("))
        assertTrue(
            "`setRoutingMode` no longer routes through applyRoutingMode(), so the send and its " +
                "failure handling are no longer the single implementation this test binds",
            body.contains("applyRoutingMode("),
        )
        val apply = code(bodyOf(viewModelSource, "private suspend fun applyRoutingMode("))
        assertTrue(
            "the clash-mode RPC no longer sends `mode.wire`; the wire spelling is now derived " +
                "somewhere else and can drift from the one the generated config declares",
            apply.contains("setClashMode(mode.wire)"),
        )
        assertTrue(
            "the clash-mode RPC is no longer the runtime CommandClient call",
            apply.contains("CommandTarget.standaloneClient()"),
        )
        // No accidental second control channel: this is the same one-shot client every other
        // runtime command uses (selectOutbound, urlTest, serviceReload).
        assertTrue(
            "a second command channel was introduced for the routing mode",
            !viewModelCode.contains("external_controller"),
        )
    }

    @Test
    fun `the runtime command is only sent when the core is actually started`() {
        // `SetClashMode` answers `invalid argument` unless the service reports STARTED, so an
        // unconditional send while stopped is a guaranteed failure - and, worse, a failure
        // that would be reported to a user who did nothing wrong.
        val body = code(bodyOf(viewModelSource, "fun setRoutingMode("))
        assertTrue(
            "`setRoutingMode` no longer checks the status before sending; every switch made " +
                "while the VPN is off would surface a spurious failure",
            body.contains("Status.Started"),
        )
    }

    @Test
    fun `a change is never suppressed by an earlier reconcile`() {
        // `routingModeApplied` is the once-per-session guard for the reconcile. If a user
        // change did not clear it, the guard would swallow the very switch the user just made
        // and the core would keep the previous mode - the "UI says Direct, traffic says Rule"
        // defect, caused by the guard itself.
        val body = code(bodyOf(viewModelSource, "fun setRoutingMode("))
        assertTrue(
            "`setRoutingMode` no longer clears routingModeApplied, so a mode chosen after the " +
                "start-up reconcile would be dropped",
            body.contains("routingModeApplied = false"),
        )
    }

    // --- lifecycle: a mode chosen while stopped must survive ---------------------------------

    @Test
    fun `the stored mode is re-asserted once the core is serving`() {
        // The kernel persists the mode itself, but that cache is NOT the user's intent: a raw
        // config brings its own mode list, and a stored mode that is not in it is discarded on
        // restore. The app setting is authoritative, so it is re-sent - and the kernel's
        // SetMode returns early when the value already matches, so re-sending is free.
        assertTrue(
            "reconcileRoutingMode() is gone: a mode chosen while the VPN was stopped would " +
                "only be applied if the kernel's own cache happened to agree",
            viewModelCode.contains("reconcileRoutingMode()"),
        )
    }

    @Test
    fun `the reconcile is driven by the status tick, not by the connect callback`() {
        // Measured against the pinned core: `daemon.StartedService.SetClashMode` returns
        // os.ErrInvalid unless the service status is STARTED. A StatusMessage is only emitted
        // once the tun is up, so the status handler is the first moment the RPC can succeed;
        // sending from onConnected() instead would race the very state being reconciled.
        val statusHandler = code(bodyOf(viewModelSource, "override fun updateStatus("))
        assertTrue(
            "the routing-mode reconcile is no longer triggered from the status tick, so it can " +
                "run before the service reports STARTED and fail with `invalid argument`",
            statusHandler.contains("reconcileRoutingMode()"),
        )
        val onConnected = code(bodyOf(viewModelSource, "override fun onConnected()"))
        assertTrue(
            "the reconcile was moved into onConnected(), which fires as soon as the command " +
                "socket attaches - before the service is STARTED, where SetClashMode is rejected",
            !onConnected.contains("reconcileRoutingMode()"),
        )
    }

    @Test
    fun `the reconcile runs once per session`() {
        // The status stream ticks at 1 Hz. Without the guard this would issue a command socket
        // connect and an RPC every second for the life of the tunnel.
        val body = code(bodyOf(viewModelSource, "private fun reconcileRoutingMode()"))
        assertTrue(
            "`reconcileRoutingMode` lost its once-per-session guard and now re-sends on every " +
                "1 Hz status tick",
            body.contains("if (routingModeApplied) return"),
        )
    }

    @Test
    fun `a fresh core is allowed to be told again`() {
        // Same ownership rule as selectionRestoreAttempted, reset in the same place: a new core
        // is a new runtime, and a guard left set across a restart would skip the re-assert.
        val body = code(bodyOf(viewModelSource, "private fun markStopped()"))
        assertTrue(
            "`markStopped` no longer clears routingModeApplied, so after the first stop/start " +
                "the routing mode would never be re-applied to the new core",
            body.contains("routingModeApplied = false"),
        )
    }

    @Test
    fun `a failed send cannot leave the dashboard claiming a mode the core refused`() {
        // The policy is rollback-and-report, and both halves matter. Rolling back without
        // reporting hides a real failure; reporting without rolling back leaves the UI showing
        // a mode the kernel rejected. The reconcile path has nothing to roll back to (the
        // stored value IS the intent), so it re-arms instead - also asserted, because a guard
        // left set after a failed reconcile would mean "retry never".
        val apply = code(bodyOf(viewModelSource, "private suspend fun applyRoutingMode("))
        assertTrue("a failed switch no longer rolls the UI state back", apply.contains("Settings.routingMode = previous.wire"))
        assertTrue("a failed switch no longer rolls the stored value back", apply.contains("_routingMode.value = previous"))
        assertTrue("a failed switch is no longer reported", apply.contains("vm_routing_mode_failed"))
        assertTrue(
            "a failed reconcile no longer re-arms the guard, so the mode would never be " +
                "retried for the life of that core",
            apply.contains("routingModeApplied = false"),
        )
    }

    // --- UI: one source of truth, and two axes that stay separate ---------------------------

    @Test
    fun `the dashboard renders the selector from ViewModel state`() {
        // Not from Compose `remember`: a configuration change (theme switch, rotation,
        // font scale) re-creates the composition, and a remembered mode would silently snap
        // back to the default while the core kept the user's choice.
        assertTrue(
            "the dashboard no longer binds the routing mode to ViewModel state, so it can " +
                "survive a recomposition only by accident",
            dashboardCode.contains("viewModel.routingMode.collectAsState()"),
        )
        assertTrue(
            "the dashboard no longer renders the routing-mode selector at all",
            dashboardCode.contains("RoutingModeSelector("),
        )
        assertTrue(
            "the dashboard stores the routing mode in a Compose `remember` instead of reading " +
                "the ViewModel - the UI would drift from Settings after a configuration change",
            !dashboardCode.contains("remember { mutableStateOf(RoutingMode"),
        )
    }

    @Test
    fun `the routing mode is not merged into the node-selection control`() {
        // The two axes must not share state. "自动/手动" reports whether the MAIN SELECTOR
        // points at the urltest group or at a node, and is derived from selectedOutboundTag;
        // the routing mode decides whether the route rules run. If the routing chip were ever
        // fed from selectedOutboundTag (the tempting shortcut - `direct` is also an outbound
        // tag), picking the `direct` node would appear to switch to 直连 while changing
        // nothing about the route, and 直连 would appear to select a node.
        assertTrue(
            "the routing-mode selector is now driven by selectedOutboundTag, which merges the " +
                "node pick with the routing mode",
            !dashboardCode.contains("RoutingModeSelector(\n                selected = storedSelected"),
        )
        // The selector's parameter is the enum, not a tag string: a String parameter would be
        // the seam through which a node tag ("direct", "auto") could be passed in as a mode.
        // The declaration line is checked rather than the body, because the parameter type is
        // part of the SIGNATURE - the first version of this test read the body and could not
        // have passed for any implementation.
        val declaration = dashboardSource.substringAfter("private fun RoutingModeSelector(").substringBefore(")")
        assertTrue(
            "RoutingModeSelector no longer takes a RoutingMode; a looser parameter type is how " +
                "a selector tag gets smuggled in as a routing mode. Declaration was: $declaration",
            declaration.contains("selected: RoutingMode"),
        )
        assertTrue(
            "RoutingModeSelector takes something other than the RoutingMode enum as its value; " +
                "declaration was: $declaration",
            !declaration.contains("selected: String"),
        )
        // The node-selection chip keeps its own source and its own destination.
        assertTrue(
            "the node-selection chip no longer reads selectedOutboundTag",
            dashboardCode.contains("viewModel.selectedOutboundTag.collectAsState()"),
        )
    }

    @Test
    fun `the selector keeps the axis in its accessibility semantics`() {
        // "规则" alone does not say what it changes, and the node chip beside it is also called
        // a "模式". A screen-reader user has to be told which axis a segment belongs to.
        val segment = code(bodyOf(dashboardSource, "private fun RowScope.RoutingModeSegment("))
        assertTrue(
            "the routing-mode segments no longer announce their value",
            segment.contains("contentDescription"),
        )
        assertTrue(
            "the routing-mode segments must report selection state, not only draw it",
            segment.contains("selectable("),
        )
        val group = code(bodyOf(dashboardSource, "private fun RoutingModeSelector("))
        assertTrue(
            "the group no longer declares itself a single choice set",
            group.contains("selectableGroup()"),
        )
    }
}
