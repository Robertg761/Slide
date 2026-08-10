package com.slide.ime.view

import com.slide.core.layout.Key
import com.slide.core.layout.KeyType
import com.slide.core.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two symbols pages and the keys that move between them.
 *
 * The regression being pinned here is a layer key that asks for the layer already on screen: the
 * `=\<` key used to be typed as [KeyType.SYMBOLS], so pressing it on the symbols page requested the
 * symbols page, the layout setter saw no change, and the key looked broken.
 */
class SymbolLayersTest {

    private fun keysOf(layout: com.slide.core.layout.KeyboardLayout): List<Key> =
        layout.rows.flatMap { it.keys }

    @Test
    fun `each symbols page switches to the other one`() {
        val first = keysOf(Layouts.SymbolsEn)
        val second = keysOf(Layouts.SymbolsAltEn)

        // Page one offers the way forward, and nothing that asks for the page it is already on.
        assertTrue(first.any { it.type == KeyType.SYMBOLS_ALT })
        assertTrue(first.none { it.type == KeyType.SYMBOLS })

        // Page two offers the way back, and likewise.
        assertTrue(second.any { it.type == KeyType.SYMBOLS })
        assertTrue(second.none { it.type == KeyType.SYMBOLS_ALT })

        assertTrue(first.any { it.type == KeyType.ALPHA })
        assertTrue(second.any { it.type == KeyType.ALPHA })
    }

    @Test
    fun `the second page carries the characters the first one lacks`() {
        val first = keysOf(Layouts.SymbolsEn).map { it.outputText }
        val second = keysOf(Layouts.SymbolsAltEn).map { it.outputText }

        for (character in listOf("=", "/", "\\", "<", ">", "[", "]", "{", "}", "|", "^")) {
            assertTrue("$character is not reachable", character in second)
            assertTrue("$character duplicates page one", character !in first)
        }
    }

    @Test
    fun `both pages lay out over the same cells as the letters`() {
        val letters = KeyGeometry.place(Layouts.QwertyEn, width = 1080f, contentHeight = 900f)
        val second = KeyGeometry.place(Layouts.SymbolsAltEn, width = 1080f, contentHeight = 900f)

        assertEquals(letters.size, second.size)
        assertNotEquals(letters.map { it.key.label }, second.map { it.key.label })
        // Nothing on a symbols page may feed the gesture decoder; a swipe there has no letters to
        // spell with and would commit confident nonsense.
        assertTrue(second.none { it.key.gestureEligible })
    }

    @Test
    fun `common symbols are reachable from alpha long press`() {
        val alternates = keysOf(Layouts.QwertyEn).flatMap { it.alternates }.toSet()
        for (symbol in listOf("@", "#", "$", "%", "&", "_", "+", "(", ")", "*", "\"", "'", ":", ";", "!", "?")) {
            assertTrue("$symbol is not reachable from the letter layer", symbol in alternates)
        }
    }
}
