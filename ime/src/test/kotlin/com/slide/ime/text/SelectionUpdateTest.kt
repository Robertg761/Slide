package com.slide.ime.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionUpdateTest {

    @Test
    fun `self edit survives an editor that omits composing bounds`() {
        val update = evaluate(old = 3, new = 4, selfEdit = true, candidates = -1 to -1)

        assertFalse(update.externalSelectionChanged)
        assertFalse(update.cursorLeftComposing)
        assertEquals(true, update.composingAtEnd)
    }

    @Test
    fun `external cursor move is unsafe when composing bounds are unknown`() {
        val update = evaluate(old = 7, new = 2, selfEdit = false, candidates = -1 to -1)

        assertTrue(update.externalSelectionChanged)
        assertTrue(update.cursorLeftComposing)
        assertNull(update.composingAtEnd)
    }

    @Test
    fun `tap inside a reported composing region keeps it and records cursor position`() {
        val update = evaluate(old = 8, new = 6, selfEdit = false, candidates = 4 to 8)

        assertTrue(update.externalSelectionChanged)
        assertFalse(update.cursorLeftComposing)
        assertEquals(false, update.composingAtEnd)
    }

    @Test
    fun `tap outside a reported composing region drops it`() {
        val update = evaluate(old = 8, new = 2, selfEdit = false, candidates = 4 to 8)

        assertTrue(update.cursorLeftComposing)
    }

    @Test
    fun `selection drops composing even when it lies inside the region`() {
        val update = SelectionUpdate.evaluate(
            oldSelStart = 8,
            oldSelEnd = 8,
            newSelStart = 5,
            newSelEnd = 7,
            candidatesStart = 4,
            candidatesEnd = 8,
            selfEdit = false,
            hasComposingText = true,
        )

        assertTrue(update.cursorLeftComposing)
    }

    @Test
    fun `movement with no composing text is still exposed for strip invalidation`() {
        val update = SelectionUpdate.evaluate(
            oldSelStart = 10,
            oldSelEnd = 10,
            newSelStart = 4,
            newSelEnd = 4,
            candidatesStart = -1,
            candidatesEnd = -1,
            selfEdit = false,
            hasComposingText = false,
        )

        assertTrue(update.externalSelectionChanged)
        assertFalse(update.cursorLeftComposing)
    }

    private fun evaluate(
        old: Int,
        new: Int,
        selfEdit: Boolean,
        candidates: Pair<Int, Int>,
    ) = SelectionUpdate.evaluate(
        oldSelStart = old,
        oldSelEnd = old,
        newSelStart = new,
        newSelEnd = new,
        candidatesStart = candidates.first,
        candidatesEnd = candidates.second,
        selfEdit = selfEdit,
        hasComposingText = true,
    )
}
