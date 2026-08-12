package com.slide.ime.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfEditFallbackTest {
    @Test
    fun `fully rejected attempt cannot poison the next selection callback`() {
        assertFalse(
            SelfEditFallback.afterAttempt(
                previouslyPending = false,
                callbackPossible = false,
                fallbackStillArmed = true,
            ),
        )
    }

    @Test
    fun `accepted untracked mutation keeps the one-shot fallback`() {
        assertTrue(
            SelfEditFallback.afterAttempt(
                previouslyPending = false,
                callbackPossible = true,
                fallbackStillArmed = true,
            ),
        )
    }

    @Test
    fun `exact selection tracking disarms the coarse fallback`() {
        assertFalse(
            SelfEditFallback.afterAttempt(
                previouslyPending = false,
                callbackPossible = true,
                fallbackStillArmed = false,
            ),
        )
    }

    @Test
    fun `older pending edit survives a later rejected or exactly tracked attempt`() {
        assertTrue(
            SelfEditFallback.afterAttempt(
                previouslyPending = true,
                callbackPossible = false,
                fallbackStillArmed = false,
            ),
        )
        assertTrue(
            SelfEditFallback.afterAttempt(
                previouslyPending = true,
                callbackPossible = true,
                fallbackStillArmed = false,
            ),
        )
    }
}
