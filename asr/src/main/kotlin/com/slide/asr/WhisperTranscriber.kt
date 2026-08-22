package com.slide.asr

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.min
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
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
    dispatcher: CoroutineDispatcher? = null,
) {

    /**
     * Owned only when no dispatcher was injected. Model load and decode block a thread inside JNI
     * for whole seconds at a time; giving them their own thread keeps that block off
     * `Dispatchers.Default`, whose small shared pool serves everything else in the process.
     */
    private val ownedDecodeExecutor: ExecutorService? =
        if (dispatcher == null) {
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "SlideWhisperDecode") }
        } else {
            null
        }

    private val dispatcher: CoroutineDispatcher =
        dispatcher ?: checkNotNull(ownedDecodeExecutor).asCoroutineDispatcher()

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
     *
     * [onPartial], when given, receives the growing transcript every time whisper finalises a
     * segment — invoked on this class's decode thread, so the receiver must move it off that
     * thread itself. The final [Result.Text] remains the only authoritative transcript: a
     * cancelled or failed decode can still have emitted partials for audio whose tail never made
     * it through.
     */
    suspend fun transcribe(samples: FloatArray, onPartial: ((String) -> Unit)? = null): Result = try {
        withContext(dispatcher) {
            mutex.withLock {
                if (handle == 0L) return@withLock Result.Failed("No speech model loaded")
                if (samples.isEmpty()) return@withLock Result.NoSpeech
                // Whisper's learned no-speech token is probabilistic and can still hallucinate a
                // short word from digital silence (the packaged base model has produced "you" on
                // both API 26 and API 37 emulators). Reject audio with no meaningful signal before
                // inference. Peak amplitude is intentionally used rather than RMS so a brief real
                // consonant is not erased by a long quiet lead-in or tail.
                if (isDigitallySilent(samples)) return@withLock Result.NoSpeech

                val token = WhisperNative.createCancellationToken()
                if (token == 0L) return@withLock Result.Failed("Speech recognition failed")

                synchronized(cancellationLock) { activeCancellationToken = token }
                // One buffer, owned by the decode thread that appends to it and read only after
                // the native call has returned: no cross-thread access needs synchronising.
                val accumulated = StringBuilder()
                val partialListener = onPartial?.let { receiver ->
                    WhisperNative.PartialListener { chunk ->
                        val piece = decodeTranscript(chunk)
                        if (!piece.isNullOrEmpty()) {
                            accumulated.append(piece)
                            receiver(accumulated.toString())
                        }
                    }
                }
                try {
                    val started = System.nanoTime()
                    val text = suspendCancellableCoroutine { continuation ->
                        // This handler runs immediately on cancellation, even while the dispatcher
                        // thread is blocked inside JNI.
                        continuation.invokeOnCancellation { cancelToken(token) }
                        val decoded = decodeTranscript(
                            WhisperNative.transcribe(
                                handle,
                                samples,
                                threadCount(),
                                token,
                                partialListener,
                            ),
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
        // Runs after the final decode-thread work above has completed; shutdown() lets any
        // already-queued task finish, so this never aborts an in-flight native call.
        ownedDecodeExecutor?.shutdown()
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

        /** Decodes Whisper's ordinary UTF-8 and erases the native transfer buffer afterwards. */
        internal fun decodeTranscript(bytes: ByteArray?): String? {
            if (bytes == null) return null
            return try {
                bytes.toString(Charsets.UTF_8)
            } finally {
                bytes.fill(0)
            }
        }

        /** True only for effectively zero PCM, below one 16-bit capture quantisation step. */
        internal fun isDigitallySilent(samples: FloatArray): Boolean =
            samples.none { abs(it) >= MIN_AUDIBLE_PEAK }

        private const val MIN_AUDIBLE_PEAK = 1f / 32768f
    }
}
