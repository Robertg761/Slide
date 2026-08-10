package com.slide.core.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeSelectionTest {

    private val known = setOf("light", "dark", "cobalt")

    @Test
    fun `explicit preset selection wins over system mode`() {
        assertEquals("light", ThemeSelection.presetId("light", known, true, true))
        assertEquals("dark", ThemeSelection.presetId("dark", known, false, true))
        assertEquals("cobalt", ThemeSelection.presetId("cobalt", known, false, true))
    }

    @Test
    fun `legacy follow-system only controls unknown-id fallback`() {
        assertEquals("dark", ThemeSelection.presetId("missing", known, true, true))
        assertEquals("light", ThemeSelection.presetId("missing", known, true, false))
    }
}
