package com.slide.asr

import android.content.res.AssetManager

/**
 * Raw entry points into libslide_asr.so.
 *
 * Nothing outside this package should hold one of these handles: it is a native pointer, and using
 * one after [closeModel] corrupts memory rather than throwing. [WhisperTranscriber] owns the
 * lifetime and is the type the rest of the app talks to.
 */
internal object WhisperNative {

    /** True when the native library loaded; false on a device the .so was not built for. */
    val isAvailable: Boolean = try {
        System.loadLibrary("slide_asr")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    /** Returns a session handle, or 0 if the model could not be read or parsed. */
    @JvmStatic
    external fun openModel(assets: AssetManager, assetName: String, threads: Int): Long

    /** Safe to call with 0. */
    @JvmStatic
    external fun closeModel(handle: Long)

    /** Allocates a cancellation token for one transcription, or returns 0 on allocation failure. */
    @JvmStatic
    external fun createCancellationToken(): Long

    /** Thread-safe. Causes Whisper's abort callback to stop work using [token]. */
    @JvmStatic
    external fun cancelTranscription(token: Long)

    /** Safe to call with 0 once no native transcription can still reference [token]. */
    @JvmStatic
    external fun closeCancellationToken(token: Long)

    /**
     * [samples] is mono 16kHz PCM in -1..1.
     *
     * Whisper produces ordinary UTF-8. Returning its bytes keeps that encoding out of JNI's
     * Modified-UTF-8 string API; [WhisperTranscriber] performs the standards-compliant decode.
     * Returns null if recognition failed.
     */
    @JvmStatic
    external fun transcribe(
        handle: Long,
        samples: FloatArray,
        threads: Int,
        cancellationToken: Long,
    ): ByteArray?
}
