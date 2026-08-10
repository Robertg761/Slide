package com.slide.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PcmBuffersTest {

    @Test
    fun copyAndWipeReturnsSamplesAndErasesRetainedBuffer() {
        val retained = floatArrayOf(0.25f, -0.5f, 0.75f, 1f)

        val copy = PcmBuffers.copyAndWipe(retained, 3)

        assertArrayEquals(floatArrayOf(0.25f, -0.5f, 0.75f), copy, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), retained, 0f)
    }

    @Test
    fun wipeCanEraseAnEntireTranscriptionCopy() {
        val audio = floatArrayOf(0.1f, -0.2f, 0.3f)

        PcmBuffers.wipe(audio)

        assertArrayEquals(floatArrayOf(0f, 0f, 0f), audio, 0f)
    }
}
