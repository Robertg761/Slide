package com.slide.ime.text

/** Tracks consecutive committed spaces, using the key's monotonic press time. */
internal class DoubleSpacePeriod(private val windowMs: Long) {
    private var previousSpaceMs: Long? = null

    fun recordSpace(pressedAtMs: Long) {
        previousSpaceMs = pressedAtMs
    }

    fun reset() {
        previousSpaceMs = null
    }

    fun isWithinWindow(pressedAtMs: Long): Boolean {
        val previous = previousSpaceMs ?: return false
        return pressedAtMs - previous in 0 until windowMs
    }

    fun shouldReplace(
        pressedAtMs: Long,
        allowsAutomaticPunctuation: Boolean,
        hasSelection: Boolean,
        textBeforeCursor: CharSequence?,
    ): Boolean {
        if (!allowsAutomaticPunctuation || hasSelection || !isWithinWindow(pressedAtMs)) return false
        val before = textBeforeCursor ?: return false
        if (before.length < 2 || before.last() != ' ') return false

        var cursor = before.length - 1
        while (cursor > 0) {
            val codePoint = Character.codePointBefore(before, cursor)
            if (Character.isLetterOrDigit(codePoint)) return true
            if (Character.getType(codePoint) !in COMBINING_MARK_TYPES) return false
            cursor -= Character.charCount(codePoint)
        }
        return false
    }

    private companion object {
        val COMBINING_MARK_TYPES = setOf(
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
        )
    }
}
