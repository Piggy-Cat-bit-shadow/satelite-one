package com.interstellar.proxy.bg

import org.junit.After
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
     * The delivery order must cover the FIRST fact pair, and it did not.
     *
     * `foregroundDelivered` used to start at `0L` while `awaitForegroundTurn` waited for
     * `foregroundDelivered < ticket - 1`. Ticket `0` needs `-1` and so never waited (correct), but
     * ticket `1` needed `0` - a value already present before anything had been delivered - so it ran
     * its listener while ticket `0`'s listener was still on the stack. Two facts recorded `true`
     * then `false` therefore reached the listener as `false` then `true`: the last value it acted on
     * was one the object had already superseded, which is exactly the hazard the ordering exists to
     * remove, and it was reachable on the very first pair after every process start.
     *
     * # Why the state is forced, and why it is forced AFTER subscribing
     *
     * `PlatformFacts` is a singleton, so the off-by-one only exists while the watermark still holds
     * its initial value; once any other test has recorded a fact the same probe passes against the
     * broken code. Rewinding the two private fields reproduces the pre-delivery state wherever this
     * runs, so the check means the same thing regardless of test order.
     *
     * The rewind has to happen *after* `addForegroundListener`, which records a foreground fact of
     * its own by replaying the canonical value. Doing it before subscription does not merely weaken
     * the test, it silently voids it: the replay advances the watermark to `0`, which is exactly the
     * value the old code initialised it to, so the broken build passes. That mistake was made and
     * measured here, which is why the order is called out rather than left implicit.
     *
     * Measured against the shipped code: the second `onForegroundChanged` returns after ~0 ms and the
     * ordering assertion fails. After the fix it waits for its predecessor (~350 ms).
     */
    @Test
    fun `the delivery order covers the first fact pair`() {
        val sequenceField = PlatformFacts::class.java.getDeclaredField("foregroundSequence")
        val deliveredField = PlatformFacts::class.java.getDeclaredField("foregroundDelivered")
        sequenceField.isAccessible = true
        deliveredField.isAccessible = true

        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val inFirstNotification = CountDownLatch(1)
        val stallMs = 400L
        val order = CopyOnWriteArrayList<Boolean>()

        val listener: (Boolean) -> Unit = { value ->
            order += value
            // Invocation 1 is the canonical replay that addForegroundListener always performs; the
            // notification under test is invocation 2.
            if (calls.incrementAndGet() == 2) {
                inFirstNotification.countDown()
                Thread.sleep(stallMs)
            }
        }
        PlatformFacts.addForegroundListener(listener)
        try {
            // A process that has recorded nothing yet: ticket 0 is next and nothing is delivered.
            // This is the state every cold start passes through, and the state the off-by-one got
            // wrong. Set here, after the subscription's own replay has already taken its ticket.
            sequenceField.setLong(PlatformFacts, 0L)
            deliveredField.setLong(PlatformFacts, -1L)

            val first = Thread { PlatformFacts.onForegroundChanged(true) }
            first.start()
            check(inFirstNotification.await(5, TimeUnit.SECONDS)) {
                "the first recorded fact never reached its listener, so this test cannot measure the " +
                    "ordering it exists for"
            }
            Thread.sleep(50)

            val started = System.currentTimeMillis()
            PlatformFacts.onForegroundChanged(false)
            val elapsed = System.currentTimeMillis() - started
            first.join()

            check(elapsed >= stallMs - 150) {
                "two facts recorded in order (true then false) were not delivered in that order: the " +
                    "second notification returned after only ${elapsed}ms while the first was still " +
                    "inside its listener (${stallMs}ms of work). The listener's LAST value is then one " +
                    "the object has already superseded, and the UI's listener turns that into " +
                    "resumeUiClient() - connecting a client while the canonical fact is 'background'."
            }
            // The fact that was recorded last must be the value the listener was handed last.
            check(order.isNotEmpty() && order.last() == false) {
                "the listener acted last on ${order.last()} although the last recorded fact was false; " +
                    "delivery order = $order"
            }
        } finally {
            PlatformFacts.removeForegroundListener(listener)
        }
    }

    /**
     * The watermark's declared initial value, checked at the source.
     *
     * The behavioural test above cannot reach this, and that is not an oversight to paper over: the
     * defect is an *initial value*, and any test that runs after the object has handled a single fact
     * observes a watermark that has already been written. Rewinding the field does not help either —
     * both the broken and the fixed predicate wait once the watermark is `-1`, so the two are
     * indistinguishable from then on. Only the declaration discriminates, so the declaration is what
     * is asserted.
     *
     * `0L` restores the shipped defect exactly: ticket 1 then compares its predecessor `0` against a
     * watermark already holding `0`, concludes "delivered", and runs its listener while ticket 0's is
     * still on the stack.
     */
    @Test
    fun `the delivery watermark is initialised below the first ticket`() {
        val declared = Regex("private var foregroundDelivered = (-?\\d+)L")
            .find(File("src/main/java/com/interstellar/proxy/bg/PlatformFacts.kt").readText())
            ?.groupValues?.get(1)?.toLong()
        check(declared == -1L) {
            "foregroundDelivered is declared as $declared, not -1. Tickets start at 0, so the first " +
                "fact's predecessor is -1; a watermark of 0 says that predecessor has already been " +
                "delivered, and the second recorded fact can then reach the listeners before the " +
                "first has finished - a listener acting on a superseded value on the first pair of " +
                "facts after every process start."
        }
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
    fun `a notification is complete when it returns, so no listener acts on a superseded fact`() {
        // The ordering guarantee, in the form that is actually guaranteed: when `onForegroundChanged`
        // returns, every listener has been handed that fact. So by the time a later fact is recorded,
        // the earlier one has already been delivered - which is what stops the UI's listener from
        // acting on a superseded value.
        //
        // # Why this is the testable half
        //
        // Releasing the lock gave up the serialisation the lock provided for free, and an adversarial
        // pass reproduced the consequence on the pre-fix code: a listener was handed `[false, true]`
        // for facts recorded `true` then `false`, so the last value it acted on was one the object had
        // already superseded. A stale `true` delivered last calls `resumeUiClient()`, which connects a
        // client while the canonical fact is "the app is in the background", and nothing later
        // corrects it - `mayReconnect()` gates new connects and never disconnects an existing one.
        //
        // A truly concurrent two-thread ordering test was attempted and abandoned: `PlatformFacts` is
        // a singleton shared by every test method in this JVM (Gradle forks once per module), so a
        // listener left registered by another test receives this test's facts and the fixture measures
        // more than its own window. Rather than write a test whose result depends on which other
        // methods ran, this asserts the contract that holds on every path: the dispatch is not
        // deferred past the recording call.
        val handed = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val listener: (Boolean) -> Unit = { handed.set(it) }
        PlatformFacts.addForegroundListener(listener)
        try {
            PlatformFacts.onForegroundChanged(true)
            check(handed.get() == true) {
                "`onForegroundChanged(true)` returned with the listener never handed `true` " +
                    "(it holds ${handed.get()}). A deferred dispatch lets a later fact overtake this " +
                    "one, which is how a listener ends up acting on a superseded value."
            }
            PlatformFacts.onForegroundChanged(false)
            check(handed.get() == false) {
                "`onForegroundChanged(false)` returned with the listener still holding " +
                    "${handed.get()}. The listener's last value must be the fact that was recorded " +
                    "last, or the app reconnects a UI client it was told to switch off."
            }
            check(PlatformFacts.isAppForeground == handed.get()) {
                "the listener was last handed ${handed.get()} while the object reports " +
                    "isAppForeground=${PlatformFacts.isAppForeground}: the delivery and the recorded " +
                    "fact disagree, which is the whole defect - a listener acting on a value the " +
                    "object has already superseded."
            }
        } finally {
            PlatformFacts.removeForegroundListener(listener)
        }
    }

    /**
     * A listener that records the same fact must not wait for itself, and must not strand the next one.
     *
     * Two properties, and both are about the same mechanism. `addForegroundListener` is reached from
     * `connect()`, a subscriber's replay handler IS a listener, and the UI's listener is that
     * subscriber - so a notification can arrive from inside a notification, on the same thread, with
     * the outer one still on the stack.
     *
     *  1. The reentrant dispatch skips [awaitForegroundTurn] and delivers depth-first. Waiting would
     *     be a self-deadlock: the predecessor ticket belongs to the dispatch this thread is already
     *     inside, so the turn it waits for can only be granted by the frame it is blocking.
     *  2. The OUTER dispatch must not then undo what the nested one published. The watermark is the
     *     highest delivered ticket, and the nested ticket is strictly higher than the outer one, so
     *     the outer's own publication has to be monotonic. A plain assignment moved it backwards and
     *     left the NEXT notification waiting forever for a turn that had already been delivered -
     *     measured: ticket 0 published 0, the nested ticket 1 had already published 0, the outer
     *     re-published 0, and ticket 2 spun in `awaitForegroundTurn` waiting for 1.
     *
     * Property 2 is asserted by recording one more fact after the reentrant one and requiring that it
     * arrives. That is the defect's real symptom: not a wrong value, but a notification that never
     * comes, which in the app is a UI client that silently stops updating.
     *
     * # Why the record happens on the test's own thread
     *
     * Deliberately NOT a background thread joined with a timeout. That shape was tried first and had
     * to be abandoned: when it detected a regression it threw while the recorded thread was still
     * spinning in `awaitForegroundTurn`, and `tearDown` - which records a fact of its own - then took
     * a later ticket and waited for the abandoned one, hanging the whole class instead of reporting
     * one failure. `Thread.yield()` cannot be interrupted and the watermark is global, so there is no
     * safe way to abandon a spinning thread. Keeping the nesting on the test thread means no such
     * thread can exist.
     *
     * The cost is stated plainly: a regression shows up as a hang (caught by the build timeout) rather
     * than a clean assertion failure. Everything asserted here is deterministic on correct code, so
     * this test cannot flake.
     *
     * # What an earlier version got wrong
     *
     * It compared the whole recorded-order list against an exact expectation and read absolute ticket
     * numbers out of the two private counters. Two things defeated that: subscribing replays the
     * canonical value, so the listener records one entry before the notification under test even
     * starts; and `PlatformFacts` is a singleton shared with every other test in this JVM, so tickets
     * are not at known values. The assertions here are about the sequence of events within this
     * listener and about the watermark relative to the ticket this test captured, never about
     * absolute counter values.
     */
    @Test
    fun `a listener may record the same fact without deadlocking on its own turn`() {
        val order = CopyOnWriteArrayList<String>()
        val lastDelivered = java.util.concurrent.atomic.AtomicBoolean(false)

        val fields = PlatformFacts::class.java
        fun watermark(): Long =
            fields.getDeclaredField("foregroundDelivered").apply { isAccessible = true }
                .getLong(PlatformFacts)
        fun sequence(): Long =
            fields.getDeclaredField("foregroundSequence").apply { isAccessible = true }
                .getLong(PlatformFacts)

        val listener: (Boolean) -> Unit = { value ->
            if (value) {
                order += "outer-start"
                val outerTicket = sequence() - 1
                // Record the same fact from inside a listener: this is `connect()` ->
                // `addForegroundListener()`, and the replay it performs, in miniature. Recording is
                // synchronous, so this returns only if the reentrant dispatch did not wait for the
                // frame below it on the stack - and if it did wait, this thread spins forever.
                PlatformFacts.onForegroundChanged(false)
                order += "outer-end"
                // The next notification takes ticket outerTicket + 1 and waits for `outerTicket`.
                // Publishing it here is what keeps that notification from waiting forever on a turn
                // that has already been delivered.
                val published = watermark()
                check(published >= outerTicket) {
                    "after the nested record returned, the outer dispatch had published " +
                        "foregroundDelivered=$published but its own ticket was $outerTicket; the next " +
                        "notification would wait forever for a turn that has already been delivered"
                }
            } else {
                order += "nested"
                lastDelivered.set(true)
            }
        }
        PlatformFacts.addForegroundListener(listener)
        // Subscribing replays the canonical value, which the listener above records as a "nested"
        // entry because that value is currently false. Only the entries added by the record below are
        // the notification under test, so the seeded replay is measured off rather than asserted
        // over - an earlier version compared the whole list and failed on its own fixture.
        val seeded = order.size
        try {
            PlatformFacts.onForegroundChanged(true)

            // Depth-first: the nested fact is delivered while the outer listener is still running.
            // This is the property that distinguishes reentrant delivery from a deferral, and the
            // deferral is exactly what would deadlock.
            check(order.drop(seeded) == listOf("outer-start", "nested", "outer-end")) {
                "the reentrant notification was not delivered depth-first; observed " +
                    "${order.drop(seeded)} (the subscribe-time replay contributes the first $seeded " +
                    "entries). A reentrant dispatch that defers its own delivery past the listener " +
                    "that recorded it is the self-deadlock this path exists to avoid."
            }
            // Property 2. This is the assertion that catches a non-monotonic publish: with the
            // watermark moved backwards, this record spins on `Thread.yield()` in
            // `awaitForegroundTurn` and never reaches the line below.
            lastDelivered.set(false)
            PlatformFacts.onForegroundChanged(false)
            check(lastDelivered.get()) {
                "the notification recorded AFTER the reentrant one never reached its listener. That is " +
                    "the watermark having moved backwards: the nested dispatch published " +
                    "${order.size} ticket(s) into the future and the outer dispatch then re-published " +
                    "its own, lower ticket, so this notification waits for a turn that has already " +
                    "been delivered. In the app the symptom is a UI client that stops updating."
            }
        } finally {
            PlatformFacts.removeForegroundListener(listener)
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
