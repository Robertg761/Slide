package com.slide.ime.view

import android.graphics.Paint
import com.slide.core.emoji.EmojiData

/**
 * Works out which emoji the device can actually draw.
 *
 * The catalogue is built from the current Unicode release, but the font on the phone is whatever
 * shipped with its Android version. On an older device the newest few hundred emoji render as
 * tofu — an empty box that says nothing about what it would have been, and that arrives in the
 * recipient's message as a character they cannot see either.
 *
 * Filtering costs one font lookup per entry, so it runs off the main thread and its result is
 * handed to [EmojiPanelView.renderable].
 */
object EmojiGlyphs {

    /**
     * Entry indices per category that [Paint.hasGlyph] accepts, in presentation order.
     *
     * A category that loses every entry still gets an empty array rather than being dropped: the
     * tab strip is built from the catalogue's categories, and a missing row would misalign it.
     */
    fun renderable(data: EmojiData): Array<IntArray> {
        val paint = Paint()
        return Array(data.categories.size) { category ->
            data.indicesIn(category).filter { paint.hasGlyph(data.emojiAt(it)) }.toIntArray()
        }
    }
}
