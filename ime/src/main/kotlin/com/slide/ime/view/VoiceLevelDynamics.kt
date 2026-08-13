package com.slide.ime.view

import kotlin.math.exp
import kotlin.math.sqrt

/** Frame-rate-independent attack and release for the live microphone meter. */
internal class VoiceLevelDynamics {
    var level: Float = 0f
        private set

    private var target = 0f

    fun accept(rawLevel: Float) {
        // Recorder levels are linear RMS. The square root expands ordinary speech without making
        // loud sounds exceed the visual range, so a quiet voice still produces obvious movement.
        target = sqrt(rawLevel.coerceIn(0f, 1f))
    }

    fun advance(elapsedMs: Long): Float {
        if (elapsedMs <= 0L) return level
        val timeConstant = if (target > level) ATTACK_MS else RELEASE_MS
        val amount = (1.0 - exp(-elapsedMs.toDouble() / timeConstant)).toFloat()
        level += (target - level) * amount
        if (level < MIN_VISIBLE_LEVEL) level = 0f
        return level
    }

    fun reset() {
        level = 0f
        target = 0f
    }

    private companion object {
        const val ATTACK_MS = 45.0
        const val RELEASE_MS = 180.0
        const val MIN_VISIBLE_LEVEL = 0.001f
    }
}
