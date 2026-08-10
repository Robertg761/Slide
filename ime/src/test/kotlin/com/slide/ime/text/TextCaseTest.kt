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
}
