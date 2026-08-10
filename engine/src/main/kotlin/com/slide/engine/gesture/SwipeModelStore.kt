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
        if (destination.isFile && sha256(destination) == expectedSha256) return destination

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
        return destination
    }

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
