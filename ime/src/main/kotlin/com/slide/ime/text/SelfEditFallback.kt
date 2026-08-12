package com.slide.ime.text

/**
 * Resolves the one-shot selection fallback after an editor mutation attempt.
 *
 * [previouslyPending] protects an older accepted edit when several keys are applied before its
 * callback arrives. [fallbackStillArmed] lets a handler disarm the coarse fallback after it has
 * registered an exact expected selection instead. A rejected or observably neutral attempt must
 * never arm a new fallback of its own.
 */
internal object SelfEditFallback {
    fun afterAttempt(
        previouslyPending: Boolean,
        callbackPossible: Boolean,
        fallbackStillArmed: Boolean,
    ): Boolean = previouslyPending || (callbackPossible && fallbackStillArmed)
}
