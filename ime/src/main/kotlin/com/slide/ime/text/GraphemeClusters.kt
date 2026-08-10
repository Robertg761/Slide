package com.slide.ime.text

/**
 * Allocation-free fallback boundary helper for the text immediately around the cursor.
 *
 * Android editors expose offsets as UTF-16 indices, but a user-visible emoji can contain several
 * code points (skin tone, variation selector, keycap or a ZWJ family), and flags are pairs of
 * regional indicators. These deliberately narrow rules cover the emoji sequences Slide must not
 * split plus combining marks and CRLF. Production uses Android ICU's full Unicode iterator first;
 * this remains the deterministic fallback and the JVM-testable contract for those critical cases.
 */
internal object GraphemeClusters {

    fun previousBoundary(text: CharSequence, index: Int): Int {
        val limit = index.coerceIn(0, text.length)
        if (limit == 0) return 0

        var boundary = 0
        var cursor = 0
        while (cursor < limit) {
            boundary = cursor
            cursor = nextBoundary(text, cursor)
        }
        return boundary
    }

    fun nextBoundary(text: CharSequence, index: Int): Int {
        var cursor = index.coerceIn(0, text.length)
        if (cursor == text.length) return cursor

        val first = codePointAt(text, cursor)
        cursor += Character.charCount(first)

        // Android and editors treat CRLF as one line break, so cursor/delete should too.
        if (first == '\r'.code && cursor < text.length && codePointAt(text, cursor) == '\n'.code) {
            return cursor + 1
        }

        // A flag is one pair. A longer run is grouped into pairs from its start.
        if (isRegionalIndicator(first) && cursor < text.length) {
            val next = codePointAt(text, cursor)
            if (isRegionalIndicator(next)) cursor += Character.charCount(next)
        }

        while (cursor < text.length) {
            val next = codePointAt(text, cursor)
            when {
                isExtend(next) -> cursor += Character.charCount(next)
                next == ZERO_WIDTH_JOINER && cursor + Character.charCount(next) < text.length -> {
                    cursor += Character.charCount(next)
                    val joined = codePointAt(text, cursor)
                    cursor += Character.charCount(joined)
                }
                else -> return cursor
            }
        }
        return cursor
    }

    fun move(text: CharSequence, index: Int, steps: Int): Int {
        var cursor = index.coerceIn(0, text.length)
        if (steps < 0) {
            repeat(-steps) { cursor = previousBoundary(text, cursor) }
        } else {
            repeat(steps) { cursor = nextBoundary(text, cursor) }
        }
        return cursor
    }

    private fun codePointAt(text: CharSequence, index: Int): Int =
        Character.codePointAt(text, index)

    private fun isExtend(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            codePoint in 0xFE00..0xFE0F ||
            codePoint in 0xE0100..0xE01EF ||
            codePoint in 0x1F3FB..0x1F3FF ||
            codePoint in 0xE0020..0xE007F
    }

    private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

    private const val ZERO_WIDTH_JOINER = 0x200D
}
