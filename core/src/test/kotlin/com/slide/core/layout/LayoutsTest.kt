package com.slide.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutsTest {

    @Test
    fun `number row is limited to compatible alphabetic layouts`() {
        assertEquals(Layouts.QwertyEn.rows.size + 1, Layouts.withNumberRow(Layouts.QwertyEn, true).rows.size)
        assertEquals(Layouts.EmailEn.rows.size + 1, Layouts.withNumberRow(Layouts.EmailEn, true).rows.size)
        assertEquals(Layouts.UriEn.rows.size + 1, Layouts.withNumberRow(Layouts.UriEn, true).rows.size)
        assertSame(Layouts.SymbolsEn, Layouts.withNumberRow(Layouts.SymbolsEn, true))
        assertSame(Layouts.SymbolsAltEn, Layouts.withNumberRow(Layouts.SymbolsAltEn, true))
        assertSame(Layouts.NumberPad, Layouts.withNumberRow(Layouts.NumberPad, true))
    }

    @Test
    fun `specialized text layouts expose their primary delimiters`() {
        assertTrue(outputs(Layouts.EmailEn).containsAll(listOf("@", ".", ".com")))
        assertTrue(outputs(Layouts.UriEn).containsAll(listOf("/", ".", ".com")))
        assertTrue(Layouts.EmailEn.supportsNumberRow)
        assertTrue(Layouts.UriEn.supportsNumberRow)
    }

    @Test
    fun `every numeric pad retains delete and editor action keys`() {
        for (layout in numericLayouts()) {
            val types = layout.rows.flatMap { row -> row.keys.map(Key::type) }
            assertTrue("${layout.id} lacks Delete", KeyType.DELETE in types)
            assertTrue("${layout.id} lacks Enter", KeyType.ENTER in types)
            assertFalse(layout.supportsNumberRow)
        }
    }

    @Test
    fun `numeric affordances match signed decimal and phone modes`() {
        assertFalse("-" in outputs(Layouts.NumberPad))
        assertFalse("." in outputs(Layouts.NumberPad))
        assertTrue("-" in outputs(Layouts.SignedNumberPad))
        assertTrue("." in outputs(Layouts.DecimalPad))
        assertTrue(outputs(Layouts.SignedDecimalPad).containsAll(listOf("-", ".")))
        assertTrue(outputs(Layouts.PhonePad).containsAll(listOf("*", "#")))
        val phoneZero = Layouts.PhonePad.rows.flatMap(KeyRow::keys).first { it.outputText == "0" }
        assertTrue("+" in phoneZero.alternates)
    }

    @Test
    fun `date and time pads expose required separators and meridiems`() {
        assertTrue(outputs(Layouts.DatePad).containsAll(listOf("/", "-", ".")))
        assertTrue(outputs(Layouts.TimePad).containsAll(listOf(":", ".", "AM", "PM")))
        assertTrue(
            outputs(Layouts.DateTimePad).containsAll(listOf("/", "-", ":", ".", "AM", "PM")),
        )
    }

    @Test
    fun `IME switcher is conditional unique and preserves bottom row width`() {
        for (layout in listOf(Layouts.QwertyEn, Layouts.SymbolsEn) + numericLayouts()) {
            assertSame(layout, Layouts.withImeSwitcher(layout, false))
            val switched = Layouts.withImeSwitcher(layout, true)
            assertEquals(
                "${layout.id} must expose exactly one switch key",
                1,
                switched.rows.sumOf { row -> row.keys.count { it.type == KeyType.GLOBE } },
            )
            assertEquals(
                layout.rows.last().totalWeight,
                switched.rows.last().totalWeight,
                0.0001f,
            )
            assertSame(switched, Layouts.withImeSwitcher(switched, true))
        }
    }

    private fun outputs(layout: KeyboardLayout): Set<String> =
        layout.rows.flatMap(KeyRow::keys).map(Key::outputText).toSet()

    private fun numericLayouts() = listOf(
        Layouts.NumberPad,
        Layouts.SignedNumberPad,
        Layouts.DecimalPad,
        Layouts.SignedDecimalPad,
        Layouts.PhonePad,
        Layouts.DatePad,
        Layouts.TimePad,
        Layouts.DateTimePad,
    )
}
