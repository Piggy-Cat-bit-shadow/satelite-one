package com.interstellar.proxy.data

import java.io.File
import java.nio.file.Files
import org.junit.Test

/** Atomic raw-body persistence: a failed write must not look like success. */
class AtomicFileWriteTest {

    private fun tempDir(): File = Files.createTempDirectory("atomic-write-test").toFile()

    @Test
    fun `writes and replaces the target`() {
        val dir = tempDir()
        val target = File(dir, "body.raw")
        check(AtomicFileWrite.writeText(target, "first"))
        check(target.readText() == "first")
        check(AtomicFileWrite.writeText(target, "second"))
        check(target.readText() == "second")
    }

    @Test
    fun `a write that cannot create its temp file returns false`() {
        val dir = tempDir()
        val target = File(File(dir, "missing-parent"), "body.raw")
        check(!AtomicFileWrite.writeText(target, "body")) { "writing into a missing directory must fail" }
    }
}
