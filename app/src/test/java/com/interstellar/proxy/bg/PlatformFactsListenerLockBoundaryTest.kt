package com.interstellar.proxy.bg

import org.junit.After
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The foreground listeners must run with `PlatformFacts.lock` released.
 *
 * # Why this is its own file, and what it does NOT assert
 *
 * Every other test in this package asks what a decision was. This one asks whether a callback runs
 * while a lock is held — a property of the notification path that no single fact can show. It is the
 * P0-B audit item.
 *
 * The defect is not "the lock is held for a long time". A listener is not a leaf: the UI's calls
 * `resumeUiClient()` / `suspendUiClient()`, which reach `connect()` / `disconnect()`, which call
 * [PlatformFacts.addForegroundListener] and [PlatformFacts.removeForegroundListener] back into this
 * object. Under the lock that nesting re-enters a reentrant JVM lock and the nested
 * `addForegroundListener` replays the canonical value *from inside the outer notification*, so one
 * foreground edge can produce a second one. That double delivery is what P0-A had to gate
 * `connect()` against, and `AppViewModelWiringTest` pins the gate from the other side.
 *
 * **Note what is deliberately not asserted here:** that the nested delivery never happens while the
 * outer listener's body is on the stack. It always does, and correctly so — the outer listener's last
 * statement *is* the subscription, so the replay is delivered inside the outer listener by
 * construction. An earlier version of this file asserted the opposite, failed against correct code,
 * and was wrong rather than the code. The reachable property is the lock, and that is what is measured.
 *
 * `AppViewModel` cannot be constructed in a plain JVM test here (AndroidViewModel, no Robolectric),
 * so the nesting is reproduced with plain listeners — which is the point: the property belongs to
 * `PlatformFacts`, not to its caller.
 */
class PlatformFactsListenerLockBoundaryTest {

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    private fun subscribe(listener: (Boolean) -> Unit) {
        listeners += listener
        PlatformFacts.addForegroundListener(listener)
    }

    @After
    fun tearDown() {
        listeners.forEach { PlatformFacts.removeForegroundListener(it) }
        listeners.clear()
        // Leave deterministic facts for the next test, the same as PlatformFactsTest does.
        PlatformFacts.onScreenChanged(true)
        PlatformFacts.onForegroundChanged(false)
    }

    /**
     * [PlatformFacts]'s own singleton instance, which is what its `synchronized` blocks lock on.
     *
     * `PlatformFacts` is a Kotlin `object`, so the compiler may place its fields on the class rather
     * than on a per-instance object, and a `synchronized(...)` inside a member function then locks the
     * INSTANCE (`object` = singleton), not the `Class`. Getting this wrong is not a near miss: the
     * first version of this probe looked up `lock` on `PlatformFacts::class.java`, read a static
     * monitor nothing ever locks, and reported a lock that was never held. The probe below
     * (`the lock probe distinguishes a held lock from a released one`) exists so that mistake cannot
     * come back silently, and the failure message names the instance so a wrong monitor is visible.
     */
    private val instance: Any by lazy {
        // `INSTANCE` first: it is what Kotlin emits for an `object`, and it is exact.
        runCatching { PlatformFacts::class.java.getDeclaredField("INSTANCE").get(null) }
            .getOrElse {
                // Fall back to the single static field whose type is the object's own class, which is
                // how a Kotlin `object` can also be represented.
                PlatformFacts::class.java.declaredFields.firstOrNull { field ->
                    java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                        field.type == PlatformFacts::class.java
                }?.let { field ->
                    field.isAccessible = true
                    field.get(null)
                } ?: throw AssertionError(
                    "could not reach the PlatformFacts singleton, so this test cannot measure the " +
                        "lock boundary it exists for",
                )
            }
    }

    /** The monitor a `synchronized` block in [PlatformFacts] actually acquires. */
    private val monitor: Any by lazy {
        try {
            val field = instance.javaClass.getDeclaredField("lock")
            field.isAccessible = true
            field.get(instance) ?: throw AssertionError("PlatformFacts.lock is null")
        } catch (e: NoSuchFieldException) {
            throw AssertionError(
                "PlatformFacts no longer declares a `lock` field, so this test cannot measure the " +
                    "lock boundary it exists for. The lock moved or was renamed.",
                e,
            )
        }
    }

    /** Whether the calling thread holds [PlatformFacts]'s monitor right now. */
    private fun holdsPlatformFactsLock(): Boolean = Thread.holdsLock(monitor)

    @Test
    fun `the lock probe distinguishes a held lock from a released one`() {
        // A boundary test whose probe cannot see the boundary is worse than no test, so the probe is
        // checked against both answers: released here, and held inside a synchronized block on the
        // very monitor it resolved.
        check(!holdsPlatformFactsLock()) {
            "the probe reports the lock as held outside any synchronized block, so it resolved the " +
                "wrong monitor and every assertion built on it is meaningless"
        }
        var heldInside = false
        synchronized(monitor) { heldInside = holdsPlatformFactsLock() }
        check(heldInside) {
            "the probe reports the lock as released while the calling thread is inside a " +
                "synchronized block on the monitor it resolved, so it cannot see the boundary it " +
                "exists to measure"
        }
    }

    @Test
    fun `a listener runs with the lock released`() {
        // The nesting, exactly as production performs it: a listener that subscribes. This is
        // `connect()` -> `subscribeForeground()`.
        //
        // The probe is deliberately taken on the FIRST delivery only, because the first delivery is
        // the notification and the second is the nested subscription's canonical replay - two
        // different code paths, and only one of them is the boundary under test. Measuring both made
        // this test fail against correct code: the nested replay happens inside the outer listener's
        // body by construction (the subscription IS that body's last statement), so a probe that ran
        // on it reported the still-open notification. The replay's own boundary is the second test.
        val heldDeliveries = CopyOnWriteArrayList<Boolean>()
        val outerDeliveries = CopyOnWriteArrayList<Boolean>()
        val nestedDeliveries = CopyOnWriteArrayList<Boolean>()

        // Pin the canonical state BEFORE subscribing, because the authoritative fact for this test is
        // "a foreground edge arrives", and a subscription replays whatever is current. Without this
        // the fixture inherits the previous test's tearDown state (`false`), the subscription's own
        // replay delivers that `false`, and `outerDeliveries` is `[false, true]` - the subscription
        // replay plus the notification. That is correct behaviour being read as a failure, and the
        // first version of this test did exactly that.
        PlatformFacts.onForegroundChanged(true)

        subscribe { foreground ->
            outerDeliveries += foreground
            if (holdsPlatformFactsLock()) heldDeliveries += foreground
            subscribe { nested -> nestedDeliveries += nested }
        }
        // The subscription's own canonical replay is not part of what this test asserts; the
        // notification is. Cleared so the assertion below is about the edge, not about the seed.
        outerDeliveries.clear()

        PlatformFacts.onForegroundChanged(true)

        check(heldDeliveries.isEmpty()) {
            "the foreground notification delivered ${heldDeliveries.toList()} to listeners while " +
                "PlatformFacts' own monitor was held. The JVM lock is reentrant, so a listener's " +
                "re-entry through addForegroundListener/removeForegroundListener succeeds anyway and " +
                "replays the canonical foreground value from inside the notification - one foreground " +
                "edge produces a second one, and whether it does depends on whether a client happened " +
                "to be subscribed at that moment. A listener is not a leaf: it calls " +
                "connect()/disconnect(), and the core side already bans this shape for the same reason."
        }
        // Both the notification and the nested replay must arrive, with the value that was recorded.
        // This is not a test for "do not deliver"; it is a test for WHAT is delivered, and WHEN.
        check(outerDeliveries == listOf(true)) {
            "the notified listener received ${outerDeliveries.toList()}, expected exactly the recorded " +
                "foreground fact"
        }
        check(nestedDeliveries.isNotEmpty() && nestedDeliveries.all { it }) {
            "the nested replay delivered ${nestedDeliveries.toList()}; the subscription made from " +
                "inside a listener must be fed the canonical fact"
        }
    }

    @Test
    fun `the canonical replay on subscribe also runs with the lock released`() {
        // The second lock boundary, and a different one: `connect()` calls this on every entry, and
        // the replay is a full listener invocation - the UI's runs `resumeUiClient()`/`suspendUiClient()`.
        var held = false
        subscribe { if (holdsPlatformFactsLock()) held = true }
        check(!held) {
            "the canonical foreground replay performed by addForegroundListener ran while " +
                "PlatformFacts' monitor was held. Every connect() performs this replay, so the whole " +
                "UI lifecycle decision would run inside this object's lock."
        }
    }

    @Test
    fun `a listener may mutate the listener list without failing the notification`() {
        // The copy exists for this, and it is not the same thing as releasing the lock: iterating the
        // live list would throw ConcurrentModificationException, while iterating a copy under the lock
        // still nests. The old comment ran the two together ("via a copy, so registering from inside a
        // callback is safe"), which is true and was never the problem.
        val before = CopyOnWriteArrayList<Boolean>()
        val after = CopyOnWriteArrayList<Boolean>()
        val failing: (Boolean) -> Unit = { before += it }

        subscribe { foreground ->
            PlatformFacts.removeForegroundListener(failing)
            subscribe { after += it }
            check(foreground) { "the fact delivered to a listener must be the fact that was recorded" }
        }
        subscribe(failing)

        PlatformFacts.onForegroundChanged(true)

        check(after.isNotEmpty()) { "the listener subscribed from inside the callback received nothing" }
    }
}
