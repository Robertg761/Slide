package com.slide.ime.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrecedingWordTest {

    @Test
    fun `finds the word before the one being typed`() {
        assertEquals("at", PrecedingWord.of("we arrive at ocn"))
        assertEquals("over", PrecedingWord.of("over the"))
    }

    /** Nothing has been typed of the new word yet; the cursor sits after the space. */
    @Test
    fun `finds it with the new word not yet begun`() {
        assertEquals("at", PrecedingWord.of("we arrive at "))
    }

    /**
     * A reopened word extends past the cursor, so only part of it lies behind. Walking back over
     * whatever letters are there handles that without needing to know how long the word is.
     */
    @Test
    fun `finds it with the cursor inside the word being typed`() {
        assertEquals("my", PrecedingWord.of("in my thr"))
    }

    @Test
    fun `is silent at the start of a field`() {
        assertNull(PrecedingWord.of(""))
        assertNull(PrecedingWord.of("hel"))
        assertNull(PrecedingWord.of("   "))
    }

    /**
     * The model only ever counted pairs within a sentence, so the last word of the previous one is
     * not evidence — it is a word that merely happens to be nearby.
     */
    @Test
    fun `is silent across a sentence boundary`() {
        assertNull(PrecedingWord.of("I went home. The"))
        assertNull(PrecedingWord.of("Really! Th"))
        assertNull(PrecedingWord.of("Are you sure? Ye"))
        assertNull(PrecedingWord.of("first line\nsec"))
    }

    @Test
    fun `steps over punctuation that does not end a sentence`() {
        assertEquals("hello", PrecedingWord.of("hello, wor"))
        assertEquals("one", PrecedingWord.of("one (tw"))
        assertEquals("yes", PrecedingWord.of("yes -- an"))
    }

    /** The apostrophe is part of a word, not a separator; "don't" is one word and a common one. */
    @Test
    fun `keeps an apostrophe inside the word`() {
        assertEquals("don't", PrecedingWord.of("don't hae"))
        assertEquals("it's", PrecedingWord.of("it's rainin"))
    }

    /**
     * A swipe commits a whole word, so there is no fragment in front of the cursor to step over.
     * Stepping over one anyway would hand the decoder the word before last.
     */
    @Test
    fun `before a swipe, takes the word the cursor sits after`() {
        assertEquals("like", PrecedingWord.beforeNewWord("I like"))
        assertEquals("like", PrecedingWord.beforeNewWord("I like "))
        assertEquals("don't", PrecedingWord.beforeNewWord("I don't "))
        assertNull(PrecedingWord.beforeNewWord(""))
        assertNull(PrecedingWord.beforeNewWord("I went home. "))
    }

    @Test
    fun `returns two-word context for typing and whole-word input`() {
        assertEquals(
            PrecedingWord.Context("we", "arrive"),
            PrecedingWord.contextOf("we arrive hom"),
        )
        assertEquals(
            PrecedingWord.Context("we", "arrive"),
            PrecedingWord.contextBeforeNewWord("we arrive "),
        )
        assertEquals(
            PrecedingWord.Context("arrive", "home"),
            PrecedingWord.contextBeforeNewWord("we arrive home"),
        )
    }

    @Test
    fun `two-word context stops at a sentence boundary`() {
        assertEquals(
            PrecedingWord.Context(null, "The"),
            PrecedingWord.contextOf("we arrived. The nex"),
        )
        assertEquals(
            PrecedingWord.Context(null, null),
            PrecedingWord.contextOf("we arrived. nex"),
        )
    }

    /** The two differ exactly where they should: one skips a word in progress, the other does not. */
    @Test
    fun `the typing and swipe lookups disagree only about the trailing word`() {
        assertEquals("I", PrecedingWord.of("I like"))
        assertEquals("like", PrecedingWord.beforeNewWord("I like"))
        // With nothing in progress they agree, because there is nothing to skip.
        assertEquals(PrecedingWord.of("I like "), PrecedingWord.beforeNewWord("I like "))
    }

    /**
     * An apostrophe is part of a word only where a word runs into it. On its own it is an opening
     * quote or a contraction not yet typed, and returning it feeds `'` to the decoder as the
     * previous word and learns a user bigram keyed on punctuation.
     */
    @Test
    fun `never returns a bare apostrophe as the preceding word`() {
        assertEquals("said", PrecedingWord.beforeNewWord("he said '"))
        assertEquals("said", PrecedingWord.beforeNewWord("he said ' "))
        assertEquals("said", PrecedingWord.of("he said 'wor"))
        assertEquals("x", PrecedingWord.beforeNewWord("x ''' "))
        assertNull(PrecedingWord.beforeNewWord("'"))
        assertNull(PrecedingWord.of("'wor"))
        assertNull(PrecedingWord.beforeNewWord("he said. '"))
    }

    @Test
    fun `a trailing apostrophe does not hide the word it follows`() {
        // Either an opening quote after the word or a contraction the user has only started.
        assertEquals("hello", PrecedingWord.beforeNewWord("hello'"))
        assertEquals(
            PrecedingWord.Context("we", "arrive"),
            PrecedingWord.contextBeforeNewWord("we arrive '"),
        )
    }

    @Test
    fun `handles a window that starts mid-word`() {
        // getTextBeforeCursor returns a fixed number of characters, so the earliest word in the
        // window is very often a fragment. Returning the fragment is right: it is either a real
        // word or one the lexicon will not know, and an unknown context is already handled.
        assertEquals("cellent", PrecedingWord.of("cellent wor"))
    }
}
