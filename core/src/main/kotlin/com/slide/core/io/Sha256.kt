package com.slide.core.io

import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 as lower-case hex, shared by the packaged-asset, learned-data and update code paths so
 * each does not carry its own copy of the same loop.
 */
object Sha256 {
    fun hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    /** Streams [file] through the digest, so the cost is one buffer rather than the file's size. */
    fun hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHexString()
    }
}
