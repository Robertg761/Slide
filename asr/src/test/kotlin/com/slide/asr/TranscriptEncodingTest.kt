package com.slide.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptEncodingTest {

    @Test
    fun `ordinary UTF-8 preserves supplementary characters and is wiped`() {
        val bytes = "voice 🎙️ and 🙂".toByteArray(Charsets.UTF_8)

        assertEquals("voice 🎙️ and 🙂", WhisperTranscriber.decodeTranscript(bytes))
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun `null native output remains a recognition failure`() {
        assertEquals(null, WhisperTranscriber.decodeTranscript(null))
    }

    @Test
    fun `digital silence is rejected before whisper can hallucinate`() {
        assertTrue(WhisperTranscriber.isDigitallySilent(FloatArray(32_000)))
        assertTrue(
            WhisperTranscriber.isDigitallySilent(
                floatArrayOf(0f, (1f / 32768f) * 0.5f, -(1f / 32768f) * 0.99f),
            ),
        )
        assertFalse(
            WhisperTranscriber.isDigitallySilent(floatArrayOf(0f, 1f / 32768f, 0f)),
        )
    }
}
