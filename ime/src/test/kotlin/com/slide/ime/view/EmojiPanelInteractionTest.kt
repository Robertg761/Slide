package com.slide.ime.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmojiPanelInteractionTest {

    @Test
    fun `accessibility paging clamps at both ends`() {
        assertEquals(
            90f,
            EmojiAccessibilityPaging.targetOffset(10f, 100f, 80f, direction = 1),
            0.001f,
        )
        assertEquals(
            100f,
            EmojiAccessibilityPaging.targetOffset(90f, 100f, 80f, direction = 1),
            0.001f,
        )
        assertEquals(
            0f,
            EmojiAccessibilityPaging.targetOffset(20f, 100f, 80f, direction = -1),
            0.001f,
        )
    }

    @Test
    fun `accessibility paging transfers focus to the same viewport slot`() {
        assertEquals(26, EmojiAccessibilityPaging.focusTarget(0..17, 18..35, focused = 8))
        assertEquals(35, EmojiAccessibilityPaging.focusTarget(0..17, 18..35, focused = 17))
        assertNull(EmojiAccessibilityPaging.focusTarget(0..17, 12..29, focused = 14))
    }

    @Test
    fun `tone popup maps both axes and reserves the first slot for untoned`() {
        assertEquals(
            -1,
            EmojiTonePopupHitTest.toneAt(5f, 5f, 0f, 0f, 60f, 10f, slots = 6),
        )
        assertEquals(
            4,
            EmojiTonePopupHitTest.toneAt(59.9f, 5f, 0f, 0f, 60f, 10f, slots = 6),
        )
        assertNull(EmojiTonePopupHitTest.toneAt(25f, -0.01f, 0f, 0f, 60f, 10f, slots = 6))
        assertNull(EmojiTonePopupHitTest.toneAt(25f, 10f, 0f, 0f, 60f, 10f, slots = 6))
        assertNull(EmojiTonePopupHitTest.toneAt(60f, 5f, 0f, 0f, 60f, 10f, slots = 6))
    }
}
