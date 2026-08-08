package com.slide.asr

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Captures microphone audio in the one format whisper accepts: mono 16kHz, -1..1.
 *
 * Recording runs on its own thread rather than a coroutine dispatcher. [AudioRecord.read] blocks
 * against a hardware buffer that overruns if it is not drained promptly, and a dispatcher offers no
 * promise about when a suspended read resumes; a dedicated thread does.
 */
class AudioRecorder {

    fun interface LevelListener {
        /** Loudness of the last chunk, 0..1, for a level meter. Called on the recording thread. */
        fun onLevel(level: Float)
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile private var recording = false

    /** Guarded by [samplesLock]; the recording thread appends and the caller drains on stop. */
    private var samples = FloatArray(INITIAL_CAPACITY)
    private var sampleCount = 0
    private val samplesLock = Any()

    val isRecording: Boolean get() = recording

    /**
     * Begins recording, returning false if the microphone could not be opened.
     *
     * A false here is normal, not exceptional: another app may hold the microphone, or the user may
     * have revoked the permission since the keyboard started.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @SuppressLint("MissingPermission")
    fun start(listener: LevelListener? = null): Boolean {
        if (recording) return true

        val minimum = AudioRecord.getMinBufferSize(
            WhisperTranscriber.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) {
            Log.e(TAG, "Device reports no usable 16kHz mono capture buffer")
            return false
        }

        // Several times the minimum, so a stall on the main thread cannot drop audio mid-word.
        val bufferBytes = minimum * BUFFER_MULTIPLIER

        val created = try {
            AudioRecord(
                // VOICE_RECOGNITION asks the platform for a clean, unprocessed voice signal.
                // Sources like MIC or VOICE_COMMUNICATION apply AGC, noise suppression and echo
                // cancellation tuned for phone calls, which distort exactly the detail whisper
                // relies on.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WhisperTranscriber.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Could not create AudioRecord", e)
            return false
        }

        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            Log.e(TAG, "Microphone unavailable")
            return false
        }

        synchronized(samplesLock) { sampleCount = 0 }
        record = created
        recording = true

        created.startRecording()
        thread = Thread({ loop(created, listener) }, "slide-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    /**
     * Stops recording and returns everything captured.
     *
     * Returns an empty array if nothing was recorded, which the caller should treat the same as
     * silence rather than as an error.
     */
    fun stop(): FloatArray {
        if (!recording) return FloatArray(0)
        recording = false

        thread?.join(JOIN_TIMEOUT_MS)
        thread = null

        record?.let { active ->
            try {
                active.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioRecord was already stopped", e)
            }
            active.release()
        }
        record = null

        return synchronized(samplesLock) { samples.copyOf(sampleCount) }
    }

    /** Stops and discards, for a cancelled dictation. */
    fun cancel() {
        stop()
        synchronized(samplesLock) { sampleCount = 0 }
    }

    private fun loop(active: AudioRecord, listener: LevelListener?) {
        val chunk = ShortArray(CHUNK_SAMPLES)

        while (recording) {
            val read = active.read(chunk, 0, chunk.size)
            if (read <= 0) {
                // ERROR_INVALID_OPERATION and friends mean the record has gone away underneath us.
                if (read < 0) Log.w(TAG, "Microphone read failed: $read")
                break
            }

            var sumOfSquares = 0.0
            synchronized(samplesLock) {
                if (sampleCount + read > MAX_SAMPLES) {
                    Log.i(TAG, "Reached the recording limit; stopping")
                    recording = false
                    return@synchronized
                }
                ensureCapacity(sampleCount + read)
                for (i in 0 until read) {
                    val sample = chunk[i] / PCM_16_FULL_SCALE
                    samples[sampleCount + i] = sample
                    sumOfSquares += (sample * sample).toDouble()
                }
                sampleCount += read
            }

            listener?.onLevel(min(1f, sqrt(sumOfSquares / read).toFloat() * LEVEL_GAIN))
        }
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= samples.size) return
        var capacity = samples.size
        while (capacity < needed) capacity *= 2
        samples = samples.copyOf(min(capacity, MAX_SAMPLES))
    }

    private companion object {
        const val TAG = "SlideAsr"

        /** 16-bit PCM is signed, so full scale one way is 32768. */
        const val PCM_16_FULL_SCALE = 32768f

        const val CHUNK_SAMPLES = 1600 // 100ms, which is also a comfortable level-meter refresh
        const val BUFFER_MULTIPLIER = 4
        const val INITIAL_CAPACITY = WhisperTranscriber.SAMPLE_RATE * 4 // four seconds

        /** Two minutes. Past this the wait for a transcript stops being worth it. */
        const val MAX_SAMPLES = WhisperTranscriber.SAMPLE_RATE * 120

        const val JOIN_TIMEOUT_MS = 500L

        /** Speech sits well below full scale; without this the meter barely moves. */
        const val LEVEL_GAIN = 4f
    }
}
