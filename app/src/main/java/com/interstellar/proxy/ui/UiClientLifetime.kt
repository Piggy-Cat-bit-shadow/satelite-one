package com.interstellar.proxy.ui

/**
 * When the UI's command-socket client should be on, and whether its failure may drive UI status.
 *
 * # Why this is an object and not three booleans in `AppViewModel`
 *
 * Backgrounding the app has to switch off the main UI `CommandClient` — its 1 Hz `Status` tick,
 * its `Groups`/`Outbounds` control plane, its 2 s reconnect poll and its chart sampling — while the
 * VPN, the TUN, the core, the command server and every active connection keep running untouched.
 *
 * The trap is that `CommandClient.disconnect()` notifies its handlers on the *deliberate* path too
 * (deliberately un-gated, because it is deliberate). `AppViewModel` reacts to that notification by
 * calling `applySocketDrop()`, which for `Status.Started` arms a 400 ms watchdog that calls
 * `markStopped()` **and `BoxService.stop()`**. So a bare
 *
 * ```kotlin
 * onBackground { commandClient.disconnect() }
 * ```
 *
 * does not merely mislabel the UI: it tears the running tunnel down about 400 ms later. The same
 * shape appears on `onConnectionError`, and again in `connect()`, which fires the same
 * `onDisconnected()` when a client is already installed — so a redundant resume has the identical
 * hazard.
 *
 * Those three decisions are therefore one decision set, and they live here so they are provable in a
 * plain JVM test rather than only observable on a device. `AppViewModel` keeps the side effects; this
 * keeps the rules. The same split the project already uses for `NotificationPublishGate`.
 *
 * # Semantics
 *
 * ```
 * ACTIVE_UI                      -> the UI client should be connected
 * SUSPENDED_BY_APP_BACKGROUND    -> the UI client is deliberately off
 *                                   != core stopped
 *                                   != socket failure
 *                                   != VPN stopped
 * ```
 *
 * Suspension never touches `Status`, `SpeedState` or the history window: a backgrounded app has
 * learned nothing new about the core, so the last known values stay on screen and nothing is
 * fabricated.
 *
 * The methods are **synchronized and safe to call from any thread**. They used to be documented as
 * main-thread-only, on the assumption that `viewModelScope` is `Dispatchers.Main.immediate`, but that
 * assumption does not hold for every caller: the url-test's `finally` runs on `Dispatchers.IO` and calls
 * back into here through `reapplyOwedSuspend`. Two threads then raced a read-modify-write - one could
 * read `owesSuspend`, be preempted, and apply a stale decision after the other had already handled a
 * foreground edge, leaving the client off while the app was in front with no future edge to correct it
 * (`addForegroundListener` delivers the canonical value only at subscribe). A lock removes the race;
 * the plain `Boolean` fields below are what it protects.
 */
internal class UiClientLifetime {

    /** Guards every field below. Held only across field updates, never across a caller's side effect. */
    private val lock = Any()

    /**
     * Whether the app UI is in the foreground.
     *
     * Starts `true` so a cold start behaves exactly as before: the composition connects on first
     * frame and the first `ON_START` is a no-op.
     */
    private var foreground = true

    private var suspended = false

    private var closing = false

    /** True while the app UI is in the background and the UI client is deliberately off. */
    val isSuspended: Boolean get() = synchronized(lock) { suspended }

    /**
     * The UI went to the background while a headless probe held the socket, so the suspend is owed
     * and has not been applied yet. `suspendUiClient` re-checks this after the probe finishes.
     */
    private var deferringForProbe = false

    /**
     * Whether a suspend is owed because the app went to the background during a headless probe.
     *
     * This exists so the background edge cannot be silently consumed: without it, backgrounding
     * during a url-test left the client running for the rest of the probe and then forever, because
     * `probing` clears in the probe's `finally` and nothing re-applied the suspend.
     */
    val owesSuspend: Boolean get() = synchronized(lock) { deferringForProbe && !suspended }

    /**
     * The app UI went to the background.
     *
     * @param headlessProbe a headless url-test is driving this same client. It signals readiness only
     *   through `onConnected()`, so switching the client off mid-probe would make the probe time out;
     *   the probe is short-lived and there is no UI to serve, so the probe keeps the socket. The
     *   *suspend* is not lost, though: it is remembered and takes effect when the probe ends, because
     *   the background edge must not be silently consumed by an unrelated in-flight operation. What
     *   the caller must do in this case is stop the reconnect poll, which belongs to the UI and not
     *   to the probe.
     * @return true when the caller should disconnect the client now. Idempotent: a repeated
     *   background edge returns false.
     */
    fun onBackground(headlessProbe: Boolean): Boolean = synchronized(lock) {
        foreground = false
        if (headlessProbe) {
            // Remember it. `onForeground` clears this, so a resume still works if the user returns
            // before the probe finishes.
            deferringForProbe = !suspended
            return@synchronized false
        }
        deferringForProbe = false
        if (suspended) return@synchronized false
        suspended = true
        true
    }

    /**
     * The app UI came back to the foreground.
     *
     * @return true when the caller should re-establish the client. Idempotent: a repeated foreground
     *   edge returns false, and an edge arriving while the client was never suspended returns false —
     *   which is what stops a duplicate `connect()`, and with it the `onDisconnected()` /
     *   `applySocketDrop()` sequence that a redundant `connect()` would otherwise trigger.
     */
    fun onForeground(): Boolean = synchronized(lock) {
        foreground = true
        deferringForProbe = false
        if (!suspended) return@synchronized false
        suspended = false
        true
    }

    /**
     * Whether a socket-loss notification may drive UI status right now.
     *
     * False while suspended, because the notification is then a report about a client the user's
     * backgrounding switched off, not about the tunnel. False while a headless probe owns the socket,
     * which is the pre-existing rule this generalises. False while the UI is being torn down, which is
     * the same defect reached by a different route - see [onTearingDown].
     */
    fun mayReportSocketLoss(headlessProbe: Boolean): Boolean =
        synchronized(lock) { !suspended && !closing && !headlessProbe }

    /**
     * The UI is being torn down: `disconnect()` is running because the composition is disposed or the
     * Activity is going away.
     *
     * This is the same defect as suspension, reached without any backgrounding. `disconnect()` cancels
     * `startingWatchdog` and *then* calls `commandClient.disconnect()`, which fires `onDisconnected()`,
     * which calls `applySocketDrop()`, which for `Status.Started` **re-arms the watchdog it just
     * cancelled** - so destroying the Activity or flipping a theme that recreates `AppRoot` while the
     * tunnel is up would `markStopped()` and `BoxService.stop()` 400 ms later. Cancelling before the
     * callback that re-arms it is not a guard.
     *
     * Setting this before the client is disconnected makes the teardown deliberate at the only layer
     * that can tell. It is cleared by [onClientConnected], so the next session is not muted by the
     * previous one's teardown.
     */
    fun onTearingDown() = synchronized(lock) { closing = true }

    /** A client is being established: the teardown flag from any previous session no longer applies. */
    fun onClientConnected() = synchronized(lock) { closing = false }

    /**
     * Whether the reconnect poll may re-establish the client.
     *
     * False while suspended: the poll runs on `viewModelScope`, which outlives backgrounding, so
     * without this gate it would resurrect the client within 2 s of the user leaving the app.
     */
    fun mayReconnect(): Boolean = synchronized(lock) { !suspended }

    /** Whether the client is expected to be connected, for callers that need to assert convergence. */
    fun wantsClient(): Boolean = synchronized(lock) { foreground && !suspended }

    /**
     * All four facts, read under ONE lock acquisition.
     *
     * Each getter is individually atomic, but a caller that reads several of them in sequence is
     * comparing different moments and can conclude a state the machine never produced. This exists so a
     * cross-field invariant can be checked against one consistent view; the thread-safety test needs
     * exactly that, and reading the getters separately made it fail on a correct implementation.
     */
    internal fun snapshot(): State = synchronized(lock) {
        State(
            foreground = foreground,
            suspended = suspended,
            closing = closing,
            owesSuspend = deferringForProbe && !suspended,
        )
    }

    internal data class State(
        val foreground: Boolean,
        val suspended: Boolean,
        val closing: Boolean,
        val owesSuspend: Boolean,
    )
}
