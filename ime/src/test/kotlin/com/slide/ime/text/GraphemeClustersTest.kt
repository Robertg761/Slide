package com.slide.ime.text

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphemeClustersTest {

    @Test
    fun `previous boundary keeps emoji sequences intact`() {
        for (emoji in listOf("👍🏽", "👨‍👩‍👧‍👦", "🇨🇦", "1️⃣", "❤️")) {
            assertEquals(0, GraphemeClusters.previousBoundary(emoji, emoji.length))
        }
    }

    @Test
    fun `combining mark stays with its base`() {
        val text = "xe\u0301y"
        assertEquals(1, GraphemeClusters.previousBoundary(text, 3))
        assertEquals(3, GraphemeClusters.nextBoundary(text, 1))
    }

    @Test
    fun `movement counts visible clusters rather than utf16 units`() {
        val text = "a👨‍👩‍👧‍👦🇨🇦b"
        val afterFamily = GraphemeClusters.move(text, 1, 1)
        val afterFlag = GraphemeClusters.move(text, afterFamily, 1)

        assertEquals(1, GraphemeClusters.move(text, afterFamily, -1))
        assertEquals(text.length - 1, afterFlag)
        assertEquals(text.length, GraphemeClusters.move(text, afterFlag, 1))
    }

    @Test
    fun `crlf is a single cursor stop`() {
        assertEquals(3, GraphemeClusters.nextBoundary("a\r\nb", 1))
        assertEquals(1, GraphemeClusters.previousBoundary("a\r\nb", 3))
    }
}
