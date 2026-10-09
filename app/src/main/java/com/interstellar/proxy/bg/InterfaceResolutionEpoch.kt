package com.interstellar.proxy.bg

/**
 * Which "what is the default interface?" answer is still the truth.
 *
 * ## The problem this exists for
 *
 * Resolving an interface is slow and fallible: `getLinkProperties` returns null and
 * `NetworkInterface.getByName` throws while an interface is still coming up. So the
 * monitor retries, with a pause between attempts. Retrying introduces the classic
 * stale-writer bug: a loop started for network *A* can finish after the device has moved
 * to network *B* and overwrite B's answer with A's.
 *
 * Round 6 tried to prevent that with an `epoch` counter bumped inside the resolve loop.
 * It was not enough, for two reasons that this class fixes by construction:
 *
 *  1. **The "no network" branch never bumped it.** `updateDefaultInterface("", -1, …)` for
 *     a lost network returned *before* `++epoch`, so a loop still running for the previous
 *     network saw a matching epoch and happily announced an interface the device had
 *     already left — after the loss had been reported.
 *  2. **It was advisory, not authoritative.** Each writer read `epoch` and compared it
 *     against a value it had captured itself, which is exactly the check-then-act shape
 *     that races. Here a writer must *win* through [beginAttempt], and [isWinner] is the
 *     single authority on whether its result may be published.
 *
 * ## Why it is synchronous and allocation-free
 *
 * Every method is a plain lock-and-compare. There is no dispatcher, no coroutine and no
 * Android type, so the cancellation rules can be tested exhaustively on the JVM rather
 * than inferred from a device run. `DefaultNetworkMonitor` supplies the asynchrony.
 *
 * All methods are safe from any thread.
 */
internal class InterfaceResolutionEpoch {

    private val lock = Any()
    private var current = 0L

    /**
     * Open a new attempt: every older attempt becomes a loser from this moment.
     *
     * Called for **every** event that changes the answer, *including* a lost network and
     * an explicit `null`. That inclusion is the whole point — omitting it was defect (1).
     */
    fun beginAttempt(): Long = synchronized(lock) { ++current }

    /**
     * May the attempt holding [token] publish its result?
     *
     * False once any newer event has been seen, whatever that event was. A loser must drop
     * its result silently: publishing it would announce an interface the device has left.
     */
    fun isWinner(token: Long): Boolean = synchronized(lock) { token == current }

    /** The id of the newest event, for diagnostics and tests. */
    val currentAttempt: Long get() = synchronized(lock) { current }
}
