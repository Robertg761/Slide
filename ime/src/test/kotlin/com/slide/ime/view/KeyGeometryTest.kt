package com.slide.ime.view

import com.slide.core.layout.KeyType
import com.slide.core.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyGeometryTest {

    @Test
    fun `symbols layer resolves its own keys and cells`() {
        val letters = KeyGeometry.place(Layouts.QwertyEn, width = 1080f, contentHeight = 900f)
        val symbols = KeyGeometry.place(Layouts.SymbolsEn, width = 1080f, contentHeight = 900f)

        assertEquals(letters.size, symbols.size)
        assertTrue(letters.any { it.key.label == "q" })
        assertTrue(symbols.any { it.key.label == "1" })
        assertTrue(symbols.any { it.key.type == KeyType.ALPHA && it.key.label == "ABC" })
        assertNotEquals(
            letters.map { it.key.label },
            symbols.map { it.key.label },
        )
    }

    @Test
    fun `search-header offset keeps key cells below the header`() {
        val offset = 68f
        val keys = KeyGeometry.place(
            layout = Layouts.QwertyEn,
            width = 1080f,
            contentHeight = 700f,
            topOffset = offset,
        )

        assertEquals(offset, keys.minOf { it.top }, 0.001f)
        assertTrue(keys.all { it.bottom > offset })
    }

    @Test
    fun `row indent remains a forgiving touch target`() {
        val keys = KeyGeometry.place(Layouts.QwertyEn, width = 1_000f, contentHeight = 400f)
        val secondRow = keys.filter { it.row == 1 }
        val firstKey = secondRow.first()

        assertTrue(firstKey.left > 0f)
        assertSame(
            firstKey,
            KeyGeometry.hitTest(keys, firstKey.left / 2f, firstKey.centerY),
        )
    }

    @Test
    fun `blank space below and above the key surface does not select an edge key`() {
        val keys = KeyGeometry.place(
            layout = Layouts.QwertyEn,
            width = 1_000f,
            contentHeight = 400f,
            topOffset = 20f,
        )

        assertNull(KeyGeometry.hitTest(keys, 500f, 19.999f))
        assertNull(KeyGeometry.hitTest(keys, 500f, keys.maxOf { it.bottom } + 0.001f))
    }

    @Test
    fun `far horizontal touch does not snap to an edge key`() {
        val keys = KeyGeometry.place(Layouts.QwertyEn, width = 1_000f, contentHeight = 400f)
        val row = keys.filter { it.row == 1 }
        val last = row.last()

        assertNull(KeyGeometry.hitTest(keys, last.right + last.width, last.centerY))
    }
}
