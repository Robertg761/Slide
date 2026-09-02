package com.slide.core.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AtomicFilesTest {
    @Test
    fun `replace moves the temporary over an existing target and removes the temporary`() {
        val directory = Files.createTempDirectory("atomic-files").toFile()
        try {
            val target = File(directory, "words.txt").apply { writeText("old") }
            val temporary = File(directory, "words.txt.tmp").apply { writeText("new") }

            AtomicFiles.replace(temporary, target)

            assertEquals("new", target.readText())
            assertFalse(temporary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `replace creates the target when none exists`() {
        val directory = Files.createTempDirectory("atomic-files").toFile()
        try {
            val target = File(directory, "words.txt")
            val temporary = File(directory, "words.txt.tmp").apply { writeText("first") }

            AtomicFiles.replace(temporary, target)

            assertEquals("first", target.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
