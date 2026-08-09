package com.slide.engine.lexicon

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserDictionaryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dictionary = UserDictionary()

    private fun store(words: File = File(folder.root, "learned.txt")) =
        UserDictionaryStore(words, File(folder.root, "pairs.txt"))

    // region Learning

    /**
     * The rule that keeps a typo from being defended for ever. One occurrence is as likely to be a
     * slip as a word, so it is remembered and not yet believed.
     */
    @Test
    fun `a word typed once is not yet trusted`() {
        dictionary.learn("kubernetes")
        assertFalse(dictionary.isTrusted("kubernetes"))

        dictionary.learn("kubernetes")
        assertTrue(dictionary.isTrusted("kubernetes"))
    }

    @Test
    fun `a strong signal trusts a word at once`() {
        dictionary.learn("robertg", weight = 2)
        assertTrue(dictionary.isTrusted("robertg"))
    }

    @Test
    fun `is not case sensitive`() {
        dictionary.learn("Slide", weight = 2)
        assertTrue(dictionary.isTrusted("slide"))
        assertTrue(dictionary.isTrusted("SLIDE"))
    }

    @Test
    fun `refuses what is not a word`() {
        for (rubbish in listOf("", "a", "x1", "he!lo", "'''", "  ", "a".repeat(40))) {
            dictionary.learn(rubbish, weight = 9)
            assertFalse("learned '$rubbish'", dictionary.isTrusted(rubbish))
        }
    }

    @Test
    fun `forgets on request`() {
        dictionary.learn("mistayk", weight = 9)
        assertTrue(dictionary.isTrusted("mistayk"))

        assertTrue(dictionary.forget("mistayk"))
        assertFalse(dictionary.isTrusted("mistayk"))
        assertEquals(0, dictionary.countOf("mistayk"))
    }

    // endregion

    // region Completions

    @Test
    fun `completes a trusted word`() {
        dictionary.learn("kubernetes", weight = 3)
        assertEquals(listOf("kubernetes"), dictionary.completions("kube", limit = 3))
    }

    /** Offering a word seen once would put the user's own typos in the strip. */
    @Test
    fun `withholds a word it does not yet trust`() {
        dictionary.learn("teh")
        assertTrue(dictionary.completions("te", limit = 3).isEmpty())
    }

    @Test
    fun `offers the most used first`() {
        dictionary.learn("kubernetes", weight = 3)
        dictionary.learn("kubectl", weight = 30)
        assertEquals(listOf("kubectl", "kubernetes"), dictionary.completions("kub", limit = 3))
    }

    /** The word itself is not a completion of itself; the strip already shows what was typed. */
    @Test
    fun `does not offer the prefix back`() {
        dictionary.learn("kubectl", weight = 3)
        assertTrue(dictionary.completions("kubectl", limit = 3).isEmpty())
    }

    // endregion

    @Test
    fun `stays inside its capacity, keeping what is used most`() {
        val small = UserDictionary(capacity = 100)
        for (i in 0 until 500) small.learn("word$i".filter(Char::isLetter) + "abcdefgh".take(i % 8 + 2))
        small.learn("important", weight = 200)

        assertTrue("grew to ${small.size}", small.size <= 100)
        assertTrue("dropped the most used word", small.isTrusted("important"))
    }

    // region Persistence

    @Test
    fun `survives a round trip through the file`() {
        val store = store()
        dictionary.learn("kubectl", weight = 5)
        dictionary.learn("robertg", weight = 2)
        dictionary.learn("seenonce")
        store.save(dictionary)

        val restored = UserDictionary()
        store.load(restored)

        assertEquals(5, restored.countOf("kubectl"))
        assertTrue(restored.isTrusted("robertg"))
        // Counts below the threshold survive too: the second sighting should still trust the word,
        // not start again from nothing because the keyboard was closed in between.
        assertEquals(1, restored.countOf("seenonce"))
        assertFalse(restored.isTrusted("seenonce"))
    }

    @Test
    fun `survives a corrupt file rather than refusing to type`() {
        val file = File(folder.root, "learned.txt")
        file.writeText("kubectl\t5\ngarbage-with-no-count\n\tnope\nrobertg\tnotanumber\nfine\t3\n")

        val restored = UserDictionary()
        store(file).load(restored)

        assertEquals(5, restored.countOf("kubectl"))
        assertEquals(3, restored.countOf("fine"))
        assertEquals(0, restored.countOf("robertg"))
    }

    @Test
    fun `loading a file that is not there leaves an empty dictionary`() {
        val restored = UserDictionary()
        store(File(folder.root, "absent.txt")).load(restored)
        assertEquals(0, restored.size)
    }

    @Test
    fun `pairs survive a round trip through their own file`() {
        val store = store()
        val pairs = UserBigrams()
        repeat(5) { pairs.learn("kubectl", "apply") }
        pairs.learn("sam", "whitmore")
        store.save(pairs)

        val restored = UserBigrams()
        store.load(restored)

        assertEquals(5, restored.successorsOf("kubectl")["apply"])
        assertEquals(1, restored.successorsOf("sam")["whitmore"])
    }

    @Test
    fun `a corrupt pair file does not stop the words loading`() {
        val words = File(folder.root, "learned.txt")
        val pairFile = File(folder.root, "pairs.txt")
        words.writeText("kubectl\t5\n")
        pairFile.writeText("only-two\tfields\nkubectl\tapply\tnotanumber\ngood\tpair\t4\n")

        val restoredWords = UserDictionary()
        val restoredPairs = UserBigrams()
        store(words).load(restoredWords)
        store(words).load(restoredPairs)

        assertEquals(5, restoredWords.countOf("kubectl"))
        assertEquals(4, restoredPairs.successorsOf("good")["pair"])
        assertEquals(1, restoredPairs.size)
    }

    // endregion
}
