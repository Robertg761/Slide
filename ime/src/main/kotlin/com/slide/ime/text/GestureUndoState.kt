package com.slide.ime.text

/** Everything needed to safely undo one just-committed swipe. */
internal data class GestureUndo(
    val committedText: String,
    val editorGeneration: Long,
    val cursorPosition: Int?,
    val learnedPair: Pair<String, String>?,
)

/**
 * One-shot state for Gboard-style "Backspace deletes the last swiped word" behaviour.
 *
 * This deliberately lives apart from the suggestion strip. Hiding candidates must not erase the
 * undo opportunity, especially when the strip is disabled. The editor text is checked literally
 * before deletion because an InputConnection belongs to another process and may have changed.
 */
internal class GestureUndoState {
    private var pending: GestureUndo? = null

    val expectedTextLength: Int?
        get() = pending?.committedText?.length

    fun snapshot(): GestureUndo? = pending

    fun arm(
        committedText: String,
        editorGeneration: Long,
        cursorPosition: Int?,
        learnedPair: Pair<String, String>?,
    ) {
        pending = committedText.takeIf(String::isNotEmpty)?.let {
            GestureUndo(it, editorGeneration, cursorPosition, learnedPair)
        }
    }

    fun matchesEditorAndCursor(editorGeneration: Long, cursorPosition: Int?): Boolean {
        val candidate = pending ?: return false
        return candidate.editorGeneration == editorGeneration &&
            (candidate.cursorPosition == null || candidate.cursorPosition == cursorPosition)
    }

    fun invalidate() {
        pending = null
    }

    /**
     * Consumes the opportunity whether it matches or not, so a later Backspace can never delete a
     * stale word after the first one already behaved as an ordinary edit.
     */
    fun consume(
        editorGeneration: Long,
        hasSelection: Boolean,
        textBeforeCursor: String?,
        cursorPosition: Int?,
    ): GestureUndo? {
        val candidate = pending ?: return null
        pending = null
        return candidate.takeIf {
            it.editorGeneration == editorGeneration &&
                !hasSelection &&
                textBeforeCursor == it.committedText &&
                (it.cursorPosition == null || it.cursorPosition == cursorPosition)
        }
    }
}
