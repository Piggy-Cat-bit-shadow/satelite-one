package com.interstellar.proxy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.interstellar.proxy.utils.CommandClient
import com.interstellar.proxy.utils.CommandTarget
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

class ConnectionsViewModel(application: Application) : AndroidViewModel(application) {

    private val _connections = MutableStateFlow<List<ActiveConnection>>(emptyList())
    val connections: StateFlow<List<ActiveConnection>> = _connections

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var pollJob: Job? = null

    // libbox built-in store — applies event deltas correctly and evicts closed
    private val store = Libbox.newConnections()

    private val client = CommandClient(
        viewModelScope,
        CommandClient.ConnectionType.Connections,
        object : CommandClient.Handler {
            override fun onConnected() {
                _connected.value = true
            }

            override fun onDisconnected() {
                _connected.value = false
            }

            override fun onConnectionError(kind: CommandClient.ConnectionErrorKind, message: String) {
                _connected.value = false
            }

            override fun writeConnectionEvents(events: ConnectionEvents) {
                kotlinx.coroutines.runBlocking { publish(events) }
            }
        },
    )

    private suspend fun publish(events: ConnectionEvents) = withContext(Dispatchers.Default) {
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
        _connections.value = list
    }

    fun connect() {
        client.connect()
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(1500)
                if (!_connected.value) client.connect()
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        client.disconnect()
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
}
