package com.slide.ime.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLevelDynamicsTest {

    @Test
    fun `ordinary quiet speech becomes visibly responsive`() {
        val meter = VoiceLevelDynamics()

        meter.accept(0.09f)
        val level = meter.advance(100)

        assertTrue("quiet speech remained visually flat: $level", level > 0.25f)
        assertTrue(level <= 0.3f)
    }

    @Test
    fun `attack is quick and release is calm`() {
        val meter = VoiceLevelDynamics()
        meter.accept(1f)

        val attacked = meter.advance(100)
        assertTrue(attacked > 0.85f)

        meter.accept(0f)
        val firstReleaseFrame = meter.advance(16)
        assertTrue(firstReleaseFrame > attacked * 0.9f)
        assertTrue(meter.advance(700) < 0.03f)
    }

    @Test
    fun `levels clamp and reset`() {
        val meter = VoiceLevelDynamics()
        meter.accept(4f)
        meter.advance(500)
        assertTrue(meter.level <= 1f)

        meter.reset()
        assertEquals(0f, meter.level, 0f)
        meter.accept(-2f)
        assertEquals(0f, meter.advance(100), 0f)
    }
}
