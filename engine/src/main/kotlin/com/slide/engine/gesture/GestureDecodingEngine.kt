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
