package com.slide.ime.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hold-to-repeat arrow's slide-off rule, including its boundary semantics. */
class ArrowButtonSlideOffTest {

    @Test
    fun `bounds are inclusive at the origin and exclusive on the far edges`() {
        assertTrue(ArrowButtonPolicy.contains(x = 0f, y = 0f, width = 48, height = 32))
        assertFalse(ArrowButtonPolicy.contains(x = 48f, y = 16f, width = 48, height = 32))
        assertFalse(ArrowButtonPolicy.contains(x = 24f, y = 32f, width = 48, height = 32))
        assertFalse(ArrowButtonPolicy.contains(x = -0.5f, y = 16f, width = 48, height = 32))
        assertFalse(ArrowButtonPolicy.contains(x = 24f, y = -0.5f, width = 48, height = 32))
    }

    @Test
    fun `sliding off cancels only while the button is pressed`() {
        assertTrue(
            ArrowButtonPolicy.slidOff(isPressed = true, x = 100f, y = 100f, width = 48, height = 32),
        )
        // A press that never started cannot be cancelled into firing or unpressing anything.
        assertFalse(
            ArrowButtonPolicy.slidOff(isPressed = false, x = 100f, y = 100f, width = 48, height = 32),
        )
        assertFalse(
            ArrowButtonPolicy.slidOff(isPressed = true, x = 10f, y = 10f, width = 48, height = 32),
        )
    }
}
