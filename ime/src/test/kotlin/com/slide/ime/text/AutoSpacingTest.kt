package com.slide.ime.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSpacingTest {

    @Test
    fun `closing punctuation separates the next word`() {
        for (punctuation in listOf(",", ".", "!", "?", ";", ":", "…", "]", "}", "”")) {
            assertTrue("$punctuation should separate words", AutoSpacing.beforeWord(punctuation))
        }
    }

    @Test
    fun `existing separators and word-internal marks are left alone`() {
        for (context in listOf<String?>(null, " ", "\n", "(", "[", "{", "“", "-", "/", "@", "#", "\$", "+")) {
            assertFalse("$context should not add a space", AutoSpacing.beforeWord(context))
        }
    }

    @Test
    fun `letters numbers emoji and supplementary symbols separate a whole word`() {
        for (context in listOf("a", "7", "cafe\u0301", "🙂", "𝐀")) {
            assertTrue("$context should separate words", AutoSpacing.beforeWord(context))
        }
    }

    @Test
    fun `ambiguous quotes use context and apostrophes can continue typed contractions`() {
        assertFalse(AutoSpacing.beforeWord("he said \""))
        assertTrue(AutoSpacing.beforeWord("\"hello\""))
        assertFalse(AutoSpacing.beforeWord("don'", typingAfterApostrophe = true))
        assertFalse(AutoSpacing.beforeWord("don’", typingAfterApostrophe = true))
        assertTrue(AutoSpacing.beforeWord("‘hello’"))
        assertTrue(AutoSpacing.beforeWord("‘hello’", typingAfterApostrophe = true))
        assertTrue(AutoSpacing.beforeWord("‘don’t stop’", typingAfterApostrophe = true))
    }

    @Test
    fun `literal character at a word edge continues it while whole-word input separates`() {
        assertFalse(AutoSpacing.beforeWord("hello", continuingTypedWord = true))
        assertFalse(AutoSpacing.beforeWord("version2", continuingTypedWord = true))
        assertTrue(AutoSpacing.beforeWord("hello"))
    }

    @Test
    fun `typing straight after a swiped word starts a new word with a space`() {
        assertTrue(AutoSpacing.beforeTypedWord("hello", swipedWordBehindCursor = "hello"))
        // Mid-sentence swipes commit their own leading space; the record includes it.
        assertTrue(AutoSpacing.beforeTypedWord("hi hello", swipedWordBehindCursor = " hello"))
    }

    @Test
    fun `a swipe record that no longer matches the editor text continues the word instead`() {
        // The user edited after the swipe (deleted a letter): back to extending the word.
        assertFalse(AutoSpacing.beforeTypedWord("hell", swipedWordBehindCursor = "hello"))
        assertFalse(AutoSpacing.beforeTypedWord("hello"))
        assertFalse(AutoSpacing.beforeTypedWord("hello", swipedWordBehindCursor = null))
    }

    @Test
    fun `typed contractions and fresh separators are unaffected by a swipe record`() {
        assertFalse(AutoSpacing.beforeTypedWord("don'", swipedWordBehindCursor = "world"))
        assertFalse(AutoSpacing.beforeTypedWord("hello ", swipedWordBehindCursor = "hello"))
        assertTrue(AutoSpacing.beforeTypedWord("hello,"))
        assertFalse(AutoSpacing.beforeTypedWord("hello, "))
        assertFalse(AutoSpacing.beforeTypedWord(null, swipedWordBehindCursor = "hello"))
    }
}
