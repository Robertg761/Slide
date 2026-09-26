package com.slide.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputMethodSelectionTest {
    @Test
    fun `the installed package recognizes its selected input method`() {
        assertTrue(inputMethodBelongsToPackage("com.slide/com.slide.ime.SlideInputMethodService", "com.slide"))
        assertTrue(inputMethodBelongsToPackage("com.slide/.ime.SlideInputMethodService", "com.slide"))
        assertTrue(inputMethodBelongsToPackage("com.slide.debug/com.slide.ime.SlideInputMethodService", "com.slide.debug"))
    }

    @Test
    fun `debug and release keyboards are distinct selections`() {
        assertFalse(inputMethodBelongsToPackage("com.slide.debug/com.slide.ime.SlideInputMethodService", "com.slide"))
        assertFalse(inputMethodBelongsToPackage("com.slide/com.slide.ime.SlideInputMethodService", "com.slide.debug"))
        assertFalse(inputMethodBelongsToPackage("com.slideshow/.Keyboard", "com.slide"))
    }

    @Test
    fun `missing or unstructured input method is not selected`() {
        for (inputMethodId in listOf(null, "", "com.slide")) {
            assertFalse(inputMethodBelongsToPackage(inputMethodId, "com.slide"))
        }
    }
}
