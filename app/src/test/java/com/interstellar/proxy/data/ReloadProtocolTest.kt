package com.interstellar.proxy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The process-local half of the reload handshake, and its documented limit.
 *
 * `CommandClient.serviceReload()` carries no request payload, so the service cannot be told which
 * caller it is serving. The client registers an intent per call, the service consumes the oldest
 * intent for the revision it read, and the receipt is kept per intent. That fixes lost receipts
 * (two same-revision reloads, a retry after a failed send) but cannot fix a swapped pair: if the
 * second call reaches the service first, it consumes the first caller's intent. These tests pin
 * both the improvement and the limitation so the latter is a protocol requirement, not a surprise.
 */
class ReloadProtocolTest {

    private fun receipt(requestId: Long, revision: Long = 7) = ReloadReceipt(
        requestId = requestId,
        success = true,
        error = null,
        configRevision = revision,
        engineGeneration = 3,
        atMillis = 1,
    )

    @Test
    fun `each request keeps its own receipt even after a later one is recorded`() {
        val book = ReloadReceiptBook(capacity = 4)
        book.record(receipt(1))
        book.record(receipt(2))
        assertEquals(1L, book.forRequest(1)?.requestId)
        assertEquals(2L, book.forRequest(2)?.requestId)
        assertEquals(2L, book.latest()?.requestId)
        assertNull(book.forRequest(99))
    }

    @Test
    fun `the receipt book is bounded`() {
        val book = ReloadReceiptBook(capacity = 2)
        book.record(receipt(1))
        book.record(receipt(2))
        book.record(receipt(3))
        assertNull(book.forRequest(1))
        assertNotNull(book.forRequest(2))
        assertNotNull(book.forRequest(3))
    }

    @Test
    fun `two same-revision intents are consumed oldest first`() {
        val queue = ReloadIntentQueue(clock = { 0L })
        val first = queue.register(7)
        val second = queue.register(7)
        // The service consumes in registration order whatever order the RPCs arrived in. If the
        // second call arrives first, it consumes `first` and the receipts are swapped.
        assertEquals(first.id, queue.consume(7))
        assertEquals(second.id, queue.consume(7))
        assertNull(queue.consume(7))
    }

    @Test
    fun `a failed send leaves its intent for a retry to consume`() {
        val queue = ReloadIntentQueue(clock = { 0L })
        val first = queue.register(7)
        // The first RPC throws before the service reads an intent: `first` stays queued.
        assertEquals(1, queue.pending())
        val second = queue.register(7)
        // A retry reaches the service and consumes the FIRST intent, not its own.
        assertEquals(first.id, queue.consume(7))
        // The second caller has no receipt of its own; classify() must say unconfirmed.
        val book = ReloadReceiptBook()
        book.record(receipt(first.id))
        assertNull(book.forRequest(second.id))
        // The first caller's receipt is real (the retry applied the same revision), so its own
        // classification may be APPLIED even though its first RPC threw.
        assertEquals(
            ActiveConfigApply.Verdict.APPLIED,
            ActiveConfigApply.classify(
                request = ReloadRequest(first.id, 7, 2),
                rpcSucceeded = false,
                receipt = book.forRequest(first.id),
                currentRevision = 7,
                currentGeneration = 3,
                appliedRevision = 7,
            ),
        )
        assertEquals(
            ActiveConfigApply.Verdict.UNCONFIRMED,
            ActiveConfigApply.classify(
                request = ReloadRequest(second.id, 7, 2),
                rpcSucceeded = true,
                receipt = book.forRequest(second.id),
                currentRevision = 7,
                currentGeneration = 3,
                appliedRevision = 7,
            ),
        )
    }

    @Test
    fun `an intent older than the window is not matched by a later reload`() {
        var now = 1_000L
        val queue = ReloadIntentQueue(expiryMillis = 60_000, clock = { now })
        queue.register(7)
        now += 61_000
        assertNull(queue.consume(7))
    }
}
