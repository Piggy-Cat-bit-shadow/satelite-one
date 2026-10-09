package com.interstellar.proxy.core

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The log ring is the hot path for every kernel log line, and the reason it
 * replaced an ArrayDeque is eviction correctness — so the wrap-around order is
 * pinned here rather than trusted.
 */
class LogRingBufferTest {

    @Test
    fun `empty buffer reports zero and snapshots empty`() {
        val ring = LogRingBuffer<Int>(4)
        check(ring.size == 0)
        check(ring.snapshot().isEmpty())
    }

    @Test
    fun `single append is visible`() {
        val ring = LogRingBuffer<Int>(4)
        ring.add(7)
        check(ring.size == 1)
        check(ring.snapshot() == listOf(7))
    }

    @Test
    fun `filling to capacity keeps insertion order`() {
        val ring = LogRingBuffer<Int>(3)
        ring.add(1); ring.add(2); ring.add(3)
        check(ring.size == 3)
        check(ring.snapshot() == listOf(1, 2, 3))
    }

    @Test
    fun `overflow evicts the oldest and keeps order`() {
        val ring = LogRingBuffer<Int>(3)
        (1..5).forEach { ring.add(it) }
        check(ring.size == 3) { "size must stay capped, was ${ring.size}" }
        check(ring.snapshot() == listOf(3, 4, 5)) { "oldest must be evicted: ${ring.snapshot()}" }
    }

    @Test
    fun `wrap-around stays correct over many cycles`() {
        val ring = LogRingBuffer<Int>(4)
        (1..25).forEach { ring.add(it) }
        check(ring.size == 4)
        check(ring.snapshot() == listOf(22, 23, 24, 25))
    }

    @Test
    fun `size never exceeds capacity`() {
        val ring = LogRingBuffer<Int>(8)
        (1..1000).forEach { ring.add(it) }
        check(ring.size == 8)
    }

    @Test
    fun `clear empties the buffer`() {
        val ring = LogRingBuffer<Int>(4)
        (1..6).forEach { ring.add(it) }
        ring.clear()
        check(ring.size == 0)
        check(ring.snapshot().isEmpty())
    }

    @Test
    fun `append works again after clear`() {
        val ring = LogRingBuffer<Int>(3)
        (1..5).forEach { ring.add(it) }
        ring.clear()
        ring.add(42); ring.add(43)
        check(ring.snapshot() == listOf(42, 43))
    }

    @Test
    fun `snapshot does not mutate internal state`() {
        val ring = LogRingBuffer<Int>(3)
        ring.add(1); ring.add(2)
        val first = ring.snapshot()
        val second = ring.snapshot()
        check(first == second)
        check(ring.size == 2)
        ring.add(3)
        check(ring.snapshot() == listOf(1, 2, 3))
    }

    @Test
    fun `snapshot is a defensive copy`() {
        val ring = LogRingBuffer<Int>(3)
        ring.add(1)
        val snap = ring.snapshot()
        ring.add(2)
        check(snap == listOf(1)) { "an earlier snapshot must not observe later appends" }
    }

    @Test
    fun `capacity of one keeps only the newest`() {
        val ring = LogRingBuffer<Int>(1)
        ring.add(1); ring.add(2)
        check(ring.snapshot() == listOf(2))
    }

    /**
     * Not a proof of lock-freedom — the point is that concurrent writers plus a
     * concurrent reader never corrupt the window (size stays capped, every
     * retained value is one that was actually written, order is monotonic).
     */
    @Test
    fun `concurrent writes and snapshots stay consistent`() {
        val capacity = 256
        val writers = 4
        val perWriter = 5000
        val ring = LogRingBuffer<Int>(capacity)
        val pool = Executors.newFixedThreadPool(writers + 1)
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers)

        repeat(writers) { w ->
            pool.execute {
                start.await()
                repeat(perWriter) { i -> ring.add(w * perWriter + i) }
                done.countDown()
            }
        }
        val reader = pool.submit {
            start.await()
            var seen = 0
            while (done.count > 0) {
                val snap = ring.snapshot()
                check(snap.size <= capacity) { "snapshot exceeded capacity: ${snap.size}" }
                seen += snap.size
            }
            seen
        }

        start.countDown()
        check(done.await(60, TimeUnit.SECONDS)) { "writers did not finish" }
        reader.get(60, TimeUnit.SECONDS)
        pool.shutdownNow()

        check(ring.size == capacity) { "expected a full buffer, was ${ring.size}" }
        val finalSnap = ring.snapshot()
        check(finalSnap.size == capacity)
        // every retained value must exist in the written domain
        check(finalSnap.all { it in 0 until writers * perWriter })
    }

    @Test
    fun `random interleaving of add and clear never breaks invariants`() {
        val ring = LogRingBuffer<Int>(16)
        val rnd = Random(1234)
        repeat(2000) {
            when (rnd.nextInt(10)) {
                0 -> ring.clear()
                else -> ring.add(rnd.nextInt())
            }
            check(ring.size <= 16)
            check(ring.snapshot().size == ring.size) { "snapshot size must match size" }
        }
    }
}
