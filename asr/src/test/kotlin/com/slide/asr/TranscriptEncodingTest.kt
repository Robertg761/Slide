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
        val lsb = 1f / 32768f

        // Literal digital silence, and sub-LSB noise.
        assertTrue(WhisperTranscriber.isDigitallySilent(FloatArray(32_000)))
        assertTrue(
            WhisperTranscriber.isDigitallySilent(floatArrayOf(0f, lsb * 0.5f, -lsb * 0.99f)),
        )

        // Dither around the least significant bit — one isolated blip, or a whole buffer of it —
        // is converter noise rather than speech and must reach the same rejection.
        assertTrue(WhisperTranscriber.isDigitallySilent(floatArrayOf(0f, lsb, 0f)))
        assertTrue(
            WhisperTranscriber.isDigitallySilent(
                FloatArray(32_000) { if (it % 2 == 0) lsb else -lsb },
            ),
        )

        // Real signal clears the dither floor comfortably.
        assertFalse(
            WhisperTranscriber.isDigitallySilent(floatArrayOf(0f, 100 * lsb, 0f)),
        )
        // A brief real consonant inside a long quiet lead-in keeps its classification: ten
        // milliseconds of ordinary speech energy lifts the whole-buffer RMS far above dither.
        val burst = FloatArray(160) { 0.05f }
        val withLeadIn = burst + FloatArray(32_000 - burst.size)
        assertFalse(WhisperTranscriber.isDigitallySilent(withLeadIn))
    }

    @Test
    fun `partial assembly keeps word boundaries across segments`() {
        // Raw whisper segments: every one starts with a space, and that space is the only thing
        // separating words once a caption spans more than one segment. The assembler must keep
        // it, exactly like the native final assembly does.
        val assembler = PartialAssembler()

        assertEquals(null, assembler.append(null))
        assertEquals("Hello", assembler.append(" Hello"))
        assertEquals("Hello brave", assembler.append(" brave"))
        assertEquals("Hello brave world", assembler.append(" world"))
    }

    @Test
    fun `partial assembly trims only boundary whitespace`() {
        // Leading and trailing space/tab/newline go, interior whitespace stays — matching
        // whisper_jni.cpp's tidy() so the last partial equals the final result byte for byte.
        assertEquals("a b\tc\nd", PartialAssembler.tidy("\t\n a b\tc\nd \n"))

        val assembler = PartialAssembler()
        assertEquals(null, assembler.append(" \n\t"))
        assertEquals(null, assembler.append(null))
        assertEquals(null, assembler.append(""))
        assertEquals("word", assembler.append(" word"))
    }
}
