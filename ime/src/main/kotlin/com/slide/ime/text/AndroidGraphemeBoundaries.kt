package com.slide.ime.text

import android.icu.text.BreakIterator
import java.util.Locale

/** Full Unicode grapheme navigation backed by the ICU version shipped on the device. */
internal object AndroidGraphemeBoundaries {

    fun previousBoundary(text: CharSequence, index: Int): Int {
        val limit = index.coerceIn(0, text.length)
        if (limit == 0) return 0
        return runCatching {
            iterator(text).preceding(limit).takeUnless { it == BreakIterator.DONE } ?: 0
        }.getOrElse { GraphemeClusters.previousBoundary(text, limit) }
    }

    fun nextBoundary(text: CharSequence, index: Int): Int {
        val start = index.coerceIn(0, text.length)
        if (start == text.length) return start
        return runCatching {
            iterator(text).following(start).takeUnless { it == BreakIterator.DONE } ?: text.length
        }.getOrElse { GraphemeClusters.nextBoundary(text, start) }
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

    private fun iterator(text: CharSequence): BreakIterator =
        BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text.toString()) }
}
