package com.slide.ime.text

/**
 * Decides whether a newly typed word needs a separator from the punctuation behind the cursor.
 *
 * The space is inserted when the next word starts, rather than immediately after punctuation.
 * That preserves deliberate punctuation runs such as `?!` and means a space the person types
 * themselves can never be doubled by the keyboard.
 */
internal object AutoSpacing {
    /**
     * Whether a word needs a leading space after [textBeforeCursor].
     *
     * The caller passes a small context window rather than one [Char]. A Unicode code point may
     * occupy two UTF-16 code units (emoji are the common case), and an ASCII quote is only
     * distinguishable as opening or closing by looking at what precedes it.
     *
     * [continuingTypedWord] distinguishes a literal key tap at an existing word edge (`hello|` +
     * `s` means `hellos`) from a whole-word swipe or dictation (`hello|` + `world` needs a space).
     * [typingAfterApostrophe] handles the remaining quote ambiguity: while entering one character
     * at a time, `don'` followed by `t` must remain a contraction.
     */
    fun beforeWord(
        textBeforeCursor: CharSequence?,
        typingAfterApostrophe: Boolean = false,
        continuingTypedWord: Boolean = false,
    ): Boolean {
        if (textBeforeCursor.isNullOrEmpty()) return false

        var cursor = textBeforeCursor.length
        var codePoint = Character.codePointBefore(textBeforeCursor, cursor)
        // A decomposed combining mark belongs to the preceding base character. Classifying the
        // mark itself as punctuation would join a following word to e.g. `cafe\u0301`.
        while (cursor > 0 && isCombiningMark(codePoint)) {
            cursor -= Character.charCount(codePoint)
            if (cursor == 0) return false
            codePoint = Character.codePointBefore(textBeforeCursor, cursor)
        }

        if (Character.isWhitespace(codePoint) || isLineBoundary(codePoint)) return false
        if (Character.isLetterOrDigit(codePoint)) return !continuingTypedWord
        if (typingAfterApostrophe && codePoint in WORD_APOSTROPHES) {
            // U+2019 doubles as a contraction apostrophe and a closing quote. A still-unmatched
            // U+2018 in the supplied context makes the latter unambiguous.
            return codePoint == RIGHT_SINGLE_QUOTE &&
                hasUnmatchedOpeningSingleQuote(
                    textBeforeCursor,
                    cursor - Character.charCount(codePoint),
                )
        }
        if (codePoint in OPENING_PUNCTUATION || codePoint in WORD_JOINING_PUNCTUATION) return false
        if (codePoint in UNAMBIGUOUS_CLOSING_PUNCTUATION) return true

        if (codePoint == ASCII_APOSTROPHE || codePoint == MODIFIER_APOSTROPHE) {
            if (typingAfterApostrophe) return false
            return ambiguousQuoteCloses(textBeforeCursor, cursor - Character.charCount(codePoint))
        }
        if (codePoint == ASCII_QUOTE) {
            return ambiguousQuoteCloses(textBeforeCursor, cursor - Character.charCount(codePoint))
        }

        return when (Character.getType(codePoint)) {
            Character.END_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.OTHER_SYMBOL.toInt(),
            -> true
            else -> false
        }
    }

    private fun ambiguousQuoteCloses(text: CharSequence, quoteStart: Int): Boolean {
        if (quoteStart <= 0) return false
        val previous = Character.codePointBefore(text, quoteStart)
        return !Character.isWhitespace(previous) &&
            !isLineBoundary(previous) &&
            previous !in OPENING_PUNCTUATION
    }

    private fun hasUnmatchedOpeningSingleQuote(text: CharSequence, before: Int): Boolean {
        var depth = 0
        var cursor = 0
        while (cursor < before) {
            val codePoint = Character.codePointAt(text, cursor)
            when (codePoint) {
                LEFT_SINGLE_QUOTE -> depth++
                RIGHT_SINGLE_QUOTE -> {
                    val next = cursor + Character.charCount(codePoint)
                    val insideWord = cursor > 0 && next < before &&
                        Character.isLetter(Character.codePointBefore(text, cursor)) &&
                        Character.isLetter(Character.codePointAt(text, next))
                    if (!insideWord && depth > 0) depth--
                }
            }
            cursor += Character.charCount(codePoint)
        }
        return depth > 0
    }

    private fun isCombiningMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> false
    }

    private fun isLineBoundary(codePoint: Int): Boolean =
        codePoint == '\n'.code || codePoint == '\r'.code ||
            codePoint == LINE_SEPARATOR || codePoint == PARAGRAPH_SEPARATOR

    private val OPENING_PUNCTUATION = setOf(
        '('.code, '['.code, '{'.code, '<'.code,
        '«'.code, '‹'.code, '‘'.code, '“'.code,
    )
    private val UNAMBIGUOUS_CLOSING_PUNCTUATION = setOf(
        ')'.code, ']'.code, '}'.code, '>'.code,
        '»'.code, '›'.code, '’'.code, '”'.code,
    )
    private val WORD_JOINING_PUNCTUATION = setOf(
        '-'.code, '‐'.code, '‑'.code, '/'.code, '\\'.code, '@'.code, '#'.code, '&'.code,
    )
    private val WORD_APOSTROPHES = setOf(
        ASCII_APOSTROPHE,
        MODIFIER_APOSTROPHE,
        '’'.code,
        '\uff07'.code,
    )

    private const val ASCII_APOSTROPHE = '\''.code
    private const val MODIFIER_APOSTROPHE = '\u02bc'.code
    private const val LEFT_SINGLE_QUOTE = '\u2018'.code
    private const val RIGHT_SINGLE_QUOTE = '\u2019'.code
    private const val ASCII_QUOTE = '"'.code
    private const val LINE_SEPARATOR = 0x2028
    private const val PARAGRAPH_SEPARATOR = 0x2029
}
