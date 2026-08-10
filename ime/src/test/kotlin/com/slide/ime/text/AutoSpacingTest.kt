package com.slide.ime.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSpacingTest {

    @Test
    fun `closing punctuation separates the next word`() {
        for (punctuation in listOf(',', '.', '!', '?', ';', ':', '…')) {
            assertTrue("$punctuation should separate words", AutoSpacing.beforeWord(punctuation))
        }
    }

    @Test
    fun `existing separators and word-internal marks are left alone`() {
        for (character in listOf<Char?>(null, ' ', '\n', '\'', '-', '/', 'a', '7')) {
            assertFalse("$character should not add a space", AutoSpacing.beforeWord(character))
        }
    }
}
