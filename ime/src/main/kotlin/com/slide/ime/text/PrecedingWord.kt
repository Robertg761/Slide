package com.slide.ime.text

/**
 * Finds the word before the one being typed, given the text behind the cursor.
 *
 * Split out from the service because it is pure string work with more edge cases than it looks —
 * a cursor inside a reopened word, a run of punctuation, the start of a field, the end of the
 * previous sentence — and because getting it wrong degrades corrections silently rather than
 * visibly. Nothing here touches Android, so it can be tested directly.
 */
object PrecedingWord {

    data class Context(val older: String?, val previous: String?)

    /** Matches the corrector's notion of a word, including decomposed letters and smart apostrophes. */
    private fun isWordCharacter(codePoint: Int): Boolean =
        Character.isLetter(codePoint) || isCombiningMark(codePoint) || isApostrophe(codePoint)

    private fun isWordCore(codePoint: Int): Boolean =
        Character.isLetter(codePoint) || isCombiningMark(codePoint)

    /**
     * The word before the one currently being typed.
     *
     * @param before the text immediately behind the cursor, ending with however much of the word in
     *   progress lies behind it. That trailing fragment is skipped by walking back over it rather
     *   than by subtracting a known length: the composing region can extend past the cursor when
     *   the user has tapped back into a finished word, so its length is not how much sits behind.
     */
    fun of(before: String): String? {
        var cursor = before.length
        while (cursor > 0) {
            val codePoint = Character.codePointBefore(before, cursor)
            if (!isWordCharacter(codePoint)) break
            cursor -= Character.charCount(codePoint)
        }
        return wordEndingAt(before, cursor)
    }

    /**
     * The word before a word not yet begun — a swipe, which commits whole.
     *
     * Distinct from [of] because there is no fragment in front of the cursor to step over, so
     * stepping over one anyway would return the word before last. With the cursor sitting straight
     * after "I like", the swipe that follows lands after "like", and "like" is what predicts it.
     */
    fun beforeNewWord(before: String): String? = wordEndingAt(before, before.length)

    /** The two words before the fragment currently being typed, without crossing a sentence. */
    fun contextOf(before: String): Context {
        var cursor = before.length
        while (cursor > 0) {
            val codePoint = Character.codePointBefore(before, cursor)
            if (!isWordCharacter(codePoint)) break
            cursor -= Character.charCount(codePoint)
        }
        return contextEndingAt(before, cursor)
    }

    /** The two words before a whole-word input such as a swipe or next-word prediction. */
    fun contextBeforeNewWord(before: String): Context = contextEndingAt(before, before.length)

    private fun contextEndingAt(before: String, from: Int): Context {
        var cursor = from

        fun previousWord(): String? {
            while (cursor > 0) {
                val codePoint = Character.codePointBefore(before, cursor)
                if (isWordCore(codePoint)) break
                if (isSentenceBoundary(codePoint)) return null
                cursor -= Character.charCount(codePoint)
            }
            val end = cursor
            while (cursor > 0) {
                val codePoint = Character.codePointBefore(before, cursor)
                if (!isWordCharacter(codePoint)) break
                cursor -= Character.charCount(codePoint)
            }
            return before.substring(trimLeadingApostrophes(before, cursor, end), end)
                .takeIf { word -> word.codePoints().anyMatch(Character::isLetter) }
        }

        val previous = previousWord() ?: return Context(null, null)
        val older = previousWord()
        return Context(older, previous)
    }

    /**
     * Walks back from [from] over any separators and returns the word before them.
     *
     * @return null if there is none, or if reaching it would cross a sentence boundary. Bigrams
     *   were only ever counted within a sentence, so the last word of the previous one is not
     *   evidence about this one — it is a word that merely happens to be nearby.
     */
    private fun wordEndingAt(before: String, from: Int): String? {
        // Walk back to the last *letter*, not the last word character. An apostrophe is part of a
        // word only where a word runs into it; on its own it is an opening quote or a contraction
        // not yet typed, and returning "'" as the preceding word hands the decoder a bogus context
        // and learns a bigram keyed on punctuation.
        var cursor = from
        while (cursor > 0) {
            val codePoint = Character.codePointBefore(before, cursor)
            if (isWordCore(codePoint)) break
            if (isSentenceBoundary(codePoint)) return null
            cursor -= Character.charCount(codePoint)
        }

        val end = cursor
        while (cursor > 0) {
            val codePoint = Character.codePointBefore(before, cursor)
            if (!isWordCharacter(codePoint)) break
            cursor -= Character.charCount(codePoint)
        }
        return before.substring(trimLeadingApostrophes(before, cursor, end), end)
            .takeIf { word -> word.codePoints().anyMatch(Character::isLetter) }
    }

    private fun trimLeadingApostrophes(text: String, start: Int, end: Int): Int {
        var cursor = start
        while (cursor < end) {
            val codePoint = Character.codePointAt(text, cursor)
            if (!isApostrophe(codePoint)) break
            cursor += Character.charCount(codePoint)
        }
        return cursor
    }

    private fun isApostrophe(codePoint: Int): Boolean = codePoint in APOSTROPHES

    private fun isCombiningMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> false
    }

    private fun isSentenceBoundary(codePoint: Int): Boolean =
        codePoint in SENTENCE_ENDS ||
            codePoint == '\n'.code || codePoint == '\r'.code ||
            codePoint == LINE_SEPARATOR || codePoint == PARAGRAPH_SEPARATOR

    private val APOSTROPHES = setOf('\''.code, '\u2019'.code, '\u02bc'.code, '\uff07'.code)
    private val SENTENCE_ENDS = setOf(
        '.'.code, '!'.code, '?'.code, '…'.code,
        '。'.code, '！'.code, '？'.code, '؟'.code, '।'.code, '॥'.code,
    )
    private const val LINE_SEPARATOR = 0x2028
    private const val PARAGRAPH_SEPARATOR = 0x2029
}
