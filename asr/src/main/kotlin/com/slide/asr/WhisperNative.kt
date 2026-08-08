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

    /** [samples] is mono 16kHz PCM in -1..1. Returns null if decoding failed. */
    @JvmStatic
    external fun transcribe(handle: Long, samples: FloatArray, threads: Int): String?
}
