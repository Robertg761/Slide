package com.slide.core.io

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

object AtomicFiles {
    /**
     * Moves [temporary] over [target] as one rename. The app's private files normally live on a
     * filesystem that supports atomic rename; on an unusual one that does not, the replacement is
     * still collision-safe, just not atomic. Whether the rename itself is durable is a separate
     * directory write and remains the caller's concern.
     */
    fun replace(temporary: File, target: File) {
        try {
            Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
        }
    }
}
