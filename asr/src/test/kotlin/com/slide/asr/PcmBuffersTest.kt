package com.slide.asr

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun capturedSamplesAreAvailableDuringUseAndWipedAfterward() = runBlocking {
        val audio = floatArrayOf(0.25f, -0.5f)

        val count = PcmBuffers.withCapturedSamples({ audio }) { samples ->
            assertArrayEquals(floatArrayOf(0.25f, -0.5f), samples, 0f)
            samples.size
        }

        assertEquals(2, count)
        assertArrayEquals(FloatArray(2), audio, 0f)
    }

    @Test
    fun cancellationDuringCaptureWipesSamplesWithoutDeliveringThem() = runBlocking {
        val audio = floatArrayOf(0.25f, -0.5f)
        val captureStarted = CompletableDeferred<Unit>()
        val finishCapture = CountDownLatch(1)
        var delivered = false
        val job = launch {
            PcmBuffers.withCapturedSamples(
                capture = {
                    captureStarted.complete(Unit)
                    check(finishCapture.await(5, TimeUnit.SECONDS))
                    audio
                },
            ) { delivered = true }
        }

        try {
            withTimeout(5_000) { captureStarted.await() }
            job.cancel()
        } finally {
            finishCapture.countDown()
        }
        withTimeout(5_000) { job.join() }

        assertTrue(job.isCancelled)
        assertFalse(delivered)
        assertArrayEquals(FloatArray(2), audio, 0f)
    }

    @Test
    fun failedConsumerStillWipesCapturedSamples() = runBlocking {
        val audio = floatArrayOf(0.25f, -0.5f)
        val failure = runCatching {
            PcmBuffers.withCapturedSamples({ audio }) { error("decode failed") }
        }.exceptionOrNull()

        assertEquals("decode failed", failure?.message)
        assertArrayEquals(FloatArray(2), audio, 0f)
    }
}
