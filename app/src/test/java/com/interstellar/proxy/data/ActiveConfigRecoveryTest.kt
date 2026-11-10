package com.interstellar.proxy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The failed-application recovery shared by the manual update and the periodic worker.
 *
 * The defect this binds: B commits revision 2, its reload is in flight, C commits revision 3, and
 * B's refusal lands afterwards. The old code unconditionally wrote A's document back, so the active
 * id, the config file and the revision all described A while C was the operation the user had just
 * performed. Recovery is a compare-and-swap now: only the operation that still owns its committed
 * revision may undo it.
 */
class ActiveConfigRecoveryTest {

    private class FakeStorage(
        var content: String?,
        var owner: String?,
        var revision: Long,
    ) : ActiveConfigStorage {

        val rawBodies = mutableMapOf<String, String?>()

        /** Runs after the snapshot has been read but before its result is handed back. */
        var afterSnapshotRead: (() -> Unit)? = null

        /** Runs at the start of the compare-and-swap, before the revision is compared. */
        var beforeRestore: (() -> Unit)? = null

        override fun snapshot(): ConfigStore.Snapshot {
            val snapshot = ConfigStore.Snapshot(
                content = content,
                meta = ConfigStore.Meta(ownerSubscriptionId = owner, revision = revision, sha256 = ""),
            )
            afterSnapshotRead?.invoke()
            return snapshot
        }

        override fun restoreIfRevision(
            expectedRevision: Long,
            content: String,
            ownerSubscriptionId: String?,
        ): Long? {
            beforeRestore?.invoke()
            if (revision != expectedRevision) return null
            this.content = content
            this.owner = ownerSubscriptionId
            revision += 1
            return revision
        }

        override fun readRawBody(subscriptionId: String): String? = rawBodies[subscriptionId]

        override fun restoreRawBodyIfCurrent(
            subscriptionId: String,
            currentBody: String?,
            previousBody: String?,
        ): Boolean {
            if (rawBodies[subscriptionId] != currentBody) return false
            rawBodies[subscriptionId] = previousBody
            return true
        }

        fun commit(content: String, owner: String, rawId: String? = null, rawBody: String? = null) {
            this.content = content
            this.owner = owner
            revision += 1
            if (rawId != null) rawBodies[rawId] = rawBody
        }
    }

    private fun candidate(
        committedRevision: Long = 2,
        committedOwner: String? = "b",
        previousConfig: String? = "A",
        previousOwner: String? = "a",
        rawOwnerId: String? = "b",
        committedRawBody: String? = "B-body",
        previousRawBody: String? = "A-body",
    ) = ActiveConfigRecovery.FailedCandidate(
        committedRevision = committedRevision,
        committedOwner = committedOwner,
        previousConfig = previousConfig,
        previousOwner = previousOwner,
        rawOwnerId = rawOwnerId,
        committedRawBody = committedRawBody,
        previousRawBody = previousRawBody,
    )

    @Test
    fun `the still-current failed candidate is restored together with its retained body`() {
        val store = FakeStorage(content = "B", owner = "b", revision = 2)
        store.rawBodies["b"] = "B-body"

        val result = ActiveConfigRecovery.restore(candidate(), store)

        assertEquals(ActiveConfigRecovery.Result.Restored(3, rawRestored = true), result)
        assertEquals("A", store.content)
        assertEquals("a", store.owner)
        assertEquals(3L, store.revision)
        assertEquals("A-body", store.rawBodies["b"])
    }

    /**
     * The review's barrier: B has already read its revision and is about to restore, C commits a
     * newer config in that window. The compare-and-swap must refuse B and leave C untouched.
     */
    @Test
    fun `a failure released after another commit cannot roll that commit back`() {
        val store = FakeStorage(content = "B", owner = "b", revision = 2)
        store.rawBodies["b"] = "B-body"
        val bRead = CountDownLatch(1)
        val cCommitted = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<ActiveConfigRecovery.Result?>()

        store.afterSnapshotRead = {
            bRead.countDown()
            // B now holds a snapshot of revision 2. C is allowed to commit before B's CAS.
            check(cCommitted.await(10, TimeUnit.SECONDS))
        }

        val thread = Thread {
            result.set(ActiveConfigRecovery.restore(candidate(), store))
        }
        thread.start()
        assertTrue(bRead.await(10, TimeUnit.SECONDS))
        store.commit(content = "C", owner = "c", rawId = "c", rawBody = "C-body")
        cCommitted.countDown()
        thread.join(10_000)
        assertFalse("recovery thread did not finish", thread.isAlive)

        assertEquals(ActiveConfigRecovery.Result.Superseded, result.get())
        assertEquals("C", store.content)
        assertEquals("c", store.owner)
        assertEquals(3L, store.revision)
        assertEquals("B-body", store.rawBodies["b"])
        assertEquals("C-body", store.rawBodies["c"])
    }

    /**
     * A newer write could land between the snapshot and the CAS even when the snapshot itself was
     * current. The CAS is the only thing that has to refuse it; the test blocks exactly there.
     */
    @Test
    fun `a write between the snapshot and the swap wins`() {
        val store = FakeStorage(content = "B", owner = "b", revision = 2)
        store.rawBodies["b"] = "B-body"
        store.beforeRestore = {
            store.beforeRestore = null
            store.commit(content = "C", owner = "c")
        }

        val result = ActiveConfigRecovery.restore(candidate(), store)

        assertEquals(ActiveConfigRecovery.Result.Superseded, result)
        assertEquals("C", store.content)
        assertEquals("c", store.owner)
        assertEquals(3L, store.revision) // the commit that landed before the swap owns the store
        assertEquals("B-body", store.rawBodies["b"])
    }

    @Test
    fun `a retained body already replaced by a newer update is not overwritten`() {
        val store = FakeStorage(content = "B", owner = "b", revision = 2)
        // Another update of the same subscription committed a newer body without regenerating the
        // config yet; the config CAS still owns revision 2, but the body is somebody else's.
        store.rawBodies["b"] = "C-body"

        val result = ActiveConfigRecovery.restore(candidate(), store)

        assertEquals(ActiveConfigRecovery.Result.Restored(3, rawRestored = false), result)
        assertEquals("A", store.content)
        assertEquals("C-body", store.rawBodies["b"])
    }

    @Test
    fun `without a previous document nothing is written`() {
        val store = FakeStorage(content = "B", owner = "b", revision = 2)
        store.rawBodies["b"] = "B-body"

        val result = ActiveConfigRecovery.restore(candidate(previousConfig = null), store)

        assertEquals(ActiveConfigRecovery.Result.NothingToRestore, result)
        assertEquals("B", store.content)
        assertEquals("b", store.owner)
        assertEquals(2L, store.revision)
        assertEquals("B-body", store.rawBodies["b"])
    }

    @Test
    fun `only the restored revision counts as the recovered instance, not whatever is current`() {
        // The worker restored revision 5 and waits. If another operation commits and applies
        // revision 7 meanwhile, "applied == on-disk current" would be true for C's instance; the
        // restored revision comparison is what keeps the old worker from refreshing C.
        assertTrue(ActiveConfigRecovery.servesRestoredInstance(restoredRevision = 5, appliedRevision = 5))
        assertFalse(ActiveConfigRecovery.servesRestoredInstance(restoredRevision = 5, appliedRevision = 7))
        assertFalse(ActiveConfigRecovery.servesRestoredInstance(restoredRevision = 5, appliedRevision = -1))
        assertFalse(ActiveConfigRecovery.servesRestoredInstance(restoredRevision = 5, appliedRevision = 0))
    }

    @Test
    fun `a different owner on the same revision is not this candidate`() {
        val store = FakeStorage(content = "B", owner = "someone-else", revision = 2)
        store.rawBodies["b"] = "B-body"

        val result = ActiveConfigRecovery.restore(candidate(), store)

        assertEquals(ActiveConfigRecovery.Result.Superseded, result)
        assertEquals("B", store.content)
        assertEquals("someone-else", store.owner)
    }
}
