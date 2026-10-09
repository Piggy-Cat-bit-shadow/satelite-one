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
    private var sessionGeneration = 0

    private var store: Connections? = null
    private var client: CommandClient? = null
    private var pollJob: Job? = null

    private val active: Boolean get() = store != null && client != null

    private fun isCurrent(generation: Int): Boolean = generation == sessionGeneration && active

    /**
     * Page visibility gate. Idempotent: repeated `true`/`false` are no-ops, so a
     * recomposition storm cannot open or close the session twice.
     */
    fun setActive(value: Boolean) {
        if (value == active) return
        if (value) startSession() else stopSession()
    }

    private fun startSession() {
        val generation = ++sessionGeneration
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
        // Invalidate first: anything still in flight from this session becomes a
        // no-op before we tear the client down.
        sessionGeneration++
        pollJob?.cancel()
        pollJob = null
        client?.disconnect()
        client = null
        store = null
        _connected.value = false
        // Drop the snapshot: nobody is looking at it, and holding ActiveConnection
        // rows alive while the page is closed is exactly the background work this
        // class is meant to avoid.
        _connections.value = emptyList()
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
            if (isCurrent(generation)) _connections.value = list
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
