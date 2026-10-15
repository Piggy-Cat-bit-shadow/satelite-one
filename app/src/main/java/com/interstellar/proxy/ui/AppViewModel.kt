package com.interstellar.proxy.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.interstellar.proxy.constant.Action
import com.interstellar.proxy.constant.Status
import com.interstellar.proxy.data.ConfigStore
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.data.SubscriptionRepository
import com.interstellar.proxy.data.config.MinimalConfigBuilder
import com.interstellar.proxy.data.model.RoutingMode
import com.interstellar.proxy.data.net.SubscriptionFetcher
import com.interstellar.proxy.data.subscription.SubscriptionParser
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.StatusMessage
import com.interstellar.proxy.core.CoreGroup
import com.interstellar.proxy.core.CoreGroupItem
import com.interstellar.proxy.utils.CommandClient
import com.interstellar.proxy.utils.CommandTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import com.interstellar.proxy.data.subscription.str

data class SpeedState(
    val uplinkPerSecond: Long = 0,
    val downlinkPerSecond: Long = 0,
    val uplinkTotal: Long = 0,
    val downlinkTotal: Long = 0,
    /**
     * Whether the kernel actually has a traffic manager for the running config.
     *
     * For a libbox client this is true in practice: the manager is created when
     * `needClashAPI || needAPIService || options.PlatformLogWriter != nil`, and
     * libbox always satisfies the last branch (daemon/instance.go sets
     * `PlatformLogWriter: s` in StartedService.newInstance, config-independently).
     *
     * The flag is still carried and honoured rather than assumed, because the core
     * is the authority: if it ever reports that it has no statistics, rendering
     * `0 kB/s` would claim a measurement that never happened. "Not measured" and
     * "measured zero" are different statements.
     */
    val trafficAvailable: Boolean = false,
)

/**
 * How byte counters should present themselves.
 *
 * Pure so all three states can be unit-tested without a kernel. [Unavailable] is
 * kept distinct from a zero reading on purpose: rendering `0 kB/s` for a config
 * that has no traffic manager at all would claim a measurement that never
 * happened.
 */
enum class TrafficDisplay { Idle, Unavailable, Live }

/** [running] is the core status; [trafficAvailable] is StatusMessage.trafficAvailable. */
fun trafficDisplay(running: Boolean, trafficAvailable: Boolean): TrafficDisplay = when {
    !running -> TrafficDisplay.Idle
    trafficAvailable -> TrafficDisplay.Live
    else -> TrafficDisplay.Unavailable
}

/**
 * Byte counter for the UI: never disguises "no statistics" as a measured zero.
 *
 * [format] is injectable so this policy is unit-testable without libbox (the real
 * formatter is a native call).
 */
fun bytesOrUnknown(
    value: Long,
    trafficAvailable: Boolean,
    format: (Long) -> String = { io.nekohasekai.libbox.Libbox.formatBytes(it) },
): String = if (trafficAvailable) format(value) else "—"

/**
 * Kernel runtime footprint, taken verbatim from the existing Status stream.
 *
 * Definitions, verified against the pinned core rather than assumed
 * (`daemon/started_service.go` `readStatus`):
 *
 *  - `memory` = `memory.Total()`. On Android that is `common/memory`'s
 *    `totalNative()`, which reads **`/proc/self/statm`** and returns the resident
 *    field × page size. It is therefore the **resident set size of this whole
 *    process** — libbox is linked in-process, so that includes the Kotlin/Java heap
 *    and every native allocation, not just Go's. It is **RSS, not PSS**: shared
 *    pages count in full, so it does not sum across processes and is not
 *    comparable to `dumpsys meminfo`'s TOTAL PSS.
 *  - `goroutines` = `runtime.NumGoroutine()` inside sing-box — Go goroutines, NOT a
 *    Kotlin coroutine count.
 *
 * Both arrive on the StatusMessage we already subscribe to, so the home page needs
 * no extra command client, no polling and no /proc reading of its own.
 *
 * One sample is a snapshot, not a trend: it must not be quoted as evidence of a
 * leak, of a fix, or as a memory budget.
 */
data class RuntimeState(
    val memoryBytes: Long = 0,
    val goroutines: Int = 0,
)

/**
 * Resolve which group a manual url-test should target.
 *
 * urltest groups (auto) are testable directly, so the requested tag
 * is honoured. The main entry is a *selector* whose members mix group and node
 * tags; the kernel's per-item pass skips those ("大量未测"), so a selector (or an
 * unknown/blank tag) falls back to [MinimalConfigBuilder.AUTO_TAG], whose members are
 * every node. Pure so the mapping is unit-testable without a ViewModel.
 *
 * `auto` is a name the APP's own generated config gives its urltest group. A user-supplied
 * raw config has no such outbound, and falling back to it unconditionally sent
 * `URLTest("auto")` at a config that does not define it:
 *
 *     Test failed: url test: rpc error: code = NotFound desc = outbound not found: auto
 *
 * which is why the latency test could never run on a raw config - the group the user was
 * looking at was fully populated, and every run tested nothing at all. So `auto` is used
 * only when the running config actually has it; otherwise the group the user asked for is
 * tested directly.
 */
internal fun resolveUrlTestTarget(groupTag: String, groups: List<CoreGroup>): String {
    val auto = groups.find { it.tag == MinimalConfigBuilder.AUTO_TAG }
    val live = groups.find { it.tag == groupTag && groupTag.isNotBlank() }

    // Unknown or blank: the config's own `auto` when it exists, else whatever group this
    // config does offer, rather than a name nothing defines.
    if (live == null) {
        return auto?.tag ?: groups.firstOrNull()?.tag ?: MinimalConfigBuilder.AUTO_TAG
    }
    // A urltest group is testable under its own tag.
    if (live.type.equals("urltest", ignoreCase = true)) return live.tag
    // A selector: `auto` (every node) is the better target when this config has one,
    // because the kernel skips members that are themselves groups...
    if (auto != null) return auto.tag
    // ...and when it does not, the selector itself is what the user is looking at.
    return live.tag
}

/**
 * The node tags a batch url-test submits, from the members of the group it targets.
 *
 * This is the single `eligibleNodes` source: the progress denominator, the completion condition
 * and the result summary all resolve from it, so they cannot disagree. Two independent
 * derivations are what let a run report "0 nodes · 0 ok · 0 failed" beside five real latencies
 * while also never reaching completion.
 *
 * A member that is itself a group (`selector` / `urltest`) is not an endpoint, and the kernel's
 * per-item pass skips it - so counting it would put a node in the denominator that can never
 * report. `direct` and `block` are deliberately NOT excluded: they are endpoints the kernel does
 * measure, and dropping them here would make N disagree with what was actually submitted, which
 * is the one thing this number must not do.
 *
 * Pure, so the counting rules are unit-testable without a core or a device.
 */
internal fun eligibleUrlTestTags(items: List<CoreGroupItem>): List<String> =
    items
        .filterNot { it.type.equals("selector", ignoreCase = true) }
        .filterNot { it.type.equals("urltest", ignoreCase = true) }
        .map { it.tag }
        .filter { it.isNotBlank() }
        .distinct()

/** Transient feedback pill (subscription updates etc.). */
data class UiToast(val text: String, val kind: Kind) {
    enum class Kind { Success, Error }
}

/**
 * Decide whether the node the user last picked still has to be pushed to a running core.
 *
 * A pick made while the VPN is **stopped** is only honoured immediately for a *generated*
 * config: `SubscriptionRepository` bakes it into the selector's `default` when it rewrites
 * the JSON. A **raw** config is passed through untouched (that is the documented contract
 * for a user-supplied sing-box config), so nothing carries the pick into the core. The
 * nodes page meanwhile shows `Settings.selectedOutboundTag` as the selection - so the UI
 * claimed a choice the core had never been told about, and after a restart the core ran
 * the config's own `default` while the page pointed at a different node.
 *
 * The restore is deliberately conservative; every one of these returns null, which leaves
 * the config's `default` in force rather than guessing:
 *
 *  - no group is known for the stored tag (an older value, or a pick from another config);
 *  - the group is gone from the live config;
 *  - the group is a `urltest` - those choose their own member, a manual pick is meaningless;
 *  - the item is not a member of that group any more (a stale tag must never be pushed);
 *  - the group is already on that item, so there is nothing to restore.
 *
 * Pure, so the decision is unit-testable without a device or a core.
 *
 * @return the `group to item` to select, or null when nothing is owed.
 */
internal fun pendingSelectionRestore(
    storedGroup: String,
    storedItem: String,
    groups: List<CoreGroup>,
): Pair<String, String>? {
    if (storedGroup.isBlank() || storedItem.isBlank()) return null
    val group = groups.find { it.tag == storedGroup } ?: return null
    if (!group.type.equals("selector", ignoreCase = true)) return null
    if (group.items.none { it.tag == storedItem }) return null
    if (group.selected == storedItem) return null
    return group.tag to storedItem
}

/**
 * App-wide state holder: box status via the local command socket,
 * subscriptions, and start/stop orchestration.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    /** Localized string following the CURRENT language (live, no restart needed). */
    private fun str(@androidx.annotation.StringRes id: Int): String =
        com.interstellar.proxy.ktx.AppLanguage.getString(getApplication(), id)

    private fun str(@androidx.annotation.StringRes id: Int, vararg formatArgs: Any): String =
        com.interstellar.proxy.ktx.AppLanguage.getString(getApplication(), id, *formatArgs)

    private val _status = MutableStateFlow(Status.Stopped)
    val status: StateFlow<Status> = _status

    private val _connectedAt = MutableStateFlow(0L)
    val connectedAt: StateFlow<Long> = _connectedAt

    private val _speed = MutableStateFlow(SpeedState())
    val speed: StateFlow<SpeedState> = _speed

    private val _runtime = MutableStateFlow(RuntimeState())
    val runtime: StateFlow<RuntimeState> = _runtime

    /** Per-second (down, up) samples for the live chart, newest last. */
    private val _history = MutableStateFlow<List<Pair<Long, Long>>>(emptyList())
    val history: StateFlow<List<Pair<Long, Long>>> = _history

    private val _groups = MutableStateFlow<List<CoreGroup>>(emptyList())
    val groups: StateFlow<List<CoreGroup>> = _groups

    /**
     * Groups parsed from the on-disk active config — what the page shows while
     * the core is stopped (live groups only exist once it runs).
     */
    private val _staticGroups = MutableStateFlow<List<CoreGroup>>(emptyList())
    val staticGroups: StateFlow<List<CoreGroup>> = _staticGroups

    /** App-proxy scope as the UI sees it (drives the dashboard status row). */
    data class ProxyScope(val whitelist: Boolean, val count: Int) {
        /** Per-app routing armed at all (label text is decided by the caller's locale). */
        val on: Boolean
            get() = com.interstellar.proxy.data.Settings.perAppProxyEnabled
    }

    private val _proxyScope = MutableStateFlow(readProxyScope())
    val proxyScope: StateFlow<ProxyScope> = _proxyScope

    private fun readProxyScope() = ProxyScope(
        whitelist = Settings.perAppProxyMode == Settings.PER_APP_PROXY_INCLUDE,
        count = Settings.perAppProxyList.size,
    )

    /** tag → latest url-test delay (pushed via the outbounds stream). */
    private val _delays = MutableStateFlow<Map<String, Int>>(emptyMap())
    val delays: StateFlow<Map<String, Int>> = _delays

    /**
     * True while a manual url-test RPC is in flight - nothing more.
     *
     * Mirrors the official client, whose `GroupsViewModel.urlTestGroup` adds the tag to
     * `testingGroups`, awaits the CommandClient call, and removes it in `finally`. No polling, no
     * timestamp gate, no settle wait: the call IS the lifecycle.
     *
     * In the pinned core `StartedService.URLTest` schedules its round with `go ...` and returns
     * immediately, so this window is short by design. The delays keep arriving afterwards on the
     * outbounds stream, and those are what the user actually reads off the page.
     */
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing

    /**
     * Batch progress (done, total) - DISPLAY ONLY.
     *
     * It must not decide whether a run is over: not `_testing`, not the mutex, not the epoch.
     * Its lifetime is a display concern (it ends once the run's results have all arrived, or when
     * the user dismisses the banner). That is the "downgrade done >= total from a lifecycle
     * condition to pure UI information" this refactor exists for.
     *
     * Null means "show no numbers": when the target's size cannot be established the page says
     * 测速中… rather than inventing a denominator, because a wrong 47/52 is worse than no count.
     */
    private val _testProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val testProgress: StateFlow<Pair<Int, Int>?> = _testProgress

    /**
     * Nodes of the batch run currently in flight, or of the run that just finished.
     *
     * Published so the result summary counts over the very set the run submitted instead of
     * deriving its own. Two independent derivations are how the summary came to report
     * "0 nodes · 0 ok · 0 failed" beside five real latencies.
     */
    private val _testTags = MutableStateFlow<List<String>>(emptyList())
    val testTags: StateFlow<List<String>> = _testTags

    /**
     * This run's outcomes: tag → delay, for the nodes that actually reported during it.
     *
     * Deliberately NOT the `delays` map. `delays` accumulates across runs, so after a run where
     * every node timed out it still holds the previous run's successes - and the summary then
     * reported "5 nodes · 5 ok · 0 failed" for a run in which nothing succeeded. A node missing
     * from here did not succeed in this run, which is what makes failure and timeout countable.
     */
    private val _testResult = MutableStateFlow<Map<String, Int>>(emptyMap())
    val testResult: StateFlow<Map<String, Int>> = _testResult

    /**
     * Epoch seconds when the current run started (0 = no run to attribute stamps to).
     *
     * A DISPLAY FILTER only: it decides which streamed delays belong to this run, so the progress
     * numbers and the summary describe this run rather than the accumulated `delays` cache.
     *
     * It deliberately no longer gates anything. It used to be polled by `awaitUrlTestSettled`
     * ("while testStartEpoch > 0") to decide the client was finished, which is the client-side
     * lifecycle this refactor removes - the core had long since returned.
     */
    @Volatile private var testStartEpoch = 0L

    /**
     * The ONE source of truth for a batch run: the node tags actually submitted to it.
     *
     * Both the progress denominator and the completion condition read this set, so they cannot
     * disagree. It previously did not exist and each side guessed separately - the denominator
     * from the `proxy` group (absent in a raw config), else the size of the delays map, else the
     * subscription pool; the numerator from stamps across the WHOLE outbounds snapshot, `direct`,
     * `block`, selectors and hidden nodes included. A numerator and a denominator drawn from
     * different sets can never meet, so a run sat at "测速中" until its 25 s cap every time and
     * the bar never reached N/N.
     */
    @Volatile private var testRunTags: Set<String> = emptySet()

    /**
     * tag → newest urlTestTime seen during the current run.
     *
     * Accumulated across snapshots instead of read from the latest one: `updateOutbounds` carries
     * whatever subset the kernel chose to push, so counting within a single snapshot would drop
     * results that arrived earlier and stall the run again.
     */
    private val testRunStamps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Wall clock at run start, for the per-node and total durations in the log. */
    @Volatile private var testRunStartedAtMs = 0L

    /** Nodes whose end time has already been logged, so each is reported exactly once. */
    private val testRunReported = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Serializes kernel url-test requests, and does nothing else.
     *
     * Its only job is to stop a manual test and a smart-mode test from overlapping. It must not
     * carry a completion judgement, a progress judgement, a timestamp lifetime or a timeout
     * lifetime - a lock that also encodes "the test is over" is how one run's stream could settle
     * another run's wait.
     */
    private val kernelUrlTestMutex = kotlinx.coroutines.sync.Mutex()

    private val _subscriptions =
        MutableStateFlow(SubscriptionRepository.subscriptions.toList())
    val subscriptions: StateFlow<List<SubscriptionRepository.Subscription>> = _subscriptions

    private val _activeSubscriptionId =
        MutableStateFlow(SubscriptionRepository.activeSubscriptionId)
    val activeSubscriptionId: StateFlow<String> = _activeSubscriptionId

    /** Mix: node pool = union of the checked subscriptions. */
    private val _mixEnabled = MutableStateFlow(Settings.mixEnabled)
    val mixEnabled: StateFlow<Boolean> = _mixEnabled

    private val _mixSubscriptionIds = MutableStateFlow(Settings.mixSubscriptionIds)
    val mixSubscriptionIds: StateFlow<Set<String>> = _mixSubscriptionIds

    private val _selectedOutboundTag = MutableStateFlow(Settings.selectedOutboundTag)
    val selectedOutboundTag: StateFlow<String> = _selectedOutboundTag

    /**
     * Routing mode (Rule / Global / Direct) — the UI's view of `Settings.routingMode`.
     *
     * A separate axis from [selectedOutboundTag]: that one picks *which* outbound the
     * selector points at, this one decides whether the route rules run at all. They are
     * both persisted, both restored on construction, and neither is derived from the other.
     *
     * Seeded from the persisted value, not from the core: the core is not necessarily
     * running, and on a cold start the UI must be able to render the mode the user chose
     * before anything has been attached. Reconciliation with the kernel happens once the
     * service is up — see [reconcileRoutingMode].
     */
    private val _routingMode = MutableStateFlow(RoutingMode.fromWire(Settings.routingMode) ?: RoutingMode.DEFAULT)
    val routingMode: StateFlow<RoutingMode> = _routingMode

    /** Nodes page layout: grid (default) or list; persisted. */
    private val _nodesGridView = MutableStateFlow(Settings.nodesGridView)
    val nodesGridView: StateFlow<Boolean> = _nodesGridView

    fun setNodesGridView(value: Boolean) {
        if (value == _nodesGridView.value) return
        Settings.nodesGridView = value
        _nodesGridView.value = value
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** True while a subscription URL/text import is in flight (dialog spinner). */
    private val _addingSub = MutableStateFlow(false)
    val addingSub: StateFlow<Boolean> = _addingSub

    private var addSubJob: kotlinx.coroutines.Job? = null

    /** Last import failure, surfaced INSIDE the add dialog (modal covers page toasts). */
    private val _addSubError = MutableStateFlow<String?>(null)
    val addSubError: StateFlow<String?> = _addSubError

    /** Abort an in-flight subscription import (dialog 取消). */
    fun cancelAddSubscription() {
        addSubJob?.cancel()
        addSubJob = null
        _addingSub.value = false
        _addSubError.value = null
        _message.value = str(com.interstellar.proxy.R.string.vm_import_cancelled)
    }

    /** True while any subscription refresh triggered by pull-to-refresh runs. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _toast = MutableStateFlow<UiToast?>(null)
    val toast: StateFlow<UiToast?> = _toast

    private var toastClearJob: Job? = null

    private val delaysMutex = kotlinx.coroutines.sync.Mutex()

    fun showToast(text: String, kind: UiToast.Kind) {
        _toast.value = UiToast(text, kind)
        toastClearJob?.cancel()
        toastClearJob = viewModelScope.launch {
            delay(2800)
            _toast.value = null
        }
    }


    private var pollJob: Job? = null
    private var startingWatchdog: Job? = null
    private var stoppedReceiver: BroadcastReceiver? = null
    private var autoTested = false
    @Volatile private var probing = false

    /**
     * When the UI's command-socket client should be on, and whether its failure may drive UI
     * status. The rules live in that class so they are provable in a plain JVM test; the side
     * effects stay here. See [UiClientLifetime] for why backgrounding must not be allowed to
     * look like a socket drop.
     */
    private val uiLifetime = UiClientLifetime()

    /** Set by onConnected while probing: the headless command socket is up. */
    @Volatile private var probeSocketUp = false

    private companion object {
        const val GROUP_TAG = "proxy"
        const val STARTING_TIMEOUT_MS = 15_000L
        const val SOCKET_DROP_TIMEOUT_MS = 400L

        /** Minimum spacing between failed routing-mode reconciles; see [reconcileRoutingMode]. */
        const val ROUTING_MODE_RETRY_AFTER_NANOS = 30_000_000_000L

        /** Sentinel delay for timed-out / unreachable nodes (url-test). */
        const val TIMEOUT_DELAY = 65535
    }

    private val commandClient = CommandClient(
        viewModelScope,
        listOf(
            CommandClient.ConnectionType.Status,
            CommandClient.ConnectionType.Groups,
            // delay updates stream through outbounds, not the groups snapshot
            CommandClient.ConnectionType.Outbounds,
        ),
        object : CommandClient.Handler {
            override fun onConnected() {
                if (probing) {
                    // headless probe: don't promote the UI to connected, just
                    // flag that the command socket is reachable
                    probeSocketUp = true
                    return
                }
                // App relaunch while the core is already up.
                if (_status.value == Status.Stopped) {
                    markStarted()
                }
            }

            override fun onDisconnected() {
                // An intentional suspend fires this callback too - CommandClient.disconnect()
                // notifies handlers on the deliberate path, un-gated, because it IS deliberate.
                // Treating it as a socket drop is what would kill the tunnel 400 ms after the
                // user pressed Home, so the deliberate case must be excluded here rather than
                // papered over at the call site.
                autoTested = false
                if (uiLifetime.mayReportSocketLoss(probing)) applySocketDrop()
            }

            override fun onConnectionError(kind: CommandClient.ConnectionErrorKind, message: String) {
                if (uiLifetime.mayReportSocketLoss(probing)) applySocketDrop()
            }

            override fun updateStatus(status: StatusMessage) {
                if (probing) return
                // Status ticks only after the tun is actually running — this is
                // what promotes Starting → Started, so a socket bounce during
                // boot cannot flash Stopped.
                if (_status.value == Status.Starting) {
                    markStarted()
                }
                // First tick after the tun is up: the SetClashMode RPC answers
                // "invalid argument" until the service reports STARTED, so the stored
                // routing mode is (re-)asserted here rather than from onConnected().
                // Guarded internally, so the 1 Hz stream sends it once per session.
                reconcileRoutingMode()
                _speed.value = SpeedState(
                    uplinkPerSecond = status.uplink,
                    downlinkPerSecond = status.downlink,
                    uplinkTotal = status.uplinkTotal,
                    downlinkTotal = status.downlinkTotal,
                    // A kernel without the traffic manager reports 0 here AND sets
                    // this false — the two must be carried together or the UI cannot
                    // tell "idle" from "not measured".
                    trafficAvailable = status.trafficAvailable,
                )
                _runtime.value = RuntimeState(
                    memoryBytes = status.memory,
                    goroutines = status.goroutines,
                )
                val sample = status.downlink to status.uplink
                _history.value = (_history.value + sample).takeLast(60)
            }

            override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
                _groups.value = newGroups.map(::convertGroup)
                restorePendingSelection()
            }

            override fun updateOutbounds(outbounds: List<io.nekohasekai.libbox.OutboundGroupItem>) {
                val map = _delays.value.toMutableMap()
                for (item in outbounds) {
                    map[item.tag] = item.urlTestDelay
                    // Remember every stamp this run has seen, not just this snapshot's.
                    if (testStartEpoch > 0 && item.urlTestTime > 0) {
                        testRunStamps[item.tag] = item.urlTestTime
                    }
                }
                _delays.value = map
                // Progress and summary are measured over the SAME set the run submitted. Nothing
                // here reads outbounds.size: that snapshot also carries `direct`, `block`,
                // selectors and nodes no run ever submits, none of which can report a stamp.
                //
                // Nothing here touches `_testing` either. This stream cannot know whether the RPC
                // is still in flight, and it used to end the run on `done >= total` - a condition
                // it could only satisfy by accident, because a node that is never submitted never
                // reports. `done >= total` is now display-only: it closes the PROGRESS DISPLAY,
                // and the lifecycle belongs to the RPC alone.
                val eligible = testRunTags
                if (testStartEpoch > 0 && eligible.isNotEmpty()) {
                    val startedAt = testRunStartedAtMs
                    for (tag in eligible) {
                        val stamp = testRunStamps[tag] ?: continue
                        if (stamp >= testStartEpoch && startedAt > 0 && testRunReported.add(tag)) {
                            com.interstellar.proxy.core.AppLog.log(
                                "test",
                                "  $tag → ${map[tag] ?: 0}ms at +${System.currentTimeMillis() - startedAt}ms",
                            )
                        }
                    }
                    val reported = eligible.filter { tag -> (testRunStamps[tag] ?: 0L) >= testStartEpoch }
                    // Publish this run's outcomes as they land: a tag absent from here did not
                    // report in THIS run, so the summary counts it as a failure instead of
                    // reusing a delay from an earlier run.
                    _testResult.value = reported.associateWith { tag -> map[tag] ?: 0 }
                    if (reported.size >= eligible.size) {
                        // Display complete. The RPC may be long done - the core schedules its round
                        // and returns at once - so this only closes the progress window.
                        val elapsed = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0
                        com.interstellar.proxy.core.AppLog.log(
                            "test",
                            "batch results complete: ${reported.size}/${eligible.size} in ${elapsed}ms",
                        )
                        _testProgress.value = null
                    } else {
                        _testProgress.value = reported.size to eligible.size
                    }
                }
                // NB: snapshots can arrive at any moment (delayed pushes) and must never kill an
                // in-flight run - the RPC's own finally owns the testing state.
            }

        },
    )

    /** libbox group snapshot → neutral CoreGroup. */
    private fun convertGroup(group: OutboundGroup): CoreGroup {
        val iterator = group.items
        val items = mutableListOf<CoreGroupItem>()
        while (iterator.hasNext()) {
            val item = iterator.next()
            items.add(CoreGroupItem(item.tag, item.type, item.urlTestDelay, item.urlTestTime))
        }
        return CoreGroup(
            tag = group.tag,
            type = group.type,
            selected = group.selected?.takeIf { it.isNotBlank() },
            items = items,
        )
    }

    init {
        // the nodes page shows these until the core runs and live groups arrive
        refreshStaticGroups()
    }

    /**
     * The UI's subscription to the app foreground fact.
     *
     * Registered with `PlatformFacts`, which is the single writer of that fact, rather than derived
     * here from a Compose lifecycle effect. Deriving it here would be a second writer of one fact,
     * and it would get a specific case wrong: a configuration change - a theme switch, a UI-mode
     * change, a display-size or font-scale change - stops and restarts the Activity, so a Compose
     * ON_STOP/ON_START pair would suspend and resume the UI client, and re-create the composition,
     * for a transition in which the app never left the foreground. The tracker behind `PlatformFacts`
     * is the component that knows the difference
     * (`ActivityForegroundTracker.onActivityStopped(isChangingConfigurations)`), so the edge is taken
     * from there.
     *
     * The canonical value is delivered immediately on subscribe, so a cold start and a re-subscribe
     * both converge without guessing, and the guard in `UiClientLifetime` is what stops that
     * immediate delivery from being read as a transition.
     */
    private val foregroundListener: (Boolean) -> Unit = { foreground ->
        if (foreground) resumeUiClient() else suspendUiClient()
    }

    /**
     * Idempotent. connect() is re-entered on every resume, and the registry has no de-duplication,
     * so an unconditional subscribe added a copy per resume while disconnect() removes only one -
     * leaving stale copies that both outlive their ViewModel and let a foreground edge run connect()'s
     * side effects on a dead instance (registering a SERVICE_STOPPED receiver that is never
     * unregistered). The flag makes subscribe/unsubscribe a balanced pair.
     */
    private var foregroundSubscribed = false

    private fun subscribeForeground() {
        if (foregroundSubscribed) return
        foregroundSubscribed = true
        com.interstellar.proxy.bg.PlatformFacts.addForegroundListener(foregroundListener)
    }

    private fun unsubscribeForeground() {
        if (!foregroundSubscribed) return
        foregroundSubscribed = false
        com.interstellar.proxy.bg.PlatformFacts.removeForegroundListener(foregroundListener)
    }

    fun connect() {
        uiLifetime.onClientConnected()
        subscribeForeground()
        // THE GATE. Everything below is decided by the canonical lifetime verdict, read AFTER the
        // subscription, because `subscribeForeground()` replays the canonical foreground value
        // synchronously: a `false` replay calls `suspendUiClient()`, which marks the lifetime
        // suspended and disconnects the client before `connect()` has even returned.
        //
        // Without this gate the replay was undone immediately. The sequence was:
        //
        //   connect() -> subscribeForeground() -> addForegroundListener()
        //     -> canonical replay false -> suspendUiClient() -> lifetime = suspended, client off
        //   -> back in connect() -> commandClient.connect()   <-- unconditionally
        //
        // so the canonical state said "the app is in the background and must not have a UI client"
        // and `connect()` built one anyway. The poll's own `mayReconnect()` gate did not save it
        // either: the poll is the *second* path, and the first one had already connected.
        //
        // Note what this is NOT. Moving `commandClient.connect()` above `subscribeForeground()`
        // would only narrow the window; it does not answer the question, and the replay would then
        // suspend a client that had just been created. The verdict decides, not the ordering.
        val wantsClient = uiLifetime.wantsClient()
        if (wantsClient) {
            // Establish the client HERE, not only from the poll below.
            //
            // The poll only fires when the status is Starting or Stopped, so on a resume with the core
            // already Started nothing would ever re-open the socket: the UI would render the last known
            // speed/history/groups as if they were live, and the 400 ms dead-core detector
            // (`applySocketDrop` -> `armStartingWatchdog`) could never fire again because it is driven by
            // this client's callbacks. That is a silent, permanent freeze rather than a visible failure,
            // which is why the client is established immediately whenever the verdict allows it.
            commandClient.connect()
            registerStoppedReceiver()
        }
        // Backgrounded at entry: there is no UI client to serve and no poll to run. The client is
        // deliberately not created, and no reconnect poll is started - a poll here would be the very
        // resurrection the lifetime gate exists to prevent. `resumeUiClient()` re-enters `connect()`
        // when the foreground edge arrives, which is what brings both back.
        if (!wantsClient) return
        // poll-reconnect: the box may start/stop at any time, and a failed
        // connect (server not up yet) must be retried to reflect the state
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(2000)
                // A suspended client must not be resurrected by its own poll: the loop
                // would re-connect within 2 s of the user backgrounding the app.
                if (!uiLifetime.mayReconnect()) continue
                if (_status.value == Status.Starting || _status.value == Status.Stopped) {
                    commandClient.connect()
                }
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        startingWatchdog?.cancel()
        startingWatchdog = null
        unregisterStoppedReceiver()
        // Set before the client is disconnected: that call fires onDisconnected() synchronously, and
        // without this the handler re-arms the watchdog this function just cancelled.
        uiLifetime.onTearingDown()
        unsubscribeForeground()
        commandClient.disconnect()
    }

    /**
     * App UI went to the background: stop the UI telemetry/control-plane client, its
     * reconnect polling and its chart sampling, and nothing else.
     *
     * Deliberately does NOT go through [disconnect], because that also cancels
     * [startingWatchdog] and unregisters the SERVICE_STOPPED receiver — while backgrounded
     * that broadcast is the only way [Status] converges, so losing it would strand the UI on
     * a stale status. It also deliberately leaves `status`, `speed` and `history` untouched:
     * a backgrounded app has not learned anything new about the core, so the last known
     * values stay on screen and nothing is fabricated.
     *
     * Idempotent: the second background edge does nothing.
     */
    fun suspendUiClient() {
        // A headless url-test drives this same client and signals readiness only through
        // onConnected(); switching it off mid-probe would make the probe time out. The probe is
        // short-lived and the UI is gone anyway, so it keeps the socket.
        // The reconnect poll belongs to the UI, not to the core and not to a probe, so it stops on
        // every background edge - including one taken during a headless url-test, where the probe
        // keeps the socket but the UI must not keep polling.
        pollJob?.cancel()
        pollJob = null
        if (!uiLifetime.onBackground(probing)) return
        pollJob = null
        commandClient.disconnect()
    }

    /**
     * Re-apply a suspend that was owed because the app went to the background during a headless
     * url-test.
     *
     * The probe's finally clears `probing`; without this call the background edge would have been
     * consumed by the probe and the UI client would have kept running in the background for good -
     * the ON_STOP edge fires once and nothing would re-apply it.
     */
    fun reapplyOwedSuspend() {
        if (!uiLifetime.owesSuspend) return
        suspendUiClient()
    }

    /**
     * App UI came back to the foreground: restore the UI client and re-align with the core.
     *
     * Idempotent: a repeat foreground edge while already online does nothing, and an edge that
     * arrives after a real [connect] does not open a second session.
     */
    fun resumeUiClient() {

        if (!uiLifetime.onForeground()) return
        connect()
    }

    private fun registerStoppedReceiver() {
        if (stoppedReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Action.SERVICE_STOPPED) markStopped()
            }
        }
        stoppedReceiver = receiver
        ContextCompat.registerReceiver(
            getApplication(),
            receiver,
            IntentFilter(Action.SERVICE_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unregisterStoppedReceiver() {
        val receiver = stoppedReceiver ?: return
        stoppedReceiver = null
        runCatching { getApplication<Application>().unregisterReceiver(receiver) }
    }


    private fun markStarted() {
        startingWatchdog?.cancel()
        startingWatchdog = null
        if (_status.value == Status.Started) return
        _status.value = Status.Started
        _connectedAt.value = System.currentTimeMillis()
        Settings.tileActive = true
        if (!autoTested) {
            autoTested = true
            viewModelScope.launch(Dispatchers.IO) {
                // Smart-mode auto-test. It goes through runKernelUrlTest so it shares the mutex
                // with a manual run (the two must not overlap), and it passes manual = false so
                // it cannot open the manual spinner or clear the manual run's progress. It used
                // to call the client directly here, which meant a smart test could interleave
                // with a manual one.
                runKernelUrlTest(target = MinimalConfigBuilder.AUTO_TAG, manual = false)
            }
        }
    }

    private fun markStopped() {
        startingWatchdog?.cancel()
        startingWatchdog = null
        autoTested = false
        // A fresh core must be allowed to restore the stored pick again.
        selectionRestoreAttempted = false
        // Same reason, and the same place: a fresh core must be told the routing mode
        // again, and the next request-level reconcile must be allowed to fire. The backoff
        // timestamp goes with it - a new core must not inherit the old one's waiting period.
        routingModeApplied = false
        routingModeLastAttemptNanos = 0L
        if (_status.value != Status.Stopped) {
            _status.value = Status.Stopped
        }
        _connectedAt.value = 0L
        _runtime.value = RuntimeState()
        Settings.tileActive = false
    }

    private fun armStartingWatchdog(timeoutMs: Long) {
        startingWatchdog?.cancel()
        startingWatchdog = viewModelScope.launch {
            delay(timeoutMs)
            if (_status.value == Status.Starting || _status.value == Status.Started) {
                markStopped()
                com.interstellar.proxy.bg.BoxService.stop()
            }
        }
    }

    /**
     * Command socket drops during core start/reload. Stay on the current
     * face until SERVICE_STOPPED arrives, or a short grace expires.
     * Do not flip Started → Starting — that is the "连接中" hang.
     */
    private fun applySocketDrop() {
        // connection errors from the (sing-box only) command client must not
        // tear down a healthy sidecar engine

        when (_status.value) {
            Status.Stopping -> markStopped()
            Status.Started -> armStartingWatchdog(SOCKET_DROP_TIMEOUT_MS)
            else -> Unit
        }
    }

    fun startProxy() {
        android.util.Log.d("InterstellarUI", "startProxy invoked")
        if (probing) {
            probing = false
            com.interstellar.proxy.bg.BoxService.stop()
        }
        _status.value = Status.Starting
        armStartingWatchdog(STARTING_TIMEOUT_MS)
        _message.value = null
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                delay(250)
                android.util.Log.d("InterstellarUI", "activeSub=" + (SubscriptionRepository.activeSubscription()?.name ?: "null"))
                if (SubscriptionRepository.subscriptions.isEmpty()) {
                    _message.value = str(com.interstellar.proxy.R.string.vm_need_subscription)
                    markStopped()
                    return@launch
                }
                val config = SubscriptionRepository.regenerateActiveConfig()
                if (config == null) {
                    _message.value = SubscriptionRepository.lastConfigError
                        ?: str(com.interstellar.proxy.R.string.vm_config_gen_failed_no_nodes)
                    markStopped()
                    return@launch
                }
                android.util.Log.d("InterstellarUI", "config ok length=" + config.length)
                com.interstellar.proxy.bg.BoxService.start()
                // Same client, same lifetime as every other establish in this file: `commandClient`
                // is ONE socket, and the guarded `connect()` above hands it to the UI. So the
                // startup establish is a UI-client establish too, and it needs the same verdict.
                //
                // This call is not decoration - `onConnected()` and the Status tick are what
                // promote Starting -> Started and cancel `startingWatchdog`, so the client has to
                // exist for the start to be observed at all. That is why the fix is a verdict and
                // not a removal.
                //
                // It is also reachable while suspended, which is what makes the verdict necessary
                // rather than tidy. startProxy() is invoked from the Compose UI, so it begins in
                // the foreground, but everything below runs on Dispatchers.IO after `delay(250)`:
                // tapping start and immediately pressing Home lets the background edge land first,
                // and `suspendUiClient()` has by then already disconnected the client and set the
                // verdict to "not wanted" (measured: onBackground -> true, wantsClient -> false).
                // An unguarded connect here then rebuilt it, after the disconnect, with no later
                // edge to correct it - `resumeUiClient()` only fires when the user returns, and
                // `mayReconnect()` gates new polls without ever closing an existing client. The
                // result was a backgrounded app holding a UI command socket for the rest of the
                // session. Reading the verdict here, at the last moment before the establish,
                // means the decision uses the canonical state rather than the state at entry.
                //
                // When the verdict refuses, nothing else is needed: `suspendUiClient()` has already
                // run `commandClient.disconnect()`, and the status converges through
                // `SERVICE_STOPPED` plus `startingWatchdog` exactly as it would have anyway.
                if (uiLifetime.wantsClient()) {
                    commandClient.connect()
                }
            } catch (e: Exception) {
                _message.value = str(com.interstellar.proxy.R.string.vm_start_failed, e.message ?: "")
                markStopped()
            } finally {
                _busy.value = false
            }
        }
    }

    fun stopProxy() {
        startingWatchdog?.cancel()
        startingWatchdog = null
        _status.value = Status.Stopping
        com.interstellar.proxy.bg.BoxService.stop()
    }

    /**
     * Regenerates the active config from current settings (node pick, mix pool,
     * system proxy…) and hot-reloads the running core.
     */
    fun refreshProxyConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            val config = SubscriptionRepository.regenerateActiveConfig()
            if (config == null) {
                _message.value = SubscriptionRepository.lastConfigError
                    ?: str(com.interstellar.proxy.R.string.vm_config_update_failed)
                return@launch
            }
            _staticGroups.value = parseSingboxGroups(config)
            // a switch during Starting would otherwise be silently dropped: the
            // in-flight start already consumed the previous config and no
            // reload fires — wait for the start to settle, then apply
            if (_status.value == Status.Starting) {
                var waited = 0
                while (_status.value == Status.Starting && waited < 20_000) {
                    delay(200)
                    waited += 200
                }
            }
            if (_status.value == Status.Started) {
                runCatching { CommandTarget.standaloneClient().serviceReload() }
            }
        }
    }

    /** Re-reads whatever config is on disk (startup / core switch). */
    fun refreshStaticGroups() {
        viewModelScope.launch(Dispatchers.IO) {
            val content = com.interstellar.proxy.data.ConfigStore.readActiveConfig()
            _staticGroups.value = content?.let { parseSingboxGroups(it) } ?: emptyList()
        }
    }

    private fun parseSingboxGroups(content: String): List<CoreGroup> {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(content)
            .let { it as? kotlinx.serialization.json.JsonObject } ?: return emptyList()
        val outbounds = (root["outbounds"] as? kotlinx.serialization.json.JsonArray)
            ?.filterIsInstance<kotlinx.serialization.json.JsonObject>() ?: return emptyList()
        val typeOf = outbounds.associateBy { it.str("tag") }
        return outbounds.mapNotNull { ob ->
            val type = ob.str("type")?.lowercase() ?: return@mapNotNull null
            if (type != "selector" && type != "urltest") return@mapNotNull null
            val tag = ob.str("tag") ?: return@mapNotNull null
            val members = (ob["outbounds"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?: emptyList()
            CoreGroup(
                tag = tag,
                type = type,
                selected = ob.str("default")?.takeIf { it in members },
                items = members.map { CoreGroupItem(it, typeOf[it]?.str("type") ?: "") },
            )
        }
    }

    fun selectNode(groupTag: String, itemTag: String) {
        Settings.selectedOutboundTag = itemTag
        // Stored with the group: restoring a pick needs to know WHICH selector it belonged
        // to, and a tag alone does not say (see pendingSelectionRestore).
        Settings.selectedOutboundGroup = groupTag
        _selectedOutboundTag.value = itemTag
        viewModelScope.launch(Dispatchers.IO) {
            if (_status.value == Status.Started) {
                runCatching {
                    CommandTarget.standaloneClient().selectOutbound(groupTag, itemTag)
                }.onFailure {
                    _message.value = str(com.interstellar.proxy.R.string.vm_switch_failed, it.message ?: "")
                }
            } else {
                // stopped: this rewrite bakes the pick into the selector's `default` for a
                // *generated* config. A *raw* user config is passed through untouched, so
                // for those the pick is instead restored once the core is up and its groups
                // are known (restorePendingSelection). The comment here used to promise the
                // bake unconditionally, which was simply untrue for a raw config - the pick
                // was dropped and the page kept showing a node the core was not using.
                val config = SubscriptionRepository.regenerateActiveConfig()
                if (config != null) {
                    _staticGroups.value = parseSingboxGroups(config)
                }
                _selectedOutboundTag.value = Settings.selectedOutboundTag
            }
        }
    }

    /**
     * True once a restore has been attempted for the current core.
     *
     * Reset when the core stops. It exists so the stream of group snapshots cannot push the
     * stored pick again *after* the user has made a different choice in the same session.
     *
     * Written from the UI thread (the stop path) and read from the command client's
     * callback thread (the group stream), so the reset has to be published rather than
     * hoped for - otherwise a restart can silently skip the restore.
     */
    @Volatile
    private var selectionRestoreAttempted = false

    /**
     * Push the node the user picked while the core was stopped.
     *
     * Driven by the group stream rather than by a "started" callback: those snapshots arrive
     * *over the command channel*, so running here means that channel is already usable.
     * Sending from the start callback instead would race the very socket it needs.
     */
    private fun restorePendingSelection() {
        if (selectionRestoreAttempted) return
        val pending = pendingSelectionRestore(
            Settings.selectedOutboundGroup,
            Settings.selectedOutboundTag,
            _groups.value,
        ) ?: return
        selectionRestoreAttempted = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                CommandTarget.standaloneClient().selectOutbound(pending.first, pending.second)
            }.onSuccess {
                // Positive confirmation. Until this line existed the restore was observable only
                // by its ABSENCE (no vm_switch_failed), so "the stored pick reached the core" and
                // "nothing was ever sent" looked identical in the logs - which is exactly the
                // ambiguity that made this behaviour hard to trust.
                com.interstellar.proxy.core.AppLog.log(
                    "ui",
                    str(com.interstellar.proxy.R.string.vm_switch_restored, pending.second, pending.first),
                )
            }.onFailure {
                _message.value = str(com.interstellar.proxy.R.string.vm_switch_failed, it.message ?: "")
            }
        }
    }

    // ---- routing mode ----

    /**
     * True once the stored mode has been pushed to the core for the current session.
     *
     * Reset where `selectionRestoreAttempted` is reset (the stop paths) for the same reason:
     * one push per core, so a later user choice is never overwritten by a stale reconcile.
     * Written from the UI thread and read from the command client's callback thread, so it is
     * published rather than hoped for.
     */
    @Volatile
    private var routingModeApplied = false

    /**
     * Monotonic timestamp of the last reconcile attempt; 0 = none yet.
     *
     * `System.nanoTime`, not `currentTimeMillis`: this measures an elapsed interval, and a
     * wall-clock correction must not be able to shorten it.
     */
    @Volatile
    private var routingModeLastAttemptNanos = 0L

    /**
     * The ONE place a routing mode change is decided, persisted and sent.
     *
     * Deliberately shaped like [selectNode]: persist the intent first, then send it to a core
     * that is already running, then report a failure instead of hiding it. The order matters
     * because the two cases must both end up correct:
     *
     *  - **core running** - the intent is persisted and the kernel is switched in place. No
     *    config reload, no service restart: the kernel's clash-mode Manager is runtime state
     *    and a route rule reads it per connection.
     *  - **core stopped** - nothing is sent, because there is nothing to send it to. The value
     *    is persisted and [reconcileRoutingMode] applies it when the core next comes up, so the
     *    choice is never silently dropped.
     *
     * Persisting even when the send fails is the deliberate choice: this is a *user intent*, and
     * [reconcileRoutingMode] retries it on the next start. The failure is surfaced rather than
     * swallowed, and the traffic effect is stated in the message, so "UI says Direct while the
     * core still routes Rule" cannot pass unnoticed.
     *
     * Unlike [selectNode] this does not rewrite the active config: the generated config already
     * declares every mode the UI offers, and a raw config is never rewritten at all.
     */
    fun setRoutingMode(mode: RoutingMode) {
        val previous = _routingMode.value
        if (mode == previous) return
        // Intent first, so a stop/start in between cannot lose it.
        Settings.routingMode = mode.wire
        _routingMode.value = mode
        // A change is a fresh decision: a reconcile already done for this session must not
        // suppress the push below, a later reconcile must still be allowed to re-assert it, and
        // a retry still inside its backoff window must not delay the send the user just asked for.
        routingModeApplied = false
        routingModeLastAttemptNanos = 0L
        viewModelScope.launch(Dispatchers.IO) {
            if (_status.value != Status.Started) return@launch
            applyRoutingMode(mode, previous = previous, expected = mode)
        }
    }

    /**
     * Re-assert the stored mode on a freshly started core.
     *
     * The kernel also persists the mode (`cache.db`, bucket `clash_mode`) and restores it at
     * start, which normally lands on the same value. It is not treated as authoritative,
     * because it can legitimately disagree: a raw config brings its own mode list, and a stored
     * mode that is not in that list is dropped on restore. The persisted app setting is the
     * user's intent, so it wins - and re-sending an unchanged value is free, since the kernel's
     * `SetMode` returns early when the mode is already current.
     *
     * Called from the status tick, which is the first moment the core is genuinely serving: the
     * `SetClashMode` RPC answers `invalid argument` until the service reports STARTED, so
     * sending from `onConnected()` would race the very state this is reconciling against.
     * Guarded so the 1 Hz status stream does not re-send it every second.
     */
    private fun reconcileRoutingMode() {
        if (routingModeApplied) return
        // Backoff, not a bare flag. `routingModeApplied` has to be re-armed when a reconcile
        // fails, or a single failure would mean "never applied for this core". But a re-armed
        // flag on its own is worse than the failure: the status stream ticks at 1 Hz, and each
        // attempt opens its own command socket, so a core that stays Started while the command
        // path is unavailable would have the app dialing it once a second for the life of the
        // tunnel. The delay keeps the retry and drops the hammering - and because it is anchored
        // on a monotonic clock, a wall-clock jump cannot make it fire early.
        val now = System.nanoTime()
        val last = routingModeLastAttemptNanos
        if (last != 0L && now - last < ROUTING_MODE_RETRY_AFTER_NANOS) return
        routingModeLastAttemptNanos = now
        routingModeApplied = true
        viewModelScope.launch(Dispatchers.IO) {
            applyRoutingMode(_routingMode.value, previous = null, expected = null)
        }
    }

    /** Serialises mode RPCs; see [applyRoutingMode] for why ordering is not enough on its own. */
    private val routingModeSend = Mutex()

    /**
     * Send [mode] to the running core.
     *
     * @param previous rolled back to when this is a user change that failed; null for a reconcile,
     *   which has no earlier value to restore because the stored mode IS the intent.
     * @param expected the mode the UI is showing when this send's failure is allowed to matter.
     *   Null disables the check (reconcile).
     *
     * # Why a send that failed can still be the wrong thing to act on
     *
     * Rapid taps are real: tapping 全局 then 直连 leaves two RPCs in flight, and the FIRST one can
     * fail after the second has already published the newer mode - a dial that has to wait for the
     * command socket, then loses it, is enough. Rolling back on that late failure would restore a
     * mode the user has already moved past, and it would do so *after* the newer `SetClashMode`
     * succeeded, leaving the dashboard and the stored intent on the abandoned value while the core
     * runs the newer one. So a failure is only allowed to roll anything back while [mode] is still
     * the current selection; a stale failure is logged and dropped.
     *
     * The mutex is the other half. It cannot replace the check - the late failure arrives after the
     * lock is released - but it keeps the RPCs from interleaving on one command socket, so the order
     * the user tapped in is the order the core applies.
     */
    private suspend fun applyRoutingMode(
        mode: RoutingMode,
        previous: RoutingMode?,
        expected: RoutingMode?,
    ) {
        runCatching {
            routingModeSend.withLock {
                CommandTarget.standaloneClient().setClashMode(mode.wire)
            }
        }.onSuccess {
            com.interstellar.proxy.core.AppLog.log(
                "ui",
                str(com.interstellar.proxy.R.string.vm_routing_mode_applied, mode.wire),
            )
        }.onFailure {
            if (expected != null && _routingMode.value != expected) {
                // Superseded by a newer tap. Acting on this would undo a choice the user made
                // after it, so it is reported where failures are diagnosed and nowhere else.
                com.interstellar.proxy.core.AppLog.log(
                    "ui",
                    "routing mode $mode.wire failed after being superseded " +
                        "(now ${_routingMode.value.wire}): ${it.message ?: ""}",
                )
                return@onFailure
            }
            // Roll back ONLY a change the user just made. A reconcile failure has no previous
            // state to restore (the stored value is the intent, and the core may simply not be
            // ready for it), so it keeps the setting and reports.
            if (previous != null) {
                Settings.routingMode = previous.wire
                _routingMode.value = previous
            } else {
                routingModeApplied = false
            }
            _message.value = str(
                com.interstellar.proxy.R.string.vm_routing_mode_failed,
                mode.wire,
                it.message ?: "",
            )
        }
    }





    /**
     * Resolve the group a manual url-test should target.
     *
     * urltest groups (auto) are testable directly. The main entry is
     * a *selector* whose members mix group and node tags, and the kernel's
     * per-item pass skips those wholesale ("大量未测"), so a selector keeps
     * falling back to [MinimalConfigBuilder.AUTO_TAG], whose members are every node.
     */
    private fun urlTestTarget(groupTag: String): String =
        resolveUrlTestTarget(groupTag, _groups.value)

    /**
     * Manual URL Test. The RPC call is the whole lifecycle.
     *
     * Same shape as the official client's `urlTestGroup`: mark testing, call, clear in finally.
     * The target is resolved ONCE here and then used for the RPC, the progress denominator and
     * the summary, so the three can no longer describe different groups.
     */
    fun urlTest(groupTag: String) {
        val target = urlTestTarget(groupTag)
        viewModelScope.launch(Dispatchers.IO) {
            if (_status.value == Status.Started) {
                runKernelUrlTest(target = target, manual = true)?.let { failure ->
                    _message.value = str(com.interstellar.proxy.R.string.vm_test_failed, failure)
                }
            } else {
                // The headless probe path owns its own core, so it keeps its own error handling;
                // it reaches the kernel through the same single call.
                beginTestRun(target)
                _testing.value = true
                _testProgress.value = initialProgress(target)
                try {
                    runDisconnectedUrlTest(target)
                } catch (e: Exception) {
                    _message.value = str(com.interstellar.proxy.R.string.vm_test_failed, e.message ?: "")
                } finally {
                    _testing.value = false
                    _testProgress.value = null
                }
            }
        }
    }

    /**
     * The nodes a batch url-test actually submits, resolved once for the whole run.
     *
     * Delegates to the pure [eligibleUrlTestTags] so the counting rules are unit-testable; this
     * wrapper only supplies the live group snapshot.
     */
    private fun eligibleUrlTestTags(target: String): List<String> =
        com.interstellar.proxy.ui.eligibleUrlTestTags(
            _groups.value.find { it.tag == target }?.items.orEmpty(),
        )

    /**
     * The denominator for [target], or null when it cannot be established.
     *
     * Null rather than a guess: a wrong "47 / 52" is worse than no count, and the page says
     * 测速中… instead. This is also the reason there is no `_delays.size` fallback any more - a
     * historical delay cache is not this run's member set, so using it made the total describe
     * nodes that were never submitted.
     */
    private fun urlTestTotal(target: String): Int? =
        eligibleUrlTestTags(target).size.takeIf { it > 0 }

    /** Progress to publish when a manual run starts; null = show 测速中… without numbers. */
    private fun initialProgress(target: String): Pair<Int, Int>? = urlTestTotal(target)?.let { 0 to it }

    /**
     * Drop the finished run's result banner.
     *
     * Pure display state: clearing it cannot affect any run, and the next run repopulates both
     * streams from scratch in [beginTestRun].
     */
    fun dismissTestSummary() {
        _testTags.value = emptyList()
        _testResult.value = emptyMap()
        _testProgress.value = null
    }

    /**
     * Start a run's display bookkeeping for [target]. The only place a run's node set, its epoch
     * and its per-node timeline are established.
     *
     * Note what this is NOT: it does not mark the client as testing, it does not schedule any
     * timeout, and nothing polls it to decide the run is over. It exists so the progress numbers
     * and the summary describe the run that was just requested, using the target that was
     * actually submitted.
     */
    private fun beginTestRun(target: String) {
        val eligible = eligibleUrlTestTags(target)
        testRunStamps.clear()
        testRunReported.clear()
        testRunTags = eligible.toSet()
        _testTags.value = eligible
        _testResult.value = emptyMap()
        testRunStartedAtMs = System.currentTimeMillis()
        // Stamps at or after this belong to this run. Display filter only - see testStartEpoch.
        testStartEpoch = System.currentTimeMillis() / 1000
        com.interstellar.proxy.core.AppLog.log(
            "test",
            "batch start: ${eligible.size} node(s) in group '$target': ${eligible.joinToString()}",
        )
    }

    /**
     * One kernel url-test run: mark, call, clear. Nothing else.
     *
     * The official client's whole implementation is
     * `testingGroups + tag` -> `CommandClient.urlTest(tag)` -> `finally { testingGroups - tag }`,
     * and this is that shape. Everything the previous version added around the call -
     * an epoch to attribute stamps to, a `done >= total` gate that could never be satisfied, and
     * a 15 s + 10 s settle poll after the RPC had already returned - was the client trying to
     * decide whether the kernel had finished. It cannot know, and it does not need to: the call
     * returning is the end of the client's involvement, and the delays arrive on the outbounds
     * stream afterwards.
     *
     * The mutex is kept, and is only a serialization of manual against smart-mode runs. It no
     * longer carries any completion meaning.
     *
     * A non-null result is the failure to report. The cause is kept rather than collapsed into a
     * Boolean: a libbox failure reads "url test: get command client: <cause>", which names the
     * boundary that actually failed - no command socket, core gone, dial rejected.
     */
    private suspend fun runKernelUrlTest(target: String, manual: Boolean): String? =
        kernelUrlTestMutex.withLock {
            if (manual) {
                beginTestRun(target)
                _testing.value = true
                _testProgress.value = initialProgress(target)
            } else {
                // A smart-mode run must not touch the manual test's UI state. It still needs the
                // display bookkeeping, so the stream can attribute its results to the right target
                // without ever touching _testing.
                beginTestRun(target)
            }
            try {
                CommandTarget.standaloneClient().urlTest(target)
                null
            } catch (e: Exception) {
                e.message ?: e.toString()
            } finally {
                if (manual) {
                    // The RPC is the lifecycle: whatever happened above, the client is done here.
                    _testing.value = false
                }
            }
        }

    /**
     * Spin up the core without TUN so url-test can run while the UI stays
     * 未连接. Restores the previous config afterwards.
     */
    private suspend fun runDisconnectedUrlTest(target: String = MinimalConfigBuilder.AUTO_TAG) {
        probing = true
        probeSocketUp = false
        val previous = ConfigStore.readActiveConfig()
        try {
            val probe = SubscriptionRepository.regenerateActiveConfig(includeTun = false)
                ?: throw IllegalStateException(
                    SubscriptionRepository.lastConfigError
                        ?: str(com.interstellar.proxy.R.string.vm_no_test_config),
                )
            com.interstellar.proxy.bg.BoxService.startHeadless()
            commandClient.connect()
            // Wait for the command socket. Cold boot (FGS scheduling, rule-set
            // init) can take well over 6s — probing a raw command client is a
            // slow-failing gRPC dial, so key off our own connection instead.
            var connected = false
            for (i in 0 until 150) { // 150 × 200ms = 30s budget
                delay(200)
                if (_status.value == Status.Starting || _status.value == Status.Started) return
                if (probeSocketUp) {
                    connected = true
                    break
                }
                // a failed dial is not retried inside CommandClient — re-kick it
                if (i > 0 && i % 10 == 0) commandClient.connect()
            }
            if (!connected) throw IllegalStateException(str(com.interstellar.proxy.R.string.vm_test_service_timeout))
            // A headless probe reaches the kernel through the same single call, so the client's
            // involvement ends when that call returns. It used to be followed by a
            // `awaitUrlTestSettled()` that waited out the kernel's per-node timeout plus margin -
            // the same client-side lifecycle this refactor removes from the attached path.
            val failure = runCatching {
                CommandTarget.standaloneClient().urlTest(target)
            }.exceptionOrNull()
            if (failure != null) {
                throw IllegalStateException(
                    str(
                        com.interstellar.proxy.R.string.vm_test_failed,
                        failure.message ?: failure.toString(),
                    ),
                )
            }
        } finally {
            probeSocketUp = false
            // Clear the probe flag and re-apply any owed suspend FIRST, before anything suspendable.
            //
            // This used to sit after a `delay(250)` further down, which made it skippable: on
            // `viewModelScope` cancellation the delay throws out of the `finally`, so neither
            // `probing = false` nor the re-apply ran, and the background edge the probe had consumed
            // stayed lost. Nothing here suspends, so the sequence cannot be interrupted.
            probing = false
            reapplyOwedSuspend()
            val takenOver = _status.value == Status.Starting || _status.value == Status.Started
            if (!takenOver) {
                com.interstellar.proxy.bg.BoxService.stop()
                delay(250)
                if (previous != null) ConfigStore.writeActiveConfig(previous)
                else SubscriptionRepository.regenerateActiveConfig()
            }
        }
    }

    fun addSubscriptionFromUrl(name: String, url: String) {
        addSubJob = viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            _addingSub.value = true
            _addSubError.value = null
            try {
                val result = SubscriptionFetcher.fetch(url)
                importContent(
                    name.ifBlank { result.suggestedName ?: urlHost(url) },
                    url,
                    result.body,
                    result,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = str(com.interstellar.proxy.R.string.vm_download_failed, e.message ?: "")
                _message.value = msg
                _addSubError.value = msg
            } finally {
                addSubJob = null
                _busy.value = false
                _addingSub.value = false
            }
        }
    }

    fun addSubscriptionFromText(name: String, text: String) {
        // Tracked like the URL path so Cancel can actually abort a text import
        // (previously addSubJob was only assigned here in the URL branch, so
        // cancelling a paste let the import complete anyway).
        addSubJob = viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            _addingSub.value = true
            _addSubError.value = null
            try {
                importContent(name.ifBlank { str(com.interstellar.proxy.R.string.vm_local_subscription) }, null, text, null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = str(com.interstellar.proxy.R.string.vm_download_failed, e.message ?: "")
                _message.value = msg
                _addSubError.value = msg
            } finally {
                addSubJob = null
                _busy.value = false
                _addingSub.value = false
            }
        }
    }

    private fun importContent(
        name: String,
        url: String?,
        body: String,
        traffic: SubscriptionFetcher.FetchResult?,
    ) {
        // stamp the subscription id on nodes up front so mix source labels work
        val subId = SubscriptionRepository.newSubscriptionId()
        val format = SubscriptionRepository.saveRawBody(subId, body)
        when (val parsed = SubscriptionParser.parse(body, subId)) {
            is SubscriptionParser.Result.Nodes -> {
                val sub = SubscriptionRepository.Subscription(
                    id = subId,
                    name = name,
                    url = url,
                    nodes = parsed.nodes,
                    uploadBytes = traffic?.uploadBytes ?: 0,
                    downloadBytes = traffic?.downloadBytes ?: 0,
                    totalBytes = traffic?.totalBytes ?: 0,
                    expireSeconds = traffic?.expireSeconds ?: 0,
                    lastUpdated = System.currentTimeMillis(),
                    configFormat = format,
                )
                SubscriptionRepository.upsert(sub)
                if (Settings.selectedOutboundTag.isBlank()) {
                    Settings.selectedOutboundTag = com.interstellar.proxy.data.config.MinimalConfigBuilder.AUTO_TAG
                }
                // NOT auto-checked into the mix pool: the user ticks it in the
                // subscription list (toggleMixSubscription → applyPoolChange
                // regenerates and the node list syncs from the state flow)
                _message.value = str(com.interstellar.proxy.R.string.vm_imported_nodes, parsed.nodes.size)
            }

            SubscriptionParser.Result.Empty -> {
                // a raw config may carry no extractable nodes but still be a
                // valid body — import it as a raw-only subscription
                if (format != null) {
                    SubscriptionRepository.upsert(
                        SubscriptionRepository.Subscription(
                            id = subId,
                            name = name,
                            url = url,
                            lastUpdated = System.currentTimeMillis(),
                            configFormat = format,
                        ),
                    )
                    _message.value = str(
                        com.interstellar.proxy.R.string.vm_imported_raw,
                        name,
                        com.interstellar.proxy.data.subscription.RawConfigFormat.from(format)?.label ?: "",
                    )
                } else {
                    SubscriptionRepository.rawFileOf(subId).delete()
                    _message.value = str(com.interstellar.proxy.R.string.vm_unrecognized_content)
                    _addSubError.value = str(com.interstellar.proxy.R.string.vm_unrecognized_content)
                }
            }
        }
        _subscriptions.value = SubscriptionRepository.subscriptions.toList()
        _activeSubscriptionId.value = SubscriptionRepository.activeSubscriptionId
        _mixSubscriptionIds.value = Settings.mixSubscriptionIds
    }

    fun refreshSubscription(id: String, fromPull: Boolean = false) {
        val sub = SubscriptionRepository.get(id) ?: return
        val url = sub.url ?: run {
            showToast(str(com.interstellar.proxy.R.string.vm_local_no_refresh), UiToast.Kind.Error)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (fromPull) _refreshing.value = true
            _busy.value = true
            try {
                val result = SubscriptionFetcher.fetch(url)
                val format = SubscriptionRepository.saveRawBody(id, result.body)
                when (val parsed = SubscriptionParser.parse(result.body, id)) {
                    is SubscriptionParser.Result.Nodes -> {
                        SubscriptionRepository.upsert(
                            sub.copy(
                                nodes = parsed.nodes,
                                configFormat = format,
                                uploadBytes = result.uploadBytes,
                                downloadBytes = result.downloadBytes,
                                totalBytes = result.totalBytes,
                                expireSeconds = result.expireSeconds,
                                lastUpdated = System.currentTimeMillis(),
                            ),
                        )
                        showToast(str(com.interstellar.proxy.R.string.vm_refreshed, sub.name, parsed.nodes.size), UiToast.Kind.Success)
                    }

                    SubscriptionParser.Result.Empty ->
                        if (format != null) {
                            SubscriptionRepository.upsert(
                                sub.copy(
                                    configFormat = format,
                                    uploadBytes = result.uploadBytes,
                                    downloadBytes = result.downloadBytes,
                                    totalBytes = result.totalBytes,
                                    expireSeconds = result.expireSeconds,
                                    lastUpdated = System.currentTimeMillis(),
                                ),
                            )
                            showToast(str(com.interstellar.proxy.R.string.vm_config_kept, sub.name), UiToast.Kind.Success)
                        } else {
                            showToast(str(com.interstellar.proxy.R.string.vm_refresh_unparsable, sub.name), UiToast.Kind.Error)
                        }
                }
            } catch (e: Exception) {
                showToast(str(com.interstellar.proxy.R.string.vm_refresh_failed, sub.name, e.message ?: ""), UiToast.Kind.Error)
            } finally {
                _busy.value = false
                _refreshing.value = false
                // upsert regenerated the active config behind refreshProxyConfig's back
                refreshStaticGroups()
                _subscriptions.value = SubscriptionRepository.subscriptions.toList()
                _activeSubscriptionId.value = SubscriptionRepository.activeSubscriptionId
            }
        }
    }

    /** Refresh every URL subscription in parallel, then one aggregate toast. */
    fun refreshAll() {
        val subs = SubscriptionRepository.subscriptions.filter { it.url != null }
        if (subs.isEmpty()) {
            showToast(str(com.interstellar.proxy.R.string.vm_no_sub_to_update), UiToast.Kind.Error)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _refreshing.value = true
            _busy.value = true
            val semaphore = kotlinx.coroutines.sync.Semaphore(3)
            var ok = 0
            try {
                kotlinx.coroutines.coroutineScope {
                    subs.forEach { sub ->
                        launch {
                            semaphore.withPermit {
                                val success = runCatching {
                                    val result = SubscriptionFetcher.fetch(sub.url!!)
                                    when (val parsed = SubscriptionParser.parse(result.body, sub.id)) {
                                        is SubscriptionParser.Result.Nodes -> {
                                            SubscriptionRepository.upsert(
                                                sub.copy(
                                                    nodes = parsed.nodes,
                                                    uploadBytes = result.uploadBytes,
                                                    downloadBytes = result.downloadBytes,
                                                    totalBytes = result.totalBytes,
                                                    expireSeconds = result.expireSeconds,
                                                    lastUpdated = System.currentTimeMillis(),
                                                ),
                                            )
                                            true
                                        }

                                        SubscriptionParser.Result.Empty -> {
                                            val fmt = SubscriptionRepository.saveRawBody(sub.id, result.body)
                                            if (fmt != null) {
                                                SubscriptionRepository.upsert(
                                                    sub.copy(
                                                        configFormat = fmt,
                                                        uploadBytes = result.uploadBytes,
                                                        downloadBytes = result.downloadBytes,
                                                        totalBytes = result.totalBytes,
                                                        expireSeconds = result.expireSeconds,
                                                        lastUpdated = System.currentTimeMillis(),
                                                    ),
                                                )
                                                true
                                            } else {
                                                false
                                            }
                                        }
                                    }
                                }.getOrDefault(false)
                                if (success) ok++
                            }
                        }
                    }
                }
                if (ok == subs.size) {
                    showToast(str(com.interstellar.proxy.R.string.vm_updated_all, ok), UiToast.Kind.Success)
                } else {
                    showToast(
                        str(com.interstellar.proxy.R.string.vm_updated_partial, ok, subs.size),
                        if (ok > 0) UiToast.Kind.Success else UiToast.Kind.Error,
                    )
                }
            } finally {
                _busy.value = false
                _refreshing.value = false
                // upserts regenerated the active config behind refreshProxyConfig's back
                refreshStaticGroups()
                _subscriptions.value = SubscriptionRepository.subscriptions.toList()
                _activeSubscriptionId.value = SubscriptionRepository.activeSubscriptionId
            }
        }
    }

    /** Edits an existing subscription's name / url. */
    fun updateSubscription(id: String, name: String, url: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val sub = SubscriptionRepository.get(id) ?: return@launch
            SubscriptionRepository.upsert(
                sub.copy(
                    name = name.trim().ifBlank { sub.name },
                    url = url?.trim()?.takeIf { it.isNotBlank() } ?: sub.url,
                ),
            )
            _subscriptions.value = SubscriptionRepository.subscriptions.toList()
            _activeSubscriptionId.value = SubscriptionRepository.activeSubscriptionId
        }
    }

    fun removeSubscription(id: String) {
        val wasActive = id == SubscriptionRepository.activeSubscriptionId
        SubscriptionRepository.remove(id)
        when {
            SubscriptionRepository.subscriptions.isEmpty() -> {
                // 删光了:节点/分组全清,停掉还在吃旧配置的内核
                _groups.value = emptyList()
                _delays.value = emptyMap()
                _staticGroups.value = emptyList()
                if (_status.value == Status.Started || _status.value == Status.Starting) {
                    com.interstellar.proxy.bg.BoxService.stop()
                }
            }

            // 删除的是激活订阅 → 激活项已顺延,重建配置并热重载
            wasActive -> refreshProxyConfig()
        }
        refreshStaticGroups()
        _subscriptions.value = SubscriptionRepository.subscriptions.toList()
        _activeSubscriptionId.value = SubscriptionRepository.activeSubscriptionId
        _mixSubscriptionIds.value = Settings.mixSubscriptionIds
    }

    fun activateSubscription(id: String) {
        if (id == _activeSubscriptionId.value) return
        SubscriptionRepository.activeSubscriptionId = id
        _activeSubscriptionId.value = id
        // regenerate + hot-reload the running core
        refreshProxyConfig()
    }

    /**
     * Toggles mix mode. Enabling with no (valid) checks defaults to every
     * subscription, so the pool is never silently empty on first use.
     */
    fun setMixEnabled(enabled: Boolean) {
        if (enabled == _mixEnabled.value) return
        if (enabled &&
            Settings.mixSubscriptionIds.none { id -> SubscriptionRepository.get(id) != null }
        ) {
            Settings.mixSubscriptionIds =
                SubscriptionRepository.subscriptions.map { it.id }.toSet()
            _mixSubscriptionIds.value = Settings.mixSubscriptionIds
        }
        Settings.mixEnabled = enabled
        _mixEnabled.value = enabled
        applyPoolChange()
    }

    /** Checks / unchecks one subscription in the mix pool; at least one stays checked. */
    fun toggleMixSubscription(id: String) {
        if (!_mixEnabled.value) return
        val current = _mixSubscriptionIds.value
        if (id in current && current.size <= 1) {
            _message.value = str(com.interstellar.proxy.R.string.vm_keep_one_sub)
            return
        }
        val next = if (id in current) current - id else current + id
        Settings.mixSubscriptionIds = next
        _mixSubscriptionIds.value = next
        applyPoolChange()
    }

    /** Rebuilds the config from the new pool and hot-reloads the running core. */
    private fun applyPoolChange() {
        // refreshProxyConfig regenerates from the new pool and reloads the core
        refreshProxyConfig()
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun urlHost(url: String): String = runCatching {
        java.net.URI(url).host ?: url
    }.getOrDefault(url)
}
