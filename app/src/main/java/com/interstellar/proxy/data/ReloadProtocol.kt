package com.interstellar.proxy.data

/**
 * A reload the client is about to ask for, registered before the RPC is sent.
 *
 * The wire call (`ReloadService`) carries no payload, so the process-local intent is the only way
 * to recognise which caller a receipt belongs to.
 */
data class ReloadIntent(val id: Long, val revision: Long, val atMillis: Long)

/**
 * The pending reload intents, in registration order.
 *
 * [consume] deliberately takes the OLDEST intent for a revision. The RPC has no request payload,
 * so when two callers reload the same revision the service cannot tell which call it is serving;
 * it consumes whichever intent was registered first. If the second call reaches the service first,
 * it consumes the first caller's intent and the two callers' receipts are swapped. That limitation
 * is captured by [ReloadProtocolTest] and is the reason an unmatched receipt is reported as
 * unconfirmed rather than applied or superseded; a core-side request token is the real fix.
 */
class ReloadIntentQueue(
    private val expiryMillis: Long = 60_000,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val intents = ArrayDeque<ReloadIntent>()
    private var sequence = 0L

    @Synchronized
    fun register(revision: Long): ReloadIntent {
        val now = clock()
        prune(now)
        val intent = ReloadIntent(++sequence, revision, now)
        intents.addLast(intent)
        return intent
    }

    /** The id of the oldest pending intent for [revision], or null when there is none. */
    @Synchronized
    fun consume(revision: Long): Long? {
        prune(clock())
        val iterator = intents.iterator()
        while (iterator.hasNext()) {
            val intent = iterator.next()
            if (intent.revision == revision) {
                iterator.remove()
                return intent.id
            }
        }
        return null
    }

    @Synchronized
    fun pending(): Int = intents.size

    private fun prune(now: Long) {
        while (intents.isNotEmpty() && now - intents.first().atMillis > expiryMillis) {
            intents.removeFirst()
        }
    }
}

/**
 * The receipts the Box recorded, addressed by the request intent that caused each reload.
 *
 * Keeping only the latest receipt loses an earlier caller's evidence the moment any other reload
 * finishes: two same-revision reloads arriving out of order, or a retry after a failed send, would
 * then be indistinguishable from "my request never reached the Box". A bounded book keeps each
 * request's own receipt available (and evicts the oldest, so a long session cannot grow it without
 * bound).
 */
class ReloadReceiptBook(private val capacity: Int = 32) {
    private val entries = LinkedHashMap<Long, ReloadReceipt>()

    @Synchronized
    fun record(receipt: ReloadReceipt) {
        entries[receipt.requestId] = receipt
        while (entries.size > capacity) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    @Synchronized
    fun forRequest(requestId: Long): ReloadReceipt? = entries[requestId]

    @Synchronized
    fun latest(): ReloadReceipt? = entries.values.lastOrNull()

    @Synchronized
    fun clear() = entries.clear()
}
