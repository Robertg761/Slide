package com.slide.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerStorageWiringTest {
    private val source = run {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
            it.parentFile
        }.first { File(it, "settings.gradle.kts").isFile }
        File(root, "app/src/main/kotlin/com/slide/app/UpdateManager.kt").readText()
    }

    @Test
    fun `staging space and allocation use the files directory volume`() {
        assertTrue(source.contains("storage.getUuidForPath(directory)"))
        assertTrue(source.contains("storage.getAllocatableBytes(uuid)"))
        assertTrue(source.contains("storage.allocateBytes(uuid, requiredBytes)"))
        assertFalse(source.contains("StorageManager.UUID_DEFAULT"))
    }

    @Test
    fun `parsed and saved release notes share the bounded representation`() {
        assertTrue(source.contains("notes = boundReleaseNotes(release.optString(\"body\"))"))

        val mainActivity = sourceFile("app/src/main/kotlin/com/slide/app/MainActivity.kt")
        assertTrue(mainActivity.contains("UpdateManager.boundReleaseNotes(it.notes)"))
    }

    private fun sourceFile(relative: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
            it.parentFile
        }.first { File(it, "settings.gradle.kts").isFile }
        return File(root, relative).readText()
    }
}
