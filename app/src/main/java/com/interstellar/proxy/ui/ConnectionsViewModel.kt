package com.interstellar.proxy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.interstellar.proxy.utils.CommandClient
import com.interstellar.proxy.utils.CommandTarget
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Connections
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** One active connection with live traffic (ui-facing snapshot). */
data class ActiveConnection(
    val id: String,
    val domain: String,
    val destination: String,
    val network: String,
    val protocol: String,
    val rule: String,
    val chains: List<String>,
    /** Originating process (null when the core doesn't report it). */
    val process: String? = null,
    val createdAt: Long,
    val uplink: Long,
    val downlink: Long,
    val closed: Boolean,
)

/**
 * Live connection monitor, scoped to the Connections page.
 *
 * This is a pure presentation stream: unlike logs it carries no value once
 * nobody is looking at it. A user who never opens the page must never pay for a
 * connections store, a command client, a stream, per-event snapshot rebuilds or
 * a reconnect poll — so none of them exist until [setActive] turns the session
 * on, and all of them are gone again the moment it turns off.
 *
 * Re-entering the page creates a *fresh* session (new store, new client). The
 * kernel replays the full initial connection state to every new subscriber, so a
 * fresh store always resyncs without stale entries.
 */
class ConnectionsViewModel(application: Application) : AndroidViewModel(application) {

    private val _connections = MutableStateFlow<List<ActiveConnection>>(emptyList())
    val connections: StateFlow<List<ActiveConnection>> = _connections

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** Bumped on every session start/stop; late callbacks from an old session see a stale value. */
    /** Session generation + the serialized snapshot commit (see [SessionGate]). */
    private val gate = SessionGate<ActiveConnection> { _connections.value = it }

    @Volatile
    private var store: Connections? = null

    @Volatile
    private var client: CommandClient? = null
    private var pollJob: Job? = null

    private val active: Boolean get() = store != null && client != null

    private fun isCurrent(generation: Int): Boolean = generation == gate.generationValue && active

    /**
     * Page visibility gate. Idempotent: repeated `true`/`false` are no-ops, so a
     * recomposition storm cannot open or close the session twice.
     */
    fun setActive(value: Boolean) {
        if (value == active) return
        if (value) startSession() else stopSession()
    }

    private fun startSession() {
        val generation = gate.start()
        val newStore = Libbox.newConnections()
        store = newStore
        val newClient = CommandClient(
            viewModelScope,
            CommandClient.ConnectionType.Connections,
            object : CommandClient.Handler {
                override fun onConnected() {
                    if (isCurrent(generation)) _connected.value = true
                }

                override fun onDisconnected() {
                    if (isCurrent(generation)) _connected.value = false
                }

                override fun onConnectionError(kind: CommandClient.ConnectionErrorKind, message: String) {
                    if (isCurrent(generation)) _connected.value = false
                }

                override fun writeConnectionEvents(events: ConnectionEvents) {
                    // The native event object is only valid inside this callback
                    // (CommandClient passes it through without copying, unlike the
                    // log path), so it must be consumed synchronously. What we can
                    // and do skip is the work itself: an inactive session drops
                    // the events instead of rebuilding a snapshot nobody sees.
                    if (!isCurrent(generation)) return
                    runBlocking { publish(generation, newStore, events) }
                }
            },
        )
        client = newClient
        newClient.connect()

        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(1500)
                if (!_connected.value && isCurrent(generation)) newClient.connect()
            }
        }
    }

    private fun stopSession() {
        // Stop the source first, so no new callback can start work for this session.
        pollJob?.cancel()
        pollJob = null
        client?.disconnect()
        client = null
        store = null
        _connected.value = false
        // Then invalidate and drop the snapshot as ONE step. The generation bump and
        // the clear must be atomic with respect to a commit that already passed its
        // own check: otherwise that commit could write a stale list back *after* this
        // clear and resurrect a closed page's snapshot.
        //
        // Nobody is looking at the snapshot once the page is closed, and holding
        // ActiveConnection rows alive while it is closed is exactly the background
        // work this class exists to avoid.
        gate.stop()
    }

    private suspend fun publish(generation: Int, store: Connections, events: ConnectionEvents) =
        withContext(Dispatchers.Default) {
            if (!isCurrent(generation)) return@withContext
            store.applyEvents(events)
            store.filterState(Libbox.ConnectionStateAll.toInt())
            val list = mutableListOf<ActiveConnection>()
            val iterator = store.iterator()
            while (iterator.hasNext()) {
                val c = iterator.next()
                list.add(
                    ActiveConnection(
                        id = c.id,
                        domain = c.domain.ifBlank { c.destination },
                        destination = c.destination,
                        network = c.network,
                        protocol = c.protocol,
                        rule = c.rule,
                        chains = buildList {
                            val it = c.chain()
                            while (it.hasNext()) add(it.next())
                        },
                        createdAt = c.createdAt,
                        uplink = c.uplinkTotal,
                        downlink = c.downlinkTotal,
                        closed = c.closedAt > 0,
                    ),
                )
            }
            list.sortWith(compareBy({ !it.closed }, { -it.createdAt }))
            gate.commit(generation, list)
        }

    fun closeConnection(id: String) {
        viewModelScope.launch {
            runCatching { CommandTarget.standaloneClient().closeConnection(id) }
        }
    }

    fun closeAll() {
        viewModelScope.launch {
            runCatching { CommandTarget.standaloneClient().closeConnections() }
        }
    }

    override fun onCleared() {
        stopSession()
        super.onCleared()
    }
}

/**
 * Session bookkeeping for the page-scoped connections stream.
 *
 * Pure — no Android, no libbox — so the ordering rules are unit-testable instead of
 * being argued about in comments. It owns two guarantees:
 *
 *  1. **Cross-thread visibility.** The generation is written from the UI thread by
 *     [start]/[stop] and read from the libbox callback thread. A plain field gives no
 *     visibility guarantee, and a stale read is exactly how an old session's result
 *     gets committed.
 *  2. **No resurrected snapshot.** The final commit is serialized against the clear.
 *     Without that, the ordering "check generation → stop clears → commit writes"
 *     re-publishes a list that was just discarded. With it, either the commit lands
 *     first and the clear wipes it, or the clear lands first and the check inside the
 *     lock rejects the commit: both orders end empty.
 */
internal class SessionGate<T>(private val publish: (List<T>) -> Unit) {

    @Volatile
    private var generation = 0

    private val lock = Any()

    /** Open a new session and return its generation. */
    fun start(): Int = synchronized(lock) { ++generation }

    /** The live session's generation. */
    val generationValue: Int get() = generation

    /** Publish [list] only if [candidate] is still the live session. */
    fun commit(candidate: Int, list: List<T>) {
        synchronized(lock) {
            if (candidate != generation) return
            publish(list)
        }
    }

    /** Close the session and clear what it published, as one atomic step. */
    fun stop() {
        synchronized(lock) {
            generation++
            publish(emptyList())
        }
    }
}
