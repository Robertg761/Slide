package com.slide.core.io

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class Sha256Test {
    private val abcDigest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val emptyDigest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun `bytes hash to the published vector in lower-case hex`() {
        assertEquals(abcDigest, Sha256.hex("abc".toByteArray()))
        assertEquals(emptyDigest, Sha256.hex(ByteArray(0)))
    }

    @Test
    fun `file hash matches the byte hash and spans buffer boundaries`() {
        val file = File.createTempFile("sha256-", ".bin")
        try {
            file.writeBytes("abc".toByteArray())
            assertEquals(abcDigest, Sha256.hex(file))

            // Larger than the streaming buffer, so more than one update() call is exercised.
            val big = ByteArray(DEFAULT_BUFFER_SIZE * 3 + 17) { (it * 31).toByte() }
            file.writeBytes(big)
            assertEquals(Sha256.hex(big), Sha256.hex(file))
        } finally {
            file.delete()
        }
    }
}
