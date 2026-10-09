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

    // Version 0 is "nothing published yet", so a fresh gate is quiet rather than
    // immediately due.
    //
    // This object is consulted by the UI ticker while whoever publishes writes it,
    // and it used to be plain mutable fields touched from both — the exact "pure
    // single-threaded test object that is multi-threaded in production" shape.
    private val lock = Any()
    private var publishedVersion = 0L
    private var lastPublishAt = Long.MIN_VALUE

    /** Ask whether version [version] should be rendered at [nowMs]. Read-only. */
    fun isDue(version: Long, nowMs: Long): Boolean = synchronized(lock) {
        if (version == publishedVersion) return false
        if (lastPublishAt != Long.MIN_VALUE && nowMs - lastPublishAt < minIntervalMs) return false
        true
    }

    /**
     * Record that [version] — the version of the snapshot that was actually taken —
     * has been shown at [nowMs].
     *
     * Passing the snapshot's own version (never "whatever is current now") is what
     * closes the lost-tail race: an append landing between the snapshot and this call
     * leaves the buffer ahead, so the next tick is still due.
     */
    fun markPublished(version: Long, nowMs: Long) {
        synchronized(lock) {
            publishedVersion = version
            lastPublishAt = nowMs
        }
    }

    fun reset() {
        synchronized(lock) {
            publishedVersion = 0L
            lastPublishAt = Long.MIN_VALUE
        }
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

    /**
     * The ring version [logs] currently reflects.
     *
     * The Logs page follows this instead of `logs.size`: once the 3000-line buffer
     * saturates, the size stops changing and a size-keyed effect never fires again.
     */
    private val _logsVersion = MutableStateFlow(0L)
    val logsVersion: StateFlow<Long> = _logsVersion

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /**
     * Retained history. 3000 matches the previous retention exactly — this round
     * changes how the buffer is copied, never how much is kept.
     */
    private val ring = LogRingBuffer<LogLine>(CAPACITY)

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
        // Capture only: the ring owns the version, and the UI watermark is advanced
        // exclusively by publishSnapshot() so the two can never disagree.
        ring.add(line)
    }

    private fun clearLocalHistory() {
        ring.clear()
        uiGate.reset()
        _logs.value = emptyList()
        _logsVersion.value = ring.currentVersion
    }

    private fun publishSnapshot() {
        val (snapshot, version) = ring.snapshotWithVersion()
        _logs.value = snapshot
        _logsVersion.value = version
        // Mark the version we actually published. If an append landed after the
        // snapshot, the ring is already ahead of it, so the next tick still sees a
        // newer version and the newest line is never lost.
        uiGate.markPublished(version, android.os.SystemClock.elapsedRealtime())
    }

    private fun startUiTicker() {
        if (uiTicker?.isActive == true) return
        uiTicker = viewModelScope.launch {
            while (isActive && uiVisible) {
                delay(UI_REFRESH_MS)
                if (uiGate.isDue(ring.currentVersion, android.os.SystemClock.elapsedRealtime())) {
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
