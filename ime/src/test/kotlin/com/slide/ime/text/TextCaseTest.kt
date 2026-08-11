package com.slide.ime.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCaseTest {

    @Test
    fun `candidate follows capitalization of typed text`() {
        assertEquals("the", matchTypedCase("teh", "the"))
        assertEquals("The", matchTypedCase("Teh", "the"))
        assertEquals("THE", matchTypedCase("TEH", "the"))
    }

    @Test
    fun `verbatim typed candidate keeps its mixed case`() {
        assertEquals("iPhone", matchTypedCase("iPhone", "iPhone"))
    }

    /**
     * The apostrophe is a composing word character, so a word can begin with one. Capitalisation is
     * a property of the first letter, exactly as the all-caps branch already treats it.
     */
    @Test
    fun `leading apostrophe does not hide the capital`() {
        assertEquals("Hello", matchTypedCase("'Hello", "hello"))
        assertEquals("Tis", matchTypedCase("'Tis", "tis"))
        assertEquals("hello", matchTypedCase("'hello", "hello"))
        assertEquals("HELLO", matchTypedCase("'HELLO", "hello"))
    }

    @Test
    fun `punctuation alone leaves the candidate as it is`() {
        assertEquals("the", matchTypedCase("''", "the"))
    }
}
