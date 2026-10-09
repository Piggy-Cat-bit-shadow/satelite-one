package com.interstellar.proxy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.interstellar.proxy.core.AppLog
import com.interstellar.proxy.core.LogRingBuffer
import com.interstellar.proxy.utils.CommandClient
import com.interstellar.proxy.utils.CommandTarget
import io.nekohasekai.libbox.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** One log line (libbox LogEntry). */
data class LogLine(val level: Int, val message: String, val timestamp: Long)

/**
 * Decides when a UI snapshot is due.
 *
 * Deliberately tiny and pure so the coalescing rule can be unit-tested without a
 * ViewModel: a snapshot is produced only when new lines arrived AND at most once
 * per [minIntervalMs]. Capture is never gated by this — the ring always keeps
 * every line; only the UI list is coalesced.
 */
internal class LogUiPublishGate(private val minIntervalMs: Long = 75L) {

    // publishedVersion starts equal to version: a buffer with no appends has
    // nothing new to show, so a fresh gate is quiet rather than "due".
    private var version = 0L
    private var publishedVersion = 0L
    private var lastPublishAt = Long.MIN_VALUE

    fun onAppend() {
        version++
    }

    /** Ask whether a snapshot should be taken at [nowMs]. Read-only. */
    fun isDue(nowMs: Long): Boolean {
        if (publishedVersion == version) return false
        if (lastPublishAt != Long.MIN_VALUE && nowMs - lastPublishAt < minIntervalMs) return false
        return true
    }

    /** Record that a snapshot was taken at [nowMs]. */
    fun markPublished(nowMs: Long) {
        publishedVersion = version
        lastPublishAt = nowMs
    }

    fun reset() {
        version = 0L
        publishedVersion = 0L
        lastPublishAt = Long.MIN_VALUE
    }
}

/**
 * Kernel + app log capture and the Logs page UI list.
 *
 * Capture and presentation are intentionally separated:
 *
 *  - **Capture** always runs, page open or not. `LogsViewModel.connect()` is
 *    called once from AppRoot and keeps the kernel log stream, the AppLog
 *    collector and the reconnect loop alive for the whole app session. Logs are
 *    after-the-fact debug evidence, so a problem that happened while the page was
 *    closed must still be readable afterwards. Nothing here drops, samples or
 *    filters lines.
 *  - **Presentation** only runs while the Logs page is actually visible. Hidden,
 *    the view model still appends to the ring but produces no `List` snapshot and
 *    publishes nothing; visible, it publishes at most one snapshot per
 *    [UI_REFRESH_MS] regardless of how many lines arrive in between.
 */
class LogsViewModel(application: Application) : AndroidViewModel(application) {

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /**
     * Retained history. 3000 matches the previous retention exactly — this round
     * changes how the buffer is copied, never how much is kept.
     */
    private val ring = LogRingBuffer<LogLine>(CAPACITY)

    /** Bumped by every append; lets the UI know whether anything new happened. */
    private val appendVersion = AtomicLong(0)

    private val uiGate = LogUiPublishGate(UI_REFRESH_MS)

    @Volatile
    private var uiVisible = false
    private var uiTicker: Job? = null

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
                clearLocalHistory()
            }

            override fun appendLogs(message: List<LogEntry>) {
                for (entry in message) {
                    append(LogLine(entry.level, entry.message, System.currentTimeMillis()))
                }
            }
        },
    )

    private var appLogJob: Job? = null
    private var reconnectJob: Job? = null

    fun connect() {
        startAppLogCollector()
        client.connect()
        // poll-reconnect: libbox's own client only retries briefly and then
        // gives up without redialling, so a page opened before the VPN started
        // would show only [app] lines forever. The box may come up at any time.
        if (reconnectJob?.isActive != true) {
            reconnectJob = viewModelScope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(2000)
                    if (!_connected.value) client.connect()
                }
            }
        }
    }

    fun disconnect() {
        client.disconnect()
        reconnectJob?.cancel()
        reconnectJob = null
        appLogJob?.cancel()
        appLogJob = null
        stopUiTicker()
    }

    /**
     * Logs page visibility. Entering publishes the retained history immediately
     * (so a problem that happened minutes ago is on screen at once); leaving
     * stops the ticker. Capture is untouched either way.
     */
    fun setUiVisible(visible: Boolean) {
        if (visible == uiVisible) return
        uiVisible = visible
        if (visible) {
            publishSnapshot()
            startUiTicker()
        } else {
            stopUiTicker()
        }
    }

    /** Full retained history, newest last. Used by Copy so UI batching cannot lose the tail. */
    fun snapshot(): List<LogLine> = ring.snapshot()

    fun clearLogs() {
        clearLocalHistory()
        viewModelScope.launch {
            runCatching { CommandTarget.standaloneClient().clearLogs() }
        }
    }

    // ---- internals ----

    private fun append(line: LogLine) {
        ring.add(line)
        appendVersion.incrementAndGet()
        uiGate.onAppend()
    }

    private fun clearLocalHistory() {
        ring.clear()
        uiGate.reset()
        appendVersion.incrementAndGet()
        _logs.value = emptyList()
    }

    private fun publishSnapshot() {
        _logs.value = ring.snapshot()
        uiGate.markPublished(android.os.SystemClock.elapsedRealtime())
    }

    private fun startUiTicker() {
        if (uiTicker?.isActive == true) return
        uiTicker = viewModelScope.launch {
            while (isActive && uiVisible) {
                delay(UI_REFRESH_MS)
                if (uiGate.isDue(android.os.SystemClock.elapsedRealtime())) {
                    publishSnapshot()
                }
            }
        }
    }

    private fun stopUiTicker() {
        uiTicker?.cancel()
        uiTicker = null
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

    override fun onCleared() {
        stopUiTicker()
        super.onCleared()
    }

    companion object {
        private const val LEVEL_INFO = 4

        /** Retention, unchanged from the previous ArrayDeque implementation. */
        const val CAPACITY = 3000

        /** UI coalescing window; capture is never delayed by this. */
        private const val UI_REFRESH_MS = 75L
    }
}
