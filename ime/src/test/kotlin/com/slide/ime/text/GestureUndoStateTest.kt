package com.slide.ime.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureUndoStateTest {
    @Test
    fun `consumes the exact cased contraction and leading space once`() {
        val state = GestureUndoState()
        val learned = "said" to "that's"
        state.arm(" That's", editorGeneration = 7, cursorPosition = 14, learnedPair = learned)

        assertEquals(7, state.expectedTextLength)
        assertEquals(
            GestureUndo(" That's", 7, 14, learned),
            state.consume(7, false, " That's", cursorPosition = 14),
        )
        assertNull(state.consume(7, false, " That's", cursorPosition = 14))
    }

    @Test
    fun `text mismatch consumes stale opportunity`() {
        val state = GestureUndoState()
        state.arm(" Teresa", editorGeneration = 4, cursorPosition = 21, learnedPair = null)

        assertNull(state.consume(4, false, "aTeresa", cursorPosition = 21))
        assertNull(state.consume(4, false, " Teresa", cursorPosition = 21))
    }

    @Test
    fun `selection and editor changes reject the undo`() {
        val selected = GestureUndoState().apply {
            arm("word", editorGeneration = 2, cursorPosition = 8, learnedPair = null)
        }
        val newEditor = GestureUndoState().apply {
            arm("word", editorGeneration = 2, cursorPosition = 8, learnedPair = null)
        }

        assertNull(selected.consume(2, true, "word", cursorPosition = 8))
        assertNull(newEditor.consume(3, false, "word", cursorPosition = 8))
    }

    @Test
    fun `replacement rearms undo with the selected alternative`() {
        val state = GestureUndoState()
        state.arm(" Teresa", 9, cursorPosition = 18, learnedPair = "try" to "Teresa")
        state.arm(" that's", 9, cursorPosition = 17, learnedPair = "try" to "that's")

        assertEquals(
            GestureUndo(" that's", 9, 17, "try" to "that's"),
            state.consume(9, false, " that's", cursorPosition = 17),
        )
    }

    @Test
    fun `snapshot preserves the original transaction record without consuming it`() {
        val state = GestureUndoState()
        val expected = GestureUndo(" word", 5, 11, "a" to "word")
        state.arm(
            expected.committedText,
            expected.editorGeneration,
            expected.cursorPosition,
            expected.learnedPair,
        )

        assertEquals(expected, state.snapshot())
        assertEquals(expected, state.consume(5, false, " word", 11))
    }

    @Test
    fun `explicit invalidation removes the opportunity`() {
        val state = GestureUndoState()
        state.arm("word", editorGeneration = 1, cursorPosition = null, learnedPair = null)
        state.invalidate()

        assertNull(state.expectedTextLength)
        assertNull(state.consume(1, false, "word", cursorPosition = null))
    }

    @Test
    fun `known cursor must still be at the committed occurrence`() {
        val state = GestureUndoState()
        state.arm(" word", editorGeneration = 3, cursorPosition = 25, learnedPair = null)

        assertNull(state.consume(3, false, " word", cursorPosition = 42))
    }

    @Test
    fun `losing a known cursor cannot target a matching word elsewhere`() {
        val state = GestureUndoState()
        state.arm(" word", editorGeneration = 3, cursorPosition = 25, learnedPair = null)

        assertNull(state.consume(3, false, " word", cursorPosition = null))
    }
}
