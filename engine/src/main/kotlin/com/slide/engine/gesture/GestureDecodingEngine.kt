package com.slide.engine.gesture

/** Common contract for the neural decoder and the deterministic fallback. */
interface GestureDecodingEngine {
    fun decode(
        points: List<GesturePoint>,
        keys: GestureKeyMap,
        blockOffensive: Boolean = true,
        previousWord: String? = null,
        previousPreviousWord: String? = null,
    ): List<GestureCandidate>
}

/** Which decoder produced the candidates returned by the most recent call on this engine. */
enum class GestureDecoderSource {
    NEURAL,
    FALLBACK,
    NONE,
}

/** Optional per-call provenance for engines that may transparently fail over. */
interface GestureDecoderProvenance {
    val lastDecoderSource: GestureDecoderSource
}
