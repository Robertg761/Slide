package com.slide.asr

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.sqrt

/** Small injectable boundary that lets recorder ownership be exercised without microphone hardware. */
internal interface AudioCaptureBackend {
    val isInitialized: Boolean
    fun start()
    fun read(destination: ShortArray): Int
    fun stop()
    fun release()
}

internal interface AudioCaptureBackendFactory {
    fun minimumBufferSize(): Int
    fun create(bufferBytes: Int): AudioCaptureBackend
}

private object AndroidAudioCaptureBackendFactory : AudioCaptureBackendFactory {
    override fun minimumBufferSize(): Int = AudioRecord.getMinBufferSize(
        WhisperTranscriber.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )

    @SuppressLint("MissingPermission")
    override fun create(bufferBytes: Int): AudioCaptureBackend = AndroidAudioCaptureBackend(
        AudioRecord(
            // VOICE_RECOGNITION asks the platform for a clean, unprocessed voice signal.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            WhisperTranscriber.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        ),
    )
}

private class AndroidAudioCaptureBackend(private val record: AudioRecord) : AudioCaptureBackend {
    override val isInitialized: Boolean get() = record.state == AudioRecord.STATE_INITIALIZED
    override fun start() = record.startRecording()
    override fun read(destination: ShortArray): Int = record.read(destination, 0, destination.size)
    override fun stop() = record.stop()
    override fun release() = record.release()
}

/**
 * Captures microphone audio in the one format whisper accepts: mono 16kHz, -1..1.
 *
 * Recording runs on its own thread rather than a coroutine dispatcher. Each capture owns its own
 * stop flag, sample buffer, backend and termination latch. In particular, timing out while a
 * vendor driver tears down cannot make a later recording reuse the old worker's global state.
 */
class AudioRecorder internal constructor(
    private val backendFactory: AudioCaptureBackendFactory = AndroidAudioCaptureBackendFactory,
    private val joinTimeoutMs: Long = JOIN_TIMEOUT_MS,
    private val maxSamples: Int = MAX_SAMPLES,
) {

    fun interface LevelListener {
        /** Loudness of the last chunk, 0..1, for a level meter. Called on the recording thread. */
        fun onLevel(level: Float)
    }

    enum class EndReason {
        RecordingLimitReached,
        CaptureFailed,
    }

    fun interface EndListener {
        /** Called on the recording thread when capture ends without an explicit stop or cancel. */
        fun onEnded(reason: EndReason)
    }

    private val lifecycleLock = Any()
    @Volatile private var activeCapture: Capture? = null

    val isRecording: Boolean
        get() = activeCapture?.let { !it.stopRequested.get() && !it.isTerminated } == true

    /**
     * Begins recording, returning false if the microphone could not be opened or a prior driver
     * worker is still draining after a bounded stop.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(listener: LevelListener? = null, endListener: EndListener? = null): Boolean =
        synchronized(lifecycleLock) {
            reapTerminatedCaptureLocked()
            if (activeCapture != null) return@synchronized false

            val minimum = try {
                backendFactory.minimumBufferSize()
            } catch (e: RuntimeException) {
                Log.e(TAG, "Could not query the microphone buffer", e)
                return@synchronized false
            }
            if (minimum <= 0) {
                Log.e(TAG, "Device reports no usable 16kHz mono capture buffer")
                return@synchronized false
            }

            val backend = try {
                backendFactory.create(minimum * BUFFER_MULTIPLIER)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Could not create AudioRecord", e)
                return@synchronized false
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Could not create AudioRecord", e)
                return@synchronized false
            } catch (e: SecurityException) {
                Log.e(TAG, "Microphone permission was revoked", e)
                return@synchronized false
            }

            if (!backend.isInitialized) {
                releaseUnstarted(backend)
                Log.e(TAG, "Microphone unavailable")
                return@synchronized false
            }

            try {
                backend.start()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Could not start AudioRecord", e)
                releaseUnstarted(backend)
                return@synchronized false
            } catch (e: SecurityException) {
                Log.e(TAG, "Microphone permission was revoked", e)
                releaseUnstarted(backend)
                return@synchronized false
            }

            val capture = Capture(backend)
            val worker = Thread({ loop(capture, listener, endListener) }, "slide-audio").apply {
                priority = Thread.MAX_PRIORITY
            }
            capture.worker = worker
            activeCapture = capture
            worker.start()
            true
        }

    /** Stops recording and returns exactly the samples owned by that capture. */
    fun stop(): FloatArray {
        val capture = activeCapture ?: return FloatArray(0)
        capture.requestStop()
        awaitWorker(capture)
        val audio = capture.copyAndWipe()
        reapCapture(capture)
        return audio
    }

    /** Stops and discards, for a canceled dictation. */
    fun cancel() {
        val capture = activeCapture ?: return
        capture.requestStop()
        awaitWorker(capture)
        capture.wipe()
        reapCapture(capture)
    }

    private fun awaitWorker(capture: Capture) {
        if (capture.worker === Thread.currentThread()) return
        if (!capture.terminated.await(joinTimeoutMs, TimeUnit.MILLISECONDS)) {
            // Keep activeCapture as a draining sentinel. start() must reject another capture until
            // this exact worker's finally has released the backend and counted down the latch.
            Log.e(TAG, "Audio worker did not stop within ${joinTimeoutMs}ms; blocking a new capture")
        }
    }

    private fun reapCapture(capture: Capture) = synchronized(lifecycleLock) {
        if (activeCapture === capture && capture.isDrained && capture.isTerminated) {
            activeCapture = null
        }
    }

    private fun reapTerminatedCaptureLocked() {
        val capture = activeCapture ?: return
        if (capture.isDrained && capture.isTerminated) activeCapture = null
    }

    private fun loop(capture: Capture, listener: LevelListener?, endListener: EndListener?) {
        val chunk = ShortArray(CHUNK_SAMPLES)
        try {
            while (!capture.stopRequested.get()) {
                val read = try {
                    capture.backend.read(chunk)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Microphone read threw", e)
                    READ_FAILED
                }

                if (read <= 0 || read > chunk.size) {
                    if (!capture.stopRequested.get()) {
                        Log.w(TAG, "Microphone read failed: $read")
                        notifyEnd(endListener, EndReason.CaptureFailed)
                    }
                    break
                }

                // requestStop can unblock read with one last positive buffer. It belongs to the
                // canceled capture and must not be appended after stop() has begun draining it.
                if (capture.stopRequested.get()) break

                var sumOfSquares = 0.0
                val reachedLimit = capture.append(chunk, read) { sample ->
                    sumOfSquares += (sample * sample).toDouble()
                }
                if (reachedLimit) {
                    capture.stopRequested.set(true)
                    Log.i(TAG, "Reached the recording limit; stopping")
                    notifyEnd(endListener, EndReason.RecordingLimitReached)
                    break
                }

                if (!capture.stopRequested.get()) {
                    notifyLevel(
                        listener,
                        min(1f, sqrt(sumOfSquares / read).toFloat() * LEVEL_GAIN),
                    )
                }
            }
        } finally {
            chunk.fill(0)
            capture.releaseOnce()
            capture.terminated.countDown()
            reapCapture(capture)
        }
    }

    private fun notifyLevel(listener: LevelListener?, level: Float) {
        try {
            listener?.onLevel(level)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Voice level listener failed", e)
        }
    }

    private fun notifyEnd(listener: EndListener?, reason: EndReason) {
        try {
            listener?.onEnded(reason)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Recorder end listener failed", e)
        }
    }

    private fun releaseUnstarted(backend: AudioCaptureBackend) {
        try {
            backend.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not release an unstarted microphone", e)
        }
    }

    private inner class Capture(val backend: AudioCaptureBackend) {
        val stopRequested = AtomicBoolean(false)
        val terminated = CountDownLatch(1)
        lateinit var worker: Thread

        private val cleanupLock = Any()
        private val stopStarted = AtomicBoolean(false)
        private val stopFinished = CountDownLatch(1)
        private var released = false

        private val samplesLock = Any()
        private var samples = FloatArray(min(INITIAL_CAPACITY, maxSamples).coerceAtLeast(1))
        private var sampleCount = 0
        private val drained = AtomicBoolean(false)

        val isTerminated: Boolean get() = terminated.count == 0L
        val isDrained: Boolean get() = drained.get()

        fun requestStop() {
            stopRequested.set(true)
            if (!stopStarted.compareAndSet(false, true)) return

            // AudioRecord.stop() is a vendor/driver call and is not guaranteed to return promptly.
            // Never perform it on the IME-facing service main thread: a bounded worker join is not
            // actually bounded if the caller blocks here before reaching that join.
            try {
                Thread(
                    {
                        try {
                            stopBackend()
                        } finally {
                            stopFinished.countDown()
                        }
                    },
                    "slide-audio-stop",
                ).apply { isDaemon = true }.start()
            } catch (e: RuntimeException) {
                // Thread creation failure is exceptional; preserve cleanup rather than leave a read
                // permanently blocked. This fallback can block, but only after the runtime has
                // already refused the dedicated teardown thread.
                Log.e(TAG, "Could not start the audio teardown thread", e)
                try {
                    stopBackend()
                } finally {
                    stopFinished.countDown()
                }
            }
        }

        fun append(chunk: ShortArray, count: Int, observe: (Float) -> Unit): Boolean =
            synchronized(samplesLock) {
                if (stopRequested.get()) return@synchronized false
                if (sampleCount + count > maxSamples) return@synchronized true
                ensureCapacity(sampleCount + count)
                for (index in 0 until count) {
                    val sample = chunk[index] / PCM_16_FULL_SCALE
                    samples[sampleCount + index] = sample
                    observe(sample)
                }
                sampleCount += count
                false
            }

        fun copyAndWipe(): FloatArray = synchronized(samplesLock) {
            PcmBuffers.copyAndWipe(samples, sampleCount).also {
                sampleCount = 0
                drained.set(true)
            }
        }

        fun wipe() = synchronized(samplesLock) {
            PcmBuffers.wipe(samples, sampleCount)
            sampleCount = 0
            drained.set(true)
        }

        private fun ensureCapacity(needed: Int) {
            if (needed <= samples.size) return
            var capacity = samples.size
            while (capacity < needed) capacity = min(capacity * 2, maxSamples)
            val expanded = samples.copyOf(capacity)
            PcmBuffers.wipe(samples, sampleCount)
            samples = expanded
        }

        private fun stopBackend() {
            try {
                backend.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Audio capture was already stopped", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not stop audio capture", e)
            }
        }

        fun releaseOnce() {
            if (stopStarted.compareAndSet(false, true)) {
                try {
                    stopBackend()
                } finally {
                    stopFinished.countDown()
                }
            } else {
                var interrupted = false
                while (true) {
                    try {
                        stopFinished.await()
                        break
                    } catch (_: InterruptedException) {
                        // Releasing while backend.stop is still inside vendor code can race native
                        // ownership. Finish the handoff, then restore the worker's interrupt bit.
                        interrupted = true
                    }
                }
                if (interrupted) Thread.currentThread().interrupt()
            }

            synchronized(cleanupLock) {
                if (released) return@synchronized
                try {
                    backend.release()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Could not release audio capture", e)
                } finally {
                    released = true
                }
            }
        }
    }

    private companion object {
        const val TAG = "SlideAsr"
        const val READ_FAILED = -1

        /** 16-bit PCM is signed, so full scale one way is 32768. */
        const val PCM_16_FULL_SCALE = 32768f

        const val CHUNK_SAMPLES = 1600 // 100ms, also a comfortable level-meter refresh
        const val BUFFER_MULTIPLIER = 4
        const val INITIAL_CAPACITY = WhisperTranscriber.SAMPLE_RATE * 4

        /** Two minutes. Past this the wait for a transcript stops being worth it. */
        const val MAX_SAMPLES = WhisperTranscriber.SAMPLE_RATE * 120

        const val JOIN_TIMEOUT_MS = 500L
        const val LEVEL_GAIN = 4f
    }
}
