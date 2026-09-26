package com.slide.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardHistoryTextTest {
    @Test
    fun `missing and blank text is not retained`() {
        for (text in listOf(null, "", " \t\n")) assertNull(clipboardHistoryText(text))
    }

    @Test
    fun `clipboard text preserves whitespace and line breaks`() {
        val text = "  pasted\ntext\t "
        assertEquals(text, clipboardHistoryText(StringBuilder(text)))
    }

    @Test
    fun `clipboard limit accepts exactly the maximum length`() {
        val text = "a".repeat(10_000)
        assertEquals(text, clipboardHistoryText(text))
        assertNull(clipboardHistoryText(text + "a"))
    }

    @Test
    fun `oversized clipboard is rejected without reading or copying its contents`() {
        val oversized = object : CharSequence {
            override val length = 10_001
            override fun get(index: Int): Char = error("Must not scan oversized clipboard text")
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
                error("Must not copy oversized clipboard text")
            override fun toString(): String = error("Must not materialize oversized clipboard text")
        }
        assertNull(clipboardHistoryText(oversized))
    }
}
