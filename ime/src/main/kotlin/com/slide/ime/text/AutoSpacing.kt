package com.slide.ime.text

/**
 * Decides whether a newly typed word needs a separator from the punctuation behind the cursor.
 *
 * The space is inserted when the next word starts, rather than immediately after punctuation.
 * That preserves deliberate punctuation runs such as `?!` and means a space the person types
 * themselves can never be doubled by the keyboard.
 */
internal object AutoSpacing {
    fun beforeWord(previous: Char?): Boolean = previous in WORD_SEPARATING_PUNCTUATION

    private val WORD_SEPARATING_PUNCTUATION = setOf(',', '.', '!', '?', ';', ':', '…')
}
