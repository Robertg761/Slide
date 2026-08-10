package com.slide.asr

/** Operations that make disposal of raw microphone samples explicit and testable. */
internal object PcmBuffers {

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
