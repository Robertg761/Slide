package com.slide.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

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
    fun `free space accounts for download and installer staging copies`() {
        val mib = 1024L * 1024L
        assertEquals(264L * mib, UpdateManager.requiredFreeBytes(100L * mib))
        assertEquals(Long.MAX_VALUE, UpdateManager.requiredFreeBytes(Long.MAX_VALUE))
    }
}
