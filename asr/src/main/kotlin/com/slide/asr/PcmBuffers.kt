package com.slide.asr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Operations that make disposal of raw microphone samples explicit and testable. */
internal object PcmBuffers {

    /** Owns the drain copy even when cancellation wins the return from the recording thread. */
    suspend fun <T> withCapturedSamples(
        capture: () -> FloatArray,
        use: suspend (FloatArray) -> T,
    ): T {
        var samples = FloatArray(0)
        try {
            // Assign before dispatching back to the caller. Returning the array from withContext
            // can discard it on cancellation before the caller can take responsibility for wiping it.
            withContext(Dispatchers.IO) { samples = capture() }
            return use(samples)
        } finally {
            wipe(samples)
        }
    }

    fun copyAndWipe(source: FloatArray, count: Int): FloatArray {
        require(count in 0..source.size)
        val copy = source.copyOf(count)
        wipe(source, count)
        return copy
    }

    fun wipe(source: FloatArray, count: Int = source.size) {
        require(count in 0..source.size)
        source.fill(0f, 0, count)
    }
}
