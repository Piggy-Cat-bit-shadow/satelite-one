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

    val size: Int get() = synchronized(lock) { count }

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
        }
    }

    /** Oldest-to-newest copy. Does not modify internal state. */
    fun snapshot(): List<T> {
        synchronized(lock) {
            if (count == 0) return emptyList()
            val out = ArrayList<T>(count)
            for (i in 0 until count) {
                @Suppress("UNCHECKED_CAST")
                out.add(slots[(start + i) % capacity] as T)
            }
            return out
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
        }
    }
}
