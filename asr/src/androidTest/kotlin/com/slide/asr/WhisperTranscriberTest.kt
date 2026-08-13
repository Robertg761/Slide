package com.slide.asr

import androidx.test.platform.app.InstrumentationRegistry
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end checks of the speech path, on real hardware.
 *
 * These cannot be local tests: everything interesting here is in a native library compiled for
 * arm64, and the numbers that matter — how long a model takes to load and to decode — are
 * properties of the phone, not of the code.
 */
class WhisperTranscriberTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val transcriber = WhisperTranscriber(context)

    @After
    fun tearDown() = runBlocking { transcriber.close() }

    @Test
    fun nativeLibraryLoads() {
        assertTrue("libslide_asr.so did not load", transcriber.isAvailable)
    }

    @Test
    fun transcribesKnownSpeech() = runBlocking {
        assertTrue(transcriber.load(WhisperModel.Default))

        val result = transcriber.transcribe(readTestAudio())
        assertTrue("Expected text, got $result", result is WhisperTranscriber.Result.Text)

        // Loose on purpose. Punctuation and casing vary between models and between versions of
        // whisper; pinning them would make this a change detector rather than a test that the
        // audio was understood.
        val text = (result as WhisperTranscriber.Result.Text).value.lowercase()
        assertTrue(
            "Transcript did not contain the expected phrase: $text",
            "ask not what your country can do for you" in text,
        )
    }

    @Test
    fun reportsSilenceAsNoSpeech() = runBlocking {
        assertTrue(transcriber.load(WhisperModel.Default))

        // Two seconds of nothing. Whisper's characteristic failure is to fill silence with
        // boilerplate from its training data, so this asserts the suppression is working.
        val silence = FloatArray(WhisperTranscriber.SAMPLE_RATE * 2)
        assertEquals(WhisperTranscriber.Result.NoSpeech, transcriber.transcribe(silence))
    }

    @Test
    fun failsCleanlyWithNoModelLoaded() = runBlocking {
        val result = WhisperTranscriber(context).transcribe(FloatArray(1600))
        assertTrue("Expected a failure, got $result", result is WhisperTranscriber.Result.Failed)
    }

    /**
     * Reports load and decode timings for each packaged model.
     *
     * Not an assertion — the point is the printout, which is what decides
     * [WhisperModel.Default]. Read it with:
     *   adb logcat -s SlideAsr
     */
    @Test
    fun measuresEveryModel() = runBlocking {
        val seconds = readTestAudio().size.toFloat() / WhisperTranscriber.SAMPLE_RATE

        for (model in WhisperModel.entries) {
            val subject = WhisperTranscriber(context)
            val loadStart = System.nanoTime()
            assertTrue("Could not load ${model.label}", subject.load(model))
            val loadMs = (System.nanoTime() - loadStart) / 1_000_000

            // Once to warm the caches, then a timed run.
            subject.transcribe(readTestAudio())
            val decodeStart = System.nanoTime()
            val result = subject.transcribe(readTestAudio())
            val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000

            assertTrue("Benchmark decode did not recognize speech: $result", result is WhisperTranscriber.Result.Text)

            println(
                "%s: load %dms, decode %dms for %.1fs of audio (%.2fx realtime) -> %s"
                    .format(model.label, loadMs, decodeMs, seconds, seconds * 1000 / decodeMs, result),
            )
            subject.close()
        }
    }

    private fun readTestAudio(): FloatArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use(::readWav)

    /**
     * Reads the 16-bit mono PCM WAV used by these tests.
     *
     * Only handles what the fixture actually is; a general WAV parser would be a lot of code to
     * support formats no test uses.
     */
    private fun readWav(stream: InputStream): FloatArray {
        val bytes = stream.readBytes()
        val header = "data".toByteArray(Charsets.US_ASCII)

        var offset = -1
        for (i in 12 until bytes.size - 8) {
            if (bytes[i] == header[0] && bytes[i + 1] == header[1] &&
                bytes[i + 2] == header[2] && bytes[i + 3] == header[3]
            ) {
                offset = i + 8
                break
            }
        }
        require(offset > 0) { "No data chunk in the test WAV" }

        val count = (bytes.size - offset) / 2
        return FloatArray(count) { i ->
            val low = bytes[offset + i * 2].toInt() and 0xFF
            val high = bytes[offset + i * 2 + 1].toInt() // signed; carries the sign bit
            ((high shl 8) or low) / 32768f
        }
    }
}
