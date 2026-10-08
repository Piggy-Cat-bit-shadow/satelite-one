package com.interstellar.proxy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.interstellar.proxy.core.AppLog
import com.interstellar.proxy.utils.CommandClient
import com.interstellar.proxy.utils.CommandTarget
import io.nekohasekai.libbox.LogEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One log line (libbox LogEntry). */
data class LogLine(val level: Int, val message: String, val timestamp: Long)

class LogsViewModel(application: Application) : AndroidViewModel(application) {
    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var buffer = ArrayDeque<LogLine>()

    private val client = CommandClient(
        viewModelScope,
        CommandClient.ConnectionType.Log,
        object : CommandClient.Handler {
            override fun onConnected() {
                _connected.value = true
            }

            override fun onDisconnected() {
                _connected.value = false
            }

            override fun clearLogs() {
                buffer.clear()
                _logs.value = emptyList()
            }

            override fun appendLogs(message: List<LogEntry>) {
                for (entry in message) {
                    append(LogLine(entry.level, entry.message, System.currentTimeMillis()))
                }
            }
        },
    )

    private var appLogJob: Job? = null

    fun connect() {
        startAppLogCollector()
        client.connect()
    }

    fun disconnect() {
        client.disconnect()
        appLogJob?.cancel()
        appLogJob = null
    }

    fun clearLogs() {
        buffer.clear()
        _logs.value = emptyList()
        viewModelScope.launch {
            runCatching { CommandTarget.standaloneClient().clearLogs() }
        }
    }

    // ---- shared ----

    private fun append(line: LogLine) {
        buffer.addLast(line)
        while (buffer.size > 3000) buffer.removeFirst()
        _logs.value = buffer.toList()
    }

    private fun startAppLogCollector() {
        if (appLogJob?.isActive == true) return
        AppLog.snapshot.forEach { append(LogLine(LEVEL_INFO, "[app] $it", System.currentTimeMillis())) }
        appLogJob = viewModelScope.launch {
            AppLog.events.collect { line ->
                append(LogLine(LEVEL_INFO, "[app] $line", System.currentTimeMillis()))
            }
        }
    }

    companion object {
        private const val LEVEL_INFO = 4
    }
}
