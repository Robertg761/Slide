package com.slide.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupRulesTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun legacyPrivateSettingsAndCrashResidueAreExcludedEverywhere() {
        val paths =
            listOf(
                "datastore/slide_settings.preferences_pb",
                "datastore/slide_settings.preferences_pb.tmp",
            )
        val backup = File(root, "app/src/main/res/xml/backup_rules.xml").readText()
        val extraction = File(root, "app/src/main/res/xml/data_extraction_rules.xml").readText()

        paths.forEach { path ->
            assertEquals("legacy backup exclusion for $path", 1, backup.countPath(path))
            assertEquals("cloud and device-transfer exclusions for $path", 2, extraction.countPath(path))
        }
    }

    private fun String.countPath(path: String): Int =
        Regex("""<exclude\s+domain="file"\s+path="${Regex.escape(path)}"\s*/>""")
            .findAll(this)
            .count()
}
