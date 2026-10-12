package com.interstellar.proxy.utils

import android.util.Log
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import com.interstellar.proxy.ktx.toList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

open class CommandClient(
    private val scope: CoroutineScope,
    private val connectionTypes: List<ConnectionType>,
    private val handler: Handler,
    private val localOnly: Boolean = true,
) {
    constructor(
        scope: CoroutineScope,
        connectionType: ConnectionType,
        handler: Handler,
        localOnly: Boolean = true,
    ) : this(scope, listOf(connectionType), handler, localOnly)

    private val additionalHandlers = mutableListOf<Handler>()
    private var cachedGroups: MutableList<OutboundGroup>? = null
    private var cachedOutbounds: List<io.nekohasekai.libbox.OutboundGroupItem>? = null

    fun addHandler(handler: Handler) {
        synchronized(additionalHandlers) {
            if (!additionalHandlers.contains(handler)) {
                additionalHandlers.add(handler)
                cachedGroups?.let { groups ->
                    handler.updateGroups(groups)
                }
                cachedOutbounds?.let { outbounds ->
                    handler.updateOutbounds(outbounds)
                }
            }
        }
    }

    fun removeHandler(handler: Handler) {
        synchronized(additionalHandlers) {
            additionalHandlers.remove(handler)
        }
    }

    private fun getAllHandlers(): List<Handler> = synchronized(additionalHandlers) {
        listOf(handler) + additionalHandlers
    }

    enum class ConnectionType {
        Status,
        Groups,
        Log,
        ClashMode,
        Connections,
        Outbounds,
    }

    enum class ConnectionErrorKind {
        ConnectFailed,
        ConnectionLost,
    }

    interface Handler {
        fun onConnected() {}

        fun onDisconnected() {}

        fun onConnectionError(kind: ConnectionErrorKind, message: String) {}

        fun updateStatus(status: StatusMessage) {}

        fun setDefaultLogLevel(level: Int) {}

        fun clearLogs() {}

        fun appendLogs(message: List<LogEntry>) {}

        fun updateGroups(newGroups: MutableList<OutboundGroup>) {}

        fun updateOutbounds(outbounds: List<io.nekohasekai.libbox.OutboundGroupItem>) {}

        fun initializeClashMode(modeList: List<String>, currentMode: String) {}

        fun updateClashMode(newMode: String) {}

        fun writeConnectionEvents(events: ConnectionEvents) {}
    }

    private val access = Any()
    private var connectionEpoch = 0
    private var commandClient: io.nekohasekai.libbox.CommandClient? = null

    /**
     * Whether the current outage has already been reported.
     *
     * The logs page polls `connect()` every 2 s while it is not connected, and "not connected"
     * is the ordinary state whenever the VPN is down. Logging every attempt therefore emitted a
     * ~15-line stack trace every two seconds, which buried the one line that mattered - the
     * reason the *core* failed - under its own retry noise. The first failure of an outage keeps
     * its stack (that is the diagnostic); the rest are the same failure, so they are silent.
     * Reset on a successful connect, so the next outage is reported again.
     */
    private val connectFailureReported = java.util.concurrent.atomic.AtomicBoolean(false)

    fun connect() {
        val epoch: Int
        val previousClient: io.nekohasekai.libbox.CommandClient?
        synchronized(access) {
            epoch = ++connectionEpoch
            previousClient = commandClient
            commandClient = null
        }
        if (previousClient != null) {
            getAllHandlers().forEach { it.onDisconnected() }
        }
        scope.launch(Dispatchers.IO) {
            previousClient?.apply {
                runCatching {
                    disconnect()
                }
            }
            val options = CommandClientOptions()
            connectionTypes.forEach { connectionType ->
                val command =
                    when (connectionType) {
                        ConnectionType.Status -> io.nekohasekai.libbox.Libbox.CommandStatus
                        ConnectionType.Groups -> io.nekohasekai.libbox.Libbox.CommandGroup
                        ConnectionType.Log -> io.nekohasekai.libbox.Libbox.CommandLog
                        ConnectionType.ClashMode -> io.nekohasekai.libbox.Libbox.CommandClashMode
                        ConnectionType.Connections -> io.nekohasekai.libbox.Libbox.CommandConnections
                        ConnectionType.Outbounds -> io.nekohasekai.libbox.Libbox.CommandOutbounds
                    }
                options.addCommand(command)
            }
            options.statusInterval = 1 * 1000 * 1000 * 1000
            val newClient: io.nekohasekai.libbox.CommandClient
            try {
                newClient = io.nekohasekai.libbox.CommandClient(ClientHandler(epoch), options)
                newClient.connect()
            } catch (e: Exception) {
                // First failure of this outage keeps its stack; the poll's repeats are the same
                // failure and are dropped (see connectFailureReported).
                if (connectFailureReported.compareAndSet(false, true)) {
                    Log.d("CommandClient", "connect failed (further failures are not repeated)", e)
                }
                if (isActiveEpoch(epoch)) {
                    handler.onConnectionError(
                        ConnectionErrorKind.ConnectFailed,
                        e.message ?: e.toString(),
                    )
                }
                return@launch
            }
            val stale =
                synchronized(access) {
                    if (epoch != connectionEpoch) {
                        true
                    } else {
                        commandClient = newClient
                        false
                    }
                }
            if (stale) {
                runCatching {
                    newClient.disconnect()
                }
            }
        }
    }

    fun disconnect() {
        val client: io.nekohasekai.libbox.CommandClient?
        synchronized(access) {
            connectionEpoch++
            client = commandClient
            commandClient = null
        }
        if (client != null) {
            getAllHandlers().forEach { it.onDisconnected() }
            @OptIn(DelicateCoroutinesApi::class)
            GlobalScope.launch(Dispatchers.IO) {
                runCatching {
                    client.disconnect()
                }
            }
        }
    }

    private fun isActiveEpoch(epoch: Int): Boolean = synchronized(access) { epoch == connectionEpoch }

    private inner class ClientHandler(private val epoch: Int) : CommandClientHandler {
        override fun connected() {
            if (!isActiveEpoch(epoch)) return
            connectFailureReported.set(false)
            getAllHandlers().forEach { it.onConnected() }
            Log.d("CommandClient", "connected")
        }

        override fun disconnected(message: String?) {
            if (!isActiveEpoch(epoch)) return
            getAllHandlers().forEach { it.onDisconnected() }
            if (message != null) {
                handler.onConnectionError(ConnectionErrorKind.ConnectionLost, message)
            }
            Log.d("CommandClient", "disconnected: $message")
        }

        override fun writeGroups(message: OutboundGroupIterator?) {
            if (message == null || !isActiveEpoch(epoch)) {
                return
            }
            val groups = mutableListOf<OutboundGroup>()
            while (message.hasNext()) {
                groups.add(message.next())
            }
            cachedGroups = groups
            getAllHandlers().forEach { it.updateGroups(groups) }
        }

        override fun writeOutbounds(message: OutboundGroupItemIterator?) {
            if (message == null || !isActiveEpoch(epoch)) {
                return
            }
            val outbounds = mutableListOf<io.nekohasekai.libbox.OutboundGroupItem>()
            while (message.hasNext()) {
                outbounds.add(message.next())
            }
            cachedOutbounds = outbounds
            getAllHandlers().forEach { it.updateOutbounds(outbounds) }
        }

        override fun setDefaultLogLevel(level: Int) {
            if (!isActiveEpoch(epoch)) return
            getAllHandlers().forEach { it.setDefaultLogLevel(level) }
        }

        override fun clearLogs() {
            if (!isActiveEpoch(epoch)) return
            getAllHandlers().forEach { it.clearLogs() }
        }

        override fun writeLogs(messageList: LogIterator?) {
            if (messageList == null || !isActiveEpoch(epoch)) {
                return
            }
            val logs = messageList.toList()
            getAllHandlers().forEach { it.appendLogs(logs) }
        }

        override fun writeStatus(message: StatusMessage) {
            if (!isActiveEpoch(epoch)) return
            getAllHandlers().forEach { it.updateStatus(message) }
        }

        override fun initializeClashMode(modeList: StringIterator, currentMode: String) {
            if (!isActiveEpoch(epoch)) return
            val modes = modeList.toList()
            getAllHandlers().forEach { it.initializeClashMode(modes, currentMode) }
        }

        override fun updateClashMode(newMode: String) {
            if (!isActiveEpoch(epoch)) return
            getAllHandlers().forEach { it.updateClashMode(newMode) }
        }

        override fun writeConnectionEvents(events: ConnectionEvents?) {
            if (events == null || !isActiveEpoch(epoch)) return
            getAllHandlers().forEach { it.writeConnectionEvents(events) }
        }
    }
}
