package com.slide.ime.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How a performed alternate action resolves the character it commits.
 *
 * The promise is what the node's label advertised when TalkBack enumerated it; the live list can
 * have shifted underneath (an auto-capitalise between enumeration and invocation is enough).
 */
class AlternateAccessibilityActionsResolveTest {

    @Test
    fun `the advertised promise wins over the live list`() {
        assertEquals(
            "b",
            AlternateAccessibilityActions.resolveAdvertised(
                promised = "b",
                alternateIndex = 0,
                // The shift flipped since population; honouring this would commit a character
                // different from the label the user selected against.
                liveAlternates = listOf("A", "X", "Y", "Z"),
            ),
        )
    }

    @Test
    fun `an action never populated falls back to the live lookup`() {
        val live = listOf("a", "b", "c")

        assertEquals("b", AlternateAccessibilityActions.resolveAdvertised(null, 0, live))
        assertEquals("c", AlternateAccessibilityActions.resolveAdvertised(null, 1, live))
    }

    @Test
    fun `nothing resolvable means no action`() {
        val live = listOf("a", "b")

        assertNull(AlternateAccessibilityActions.resolveAdvertised(null, 9, live))
        // A key whose only entry is its primary output has no alternates to expose.
        assertNull(AlternateAccessibilityActions.resolveAdvertised(null, 0, listOf("a")))
        assertNull(
            AlternateAccessibilityActions.resolveAdvertised(promised = null, alternateIndex = 0, liveAlternates = emptyList()),
        )
    }
}
