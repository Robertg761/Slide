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

    /** Past one of these, the preceding word belongs to a different sentence. */
    private const val SENTENCE_ENDS = ".!?\n"

    /** Matches the corrector's notion of a word: letters and the apostrophe. */
    private fun isWordCharacter(character: Char): Boolean =
        character.isLetter() || character == '\''

    /**
     * @param before the text immediately behind the cursor, ending with however much of the word in
     *   progress lies behind it. That trailing fragment is skipped by walking back over it rather
     *   than by subtracting a known length: the composing region can extend past the cursor when
     *   the user has tapped back into a finished word, so its length is not how much sits behind.
     * @return the preceding word, or null if there is none, or if reaching it would cross a
     *   sentence boundary. Bigrams were only ever counted within a sentence, so the last word of
     *   the previous one is not evidence about this one — it is a word that happens to be nearby.
     */
    fun of(before: String): String? {
        var cursor = before.length
        while (cursor > 0 && isWordCharacter(before[cursor - 1])) cursor--

        while (cursor > 0 && !isWordCharacter(before[cursor - 1])) {
            if (before[cursor - 1] in SENTENCE_ENDS) return null
            cursor--
        }

        val end = cursor
        while (cursor > 0 && isWordCharacter(before[cursor - 1])) cursor--
        return before.substring(cursor, end).takeIf { it.isNotEmpty() }
    }
}
