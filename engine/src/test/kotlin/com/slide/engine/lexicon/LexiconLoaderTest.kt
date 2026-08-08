package com.slide.engine.lexicon

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Reads the real shipped asset rather than a fixture.
 *
 * The packed format has two independent implementations -- the Python writer in
 * `tools/build_lexicon.py` and the Kotlin reader -- and nothing but this test stops them from
 * drifting apart. A silent disagreement would corrupt every word the decoder emits.
 */
class LexiconLoaderTest {

    private lateinit var lexicon: Lexicon

    @Before
    fun setUp() {
        val asset = File("src/main/assets/${LexiconLoader.ASSET_NAME}")
        assertTrue("Missing ${asset.absolutePath}; run tools/build_lexicon.py", asset.exists())
        lexicon = asset.inputStream().use(LexiconLoader::read)
    }

    @Test
    fun `decodes the whole word list`() {
        assertTrue("Suspiciously small lexicon: ${lexicon.size}", lexicon.size > 100_000)
    }

    @Test
    fun `words stay sorted and lowercase`() {
        var previous = ""
        for (index in 0 until lexicon.size) {
            val word = lexicon.lowercaseAt(index)
            assertTrue("Not sorted at $index: '$previous' then '$word'", previous < word)
            assertEquals("Not lowercase at $index: '$word'", word.lowercase(), word)
            previous = word
        }
    }

    @Test
    fun `front coding round trips common words`() {
        for (word in listOf("the", "hello", "keyboard", "swipe", "don't", "zebra")) {
            assertTrue("Missing '$word'", lexicon.contains(word))
        }
    }

    @Test
    fun `frequency ranks common words above rare ones`() {
        val the = lexicon.indexOf("the")
        val zygote = lexicon.indexOf("zygote")
        assertTrue(the >= 0 && zygote >= 0)
        assertTrue(lexicon.frequencyAt(the) > lexicon.frequencyAt(zygote))
    }

    @Test
    fun `capitalisation flag survives the round trip`() {
        val september = lexicon.indexOf("september")
        assertTrue(september >= 0)
        assertTrue("September should render capitalised", lexicon.isCapitalized(september))
        assertEquals("September", lexicon.wordAt(september))

        val the = lexicon.indexOf("the")
        assertFalse(lexicon.isCapitalized(the))
        assertEquals("the", lexicon.wordAt(the))
    }

    @Test
    fun `charAt and lengthAt agree with the decoded string`() {
        val index = lexicon.indexOf("keyboard")
        assertEquals(8, lexicon.lengthAt(index))
        assertEquals('k', lexicon.charAt(index, 0))
        assertEquals('d', lexicon.charAt(index, 7))
    }

    @Test
    fun `buckets hold exactly the words with that first and last letter`() {
        val bucket = lexicon.bucket('h', 'o')
        assertTrue("h..o bucket is empty", bucket.isNotEmpty())
        for (index in bucket) {
            val word = lexicon.lowercaseAt(index)
            assertTrue("'$word' does not belong in the h..o bucket", word.first() == 'h' && word.last() == 'o')
        }
        assertTrue("'hello' missing from its bucket", bucket.any { lexicon.lowercaseAt(it) == "hello" })
    }

    @Test
    fun `every alphabetic word is reachable through some bucket`() {
        var bucketed = 0
        for (first in 'a'..'z') {
            for (last in 'a'..'z') {
                bucketed += lexicon.bucket(first, last).size
            }
        }
        val alphabetic = (0 until lexicon.size).count { index ->
            val word = lexicon.lowercaseAt(index)
            word.first() in 'a'..'z' && word.last() in 'a'..'z'
        }
        assertEquals(alphabetic, bucketed)
    }

    @Test
    fun `unknown words are absent`() {
        assertFalse(lexicon.contains("qqzzxx"))
        assertEquals(-1, lexicon.indexOf("qqzzxx"))
    }
}
