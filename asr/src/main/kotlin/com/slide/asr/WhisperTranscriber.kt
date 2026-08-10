package com.slide.asr

import android.content.Context
import android.util.Log
import kotlin.math.min
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Turns recorded audio into text, entirely on the device.
 *
 * One instance owns one loaded model. Loading is expensive enough to be worth doing once and
 * keeping — hundreds of milliseconds and hundreds of megabytes — so the owner should hold this for
 * as long as voice input is plausibly going to be used again, and [close] it when it is not.
 *
 * Every call is serialised. A whisper context carries decoding state and cannot be used from two
 * threads at once; the mutex makes overlapping requests queue instead of corrupting each other.
 */
class WhisperTranscriber(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /** What went wrong, for a caller that has to explain itself to a user. */
    sealed interface Result {
        data class Text(val value: String) : Result

        /** Decoding succeeded but the audio held no speech. */
        data object NoSpeech : Result

        data class Failed(val reason: String) : Result
    }

    private val mutex = Mutex()
    private val cancellationLock = Any()

    private var handle = 0L
    private var loaded: WhisperModel? = null
    private var activeCancellationToken = 0L

    val isAvailable: Boolean get() = WhisperNative.isAvailable

    /**
     * Loads [model], replacing whatever was loaded before.
     *
     * Safe to call repeatedly; loading the model that is already resident does nothing. Returns
     * false if the native library is missing or the model asset could not be read, in which case
     * voice input should be presented as unavailable rather than failing at the moment of use.
     */
    suspend fun load(model: WhisperModel): Boolean = withContext(dispatcher) {
        mutex.withLock {
            if (!WhisperNative.isAvailable) {
                Log.w(TAG, "Native speech library is not present on this device")
                return@withLock false
            }
            if (loaded == model && handle != 0L) return@withLock true

            releaseLocked()

            val started = System.nanoTime()
            val opened = WhisperNative.openModel(context.assets, model.assetName, threadCount())
            if (opened == 0L) {
                Log.e(TAG, "Could not load ${model.assetName}")
                return@withLock false
            }

            handle = opened
            loaded = model
            Log.i(TAG, "Loaded ${model.label} in ${(System.nanoTime() - started) / 1_000_000}ms")
            true
        }
    }

    /**
     * Transcribes mono 16kHz PCM in the range -1..1.
     *
     * Returns [Result.NoSpeech] rather than an empty string for silence: whisper suppresses its
     * own hallucinated output on quiet input, and a caller needs to tell "nothing was said" apart
     * from "something went wrong" to say anything useful about it.
     */
    suspend fun transcribe(samples: FloatArray): Result = try {
        withContext(dispatcher) {
            mutex.withLock {
                if (handle == 0L) return@withLock Result.Failed("No speech model loaded")
                if (samples.isEmpty()) return@withLock Result.NoSpeech

                val token = WhisperNative.createCancellationToken()
                if (token == 0L) return@withLock Result.Failed("Speech recognition failed")

                synchronized(cancellationLock) { activeCancellationToken = token }
                try {
                    val started = System.nanoTime()
                    val text = suspendCancellableCoroutine { continuation ->
                        // This handler runs immediately on cancellation, even while the dispatcher
                        // thread is blocked inside JNI.
                        continuation.invokeOnCancellation { cancelToken(token) }
                        val decoded = WhisperNative.transcribe(
                            handle,
                            samples,
                            threadCount(),
                            token,
                        )
                        if (continuation.isActive) continuation.resume(decoded)
                    }
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000

                    val seconds = samples.size.toFloat() / SAMPLE_RATE
                    Log.i(TAG, "Transcribed %.1fs of audio in %dms".format(seconds, elapsedMs))

                    when {
                        text == null -> Result.Failed("Speech recognition failed")
                        text.isBlank() -> Result.NoSpeech
                        else -> Result.Text(text)
                    }
                } finally {
                    closeToken(token)
                }
            }
        }
    } finally {
        // The service owns no persistent audio history. Wipe its drain copy on every exit path,
        // including cancellation and failures before JNI starts.
        PcmBuffers.wipe(samples)
    }

    /** Interrupts an in-flight native decode. Safe to call from any thread. */
    fun cancelTranscription() {
        synchronized(cancellationLock) {
            if (activeCancellationToken != 0L) {
                WhisperNative.cancelTranscription(activeCancellationToken)
            }
        }
    }

    suspend fun close() {
        cancelTranscription()
        // Service destruction may wait for this from the main thread. Keep mutex ownership,
        // native work, unlock, and close on the worker dispatcher so a canceled load/decode never
        // needs Main in order to release the lock that close() is waiting to acquire.
        withContext(NonCancellable + dispatcher) {
            mutex.withLock { releaseLocked() }
        }
    }

    private fun cancelToken(token: Long) {
        synchronized(cancellationLock) {
            if (activeCancellationToken == token) WhisperNative.cancelTranscription(token)
        }
    }

    private fun closeToken(token: Long) {
        synchronized(cancellationLock) {
            if (activeCancellationToken == token) activeCancellationToken = 0L
            WhisperNative.closeCancellationToken(token)
        }
    }

    private fun releaseLocked() {
        if (handle != 0L) {
            WhisperNative.closeModel(handle)
            handle = 0L
            loaded = null
        }
    }

    /**
     * Whisper scales with cores up to a point and then loses to memory bandwidth and to the little
     * cores dragging behind the big ones. Four is a safe middle on current phones; the right number
     * is device specific and worth measuring rather than guessing further.
     */
    private fun threadCount(): Int = min(DEFAULT_THREADS, Runtime.getRuntime().availableProcessors())

    companion object {
        /** Whisper is trained at this rate and resamples nothing; audio must arrive at it. */
        const val SAMPLE_RATE = 16_000

        private const val DEFAULT_THREADS = 4
        private const val TAG = "SlideAsr"
    }
}
