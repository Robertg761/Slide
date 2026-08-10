package com.slide.engine.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralSwipePreprocessorTest {
    @Test
    fun `normalizes layout to the model's canonical keyboard frame`() {
        val keys = GestureFixtures.qwerty()
        val input = requireNotNull(
            NeuralSwipePreprocessor.prepare(GestureFixtures.trace("slide", keys), keys),
        )

        val q = ('q' - 'a') * 2
        val a = ('a' - 'a') * 2
        val z = ('z' - 'a') * 2
        assertEquals(0.05f, input.layoutKeys[q], 0.001f)
        assertEquals(1f / 6f, input.layoutKeys[q + 1], 0.001f)
        assertEquals(0.10f, input.layoutKeys[a], 0.001f)
        assertEquals(0.50f, input.layoutKeys[a + 1], 0.001f)
        assertEquals(0.20f, input.layoutKeys[z], 0.001f)
        assertEquals(5f / 6f, input.layoutKeys[z + 1], 0.001f)
        assertTrue(input.layoutMask.take(26).all { it })
        assertTrue(input.layoutMask.drop(26).none { it })
    }

    @Test
    fun `uses elapsed time rather than only arc length`() {
        val keys = GestureFixtures.qwerty()
        val points = listOf(
            GesturePoint(keys.centerX('a'), keys.centerY('a'), 0),
            GesturePoint(keys.centerX('s'), keys.centerY('s'), 10),
            GesturePoint(keys.centerX('d'), keys.centerY('d'), 20),
            GesturePoint(keys.centerX('f'), keys.centerY('f'), 200),
            GesturePoint(keys.centerX('g'), keys.centerY('g'), 210),
            GesturePoint(keys.centerX('h'), keys.centerY('h'), 220),
        )
        val input = requireNotNull(NeuralSwipePreprocessor.prepare(points, keys))

        // At the midpoint in time the finger is still between d and f, rather than halfway along
        // the six raw samples. This is the velocity evidence the old SHARK2 path discarded.
        val midpointX = input.features[32]
        val normalizedD = input.layoutKeys[('d' - 'a') * 2]
        val normalizedF = input.layoutKeys[('f' - 'a') * 2]
        assertTrue(midpointX in normalizedD..normalizedF)
    }
}
