package com.interstellar.proxy.bg

import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Foreground tracking feeds `setAppForeground`, which is a *fact* handed to the
 * core's shared policy — a wrong one is worse than none.
 *
 * The specific regression pinned here: the previous implementation returned early
 * on `isChangingConfigurations` **without decrementing**. Every rotation therefore
 * added a permanent +1, so after a few rotations `started` could never reach zero
 * again and the app reported "foreground" forever, even sitting in the background.
 */
class ActivityForegroundTrackerTest {

    private fun tracker(): Pair<ActivityForegroundTracker, MutableList<Boolean>> {
        val events = CopyOnWriteArrayList<Boolean>()
        return ActivityForegroundTracker { events += it } to events
    }

    @Test
    fun `no fabricated foreground before a real onStart`() {
        // A process can be started with no Activity at all (quick-settings tile,
        // background service). "We have not seen an onStart" is not evidence of being
        // in the foreground.
        val (_, events) = tracker()
        check(events.isEmpty()) { "nothing may be reported before an Activity starts: $events" }
    }

    @Test
    fun `the first start reports foreground exactly once`() {
        val (tracker, events) = tracker()
        tracker.onActivityStarted()
        tracker.onActivityStarted()
        check(events.toList() == listOf(true)) { "expected a single foreground report, got $events" }
        check(tracker.startedCount == 2)
    }

    @Test
    fun `repeated configuration changes do not inflate the count`() {
        val (tracker, events) = tracker()
        tracker.onActivityStarted()
        // A rotation or language switch is: the old Activity stops *for a
        // configuration change*, then its replacement starts. Three of those must
        // leave the count at 1 — the old implementation incremented without ever
        // decrementing and drifted to 4.
        repeat(3) {
            tracker.onActivityStopped(isChangingConfigurations = true)
            tracker.onActivityStarted()
        }
        check(tracker.startedCount == 1) {
            "the counter drifted to ${tracker.startedCount}; it must be balanced by construction"
        }
        check(events.none { !it }) { "a configuration change must not report a background edge: $events" }
    }

    @Test
    fun `a real background after rotations is still reported`() {
        val (tracker, events) = tracker()
        tracker.onActivityStarted()
        repeat(3) {
            tracker.onActivityStopped(isChangingConfigurations = true)
            tracker.onActivityStarted()
        }
        // The old implementation could never reach zero here, so this edge was lost.
        tracker.onActivityStopped(isChangingConfigurations = false)
        check(events.last() == false) { "background after rotations must be reported, got $events" }
        check(tracker.startedCount == 0)
    }

    @Test
    fun `foreground background foreground round trip`() {
        val (tracker, events) = tracker()
        tracker.onActivityStarted()
        tracker.onActivityStopped(isChangingConfigurations = false)
        tracker.onActivityStarted()
        check(events.toList() == listOf(true, false, true)) { "got $events" }
        check(tracker.startedCount == 1)
    }

    @Test
    fun `a second activity keeps the process in the foreground`() {
        val (tracker, events) = tracker()
        tracker.onActivityStarted() // A
        tracker.onActivityStarted() // B
        tracker.onActivityStopped(isChangingConfigurations = false) // A stops, B remains
        check(events.toList() == listOf(true)) { "the process is still visible, got $events" }
        check(tracker.startedCount == 1)
        tracker.onActivityStopped(isChangingConfigurations = false) // B stops
        check(events.last() == false)
    }

    @Test
    fun `the counter never underflows on an unbalanced stop`() {
        val (tracker, _) = tracker()
        tracker.onActivityStopped(isChangingConfigurations = false)
        tracker.onActivityStopped(isChangingConfigurations = false)
        check(tracker.startedCount == 0) { "count underflowed to ${tracker.startedCount}" }
    }

    @Test
    fun `a lone configuration-change stop does not claim background`() {
        val (tracker, events) = tracker()
        tracker.onActivityStarted()
        tracker.onActivityStopped(isChangingConfigurations = true)
        check(events.toList() == listOf(true)) {
            "a rotation must not look like the app going to the background: $events"
        }
        check(tracker.startedCount == 0)
    }
}
