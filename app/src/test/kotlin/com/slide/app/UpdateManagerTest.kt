package com.slide.app

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @After
    fun releaseDownloadGuard() {
        UpdateManager.endDownload()
    }

    private fun newDirectory(): File =
        Files.createTempDirectory("slide-updates").toFile().apply { deleteOnExit() }

    @Test
    fun `stable release sorts after its prerelease`() {
        assertTrue(UpdateManager.compare("0.3.0", "0.3.0-alpha.1") > 0)
        assertTrue(UpdateManager.compare("0.3.0-alpha.1", "0.3.0") < 0)
    }

    @Test
    fun `prerelease identifiers follow SemVer precedence`() {
        val ordered = listOf(
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
        )

        ordered.zipWithNext().forEach { (older, newer) ->
            assertTrue("Expected $older < $newer", UpdateManager.compare(older, newer) < 0)
        }
    }

    @Test
    fun `invalid and zero-padded versions are rejected`() {
        assertFalse(UpdateManager.isValidSemVer("1.0"))
        assertFalse(UpdateManager.isValidSemVer("01.0.0"))
        assertFalse(UpdateManager.isValidSemVer("1.0.0-alpha.01"))
        assertTrue(UpdateManager.isValidSemVer("v1.0.0-rc.1"))
        assertTrue(UpdateManager.isValidSemVer("1.0.0+build.42"))
        assertTrue(UpdateManager.isPrerelease("1.0.0-rc.1+build.42"))
        assertFalse(UpdateManager.isPrerelease("1.0.0+build.42"))
        assertEquals(0, UpdateManager.compare("1.0.0+one", "1.0.0+two"))
        assertTrue(UpdateManager.compare("999999999999999999999999.0.0", "2.0.0") > 0)
        assertTrue(
            UpdateManager.compare(
                "1.0.0-999999999999999999999999",
                "1.0.0-999999999999999999999998",
            ) > 0,
        )
    }

    @Test
    fun `upgrade requires both newer semantic version and version code`() {
        assertTrue(UpdateManager.isValidUpgrade("0.2.1", 8, "0.2.0", 7))
        assertFalse(UpdateManager.isValidUpgrade("0.2.1", 7, "0.2.0", 7))
        assertFalse(UpdateManager.isValidUpgrade("0.2.0", 8, "0.2.0", 7))
        assertFalse(UpdateManager.isValidUpgrade("0.2.0-alpha.1", 8, "0.2.0", 7))
    }

    @Test
    fun `upgrade compares long version codes without integer truncation`() {
        val installedCode = Int.MAX_VALUE.toLong() + 10
        assertTrue(UpdateManager.isValidUpgrade("2.0.0", installedCode + 1, "1.9.9", installedCode))
        assertFalse(UpdateManager.isValidUpgrade("2.0.0", installedCode, "1.9.9", installedCode))
    }

    @Test
    fun `newest release is selected independently of GitHub publication order`() {
        fun release(version: String) = UpdateInfo(version, "", "https://example.test/$version", "0".repeat(64), 1)
        val candidates = listOf(release("0.2.2-alpha.1"), release("0.3.0"), release("0.2.1"))

        assertEquals("0.3.0", UpdateManager.newest("0.2.0", candidates)?.version)
        assertEquals("0.3.0", UpdateManager.newest("0.2.0", candidates.reversed())?.version)
        assertEquals(null, UpdateManager.newest("0.3.0", candidates))
    }

    @Test
    fun `sha256 is stable and zero padded`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateManager.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun `a second download is refused while the first still holds the staging files`() {
        assertFalse(UpdateManager.isDownloading.value)

        assertTrue(UpdateManager.beginDownload())
        assertTrue(UpdateManager.isDownloading.value)
        assertFalse(UpdateManager.beginDownload())

        UpdateManager.endDownload()
        assertFalse(UpdateManager.isDownloading.value)
        assertTrue(UpdateManager.beginDownload())
        UpdateManager.endDownload()
    }

    @Test
    fun `only one of many simultaneous download attempts claims the guard`() {
        val racers = 16
        val start = CountDownLatch(1)
        val finished = CountDownLatch(racers)
        val admitted = AtomicInteger()

        repeat(racers) {
            Thread {
                start.await()
                if (UpdateManager.beginDownload()) admitted.incrementAndGet()
                finished.countDown()
            }.start()
        }
        start.countDown()

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(1, admitted.get())
        UpdateManager.endDownload()
        assertFalse(UpdateManager.isDownloading.value)
    }

    @Test
    fun `sweep removes an installed release's APK and the legacy cache directory`() {
        val staging = newDirectory()
        val legacy = newDirectory()
        // The successful-update case: this APK is what the installer just installed.
        val staged = File(staging, "Slide-0.3.2.apk").apply { writeText("apk") }
        val partial = File(staging, "Slide-0.4.0.apk.part").apply { writeText("half an apk") }
        val legacyStaged = File(legacy, "Slide-0.3.1.apk").apply { writeText("old apk") }

        assertTrue(UpdateManager.sweepStagingUnlessBusy(staging, legacy, installedVersion = "0.3.2"))

        assertFalse(staged.exists())
        assertFalse(partial.exists())
        assertFalse(legacyStaged.exists())
        assertFalse("the legacy directory itself is litter too", legacy.exists())
        assertTrue("the staging directory is reused, not recreated", staging.exists())
        staging.deleteRecursively()
    }

    @Test
    fun `sweep leaves a running download's files alone`() {
        val staging = newDirectory()
        val legacy = newDirectory()
        val partial = File(staging, "Slide-0.3.2.apk.part").apply { writeText("being written") }

        assertTrue(UpdateManager.beginDownload())
        assertFalse(UpdateManager.sweepStagingUnlessBusy(staging, legacy, installedVersion = "0.3.1"))
        assertTrue("the sweep deleted a download in progress", partial.exists())

        UpdateManager.endDownload()
        assertTrue(UpdateManager.sweepStagingUnlessBusy(staging, legacy, installedVersion = "0.3.1"))
        assertFalse(partial.exists())
        staging.deleteRecursively()
        legacy.deleteRecursively()
    }

    @Test
    fun `sweep tolerates directories that were never created`() {
        val root = newDirectory()
        assertTrue(
            UpdateManager.sweepStagingUnlessBusy(
                staging = File(root, "updates"),
                legacy = File(root, "legacy"),
                installedVersion = "0.3.1",
            ),
        )
        root.deleteRecursively()
    }

    @Test
    fun `an APK the installer may still be about to read is kept, briefly`() {
        val hour = 60L * 60L * 1000L
        val now = 1_000L * hour

        // Handed to the installer a minute ago and not yet installed: the confirmation dialog can
        // outlive the rotation that triggered this sweep.
        assertFalse(
            UpdateManager.isStagedFileStale(
                name = "Slide-0.4.0.apk",
                lastModifiedMillis = now - 60_000L,
                nowMillis = now,
                installedVersion = "0.3.2",
            ),
        )
        // Never confirmed. It is not going to be.
        assertTrue(
            UpdateManager.isStagedFileStale(
                name = "Slide-0.4.0.apk",
                lastModifiedMillis = now - 2 * hour,
                nowMillis = now,
                installedVersion = "0.3.2",
            ),
        )
        // Already installed, however recently: nothing will read it again.
        assertTrue(
            UpdateManager.isStagedFileStale(
                name = "Slide-0.4.0.apk",
                lastModifiedMillis = now,
                nowMillis = now,
                installedVersion = "0.4.0",
            ),
        )
        // A partial download, and anything else that is not an APK at all.
        assertTrue(
            UpdateManager.isStagedFileStale(
                name = "Slide-0.4.0.apk.part",
                lastModifiedMillis = now,
                nowMillis = now,
                installedVersion = "0.3.2",
            ),
        )
        // An unreadable installed version must not strand a fresh APK forever.
        assertFalse(
            UpdateManager.isStagedFileStale(
                name = "Slide-0.4.0.apk",
                lastModifiedMillis = now,
                nowMillis = now,
                installedVersion = null,
            ),
        )
    }

    @Test
    fun `free space accounts for download and installer staging copies`() {
        val mib = 1024L * 1024L
        assertEquals(264L * mib, UpdateManager.requiredFreeBytes(100L * mib))
        assertEquals(Long.MAX_VALUE, UpdateManager.requiredFreeBytes(Long.MAX_VALUE))
    }
}
