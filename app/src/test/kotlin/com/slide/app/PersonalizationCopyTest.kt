package com.slide.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizationCopyTest {
    private val source = run {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
            it.parentFile
        }.first { File(it, "settings.gradle.kts").isFile }
        File(root, "app/src/main/kotlin/com/slide/app/MainActivity.kt").readText()
    }

    @Test
    fun `learned-data disclosure names touch calibration before and after deletion`() {
        assertTrue(
            source.contains("personal words, word pairs, and per-key touch ") &&
                source.contains("calibration it learned from your typing"),
        )
        assertTrue(
            source.contains(
                "Learned words, phrases, and per-key touch calibration were cleared.",
            ),
        )
    }

    @Test
    fun `update disclosure names every automatic GitHub trigger`() {
        assertTrue(source.contains("when you enable checks or change prerelease inclusion"))
    }
}
