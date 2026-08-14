package com.slide.engine.gesture

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/** Copies verified, uncompressed model assets to a stable path that ExecuTorch can mmap. */
internal object SwipeModelStore {
    fun materialize(context: Context, name: String, expectedSha256: String): File {
        val directory = File(context.noBackupFilesDir, "swipe-models")
        check(directory.isDirectory || directory.mkdirs()) { "Could not create swipe model directory" }
        val destination = File(directory, name)
        val stamp = File(directory, "$name.stamp")
        if (destination.isFile) {
            // Re-hashing a multi-megabyte model on every process start is the expensive common
            // case, and IME processes start often. The stamp records the identity of the copy
            // that last passed verification; when the expected hash and the file's size and
            // mtime all still match it, the read can be skipped. Any mismatch or torn stamp
            // falls back to hashing, so the stamp can only ever skip work, not verification.
            if (stampMatches(stamp, destination, expectedSha256)) return destination
            if (sha256(destination) == expectedSha256) {
                writeStamp(stamp, destination, expectedSha256)
                return destination
            }
        }

        val temporary = File(directory, "$name.part")
        temporary.delete()
        context.assets.open("swipe/$name").use { input ->
            FileOutputStream(temporary).use { output -> input.copyTo(output) }
        }
        check(sha256(temporary) == expectedSha256) { "Packaged swipe model failed verification: $name" }
        try {
            Files.move(temporary.toPath(), destination.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), REPLACE_EXISTING)
        }
        writeStamp(stamp, destination, expectedSha256)
        return destination
    }

    private fun stampMatches(stamp: File, file: File, expectedSha256: String): Boolean {
        val recorded = try {
            if (!stamp.isFile) return false
            stamp.readText()
        } catch (_: java.io.IOException) {
            return false
        }
        return recorded == stampValue(file, expectedSha256)
    }

    private fun writeStamp(stamp: File, file: File, sha256: String) {
        try {
            stamp.writeText(stampValue(file, sha256))
        } catch (_: java.io.IOException) {
            // Without a stamp the next start simply re-hashes; never fail materialization over it.
            stamp.delete()
        }
    }

    private fun stampValue(file: File, sha256: String): String =
        "$sha256 ${file.length()} ${file.lastModified()}"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
