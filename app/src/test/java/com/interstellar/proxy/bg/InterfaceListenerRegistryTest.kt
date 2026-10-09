package com.interstellar.proxy.bg

import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Which core owns the default-interface listener (round 6, P1).
 *
 * ## The failure being pinned
 *
 * `DefaultNetworkMonitor` held ONE `InterfaceUpdateListener` in a process-wide field and
 * answered libbox's two calls with `setListener(listener)` / `setListener(null)`. The
 * second call is a statement about the process, not about the caller, so when a stopping
 * core's teardown ran after a newer core had registered — headless `ProxyService` handing
 * over to `VPNService`, or the reverse — the older core's close cleared the newer core's
 * registration. The newer core then stopped hearing default-interface changes and kept
 * routing to a network that no longer existed.
 *
 * Nothing new is required from the native side: `closeDefaultInterfaceMonitor` already
 * receives the listener object, so that object's identity IS the ownership proof. This
 * file proves the registry keys on it, and that the interleavings which used to break
 * cannot.
 *
 * The registry is deliberately android-free, so all of this runs as a JVM test. The real
 * `ConnectivityManager` behaviour it sits under is emulator-verified separately — this
 * file makes no claim about it.
 */
class InterfaceListenerRegistryTest {

    /** Stands in for a gomobile listener; identity is its only meaningful property. */
    private open class Listener(private val name: String) {
        val updates = CopyOnWriteArrayList<String>()
        fun update(interfaceName: String) {
            updates += interfaceName
        }
        override fun toString(): String = name
    }

    /** Drives the registry the way `DefaultNetworkMonitor` does. */
    private class Monitor {
        val registry = InterfaceListenerRegistry<Listener>()
        var currentInterface: String? = null

        /**
         * `checkDefaultInterfaceUpdate`: take the target set snapshot FIRST, then call
         * out with no lock held.
         *
         * The ordering is the point. Production resolves the interface (which can pause
         * and retry) and only then fans out to the listeners it captured at entry, so a
         * listener closed while the interface was still coming up still receives that one
         * in-flight event and nothing after it. Reading the registry at call time instead
         * would silently drop the event for a listener that was live when it started.
         */
        fun onNetworkChanged(interfaceName: String) {
            val targets = registry.snapshot()
            currentInterface = interfaceName
            targets.forEach { it.update(interfaceName) }
        }

        /**
         * `DefaultNetworkMonitor.addListener`: register, then immediately tell the new
         * listener the interface we already know about.
         *
         * This immediate report is not an optimisation - it is what stops a core that
         * starts while the network is already up from waiting for the next change. The
         * model has to include it, otherwise it would be testing a monitor that never
         * seeds a late listener.
         */
        fun addListener(listener: Listener) {
            registry.add(listener)
            currentInterface?.let { listener.update(it) }
        }
    }

    // ---- N01 / N04: one close does not take another core's registration ----

    @Test
    fun `N01 closing A leaves B registered and still receiving updates`() {
        val m = Monitor()
        val a = Listener("A")
        val b = Listener("B")
        m.registry.add(a)
        m.registry.add(b)

        m.onNetworkChanged("wlan0")
        m.registry.remove(a)
        m.onNetworkChanged("rmnet0")

        // A heard the event that was already in flight when it closed, and nothing after.
        check(a.updates.toList() == listOf("wlan0")) { "got ${a.updates}" }
        check(b.updates.toList() == listOf("wlan0", "rmnet0")) {
            "B stopped receiving updates after A closed: ${b.updates}"
        }
        m.onNetworkChanged("wlan1")
        check(a.updates.toList() == listOf("wlan0")) {
            "a closed listener received a later update: ${a.updates}"
        }
        check(b.updates.last() == "wlan1") { "B stopped receiving updates: ${b.updates}" }
    }

    @Test
    fun `N02 closing A twice is idempotent and does not remove B`() {
        val m = Monitor()
        val a = Listener("A")
        val b = Listener("B")
        m.registry.add(a)
        m.registry.add(b)

        m.registry.remove(a)
        m.registry.remove(a)
        m.registry.remove(a)

        check(m.registry.size == 1) { "expected only B to remain, size=${m.registry.size}" }
        check(m.registry.contains(b)) { "B was removed by A's repeated close" }
        check(!m.registry.contains(a))
    }

    @Test
    fun `N03 a late callback for a closed listener never reaches it and never disturbs B`() {
        val m = Monitor()
        val a = Listener("A")
        val b = Listener("B")
        m.registry.add(a)
        m.registry.add(b)
        m.onNetworkChanged("wlan0")
        m.registry.remove(a)

        // A's delivery was already in flight when it closed; B must be unaffected either
        // way, and A must not come back into the live set.
        m.onNetworkChanged("rmnet0")

        check(b.updates.toList() == listOf("wlan0", "rmnet0")) { "got ${b.updates}" }
        check(a.updates.toList() == listOf("wlan0")) { "got ${a.updates}" }
        check(!m.registry.contains(a)) { "A came back into the live set" }
    }

    @Test
    fun `N04 B closes before A and each registration is released exactly once`() {
        val m = Monitor()
        val a = Listener("A")
        val b = Listener("B")
        m.registry.add(a)
        m.registry.add(b)

        m.registry.remove(b)
        check(m.registry.size == 1 && m.registry.contains(a)) { "A was lost when B closed" }
        m.registry.remove(a)
        check(m.registry.size == 0) { "the registry did not drain" }
    }

    @Test
    fun `N09 a headless-then-VPN handover never unsubscribes the live core`() {
        // The concrete production shape: ProxyService (headless url-test) has a core with
        // a listener; the user connects, so VPNService's core registers; the headless
        // core is then torn down and closes ITS listener. Before the fix, that close was
        // `setListener(null)` and it unsubscribed the VPN core as well.
        //
        // Note the expectations: while both are registered BOTH hear each event (that is
        // what the monitor does - it fans out to every live core), so the assertion that
        // matters is about what happens AFTER the headless close.
        val m = Monitor()
        val headless = Listener("headless")
        val vpn = Listener("vpn")

        m.addListener(headless)                      // ProxyService core starts
        m.onNetworkChanged("wlan0")                  // only the headless core hears it
        m.addListener(vpn)                           // VPNService core starts -> immediate report
        m.registry.remove(headless)                  // headless teardown closes its own
        val headlessCountAtClose = headless.updates.size
        m.onNetworkChanged("rmnet0")                 // Wi-Fi -> cellular

        check(m.registry.contains(vpn)) { "the live VPN core was unsubscribed" }
        check(vpn.updates.toList() == listOf("wlan0", "rmnet0")) {
            // "wlan0" is the immediate state report the newly registered listener gets
            // (the monitor already knows the current default network); "rmnet0" is the
            // live change. Both must reach the surviving core.
            "the live VPN core lost its default-interface tracking: ${vpn.updates}"
        }
        check(headless.updates.size == headlessCountAtClose) {
            "the closed headless listener kept receiving updates: ${headless.updates}"
        }
        check(vpn.updates.last() == "rmnet0") { "the live core missed the network switch" }
    }

    // ---- N05: a new listener is told the network we already know ----

    @Test
    fun `N05 a listener registering while a network is known is updated immediately`() {
        val m = Monitor()
        m.onNetworkChanged("wlan0")              // the monitor now knows the interface
        val late = Listener("late")
        m.addListener(late)                      // production seeds it on registration
        check(late.updates.toList() == listOf("wlan0")) {
            "a core that starts while the network is up must be told immediately: ${late.updates}"
        }
        // A later change reaches it too, exactly once.
        m.onNetworkChanged("rmnet0")
        check(late.updates.toList() == listOf("wlan0", "rmnet0")) { "got ${late.updates}" }
    }

    // ---- N06: no repeated native update for one unchanged network ----

    @Test
    fun `N06 ten unchanged network events produce exactly ten dispatches, not a hundred`() {
        // The old loop ran `for (times in 0 until 10)` and called updateDefaultInterface on
        // every iteration that got past its guards - so ONE event could produce up to ten
        // JNI crossings per listener. Each event must now fan out once.
        val m = Monitor()
        val a = Listener("A")
        m.registry.add(a)

        repeat(10) { m.onNetworkChanged("wlan0") }

        check(a.updates.size == 10) {
            "one event must produce exactly one update per listener, got ${a.updates.size}"
        }
        check(a.updates.all { it == "wlan0" }) { "got ${a.updates}" }
    }

    // ---- identity, not equality ----

    @Test
    fun `two listeners that compare equal are still distinct registrations`() {
        // Guards the explicit `===` scan: an implementation that overrode equals() the way
        // this one does would be able to remove its twin if the registry used `==`.
        class EqualListener(private val tag: String) {
            val updates = CopyOnWriteArrayList<String>()
            override fun equals(other: Any?): Boolean = other is EqualListener
            override fun hashCode(): Int = 1
            override fun toString(): String = tag
        }

        val registry = InterfaceListenerRegistry<EqualListener>()
        val a = EqualListener("A")
        val b = EqualListener("B")
        check(a == b) { "the fixture must actually compare equal" }
        registry.add(a)
        registry.add(b)
        check(registry.size == 2) { "an equal-but-distinct listener was deduplicated" }

        registry.remove(a)
        check(registry.size == 1 && registry.contains(b)) {
            "removing A also removed the equal listener B"
        }
    }

    @Test
    fun `registering the same listener twice does not double it`() {
        val registry = InterfaceListenerRegistry<Listener>()
        val a = Listener("A")
        registry.add(a)
        registry.add(a)
        check(registry.size == 1) { "expected 1 registration, got ${registry.size}" }
    }

    // ---- concurrency: snapshot dispatch ----

    @Test
    fun `a close racing a dispatch never deadlocks and never corrupts the registry`() {
        // Dispatch takes a snapshot and calls out with the lock released, so a listener's
        // callback re-entering the registry (as a native listener realistically can, by
        // starting or stopping another core) cannot deadlock against it.
        repeat(200) { i ->
            val m = Monitor()
            val a = Listener("A")
            val b = Listener("B")
            m.registry.add(a)
            m.registry.add(b)

            val go = CountDownLatch(1)
            val dispatcher = Thread {
                go.await(5, TimeUnit.SECONDS)
                m.onNetworkChanged("wlan0")
            }
            val closer = Thread {
                go.await(5, TimeUnit.SECONDS)
                m.registry.remove(a)
            }
            dispatcher.start(); closer.start()
            go.countDown()
            dispatcher.join(5_000); closer.join(5_000)

            check(!dispatcher.isAlive && !closer.isAlive) { "iteration $i deadlocked" }
            check(!m.registry.contains(a)) { "iteration $i: A remained registered" }
            check(m.registry.contains(b)) { "iteration $i: B was lost" }
            // B is either in the snapshot or not, but it must never receive a *wrong*
            // interface name and must never be removed.
            check(b.updates.all { it == "wlan0" }) { "iteration $i: ${b.updates}" }
        }
    }

    @Test
    fun `a listener that re-enters the registry from its callback does not deadlock`() {
        // The pathological but realistic case: the native callback synchronously causes
        // another core's listener to be registered or closed. Because dispatch holds no
        // lock while calling out, this must complete.
        class Reentrant(private val registry: InterfaceListenerRegistry<Listener>) : Listener("reentrant") {
            var fired = false
            val added = Listener("added")

            fun onDispatch() {
                if (fired) return
                fired = true
                registry.add(added)      // re-entrant mutation during dispatch
                registry.remove(this)    // ... and a re-entrant removal of itself
            }
        }

        val registry = InterfaceListenerRegistry<Listener>()
        val reentrant = Reentrant(registry)
        registry.add(reentrant)

        // Dispatch exactly as the monitor does: snapshot, then call out with no lock held.
        registry.snapshot().forEach { (it as? Reentrant)?.onDispatch() }

        check(reentrant.fired) { "the re-entrant path never ran" }
        check(registry.contains(reentrant.added)) { "the re-entrant registration was lost" }
        check(!registry.contains(reentrant)) { "the re-entrant removal did not take effect" }
    }
}
