package com.interstellar.proxy.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The durable record that decides whether a retained raw body may be applied.
 *
 * A raw is trustworthy only when a record says VERIFIED and names exactly the bytes on disk. This
 * store survives a process restart; a corrupt or missing file means "nothing is verified", never
 * "verified".
 */
class RawVerificationStoreTest {
    private fun tempFile(): File =
        File.createTempFile("raw-verification-", ".json").apply {
            delete()
        }

    private fun reset() {
        RawVerificationStore.useFileForTest(null)
    }

    @Test
    fun `pending bytes are not usable until the metadata save verifies them`() {
        val file = tempFile()
        RawVerificationStore.useFileForTest(file)
        try {
            val sha = "a".repeat(64)
            RawVerificationStore.markPending("sub-1", sha, "singbox")
            assertEquals(RawVerificationStore.STATE_PENDING, RawVerificationStore.record("sub-1")?.state)
            assertFalse(
                "a commit whose metadata has not been saved must not apply",
                RawVerificationStore.isVerified("sub-1", sha),
            )

            assertTrue(RawVerificationStore.markVerifiedIfCurrent("sub-1", sha, "singbox"))
            assertTrue(RawVerificationStore.isVerified("sub-1", sha))
        } finally {
            reset()
            file.delete()
        }
    }

    @Test
    fun `a verified record only matches the digest it names`() {
        val file = tempFile()
        RawVerificationStore.useFileForTest(file)
        try {
            val sha = "b".repeat(64)
            RawVerificationStore.markVerified("sub-1", sha, "singbox")
            assertTrue(RawVerificationStore.isVerified("sub-1", sha))
            assertFalse(
                "different bytes are a different document",
                RawVerificationStore.isVerified("sub-1", "c".repeat(64)),
            )
            assertFalse(
                "another subscription's record does not apply",
                RawVerificationStore.isVerified("sub-2", sha),
            )
        } finally {
            reset()
            file.delete()
        }
    }

    @Test
    fun `a verification for bytes another commit replaced is refused`() {
        val file = tempFile()
        RawVerificationStore.useFileForTest(file)
        try {
            RawVerificationStore.markPending("sub-1", "d".repeat(64), "singbox")
            assertFalse(
                "a stale metadata save must not bless bytes it did not write",
                RawVerificationStore.markVerifiedIfCurrent("sub-1", "e".repeat(64), "singbox"),
            )
            assertFalse(RawVerificationStore.isVerified("sub-1", "e".repeat(64)))
        } finally {
            reset()
            file.delete()
        }
    }

    @Test
    fun `an unverified tombstone survives a restart`() {
        val file = tempFile()
        RawVerificationStore.useFileForTest(file)
        try {
            RawVerificationStore.markVerified("sub-1", "f".repeat(64), "singbox")
            RawVerificationStore.markUnverified("sub-1", "f".repeat(64), "the recovery failed")
        } finally {
            reset()
        }
        try {
            // A new process: the cached map is dropped and the file is the only source.
            RawVerificationStore.useFileForTest(file)
            val record = RawVerificationStore.record("sub-1")
            assertNotNull(record)
            assertEquals("record was $record", RawVerificationStore.STATE_UNVERIFIED, record?.state)
            assertEquals("the recovery failed", record?.reason)
            assertFalse(
                "the raw that a failed recovery left behind must not start after a restart",
                RawVerificationStore.isVerified("sub-1", "f".repeat(64)),
            )
        } finally {
            reset()
            file.delete()
        }
    }

    @Test
    fun `a verified commit survives a restart`() {
        val file = tempFile()
        RawVerificationStore.useFileForTest(file)
        try {
            RawVerificationStore.markVerified("sub-1", "1".repeat(64), "singbox")
        } finally {
            reset()
        }
        try {
            RawVerificationStore.useFileForTest(file)
            assertTrue(RawVerificationStore.isVerified("sub-1", "1".repeat(64)))
        } finally {
            reset()
            file.delete()
        }
    }

    @Test
    fun `a corrupt record file verifies nothing`() {
        val file = tempFile()
        file.writeText("{not json")
        RawVerificationStore.useFileForTest(file)
        try {
            assertNull(RawVerificationStore.record("sub-1"))
            assertFalse(RawVerificationStore.isVerified("sub-1", "2".repeat(64)))
        } finally {
            reset()
            file.delete()
        }
    }

    @Test
    fun `removing a subscription drops its record`() {
        val file = tempFile()
        RawVerificationStore.useFileForTest(file)
        try {
            RawVerificationStore.markVerified("sub-1", "3".repeat(64), "singbox")
            RawVerificationStore.remove("sub-1")
            assertNull(RawVerificationStore.record("sub-1"))
        } finally {
            reset()
            file.delete()
        }
    }

    @Test
    fun `writing the record replaces it atomically and leaves no temp file`() {
        val file = tempFile()
        RawVerificationStore.useFileForTest(file)
        try {
            RawVerificationStore.markVerified("sub-1", "4".repeat(64), "singbox")
            val siblings = file.parentFile.listFiles { candidate ->
                candidate.name.startsWith(file.name)
            }?.map { it.name }?.toSet().orEmpty()
            assertEquals(setOf(file.name), siblings)
        } finally {
            reset()
            file.delete()
        }
    }
}
