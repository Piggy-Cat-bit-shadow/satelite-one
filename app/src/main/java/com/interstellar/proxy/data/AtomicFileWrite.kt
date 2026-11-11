package com.interstellar.proxy.data

import java.io.File

/**
 * Writes a small text file so a crash or a full disk cannot leave a truncated
 * document behind. The previous file is moved aside first, so a failed rename
 * leaves the last good body in place (and puts it back if the swap half-failed).
 *
 * Returns true only when the new bytes are in place. Callers must treat false as
 * a failed persistence step — a subscription body that was not written is not a
 * body this app can run later.
 *
 * Deliberately java.io only: minSdk is 24 and java.nio.file.Files needs API 26.
 */
object AtomicFileWrite {
    fun writeText(target: File, text: String): Boolean {
        val directory = target.parentFile ?: return false
        val temporary = File(directory, target.name + ".tmp")
        val backup = File(directory, target.name + ".bak")
        return try {
            temporary.writeText(text)
            // POSIX rename replaces the target atomically: no window where the
            // old body is missing. Windows refuses to replace an existing file,
            // so that path falls back to the backup swap below.
            if (temporary.renameTo(target)) {
                backup.delete()
                return true
            }
            if (target.exists()) {
                backup.delete()
                if (!target.renameTo(backup)) {
                    temporary.delete()
                    return false
                }
            }
            if (temporary.renameTo(target)) {
                backup.delete()
                true
            } else {
                if (backup.exists()) backup.renameTo(target)
                temporary.delete()
                false
            }
        } catch (_: Exception) {
            temporary.delete()
            false
        }
    }
}
