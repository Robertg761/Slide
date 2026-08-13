package com.slide.ime.text

/** Everything needed to safely undo one just-committed swipe. */
internal data class GestureUndo(
    val committedText: String,
    val editorGeneration: Long,
    val cursorPosition: Int?,
    val learnedPair: Pair<String, String>?,
    /** Lower-level decoded word, retained only until this one-shot editor transaction expires. */
    val adaptiveWord: String? = null,
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
        adaptiveWord: String? = null,
    ) {
        pending = committedText.takeIf(String::isNotEmpty)?.let {
            GestureUndo(it, editorGeneration, cursorPosition, learnedPair, adaptiveWord)
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
     * Consumes the opportunity, whether or not it applies, so a later Backspace can never delete a
     * stale word after the first one already behaved as an ordinary edit.
     *
     * The one exception is two positions that are both known and disagree. [cursorPosition] comes
     * from the keyboard's cached selection, which is refreshed by editor callbacks that may still
     * describe an intermediate position in a chain the keyboard has already finished writing.
     * Everything read from the editor itself — its identity, the absence of a selection, and the
     * committed text sitting immediately behind the cursor — still agrees in that case, so the
     * opportunity is kept rather than destroyed by a disagreement that heals on the next callback.
     * This Backspace still falls back to an ordinary delete, which changes the text behind the
     * cursor and so retires the record for good if the disagreement was real.
     *
     * An *unknown* current position is not that. It carries no evidence either way, so it can never
     * heal and would keep the record alive through every Backspace, widening the window in which a
     * coincidentally matching word elsewhere could be swallowed. It retires the record, as it
     * always did.
     */
    fun consume(
        editorGeneration: Long,
        hasSelection: Boolean,
        textBeforeCursor: String?,
        cursorPosition: Int?,
    ): GestureUndo? {
        val candidate = pending ?: return null
        val editorStillAgrees = candidate.editorGeneration == editorGeneration &&
            !hasSelection &&
            textBeforeCursor == candidate.committedText
        if (!editorStillAgrees) {
            pending = null
            return null
        }
        val armedCursor = candidate.cursorPosition
        if (armedCursor != null && cursorPosition != null && armedCursor != cursorPosition) {
            return null
        }
        pending = null
        return candidate.takeIf { armedCursor == null || armedCursor == cursorPosition }
    }
}
