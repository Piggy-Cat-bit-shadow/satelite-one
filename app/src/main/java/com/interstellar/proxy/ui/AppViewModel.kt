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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import com.interstellar.proxy.data.subscription.str

data class SpeedState(
    val uplinkPerSecond: Long = 0,
    val downlinkPerSecond: Long = 0,
    val uplinkTotal: Long = 0,
    val downlinkTotal: Long = 0,
    /**
     * Whether the running config actually has the kernel's traffic manager.
     *
     * Auto-generated configs enable it (see MinimalConfigBuilder's empty
     * `clash_api`). A user-supplied raw sing-box JSON is handed to the kernel
     * byte-for-byte and may never enable it — in that case there are NO statistics,
     * which is a different statement from "measured zero".
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
 * `memory` is the Go heap the kernel reports and `goroutines` is
 * `runtime.NumGoroutine()` inside sing-box — NOT a Kotlin coroutine count. Both
 * arrive on the StatusMessage we already subscribe to, so the home page needs no
 * extra command client, no polling and no /proc reading.
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
 */
internal fun resolveUrlTestTarget(groupTag: String, groups: List<CoreGroup>): String {
    if (groupTag.isBlank()) return MinimalConfigBuilder.AUTO_TAG
    val live = groups.find { it.tag == groupTag }
    val isUrlTest = live != null && live.type.equals("urltest", ignoreCase = true)
    return if (isUrlTest) groupTag else MinimalConfigBuilder.AUTO_TAG
}

/** Transient feedback pill (subscription updates etc.). */
data class UiToast(val text: String, val kind: Kind) {
    enum class Kind { Success, Error }
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

    /** True while a group url-test is in flight (for UI spinners). */
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing

    /** Batch test progress (done, total); null while idle. */
    private val _testProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val testProgress: StateFlow<Pair<Int, Int>?> = _testProgress

    /** Epoch seconds when the current url-test run started (0 = idle). */
    @Volatile private var testStartEpoch = 0L

    /** Serializes kernel url-test runs. */
    private val kernelUrlTestMutex = kotlinx.coroutines.sync.Mutex()

    /** True while the in-flight kernel url-test run belongs to the manual button. */
    @Volatile private var kernelTestManual = false

    /** sing-box's per-node url-test HTTP timeout (constant.TCPTimeout, hardcoded in the kernel). */
    private val PER_NODE_TEST_TIMEOUT_MS = 15_000L

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

    /** Set by onConnected while probing: the headless command socket is up. */
    @Volatile private var probeSocketUp = false

    private companion object {
        const val GROUP_TAG = "proxy"
        const val STARTING_TIMEOUT_MS = 15_000L
        const val SOCKET_DROP_TIMEOUT_MS = 400L

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
                autoTested = false
                if (!probing) applySocketDrop()
            }

            override fun onConnectionError(kind: CommandClient.ConnectionErrorKind, message: String) {
                if (!probing) applySocketDrop()
            }

            override fun updateStatus(status: StatusMessage) {
                if (probing) return
                // Status ticks only after the tun is actually running — this is
                // what promotes Starting → Started, so a socket bounce during
                // boot cannot flash Stopped.
                if (_status.value == Status.Starting) {
                    markStarted()
                }
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
            }

            override fun updateOutbounds(outbounds: List<io.nekohasekai.libbox.OutboundGroupItem>) {
                val map = _delays.value.toMutableMap()
                for (item in outbounds) {
                    map[item.tag] = item.urlTestDelay
                }
                _delays.value = map
                // url-test progress: count results stamped after this run started
                if (testStartEpoch > 0) {
                    val done = outbounds.count { it.urlTestTime >= testStartEpoch }
                    val prevTotal = _testProgress.value?.second ?: 0
                    val total = maxOf(prevTotal, outbounds.size)
                    if (total > 0) {
                        if (done >= total) {
                            testStartEpoch = 0
                            if (kernelTestManual) {
                                _testProgress.value = null
                                _testing.value = false
                            }
                        } else if (kernelTestManual) {
                            _testProgress.value = done.coerceAtMost(total) to total
                        }
                    }
                }
                // NB: snapshots can arrive at any moment (delayed pushes) and
                // must never kill an in-flight run — the settle branch above
                // and the callers' finally own the testing state.
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

    fun connect() {
        commandClient.connect()
        registerStoppedReceiver()
        // poll-reconnect: the box may start/stop at any time, and a failed
        // connect (server not up yet) must be retried to reflect the state
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(2000)
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
        commandClient.disconnect()
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
                runCatching { CommandTarget.standaloneClient().urlTest(MinimalConfigBuilder.AUTO_TAG) }
            }
        }
    }

    private fun markStopped() {
        startingWatchdog?.cancel()
        startingWatchdog = null
        autoTested = false
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
                commandClient.connect()
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
        _selectedOutboundTag.value = itemTag
        viewModelScope.launch(Dispatchers.IO) {
            if (_status.value == Status.Started) {
                runCatching {
                    CommandTarget.standaloneClient().selectOutbound(groupTag, itemTag)
                }.onFailure {
                    _message.value = str(com.interstellar.proxy.R.string.vm_switch_failed, it.message ?: "")
                }
            } else {
                // stopped: bake the pick into the regenerated config and
                // refresh the static groups so the page reflects it at once
                val config = SubscriptionRepository.regenerateActiveConfig()
                if (config != null) {
                    _staticGroups.value = parseSingboxGroups(config)
                }
                _selectedOutboundTag.value = Settings.selectedOutboundTag
            }
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

    fun urlTest(groupTag: String) {
        if (_testing.value) return
        val target = urlTestTarget(groupTag)
        _testing.value = true
        _testProgress.value = 0 to urlTestTotal()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (_status.value == Status.Started) {
                    if (!runKernelUrlTest(manual = true, target = target)) {
                        _message.value = str(com.interstellar.proxy.R.string.vm_test_failed_send)
                    }
                } else {
                    runDisconnectedUrlTest(target)
                }
            } catch (e: Exception) {
                _message.value = str(com.interstellar.proxy.R.string.vm_test_failed, e.message ?: "")
            } finally {
                _testing.value = false
                _testProgress.value = null
                testStartEpoch = 0
            }
        }
    }

    /** Denominator for the batch progress: live group size → known delays → pool size. */
    private fun urlTestTotal(): Int {
        _groups.value.find { it.tag == GROUP_TAG }?.let { group ->
            if (group.items.isNotEmpty()) return group.items.size
        }
        if (_delays.value.isNotEmpty()) return _delays.value.size
        return SubscriptionRepository.poolOf(
            _subscriptions.value,
            _activeSubscriptionId.value,
            _mixEnabled.value,
            _mixSubscriptionIds.value,
        ).size
    }

    /**
     * Wait for the kernel url-test to finish. Nodes test concurrently, each
     * with sing-box's own per-node HTTP timeout (constant.TCPTimeout = 15s,
     * not configurable), so the run is bounded by that plus scheduling
     * margin — wait for the stream's done>=total clear, capped at 15s+10s.
     * No independent overall-stall heuristic.
     */
    private suspend fun awaitUrlTestSettled(capMs: Long = PER_NODE_TEST_TIMEOUT_MS + 10_000) {
        val deadline = System.currentTimeMillis() + capMs
        while (System.currentTimeMillis() < deadline && testStartEpoch > 0) {
            delay(500)
        }
    }

    /**
     * One kernel url-test run, serialized: without the mutex one run's stream
     * pushes would settle another run's wait (or let a stray push kill the
     * spinner mid-run). Marks the
     * run epoch, sends the command, then waits for the outbounds stream to
     * report every member stamped after the epoch — bounded by the kernel's
     * per-node timeout plus margin. False = the command could not be sent.
     */
    private suspend fun runKernelUrlTest(manual: Boolean, target: String = MinimalConfigBuilder.AUTO_TAG): Boolean =
        kernelUrlTestMutex.withLock {
            kernelTestManual = manual
            testStartEpoch = System.currentTimeMillis() / 1000
            if (manual) {
                // a colliding run's settle may have flashed these off while
                // this caller was waiting on the mutex
                _testing.value = true
                _testProgress.value = 0 to urlTestTotal()
            }
            val ok = runCatching {
                CommandTarget.standaloneClient().urlTest(target)
            }.isSuccess
            if (!ok) {
                testStartEpoch = 0
                return@withLock false
            }
            val deadline = System.currentTimeMillis() + PER_NODE_TEST_TIMEOUT_MS + 10_000
            while (testStartEpoch > 0 && System.currentTimeMillis() < deadline) delay(500)
            testStartEpoch = 0 // cap fallback; callers' finally owns the UI state
            true
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
            kernelTestManual = true // manual run: progress + settle own the UI state
            testStartEpoch = System.currentTimeMillis() / 1000
            val ok = runCatching {
                CommandTarget.standaloneClient().urlTest(target)
            }.isSuccess
            if (!ok) throw IllegalStateException(str(com.interstellar.proxy.R.string.vm_test_cmd_failed))
            // bounded by the kernel's per-node timeout (15s) + margin —
            // a fixed 10s here used to cut the headless core mid-run and
            // leave the bottom nodes 未测
            awaitUrlTestSettled()
        } finally {
            probeSocketUp = false
            val takenOver = _status.value == Status.Starting || _status.value == Status.Started
            if (!takenOver) {
                com.interstellar.proxy.bg.BoxService.stop()
                delay(250)
                if (previous != null) ConfigStore.writeActiveConfig(previous)
                else SubscriptionRepository.regenerateActiveConfig()
            }
            probing = false
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
