package com.slide.ime.text

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleSpacePeriodTest {
    private val period = DoubleSpacePeriod(windowMs = 800)

    @Test
    fun `existing trailing space does not count as a first press`() {
        assertFalse(shouldReplace(100, "word "))
    }

    @Test
    fun `two committed spaces within the window punctuate a word`() {
        period.recordSpace(100)
        assertTrue(shouldReplace(101, "word "))
        assertTrue(shouldReplace(899, "word "))
        assertFalse(shouldReplace(900, "word "))
    }

    @Test
    fun `timestamps before the previous press cannot form a double space`() {
        period.recordSpace(1_000)
        assertFalse(shouldReplace(999, "word "))
    }

    @Test
    fun `an intervening edit or editor transition cancels the first space`() {
        period.recordSpace(100)
        period.reset()
        assertFalse(shouldReplace(200, "word "))
        period.recordSpace(200)
        assertTrue(shouldReplace(300, "word "))
    }

    @Test
    fun `password and literal fields preserve the spaces the user types`() {
        period.recordSpace(100)
        for (inputType in listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        )) {
            assertFalse(period.shouldReplace(
                pressedAtMs = 200,
                allowsAutomaticPunctuation = EditorInputPolicy.from(inputType).allowsSuggestions,
                hasSelection = false,
                textBeforeCursor = "secret ",
            ))
        }
    }

    @Test
    fun `selected text is never treated as a space replacement`() {
        period.recordSpace(100)
        assertFalse(period.shouldReplace(
            pressedAtMs = 200,
            allowsAutomaticPunctuation = true,
            hasSelection = true,
            textBeforeCursor = "word ",
        ))
    }

    @Test
    fun `decomposed letters and supplementary letters are whole word endings`() {
        period.recordSpace(100)
        assertTrue(shouldReplace(200, "cafe\u0301 "))
        assertTrue(shouldReplace(200, "\uD801\uDC00 "))
        assertTrue(shouldReplace(200, "123 "))
    }

    @Test
    fun `punctuation whitespace and unknown context are left alone`() {
        period.recordSpace(100)
        for (context in listOf(null, "", " ", "word  ", "word. ", "word\n ", "\u0301 ", "🙂 ")) {
            assertFalse(shouldReplace(200, context))
        }
    }

    private fun shouldReplace(pressedAtMs: Long, context: String?): Boolean = period.shouldReplace(
        pressedAtMs = pressedAtMs,
        allowsAutomaticPunctuation = true,
        hasSelection = false,
        textBeforeCursor = context,
    )
}
