package com.slide.ime.view

import com.slide.core.layout.KeyType
import com.slide.core.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
