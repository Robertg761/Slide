package com.slide.ime.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SuggestionPlacementTest {
    @Test
    fun `best suggestion always occupies the centre cell`() {
        for (count in 1..3) {
            assertEquals(0, SuggestionPlacement.candidateAtSlot(count, 1))
            assertEquals(1, SuggestionPlacement.slotForCandidate(count, 0))
        }
    }

    @Test
    fun `alternatives fill left then right`() {
        assertEquals(1, SuggestionPlacement.candidateAtSlot(2, 0))
        assertNull(SuggestionPlacement.candidateAtSlot(2, 2))
        assertEquals(1, SuggestionPlacement.candidateAtSlot(3, 0))
        assertEquals(2, SuggestionPlacement.candidateAtSlot(3, 2))
    }

    @Test
    fun `single suggestion leaves side cells empty`() {
        assertNull(SuggestionPlacement.candidateAtSlot(1, 0))
        assertNull(SuggestionPlacement.candidateAtSlot(1, 2))
    }

    @Test
    fun `toolbar controls are excluded from suggestion hit targets`() {
        val width = 360f
        val height = 48f

        assertNull(SuggestionStripLayout.slotAt(24f, width, height, voiceEnabled = true, slotCount = 3))
        assertEquals(0, SuggestionStripLayout.slotAt(48f, width, height, voiceEnabled = true, slotCount = 3))
        assertEquals(1, SuggestionStripLayout.slotAt(150f, width, height, voiceEnabled = true, slotCount = 3))
        assertEquals(2, SuggestionStripLayout.slotAt(311f, width, height, voiceEnabled = true, slotCount = 3))
        assertNull(SuggestionStripLayout.slotAt(312f, width, height, voiceEnabled = true, slotCount = 3))
    }

    @Test
    fun `disabling voice gives its control width back to suggestions`() {
        assertEquals(264f, SuggestionStripLayout.suggestionWidth(360f, 48f, voiceEnabled = true))
        assertEquals(312f, SuggestionStripLayout.suggestionWidth(360f, 48f, voiceEnabled = false))
        assertEquals(
            2,
            SuggestionStripLayout.slotAt(340f, 360f, 48f, voiceEnabled = false, slotCount = 3),
        )
    }
}
