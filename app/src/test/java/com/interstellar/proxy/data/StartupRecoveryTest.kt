package com.interstellar.proxy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupRecoveryTest {

    private class FakeStorage(
        var snapshot: ConfigStore.Snapshot,
        var raw: String?,
        var failRawCas: Boolean = false,
        var content: SubscriptionRepository.SubscriptionContent? =
            SubscriptionRepository.SubscriptionContent(),
    ) : StartupRecoveryStorage {
        var journal: String? = null
        var configRestores = 0
        var rawRestores = 0
        var contentRestores = 0
        var failContentCas = false
        var lastConfigContent: String? = null

        override fun readJournal(): String? = journal

        override fun writeJournal(text: String) {
            journal = text
        }

        override fun clearJournal() {
            journal = null
        }

        override fun snapshot(): ConfigStore.Snapshot = snapshot

        override fun readRawBody(subscriptionId: String): String? = raw

        override fun restoreRawBodyIfCurrent(
            subscriptionId: String,
            currentBody: String?,
            previousBody: String?,
        ): Boolean {
            if (failRawCas) return false
            if (raw != currentBody) return false
            raw = previousBody
            rawRestores++
            return true
        }

        override fun readSubscriptionContent(
            subscriptionId: String,
        ): SubscriptionRepository.SubscriptionContent? = content

        override fun restoreSubscriptionContentIfCurrent(
            subscriptionId: String,
            expected: SubscriptionRepository.SubscriptionContent,
            previous: SubscriptionRepository.SubscriptionContent,
        ): Boolean {
            if (failContentCas || content != expected) return false
            content = previous
            contentRestores++
            return true
        }

        override fun restoreConfigIfRevision(
            expectedRevision: Long,
            content: String,
            ownerSubscriptionId: String?,
        ): Long? {
            if (snapshot.meta.revision != expectedRevision) return null
            configRestores++
            lastConfigContent = content
            snapshot = ConfigStore.Snapshot(
                content = content,
                meta = snapshot.meta.copy(
                    revision = expectedRevision + 1,
                    ownerSubscriptionId = ownerSubscriptionId,
                ),
            )
            return expectedRevision + 1
        }
    }

    private fun snapshot(content: String = "old-config", revision: Long = 7, owner: String = "sub-a") =
        ConfigStore.Snapshot(content = content, meta = ConfigStore.Meta(owner, revision, "sha"))

    @Test
    fun `a failed start restores the same subscription's previous config and raw`() {
        val storage = FakeStorage(snapshot = snapshot(), raw = "new-raw")
        assertTrue(
            StartupRecovery.rememberBeforeOfflineRefresh(
                subscriptionId = "sub-a",
                previousRawBody = "old-raw",
                snapshot = snapshot(),
                storage = storage,
            ),
        )
        assertEquals("new-raw", storage.raw)

        val result = StartupRecovery.restore(candidateRevision = 7, candidateOwner = "sub-a", storage = storage)

        assertTrue(result is StartupRecovery.Result.Restored)
        assertEquals("old-config", storage.snapshot.content)
        assertEquals("old-raw", storage.raw)
        assertEquals(8L, (result as StartupRecovery.Result.Restored).revision)
        assertNull(storage.journal)
        assertEquals(1, storage.configRestores)
        assertEquals(1, storage.rawRestores)
    }

    @Test
    fun `another owner's config is never used as a fallback`() {
        val storage = FakeStorage(snapshot = snapshot(), raw = "new-raw")
        assertFalse(
            StartupRecovery.rememberBeforeOfflineRefresh(
                subscriptionId = "sub-a",
                previousRawBody = "old-raw",
                snapshot = snapshot(owner = "sub-b"),
                storage = storage,
            ),
        )
        assertNull(storage.journal)
        assertEquals(StartupRecovery.Result.Nothing, StartupRecovery.restore(7, "sub-a", storage))
    }

    @Test
    fun `a newer revision is superseded without writing`() {
        val storage = FakeStorage(snapshot = snapshot(revision = 8), raw = "new-raw")
        StartupRecovery.rememberBeforeOfflineRefresh("sub-a", "old-raw", snapshot(revision = 7), storage)

        val result = StartupRecovery.restore(candidateRevision = 7, candidateOwner = "sub-a", storage = storage)

        assertEquals(StartupRecovery.Result.Superseded, result)
        assertEquals("old-config", storage.snapshot.content)
        assertEquals("new-raw", storage.raw)
        assertEquals(0, storage.configRestores)
        assertEquals(0, storage.rawRestores)
        assertNull(storage.journal)
    }

    @Test
    fun `an unchanged raw body is not evidence of the candidate`() {
        val storage = FakeStorage(snapshot = snapshot(), raw = "old-raw")
        StartupRecovery.rememberBeforeOfflineRefresh("sub-a", "old-raw", snapshot(), storage)

        val result = StartupRecovery.restore(candidateRevision = 7, candidateOwner = "sub-a", storage = storage)

        assertEquals(StartupRecovery.Result.Nothing, result)
        assertEquals(0, storage.configRestores)
        assertNull(storage.journal)
    }

    @Test
    fun `a raw body that moved during recovery is never overwritten`() {
        val storage = FakeStorage(snapshot = snapshot(), raw = "new-raw", failRawCas = true)
        StartupRecovery.rememberBeforeOfflineRefresh("sub-a", "old-raw", snapshot(), storage)

        val result = StartupRecovery.restore(candidateRevision = 7, candidateOwner = "sub-a", storage = storage)

        assertEquals(StartupRecovery.Result.Superseded, result)
        // Config was restored, then put back when the raw CAS lost, so the candidate config and
        // candidate raw still describe the same generation.
        assertEquals(2, storage.configRestores)
        assertEquals("old-config", storage.lastConfigContent)
        assertEquals("new-raw", storage.raw)
        assertNull(storage.journal)
    }

    @Test
    fun `a second offline refresh keeps the oldest known good generation`() {
        val storage = FakeStorage(snapshot = snapshot(), raw = "candidate-one")
        StartupRecovery.rememberBeforeOfflineRefresh("sub-a", "old-raw", snapshot(), storage)
        val first = storage.journal

        StartupRecovery.rememberBeforeOfflineRefresh(
            subscriptionId = "sub-a",
            previousRawBody = "candidate-one",
            snapshot = snapshot(content = "candidate-one-config", revision = 8),
            storage = storage,
        )

        assertEquals(first, storage.journal)
    }

    @Test
    fun `a failed start restores a node-list candidate's previous nodes and format`() {
        val previous = SubscriptionRepository.SubscriptionContent(configFormat = "old")
        val candidate = SubscriptionRepository.SubscriptionContent(configFormat = "new")
        val storage = FakeStorage(snapshot = snapshot(), raw = null, content = previous)
        assertTrue(StartupRecovery.rememberBeforeOfflineRefresh("sub-a", null, snapshot(), storage))
        storage.content = candidate
        assertTrue(StartupRecovery.rememberCandidate("sub-a", storage))

        val result = StartupRecovery.restore(7, "sub-a", storage)

        assertEquals(StartupRecovery.Result.Restored(8), result)
        assertEquals(previous, storage.content)
        assertEquals(1, storage.configRestores)
        assertEquals(1, storage.contentRestores)
        assertEquals(0, storage.rawRestores)
        assertNull(storage.journal)
    }

    @Test
    fun `a newer node-list content is superseded without writing`() {
        val previous = SubscriptionRepository.SubscriptionContent(configFormat = "old")
        val candidate = SubscriptionRepository.SubscriptionContent(configFormat = "new")
        val storage = FakeStorage(snapshot = snapshot(), raw = null, content = previous)
        StartupRecovery.rememberBeforeOfflineRefresh("sub-a", null, snapshot(), storage)
        storage.content = candidate
        StartupRecovery.rememberCandidate("sub-a", storage)
        storage.content = SubscriptionRepository.SubscriptionContent(configFormat = "newer")

        val result = StartupRecovery.restore(7, "sub-a", storage)

        assertEquals(StartupRecovery.Result.Superseded, result)
        assertEquals(SubscriptionRepository.SubscriptionContent(configFormat = "newer"), storage.content)
        assertEquals(0, storage.configRestores)
        assertEquals(0, storage.contentRestores)
        assertNull(storage.journal)
    }

    @Test
    fun `a node-list content that moves during recovery is never overwritten`() {
        val previous = SubscriptionRepository.SubscriptionContent(configFormat = "old")
        val candidate = SubscriptionRepository.SubscriptionContent(configFormat = "new")
        val storage = FakeStorage(snapshot = snapshot(), raw = null, content = previous)
        StartupRecovery.rememberBeforeOfflineRefresh("sub-a", null, snapshot(), storage)
        storage.content = candidate
        StartupRecovery.rememberCandidate("sub-a", storage)
        storage.failContentCas = true

        val result = StartupRecovery.restore(7, "sub-a", storage)

        assertEquals(StartupRecovery.Result.Superseded, result)
        assertEquals(candidate, storage.content)
        assertEquals(2, storage.configRestores)
        assertEquals("old-config", storage.lastConfigContent)
        assertNull(storage.journal)
    }

    @Test
    fun `a node-list candidate that was never recorded is not guessed`() {
        val storage = FakeStorage(snapshot = snapshot(), raw = null)
        StartupRecovery.rememberBeforeOfflineRefresh("sub-a", null, snapshot(), storage)

        val result = StartupRecovery.restore(7, "sub-a", storage)

        assertEquals(StartupRecovery.Result.Nothing, result)
        assertEquals(0, storage.configRestores)
        assertEquals(0, storage.contentRestores)
        assertNull(storage.journal)
    }

    @Test
    fun `a discarded attempt cannot be replayed later`() {
        val storage = FakeStorage(snapshot = snapshot(), raw = "new-raw")
        StartupRecovery.rememberBeforeOfflineRefresh("sub-a", "old-raw", snapshot(), storage)
        assertTrue(storage.journal != null)
        StartupRecovery.discard(storage)
        assertNull(storage.journal)
        assertEquals(StartupRecovery.Result.Nothing, StartupRecovery.restore(7, "sub-a", storage))
    }

    @Test
    fun `a corrupt journal is ignored`() {
        val storage = FakeStorage(snapshot = snapshot(), raw = "new-raw")
        storage.journal = "not a properties file"

        assertEquals(StartupRecovery.Result.Nothing, StartupRecovery.restore(7, "sub-a", storage))
        assertEquals(0, storage.configRestores)
    }

    @Test
    fun `confirming a start only clears the matching subscription`() {
        val storage = FakeStorage(snapshot = snapshot(), raw = "new-raw")
        StartupRecovery.rememberBeforeOfflineRefresh("sub-a", "old-raw", snapshot(), storage)
        StartupRecovery.confirmStarted("sub-b", storage)
        assertTrue(storage.journal != null)
        StartupRecovery.confirmStarted("sub-a", storage)
        assertNull(storage.journal)
    }
}
