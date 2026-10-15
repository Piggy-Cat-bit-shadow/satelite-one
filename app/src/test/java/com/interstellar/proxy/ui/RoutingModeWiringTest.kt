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
    fun `setRoutingMode persists the preset before it is acted on`() {
        // The order is the design, not an accident. A change made while the VPN is stopped is
        // applied when a core attaches, and the only thing that survives that gap is the stored
        // preset - so if the setting were written after the check, a stop/start in between would
        // lose the user's choice silently. Persisting first also means a send that fails leaves
        // the intent intact for the next attach.
        val body = code(bodyOf(viewModelSource, "fun setRoutingMode("))
        val persist = body.indexOf("Settings.routingMode =")
        val gate = body.indexOf("Status.Started")
        assertTrue("`setRoutingMode` no longer persists the preset", persist >= 0)
        assertTrue(
            "`setRoutingMode` no longer checks whether a core is running, so it would either " +
                "command nothing or command a service that is not there",
            gate >= 0,
        )
        assertTrue(
            "`setRoutingMode` decides whether to send before it persists: a process death in " +
                "that window would leave the dashboard showing a mode the app never stored, and " +
                "the next attach would silently disagree with what the user saw.",
            persist < gate,
        )
        // The preset must be what the user sees while there is no kernel to ask. Without this
        // the control would appear frozen at the last kernel-reported mode with the VPN off.
        assertTrue(
            "`setRoutingMode` no longer shows the choice while no core is reporting",
            body.contains("_activeRoutingMode.value = mode"),
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
            "`setRoutingMode` no longer routes through sendRoutingMode(), so the send and its " +
                "failure handling are no longer the single implementation this test binds",
            body.contains("sendRoutingMode("),
        )
        val send = code(bodyOf(viewModelSource, "private suspend fun sendRoutingMode("))
        assertTrue(
            "the clash-mode RPC no longer sends `mode.wire`; the wire spelling is now derived " +
                "somewhere else and can drift from the one the generated config declares",
            send.contains("setClashMode(mode.wire)"),
        )
        assertTrue(
            "the clash-mode RPC is no longer the runtime CommandClient call",
            send.contains("CommandTarget.standaloneClient()"),
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

    // --- the kernel is the running state, and it says so itself ------------------------------

    @Test
    fun `the ClashMode channel is actually subscribed`() {
        // The defect this binds: `CommandClient.ConnectionType.ClashMode` and both clash-mode
        // handler hooks existed but were never subscribed or overridden, so the whole channel
        // was dead code and the app had no way to learn what the kernel was doing. Everything
        // else in this class depends on that channel being live.
        assertTrue(
            "the main command client no longer subscribes to ClashMode, so the kernel's mode " +
                "and mode list never reach the app and the UI is back to guessing",
            viewModelCode.contains("CommandClient.ConnectionType.ClashMode"),
        )
    }

    @Test
    fun `both kernel clash-mode callbacks are consumed`() {
        // initializeClashMode carries modeList + currentMode; updateClashMode carries every later
        // change. Between them they are the whole answer to "what can this config do" and "what
        // is it doing", so neither may be dropped.
        val initialize = code(bodyOf(viewModelSource, "override fun initializeClashMode("))
        assertTrue(
            "initializeClashMode no longer feeds the mode list, so the capability is lost",
            initialize.contains("onKernelModeList("),
        )
        assertTrue(
            "initializeClashMode no longer feeds the current mode, so the app never learns " +
                "what the core restored",
            initialize.contains("onKernelMode("),
        )
        assertTrue(
            "the once-per-attach preset alignment is no longer requested on initialize",
            initialize.contains("alignPreset = true"),
        )
        val update = code(bodyOf(viewModelSource, "override fun updateClashMode("))
        assertTrue(
            "updateClashMode no longer feeds the current mode, so a mode changed by anything " +
                "other than this app is invisible",
            update.contains("onKernelMode("),
        )
        assertTrue(
            "updateClashMode must only observe: aligning the preset on every mode report would " +
                "fight the kernel for control of the mode",
            update.contains("alignPreset = false"),
        )
    }

    @Test
    fun `the capability list prefers the kernel over the compiled-in enum`() {
        // `availableRoutingModes` is a capability statement, and only the kernel can make it:
        // it derives the list from the ACTIVE config's clash_mode rules. Hard-coding all three
        // is what makes a raw config with no mode rules look like it supports Direct.
        val body = code(bodyOf(viewModelSource, "private fun onKernelModeList("))
        assertTrue(
            "onKernelModeList no longer publishes what the kernel reported",
            body.contains("_availableRoutingModes.value"),
        )
        assertTrue(
            "onKernelModeList no longer maps the kernel's strings through RoutingMode",
            body.contains("RoutingMode.fromWire"),
        )
        // An EMPTY report means "no information", not "nothing available". Treating it as an
        // empty capability set would grey out all three segments on a config whose mode list
        // merely failed to arrive - a fabricated restriction.
        assertTrue(
            "onKernelModeList acts on an empty list; an empty answer means the query did not " +
                "land, not that nothing is supported",
            body.contains("modeList.isEmpty()"),
        )
    }

    @Test
    fun `the dashboard shows what the kernel reports, not what was stored`() {
        // Two different questions: Settings answers "what should be selected with no core?",
        // the kernel answers "what is routing traffic right now?". If the UI read the stored
        // value while a core was up, a mode the kernel refused would keep looking applied.
        assertTrue(
            "the dashboard no longer reads the running mode from the ViewModel",
            dashboardCode.contains("viewModel.activeRoutingMode.collectAsState()"),
        )
        assertTrue(
            "the dashboard no longer reads the capability list from the ViewModel",
            dashboardCode.contains("viewModel.availableRoutingModes.collectAsState()"),
        )
        assertTrue(
            "the dashboard reads the stored preset as if it were the running mode, so a mode " +
                "the kernel does not support would still look active",
            !dashboardCode.contains("viewModel.presetRoutingMode.collectAsState()"),
        )
    }

    @Test
    fun `a mode the config cannot honour is offered as unavailable rather than hidden`() {
        // The segments are always drawn so the control does not change shape as configs come
        // and go; an unsupported one is disabled and says why. Hiding it would leave the user
        // unable to see that their choice is unavailable.
        val selector = code(bodyOf(dashboardSource, "private fun RoutingModeSelector("))
        // Parameters are part of the SIGNATURE, not the body - `bodyOf` starts at the opening
        // brace, so this has to read the declaration.
        val selectorSignature = dashboardSource
            .substringAfter("private fun RoutingModeSelector(")
            .substringBefore(") {")
        assertTrue(
            "RoutingModeSelector no longer takes the kernel's capability list. Signature was: " +
                "$selectorSignature",
            selectorSignature.contains("available: List<RoutingMode>"),
        )
        assertTrue(
            "the selector no longer passes availability down to the segments",
            selector.contains("enabled = mode in available"),
        )
        assertTrue(
            "the selector no longer draws every mode, so the control changes shape per config",
            selector.contains("RoutingMode.entries.forEach"),
        )
        val segment = code(bodyOf(dashboardSource, "private fun RowScope.RoutingModeSegment("))
        assertTrue(
            "the segment no longer disables itself, so an unsupported mode is clickable and " +
                "silently does nothing",
            segment.contains("enabled = enabled"),
        )
        assertTrue(
            "an unavailable segment no longer explains itself to a screen reader",
            segment.contains("dash_route_unavailable"),
        )
    }

    // --- lifecycle: a preset chosen while stopped must survive --------------------------------

    @Test
    fun `the preset is aligned once, on the kernel's own initialize report`() {
        // The kernel persists and restores its own mode, so on attaching it is reporting real
        // state. The single moment the app may close the gap between "chosen while the VPN was
        // off" and "what the core came up with" is that report - which is a kernel-initiated
        // callback, so there is no polling, no backoff and no dial storm to bound.
        assertTrue(
            "the preset is no longer aligned at all: a mode chosen with the VPN off would only " +
                "take effect if the kernel's own cache happened to agree",
            viewModelCode.contains("presetAligned"),
        )
        val body = code(bodyOf(viewModelSource, "private fun onKernelMode("))
        assertTrue(
            "the alignment no longer checks that the preset is actually available, so it can " +
                "command a mode the kernel will silently ignore",
            body.contains("_availableRoutingModes.value.contains(preset)"),
        )
        assertTrue(
            "the alignment no longer skips a kernel already on the preset",
            body.contains("preset == kernelMode"),
        )
        assertTrue(
            "the alignment is no longer guarded to once per attach, so a reconnect could " +
                "re-apply a preset the user has since changed",
            body.contains("if (presetAligned) return"),
        )
    }

    @Test
    fun `the old inference machinery is gone`() {
        // The status-tick reconcile and its backoff existed only because the kernel's own state
        // channel was not wired. With the channel live they are complexity that re-derives
        // state the kernel already pushes - and the backoff in particular could leave the UI
        // asserting a mode the core had refused. Their return must be a red build.
        assertTrue(
            "the status-tick reconcile is back; the kernel's initialize/update callbacks are " +
                "the authority now",
            !viewModelCode.contains("reconcileRoutingMode"),
        )
        assertTrue(
            "the reconcile backoff is back",
            !viewModelCode.contains("ROUTING_MODE_RETRY_AFTER_NANOS"),
        )
        assertTrue(
            "a failed-send rollback is back; the running mode is the kernel's to report, so " +
                "rolling it back locally re-derives state the kernel owns",
            !viewModelCode.contains("applyRoutingMode"),
        )
    }

    @Test
    fun `a core going away drops the reported mode instead of freezing it`() {
        // Otherwise the dashboard keeps showing the mode of a core that no longer exists, and
        // the next attach would look like it had already reported.
        val body = code(bodyOf(viewModelSource, "private fun markStopped()"))
        assertTrue(
            "markStopped no longer clears the kernel-reported mode, so a dead core's mode stays " +
                "on screen",
            body.contains("_kernelRoutingMode.value = null"),
        )
        assertTrue(
            "markStopped no longer resets the capability list, so a raw config's restriction " +
                "would survive into the next, unrelated config",
            body.contains("_availableRoutingModes.value = RoutingMode.entries.toList()"),
        )
        assertTrue(
            "markStopped no longer allows the next attach to align the preset again",
            body.contains("presetAligned = false"),
        )
    }

    @Test
    fun `mode commands cannot interleave on the one command socket`() {
        // Serialisation is what keeps rapid taps reaching the core in the order they were made.
        // The kernel is the state authority, so a reordered pair would be *reported* honestly -
        // but the user would still get the wrong mode.
        val send = code(bodyOf(viewModelSource, "private suspend fun sendRoutingMode("))
        assertTrue(
            "the clash-mode RPC is no longer serialised, so concurrent sends can reach the " +
                "core out of the order the user tapped them in",
            send.contains("routingModeSend.withLock"),
        )
        assertTrue(
            "the RPC must be INSIDE the lock; wrapping only the surrounding work would not " +
                "order the calls themselves",
            send.indexOf("routingModeSend.withLock") < send.indexOf("setClashMode(mode.wire)"),
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
            dashboardCode.contains("viewModel.activeRoutingMode.collectAsState()"),
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
