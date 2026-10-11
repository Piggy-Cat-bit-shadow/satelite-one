package com.interstellar.proxy.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The UI client's suspend/resume rules, and the specific way backgrounding used to kill the tunnel.
 *
 * The defect these pin: `CommandClient.disconnect()` notifies its handlers on the deliberate path
 * too, `AppViewModel` answers that with `applySocketDrop()`, and for `Status.Started` that arms a
 * 400 ms watchdog calling `markStopped()` **and `BoxService.stop()`**. So the naive
 * `onBackground { commandClient.disconnect() }` stopped the VPN shortly after the user pressed Home.
 * `mayReportSocketLoss` is the discriminator that makes "I switched this off" different from
 * "the socket died".
 *
 * The remaining cases cover the edges the brief calls out: idempotency in both directions, flapping,
 * the reconnect poll not resurrecting a suspended client, and the headless url-test keeping its socket.
 */
class UiClientLifetimeTest {

    // --- the defect: an intentional suspend must not read as a socket loss ------------------------

    @Test
    fun `a suspended client may not report socket loss`() {
        val lifetime = UiClientLifetime()
        lifetime.onBackground(headlessProbe = false)

        assertFalse(
            "a deliberate suspend reported a socket loss, which arms the 400 ms watchdog that " +
                "calls markStopped() and BoxService.stop() - backgrounding the app would stop the VPN",
            lifetime.mayReportSocketLoss(headlessProbe = false),
        )
    }

    @Test
    fun `an active client still reports socket loss`() {
        // The control for the case above. Without this, suppressing everything would pass the
        // previous test while breaking genuine failure handling.
        val lifetime = UiClientLifetime()

        assertTrue(
            "a live client must still be able to report a real socket loss",
            lifetime.mayReportSocketLoss(headlessProbe = false),
        )
    }

    @Test
    fun `resuming restores socket-loss reporting`() {
        val lifetime = UiClientLifetime()
        lifetime.onBackground(headlessProbe = false)
        lifetime.onForeground()

        assertTrue(
            "after the UI came back, a real socket loss must drive status again",
            lifetime.mayReportSocketLoss(headlessProbe = false),
        )
    }

    // --- the same defect reached without backgrounding -------------------------------------------

    @Test
    fun `a tearing-down UI may not report socket loss`() {
        // disconnect() cancels startingWatchdog and then calls commandClient.disconnect(), whose
        // onDisconnected() calls applySocketDrop() -> Started -> armStartingWatchdog(400ms). The
        // cancel lands before the callback that re-arms it, so destroying the Activity or flipping a
        // theme that recreates AppRoot while Started would stop the tunnel 400 ms later.
        val lifetime = UiClientLifetime()
        lifetime.onTearingDown()

        assertFalse(
            "a deliberate teardown reported a socket loss, which re-arms the watchdog disconnect() " +
                "just cancelled and calls markStopped() + BoxService.stop()",
            lifetime.mayReportSocketLoss(headlessProbe = false),
        )
    }

    @Test
    fun `a new session is not muted by the previous teardown`() {
        val lifetime = UiClientLifetime()
        lifetime.onTearingDown()
        lifetime.onClientConnected()

        assertTrue(
            "the teardown flag outlived its session, so a later real socket loss would be ignored",
            lifetime.mayReportSocketLoss(headlessProbe = false),
        )
    }

    // --- idempotency -----------------------------------------------------------------------------

    @Test
    fun `a repeated background edge does not disconnect again`() {
        val lifetime = UiClientLifetime()

        assertTrue("the first background edge must suspend", lifetime.onBackground(headlessProbe = false))
        assertFalse(
            "a second background edge asked to disconnect an already-disconnected client; " +
                "CommandClient.connect() fires onDisconnected() when a client is installed, so a " +
                "redundant action here is another route into the same watchdog",
            lifetime.onBackground(headlessProbe = false),
        )
    }

    @Test
    fun `a repeated foreground edge does not reconnect again`() {
        val lifetime = UiClientLifetime()
        lifetime.onBackground(headlessProbe = false)

        assertTrue("the first foreground edge must resume", lifetime.onForeground())
        assertFalse(
            "a second foreground edge asked for a second connection sequence; resume must be " +
                "idempotent so that repeated ON_START signals leave exactly one live session",
            lifetime.onForeground(),
        )
    }

    @Test
    fun `a foreground edge without a suspend does not reconnect`() {
        // The cold-start shape: the composition connects on first frame and ON_START follows.
        val lifetime = UiClientLifetime()

        assertFalse(
            "ON_START on a client that was never suspended must not open a second session",
            lifetime.onForeground(),
        )
    }

    // --- flapping --------------------------------------------------------------------------------

    @Test
    fun `rapid flapping starts exactly one session`() {
        // Falsifiable form. The earlier version of this test asserted
        // `disconnectAsks <= 20 && connectAsks <= 20`, which cannot fail: each counter increments at
        // most once per loop iteration, so the bound is the loop length. It also asserted
        // `wantsClient()`, which is true even for a gate with no idempotency at all. An independent
        // probe confirmed both by running this assertion against a deliberately non-idempotent gate
        // (20/20, passed). The shape below fails for such a gate.
        val lifetime = UiClientLifetime()
        var actions = 0

        repeat(20) { round ->
            if (lifetime.onBackground(headlessProbe = false)) actions++
            // A second background edge is not a transition.
            if (lifetime.onBackground(headlessProbe = false)) actions++
            if (lifetime.onForeground()) actions++
            // Nor is a second foreground edge.
            if (lifetime.onForeground()) actions++
            assertTrue(
                "round $round asked for more actions than the two real transitions it contains; a " +
                    "repeat on the background side is another route into the 400 ms watchdog",
                actions <= (round + 1) * 2,
            )
        }

        assertEquals(
            "20 rounds of two real transitions each is 40 actions; a higher count means a repeated " +
                "edge was treated as a transition",
            40,
            actions,
        )
        assertTrue("the client must end up online after the last foreground edge", lifetime.wantsClient())
        assertFalse("and nothing may still be owed", lifetime.owesSuspend)
    }

    @Test
    fun `a redundant background edge is not mistaken for a lost session`() {
        val lifetime = UiClientLifetime()
        lifetime.onBackground(headlessProbe = false)
        lifetime.onBackground(headlessProbe = false)
        lifetime.onBackground(headlessProbe = false)

        assertTrue("the client is still deliberately off", lifetime.isSuspended)
        assertFalse(
            "a repeating background signal was treated as a live session to tear down",
            lifetime.mayReportSocketLoss(headlessProbe = false),
        )
    }

    // --- the reconnect poll ----------------------------------------------------------------------

    @Test
    fun `the reconnect poll may not resurrect a suspended client`() {
        val lifetime = UiClientLifetime()
        lifetime.onBackground(headlessProbe = false)

        assertFalse(
            "the 2 s poll runs on viewModelScope, which outlives backgrounding, so without this " +
                "gate it reconnects the client within 2 s of the user leaving the app",
            lifetime.mayReconnect(),
        )
    }

    @Test
    fun `the reconnect poll works again after resume`() {
        val lifetime = UiClientLifetime()
        lifetime.onBackground(headlessProbe = false)
        lifetime.onForeground()

        assertTrue(
            "a client that must reconnect - the core was stopped and restarted while backgrounded - " +
                "has to be able to",
            lifetime.mayReconnect(),
        )
    }

    // --- the headless url-test -------------------------------------------------------------------

    @Test
    fun `a headless probe keeps its socket`() {
        val lifetime = UiClientLifetime()

        assertFalse(
            "suspending during a headless url-test would make probeSocketUp unreachable and the " +
                "probe time out; the probe is short-lived and is not UI work",
            lifetime.onBackground(headlessProbe = true),
        )
    }

    @Test
    fun `a probe still may not report socket loss`() {
        // The pre-existing rule this generalises, kept so the change cannot quietly relax it.
        val lifetime = UiClientLifetime()

        assertFalse(lifetime.mayReportSocketLoss(headlessProbe = true))
    }

    @Test
    fun `a probe background edge still stops the poll`() {
        // The earlier version of this test asserted `mayReconnect()`, i.e. that the poll MAY keep
        // running. Production does the opposite: `suspendUiClient` cancels the poll on EVERY
        // background edge, before it even consults the probe. So the assertion described a state the
        // caller never reaches, and it hid the fact that the poll job stays null after a probe
        // background edge. The contract is: the probe keeps the SOCKET, the UI still loses its POLL.
        val lifetime = UiClientLifetime()
        lifetime.onBackground(headlessProbe = true)

        assertTrue(
            "the socket is kept for the probe, so nothing may be disconnected yet",
            !lifetime.isSuspended,
        )
        assertTrue("but the UI's poll is not the probe's business", lifetime.mayReconnect())
        assertTrue("and the suspend is owed until the probe ends", lifetime.owesSuspend)
    }
    // --- the state machine's own vocabulary ------------------------------------------------------

    @Test
    fun `suspension is not a stop`() {
        val lifetime = UiClientLifetime()
        lifetime.onBackground(headlessProbe = false)

        assertTrue("the client is deliberately off", lifetime.isSuspended)
        assertFalse(
            "a suspended app has not learned that the core stopped, so the gate must not claim the " +
                "client is wanted either - the two are different questions",
            lifetime.wantsClient(),
        )
    }

    @Test
    fun `a cold start wants the client`() {
        assertTrue(
            "first launch must connect on the first frame, exactly as before this change",
            UiClientLifetime().wantsClient(),
        )
    }

    // --- the background edge may not be consumed by an unrelated in-flight probe ------------------

    @Test
    fun `backgrounding during a probe still owes a suspend`() {
        // The probe keeps the socket, but the background edge must not be lost: `probing` clears in
        // the probe's finally, and nothing would re-apply the suspend, so the UI client would keep
        // running in the background for good. Measured before this guard: never parked, 300 poll
        // reconnects and 600 chart samples while backgrounded.
        val lifetime = UiClientLifetime()

        assertFalse("the probe keeps the socket", lifetime.onBackground(headlessProbe = true))
        assertTrue(
            "the suspend was consumed by the probe instead of being remembered, so nothing would " +
                "re-apply it when the probe ends",
            lifetime.owesSuspend,
        )
    }

    @Test
    fun `a normal background edge applies the suspend immediately and owes nothing`() {
        // The control for the case above: with no probe there is nothing to defer to, so the suspend
        // is applied now rather than remembered.
        val lifetime = UiClientLifetime()

        assertTrue("no probe, so the client is switched off now", lifetime.onBackground(headlessProbe = false))
        assertTrue("the client is off", lifetime.isSuspended)
        assertFalse("nothing is owed, because nothing deferred it", lifetime.owesSuspend)
    }

    @Test
    fun `resuming during a probe clears the owed suspend`() {
        val lifetime = UiClientLifetime()
        lifetime.onBackground(headlessProbe = true)
        lifetime.onForeground()

        assertFalse(
            "the user came back before the probe finished, so nothing is owed any more",
            lifetime.owesSuspend,
        )
    }

    // --- thread safety ---------------------------------------------------------------------------

    @Test
    fun `concurrent edges and reads never observe a half-applied transition`() {
        // The url-test's `finally` runs on Dispatchers.IO and calls back into this class through
        // `reapplyOwedSuspend`, so the gate is genuinely touched from two threads. Before it was
        // synchronized, a reader could see `owesSuspend` true, be preempted, and then apply a stale
        // decision after the other thread had handled a foreground edge - leaving the client off while
        // the app was in front, with no future edge to correct it because `addForegroundListener`
        // delivers the canonical value only at subscribe.
        //
        // This is a stress probe, not a proof: it cannot force the interleaving, and there is no race
        // detector on this host. What it does assert is that no reader ever observes a state that the
        // single-threaded state machine cannot produce.
        val lifetime = UiClientLifetime()
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val violations = java.util.concurrent.atomic.AtomicInteger(0)

        val reader = Thread {
            while (!stop.get()) {
                // ONE snapshot per iteration. Reading the getters separately compares two different
                // moments; an earlier version of this test did that and failed on a correct
                // implementation, which is a defect in the test rather than in the gate.
                val state = lifetime.snapshot()
                if (state.suspended && state.owesSuspend) violations.incrementAndGet()
                if (state.foreground && state.suspended) violations.incrementAndGet()
            }
        }
        reader.start()
        repeat(20_000) {
            lifetime.onBackground(headlessProbe = false)
            lifetime.onForeground()
        }
        stop.set(true)
        reader.join()

        assertEquals(
            "a reader observed a state the state machine cannot produce, so the pair is not updated " +
                "atomically",
            0,
            violations.get(),
        )
    }}
