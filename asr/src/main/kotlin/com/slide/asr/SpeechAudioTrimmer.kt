package com.slide.asr

import kotlin.math.max
import kotlin.math.sqrt

/** Removes only confident leading and trailing silence before offline inference. */
internal object SpeechAudioTrimmer {

    private const val FRAME_SAMPLES = WhisperTranscriber.SAMPLE_RATE / 50 // 20 ms
    private const val LEADING_PADDING_FRAMES = 12 // 240 ms protects initial consonants
    private const val TRAILING_PADDING_FRAMES = 18 // 360 ms protects quiet word endings
    private const val ABSOLUTE_SPEECH_RMS = 0.008f
    private const val NOISE_MULTIPLIER = 3f

    /** Returns [samples] itself when no trim is safe, otherwise a new inference-only copy. */
    fun trim(samples: FloatArray): FloatArray {
        val frameCount = samples.size / FRAME_SAMPLES
        if (frameCount < 2) return samples

        val rms = FloatArray(frameCount)
        for (frame in 0 until frameCount) {
            val start = frame * FRAME_SAMPLES
            var sum = 0.0
            for (index in start until start + FRAME_SAMPLES) {
                val sample = samples[index]
                sum += sample * sample
            }
            rms[frame] = sqrt(sum / FRAME_SAMPLES).toFloat()
        }

        // The quietest fifth is a robust local noise estimate. Keyboard taps and speech cannot
        // raise it unless they occupy almost the entire recording, in which case trimming nothing
        // is the correct conservative result.
        val sorted = rms.copyOf().apply { sort() }
        val noiseFloor = sorted[(sorted.lastIndex / 5).coerceAtLeast(0)]
        val threshold = max(ABSOLUTE_SPEECH_RMS, noiseFloor * NOISE_MULTIPLIER)
        val firstSpeech = rms.indexOfFirst { it >= threshold }
        val lastSpeech = rms.indexOfLast { it >= threshold }
        if (firstSpeech < 0 || lastSpeech < firstSpeech) return samples

        val firstFrame = (firstSpeech - LEADING_PADDING_FRAMES).coerceAtLeast(0)
        val lastFrameExclusive = (lastSpeech + TRAILING_PADDING_FRAMES + 1).coerceAtMost(frameCount)
        val start = firstFrame * FRAME_SAMPLES
        val end = if (lastFrameExclusive == frameCount) samples.size else lastFrameExclusive * FRAME_SAMPLES
        if (start == 0 && end == samples.size) return samples
        return samples.copyOfRange(start, end)
    }
}
