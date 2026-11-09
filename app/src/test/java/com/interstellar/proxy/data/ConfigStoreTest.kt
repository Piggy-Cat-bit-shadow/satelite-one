package com.interstellar.proxy.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The two store invariants the reload path relies on.
 *
 * 1. Bytes and revision are read as one snapshot. The Box used to read them separately, so a write
 *    between the reads could apply the old document and record the new revision.
 * 2. A failed attempt may only put the previous document back while its committed revision is still
 *    current. The check and the write are one compare-and-swap; checking first and writing after is
 *    what let B's late failure overwrite C.
 */
class ConfigStoreTest {

    private lateinit var root: java.io.File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("config-store-test").toFile()
        ConfigStore.useRootForTest(root)
    }

    @After
    fun tearDown() {
        ConfigStore.useRootForTest(null)
        root.deleteRecursively()
    }

    @Test
    fun `snapshot returns the bytes and the revision of those bytes`() {
        val first = ConfigStore.writeActiveConfig("A", "a")
        val snapshot = ConfigStore.snapshot()
        assertEquals("A", snapshot.content)
        assertEquals(first, snapshot.meta.revision)
        assertEquals("a", snapshot.meta.ownerSubscriptionId)

        val second = ConfigStore.writeActiveConfig("B", "b")
        val moved = ConfigStore.snapshot()
        assertEquals("B", moved.content)
        assertEquals(second, moved.meta.revision)
        assertEquals("b", moved.meta.ownerSubscriptionId)
        assertTrue(second > first)
    }

    @Test
    fun `write of identical bytes under the same owner does not advance the revision`() {
        val revision = ConfigStore.writeActiveConfig("A", "a")
        assertEquals(revision, ConfigStore.writeActiveConfig("A", "a"))
        assertEquals(revision, ConfigStore.writeActiveConfig("A"))
    }

    @Test
    fun `restoreIfRevision only writes while the failed revision is current`() {
        val candidate = ConfigStore.writeActiveConfig("B", "b")
        assertEquals(candidate + 1, ConfigStore.restoreIfRevision(candidate, "A", "a"))
        val restored = ConfigStore.snapshot()
        assertEquals("A", restored.content)
        assertEquals("a", restored.meta.ownerSubscriptionId)
        assertEquals(candidate + 1, restored.meta.revision)

        // A newer write has moved past the failed revision: this restore is stale and must not
        // touch the newer bytes.
        val c = ConfigStore.writeActiveConfig("C", "c")
        assertNull(ConfigStore.restoreIfRevision(candidate, "A", "a"))
        assertEquals("C", ConfigStore.snapshot().content)
        assertEquals(c, ConfigStore.snapshot().meta.revision)
    }

    /**
     * The property the old two-read path violated. A writer alternates two documents while a reader
     * snapshots; every snapshot must carry the revision of the bytes it returned.
     */
    @Test
    fun `a concurrent writer cannot split the bytes from the revision inside a snapshot`() {
        ConfigStore.writeActiveConfig("A", "a")
        val stop = AtomicBoolean(false)
        val writer = Thread {
            var i = 0
            while (!stop.get()) {
                if (i % 2 == 0) ConfigStore.writeActiveConfig("A", "a") else ConfigStore.writeActiveConfig("B", "b")
                i++
            }
        }
        writer.start()
        try {
            repeat(2_000) {
                val snapshot = ConfigStore.snapshot()
                val content = snapshot.content
                assertTrue("snapshot returned no content while the file exists", content != null)
                assertEquals(
                    "snapshot paired ${content?.take(1)} with revision ${snapshot.meta.revision}",
                    ConfigStore.sha256(content!!),
                    snapshot.meta.sha256,
                )
                assertEquals(
                    "owner does not belong to the bytes in the same snapshot",
                    if (content == "A") "a" else "b",
                    snapshot.meta.ownerSubscriptionId,
                )
            }
        } finally {
            stop.set(true)
            writer.join(5_000)
        }
    }
}
