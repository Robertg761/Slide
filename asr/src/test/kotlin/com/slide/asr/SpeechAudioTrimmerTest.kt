package com.slide.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class SpeechAudioTrimmerTest {

    @Test
    fun `keeps padding around confident speech and removes distant silence`() {
        val frame = WhisperTranscriber.SAMPLE_RATE / 50
        val audio = FloatArray(frame * 80)
        for (index in frame * 30 until frame * 45) audio[index] = 0.2f

        val trimmed = SpeechAudioTrimmer.trim(audio)

        assertNotSame(audio, trimmed)
        assertEquals(frame * (45 + 18 - (30 - 12)), trimmed.size)
        assertArrayEquals(audio.copyOfRange(frame * 18, frame * 63), trimmed, 0f)
    }

    @Test
    fun `quiet uncertain speech is never discarded`() {
        val audio = FloatArray(WhisperTranscriber.SAMPLE_RATE) { 0.003f }

        assertSame(audio, SpeechAudioTrimmer.trim(audio))
    }

    @Test
    fun `recording already bounded by speech is not copied`() {
        val audio = FloatArray(WhisperTranscriber.SAMPLE_RATE) { 0.1f }

        assertSame(audio, SpeechAudioTrimmer.trim(audio))
    }

    @Test
    fun `sub-frame audio is not copied`() {
        val audio = FloatArray(100) { 0.1f }

        assertSame(audio, SpeechAudioTrimmer.trim(audio))
    }
}
