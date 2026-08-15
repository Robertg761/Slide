package com.slide.ime.view

import com.slide.core.layout.Key
import com.slide.core.layout.KeyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeySurfaceStyleTest {
    @Test
    fun `borderless mode leaves letters and standalone action glyphs on the keyboard surface`() {
        assertFalse(KeySurfaceStyle.drawsSurface(KeyType.CHARACTER, showKeyBorders = false))
        assertFalse(KeySurfaceStyle.drawsSurface(KeyType.SHIFT, showKeyBorders = false))
        assertFalse(KeySurfaceStyle.drawsSurface(KeyType.DELETE, showKeyBorders = false))
        assertFalse(KeySurfaceStyle.drawsSurface(KeyType.EMOJI, showKeyBorders = false))
    }

    @Test
    fun `borderless mode retains compact surfaces for large targets`() {
        assertTrue(KeySurfaceStyle.drawsSurface(KeyType.SPACE, showKeyBorders = false))
        assertTrue(KeySurfaceStyle.drawsSurface(KeyType.ENTER, showKeyBorders = false))
        assertTrue(KeySurfaceStyle.drawsSurface(KeyType.SYMBOLS, showKeyBorders = false))
        assertTrue(KeySurfaceStyle.usesCompactSurface(KeyType.SPACE))
        assertTrue(KeySurfaceStyle.usesCompactSurface(KeyType.ENTER))
        assertFalse(KeySurfaceStyle.usesCompactSurface(KeyType.SHIFT))
    }

    @Test
    fun `bordered mode draws every keycap`() {
        KeyType.entries.forEach { type ->
            assertTrue("No surface for $type", KeySurfaceStyle.drawsSurface(type, true))
        }
    }

    @Test
    fun `number row swaps redundant digit hints for the popup's symbol`() {
        // With a dedicated digit row above, the key advertises its symbol alternate instead.
        assertEquals(
            "%",
            KeyHintStyle.visibleHint(
                Key("q", hint = "1", alternates = listOf("1", "%")),
                showNumberRow = true,
            ),
        )
        // Accented letters are popup entries, not hints; the first symbol wins.
        assertEquals(
            "|",
            KeyHintStyle.visibleHint(
                Key("e", hint = "3", alternates = listOf("3", "è", "é", "|")),
                showNumberRow = true,
            ),
        )
        assertEquals(null, KeyHintStyle.visibleHint(Key("q", hint = "1"), showNumberRow = true))
        assertEquals("@", KeyHintStyle.visibleHint(Key("a", hint = "@"), showNumberRow = true))
        assertEquals("1", KeyHintStyle.visibleHint(Key("q", hint = "1"), showNumberRow = false))
    }

    @Test
    fun `action icons stay compact and share one stroke`() {
        assertEquals(23.5f, ActionIconStyle.radius(width = 100f, height = 120f), 0.001f)
        assertEquals(4.95f, ActionIconStyle.strokeWidth(density = 3f), 0.001f)
    }
}
