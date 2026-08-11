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

    /**
     * The one correction whose touches cannot be attributed to anything.
     *
     * "gerat" reaches "great" by a single transposition, which is the cheapest edit the corrector
     * sells and so a correction users really do accept from the strip. Both touches are honest —
     * the finger hit `e` and then `r` — but which intended letter each belongs to is exactly what
     * the alignment cannot say, and an alignment with no transposition to spend will confidently
     * say the wrong thing: a whole key's error learned into both letters at once.
     */
    @Test
    fun `a transposed correction teaches neither of the swapped keys`() {
        val model = SpatialTouchModel()
        // Learn 'e' and 'r' from honest typing first, so "unchanged" is a claim about real
        // learned data rather than about an empty slot.
        repeat(4) { model.observe("rest", "rest", touchesFor("rest"), keys) }
        val before = model.entries().associateBy { it.letter }
        assertTrue("the fixture should have taught 'e' and 'r'", 'e' in before && 'r' in before)

        val learned = model.observe("gerat", "great", touchesFor("gerat"), keys)

        assertEquals("only g, a and t are aligned to a touch of their own", 3, learned)
        val after = model.entries().associateBy { it.letter }
        assertEquals("'e' must not learn the touch that produced 'r'", before['e'], after['e'])
        assertEquals("'r' must not learn the touch that produced 'e'", before['r'], after['r'])
    }

    /**
     * Saving and reloading must not change what the model believes.
     *
     * [SpatialTouchModel.restore] clips a mean to ±0.65 key widths; a live mean past that would
     * therefore mean one thing until the process restarted and another afterwards, from the very
     * same learned data.
     */
    @Test
    fun `an extreme but plausible mean survives a save and reload`() {
        val model = SpatialTouchModel()
        // Inside the observation gate, outside the clamp the restore path enforces.
        repeat(20) { model.observe("test", "test", touchesFor("test", tOffset = 1.3f), keys) }

        val restored = SpatialTouchModel().also { it.restore(model.entries()) }
        assertEquals(model.entries(), restored.entries())

        val x = keys.centerX('t') + 1.3f * keys.keyWidth
        val y = keys.centerY('t')
        assertEquals(
            requireNotNull(model.distance('t', x, y, keys)),
            requireNotNull(restored.distance('t', x, y, keys)),
            0f,
        )
    }

    private fun touchesFor(word: String, tOffset: Float = 0f): FloatArray =
        FloatArray(word.length * 2).also { points ->
            for (position in word.indices) {
                val letter = word[position]
                points[position * 2] = keys.centerX(letter) +
                    if (letter == 't') tOffset * keys.keyWidth else 0f
                points[position * 2 + 1] = keys.centerY(letter)
            }
        }
}
