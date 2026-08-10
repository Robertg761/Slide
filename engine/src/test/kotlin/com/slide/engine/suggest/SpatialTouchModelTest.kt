package com.slide.engine.suggest

import com.slide.engine.gesture.GestureFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialTouchModelTest {
    private val keys = GestureFixtures.qwerty()

    @Test
    fun `confirmed touches move and persist a personal key centre`() {
        val model = SpatialTouchModel()
        val touches = touchesFor("test", tOffset = -0.4f)
        repeat(3) { assertEquals(4, model.observe("test", "test", touches, keys)) }

        val x = keys.centerX('t') - 0.4f * keys.keyWidth
        val y = keys.centerY('t')
        assertTrue(requireNotNull(model.distance('t', x, y, keys)) < 0.08f)

        val restored = SpatialTouchModel().also { it.restore(model.entries()) }
        assertEquals(model.entries(), restored.entries())
        assertTrue(requireNotNull(restored.distance('t', x, y, keys)) < 0.08f)
    }

    @Test
    fun `correction alignment teaches the intended key`() {
        val model = SpatialTouchModel()
        val touches = touchesFor("rest", tOffset = 0f)
        val learned = model.observe("rest", "test", touches, keys)

        assertEquals(4, learned)
        assertTrue(model.entries().any { it.letter == 't' })
    }

    @Test
    fun `implausibly distant touches cannot poison the model`() {
        val model = SpatialTouchModel()
        val touches = touchesFor("test", tOffset = 4f)
        model.observe("test", "test", touches, keys)

        assertTrue(model.entries().none { it.letter == 't' })
    }

    private fun touchesFor(word: String, tOffset: Float): FloatArray =
        FloatArray(word.length * 2).also { points ->
            for (position in word.indices) {
                val letter = word[position]
                points[position * 2] = keys.centerX(letter) +
                    if (letter == 't') tOffset * keys.keyWidth else 0f
                points[position * 2 + 1] = keys.centerY(letter)
            }
        }
}
