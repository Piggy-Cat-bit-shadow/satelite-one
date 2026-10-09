package com.interstellar.proxy.core

/**
 * Fixed-capacity ring buffer for retained log lines.
 *
 * Why this exists: the previous implementation was an `ArrayDeque` that published
 * `buffer.toList()` on **every single line**. With 3000 retained lines that is a
 * 3000-reference copy, a StateFlow publish and a Compose invalidation per log
 * line the kernel emits — pure heap churn and GC pressure on a hot path.
 *
 * This buffer keeps append O(1) and never allocates a list; the snapshot is
 * produced on demand by the UI side, which coalesces it (see `LogsViewModel`).
 *
 * Thread safety: a single monitor. Appends arrive from the libbox callback
 * thread and the AppLog collector coroutine while snapshots/clears come from the
 * UI side. A plain lock is used rather than a lock-free ring on purpose — with a
 * handful of writers a monitor is the cheapest thing that is *obviously* correct,
 * and correctness here matters more than shaving nanoseconds.
 */
class LogRingBuffer<T>(val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val lock = Any()
    private val slots = arrayOfNulls<Any?>(capacity)

    /** Index of the oldest retained element. */
    private var start = 0
    private var count = 0

    /**
     * Monotonic change counter, bumped by every mutation.
     *
     * This is the only signal that keeps changing once the buffer is full: `size`
     * stops moving at capacity, so anything that keys off length (as the Logs page
     * used to) silently stops working exactly when the buffer is saturated.
     */
    private var version = 0L

    val size: Int get() = synchronized(lock) { count }

    /** Current change version. */
    val currentVersion: Long get() = synchronized(lock) { version }

    /** Append, evicting the oldest element once [capacity] is reached. O(1). */
    fun add(item: T) {
        synchronized(lock) {
            if (count < capacity) {
                slots[(start + count) % capacity] = item
                count++
            } else {
                // Full: overwrite the oldest slot and advance the window.
                slots[start] = item
                start = (start + 1) % capacity
            }
            version++
        }
    }

    /** Oldest-to-newest copy. Does not modify internal state. */
    fun snapshot(): List<T> = snapshotWithVersion().first

    /**
     * The snapshot **and the version it corresponds to**, read under one lock.
     *
     * A caller must publish *this* version as its watermark rather than whatever
     * the version happens to be afterwards. An append that lands between the
     * snapshot and the watermark then leaves the buffer ahead of what was shown, so
     * the next pass still sees a newer version and the line is displayed — instead
     * of being silently marked as already displayed and lost forever.
     */
    fun snapshotWithVersion(): Pair<List<T>, Long> {
        synchronized(lock) {
            if (count == 0) return emptyList<T>() to version
            val out = ArrayList<T>(count)
            for (i in 0 until count) {
                @Suppress("UNCHECKED_CAST")
                out.add(slots[(start + i) % capacity] as T)
            }
            return out to version
        }
    }

    /**
     * Drop every retained element. Clearing the slots matters: leaving them set
     * would keep the old `LogLine`/`String` graphs reachable until they are
     * overwritten, which is exactly what "clear" is supposed to stop.
     */
    fun clear() {
        synchronized(lock) {
            java.util.Arrays.fill(slots, null)
            start = 0
            count = 0
            version++
        }
    }
}
