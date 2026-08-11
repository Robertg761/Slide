package com.slide.ime.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpectedSelectionTrackerTest {
    @Test
    fun `two delayed own callbacks do not look like an external edit`() {
        val tracker = ExpectedSelectionTracker()
        tracker.expect(EditorSelection(4, 4), EditorSelection(12, 12))
        tracker.expect(EditorSelection(12, 12), EditorSelection(13, 13))

        assertTrue(tracker.consume(EditorSelection(4, 4), EditorSelection(12, 12)))
        assertTrue(tracker.consume(EditorSelection(12, 12), EditorSelection(13, 13)))
        assertEquals(0, tracker.size)
    }

    @Test
    fun `coalesced final callback retires earlier expected positions`() {
        val tracker = ExpectedSelectionTracker()
        tracker.expect(EditorSelection(4, 4), EditorSelection(12, 12))
        tracker.expect(EditorSelection(12, 12), EditorSelection(13, 13))

        assertTrue(tracker.consume(EditorSelection(4, 4), EditorSelection(13, 13)))
        assertEquals(0, tracker.size)
    }

    @Test
    fun `repeated final position is coalesced through the last occurrence without going stale`() {
        var now = 1L
        val tracker = ExpectedSelectionTracker { now }
        val start = EditorSelection(4, 4)
        val end = EditorSelection(12, 12)
        tracker.expect(start, end)
        tracker.expect(end, start)
        tracker.expect(start, end)

        assertTrue(tracker.consume(start, end))
        assertEquals(0, tracker.size)

        // If the editor actually posts the intermediate callbacks, their exact transitions are
        // still ours. Once that short delivery window passes, the same cursor tap is external.
        now += 100_000_001L
        assertFalse(tracker.consume(end, start))
    }

    @Test
    fun `callbacks already posted behind an ambiguous repeated position are accepted briefly`() {
        val start = EditorSelection(4, 4)
        val end = EditorSelection(12, 12)
        val tracker = ExpectedSelectionTracker { 1L }
        tracker.expect(start, end)
        tracker.expect(end, start)
        tracker.expect(start, end)

        assertTrue(tracker.consume(start, end))
        assertTrue(tracker.consume(end, start))
        assertTrue(tracker.consume(start, end))
    }

    /**
     * A chain is registered only after its edits have been sent, so while callbacks for the earlier
     * steps are still arriving the editor is already at the end of it. That end is what the
     * keyboard must cache: an edit landing between the two callbacks — a Backspace looking for the
     * word a swipe just committed — is otherwise measured against a superseded position.
     */
    @Test
    fun `an intermediate callback still reports where the chain ends`() {
        val tracker = ExpectedSelectionTracker()
        assertNull(tracker.pendingTarget())

        tracker.expect(EditorSelection(4, 4), EditorSelection(12, 12))
        tracker.expect(EditorSelection(12, 12), EditorSelection(19, 19))
        assertEquals(EditorSelection(19, 19), tracker.pendingTarget())

        assertTrue(tracker.consume(EditorSelection(4, 4), EditorSelection(12, 12)))
        assertEquals(EditorSelection(19, 19), tracker.pendingTarget())

        assertTrue(tracker.consume(EditorSelection(12, 12), EditorSelection(19, 19)))
        assertNull(tracker.pendingTarget())
    }

    @Test
    fun `a coalesced callback leaves nothing outstanding`() {
        val tracker = ExpectedSelectionTracker()
        tracker.expect(EditorSelection(4, 4), EditorSelection(12, 12))
        tracker.expect(EditorSelection(12, 12), EditorSelection(19, 19))

        assertTrue(tracker.consume(EditorSelection(4, 4), EditorSelection(19, 19)))
        assertNull(tracker.pendingTarget())
    }

    @Test
    fun `unrelated cursor move is never consumed`() {
        val tracker = ExpectedSelectionTracker()
        tracker.expect(EditorSelection(4, 4), EditorSelection(12, 12))

        assertFalse(tracker.consume(EditorSelection(12, 12), EditorSelection(4, 4)))
        assertEquals(1, tracker.size)
    }

    @Test
    fun `gesture snapshot rejects cursor or context changes`() {
        val original = GestureEditorSnapshot(
            cursor = EditorSelection(18, 18),
            textBeforeCursor = "said ",
            textAfterCursor = " next",
        )

        assertTrue(original.matches(original.copy()))
        assertFalse(original.matches(original.copy(cursor = EditorSelection(4, 4))))
        assertFalse(original.matches(original.copy(textBeforeCursor = "moved ")))
    }

    @Test
    fun `queued character casing is resolved after queued shift`() {
        assertTrue(isCaseableCharacter("a"))
        assertTrue(isCaseableCharacter("É"))
        assertFalse(isCaseableCharacter("AM"))
        assertFalse(isCaseableCharacter("PM"))
        assertFalse(isCaseableCharacter(".com"))
        assertEquals("A", resolveCharacterCase("a", shifted = true))
        assertEquals("é", resolveCharacterCase("É", shifted = false))
    }

    @Test
    fun `length changing correction advances the following swipe origin`() {
        assertEquals(
            16,
            cursorAfterReplacement(cursor = 13, originalLength = 3, replacementLength = 6),
        )
    }
}
