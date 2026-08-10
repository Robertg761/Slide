package com.slide.engine.suggest

import com.slide.engine.HeldOutSentences
import com.slide.engine.TestBigrams
import com.slide.engine.TestLexicon
import com.slide.engine.lexicon.UserBigrams
import com.slide.engine.lexicon.UserDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offering a word before anything has been typed.
 *
 * A different question from correction, and judged differently. Correction is measured on whether
 * it gets the word right, because it is going to change the text either way. A prediction changes
 * nothing unless it is tapped, so what matters is how often it saves a word — and, just as much,
 * that it stays quiet when it has nothing useful to say.
 */
class PredictionTest {

    private val lexicon = TestLexicon.instance
    private val suggester = TypingSuggester(lexicon, bigrams = TestBigrams.instance)

    @Test
    fun `predicts the ordinary continuations of a common word`() {
        for (word in listOf("of", "in", "thank", "good")) {
            val predicted = suggester.predict(word)
            println("'$word ...' -> $predicted")
            assertTrue("nothing predicted after '$word'", predicted.isNotEmpty())
            assertTrue("too many predicted after '$word'", predicted.size <= 3)
            assertEquals("repeated itself after '$word'", predicted.distinct().size, predicted.size)
        }
    }

    @Test
    fun `says nothing when it has nothing to say`() {
        assertTrue(suggester.predict(null).isEmpty())
        assertTrue(suggester.predict("").isEmpty())
        assertTrue(suggester.predict("zzqxwv").isEmpty())
        assertTrue(suggester.predict("of", limit = 0).isEmpty())
    }

    /**
     * How often the next word would have been one tap away.
     *
     * Measured over held-out sentences, so this is prediction from the corpus alone with nothing
     * personal to help it — a floor rather than what a keyboard settled into someone's habits
     * would manage.
     */
    @Test
    fun `saves a word often enough to be worth the space`() {
        var offered = 0
        var hit = 0
        for (sentence in HeldOutSentences.instance.take(4000)) {
            val tokens = Regex("[a-z']+").findAll(sentence.lowercase()).map { it.value }.toList()
            for (i in 1 until tokens.size) {
                if (lexicon.indexOf(tokens[i - 1]) < 0) continue
                val predicted = suggester.predict(tokens[i - 1])
                if (predicted.isEmpty()) continue
                offered++
                if (predicted.any { it.equals(tokens[i], ignoreCase = true) }) hit++
            }
        }
        val rate = hit.toDouble() / offered
        println("predicted at $offered word boundaries, right %.1f%% of the time".format(rate * 100))

        // A third of a keyboard's suggestions being useful is a good strip. This floor is well
        // under the measured rate; it exists to catch prediction breaking, not to pin it.
        assertTrue("only %.1f%% of predictions were the next word".format(rate * 100), rate > 0.12)
    }

    /** A habit beats the average: what this person writes outranks what English writes. */
    @Test
    fun `personal habits lead the prediction`() {
        val pairs = UserBigrams()
        val personal = TypingSuggester(
            lexicon,
            bigrams = TestBigrams.instance,
            userDictionary = UserDictionary(),
            userBigrams = pairs,
        )

        val ordinary = personal.predict("thank")
        repeat(9) { pairs.learn("thank", "goodness") }
        val adapted = personal.predict("thank")

        println("'thank ...' ordinary $ordinary, adapted $adapted")
        assertEquals("goodness", adapted.first())
        assertTrue("the corpus was pushed out entirely", adapted.size > 1)
        assertTrue("nothing actually changed", adapted != ordinary)
    }

    @Test
    fun `personal prediction keeps its learned surface casing`() {
        val pairs = UserBigrams()
        val personal = TypingSuggester(lexicon, bigrams = TestBigrams.instance, userBigrams = pairs)
        repeat(8) { pairs.learn("Sam", "Whitmore") }
        pairs.learn("SAM", "WHITMORE")

        assertEquals("Whitmore", personal.predict("sam").first())
        // Identity remains case-insensitive even though presentation is not flattened.
        assertEquals("Whitmore", personal.predict("SAM").first())
        assertTrue(
            "one shifted pair replaced established casing: ${pairs.entries()}",
            pairs.entries().any { (previous, next, _) -> previous == "Sam" && next == "Whitmore" },
        )
    }

    /** Offensive words are withheld from a suggestion nobody asked for, as everywhere else. */
    @Test
    fun `withholds offensive words unless asked for them`() {
        val offensive = (0 until lexicon.size)
            .filter { lexicon.isOffensive(it) }
            .map { lexicon.lowercaseAt(it) }
            .toSet()

        var leaked = 0
        for (index in 0 until lexicon.size step 211) {
            val word = lexicon.lowercaseAt(index)
            if (suggester.predict(word).any { it.lowercase() in offensive }) leaked++
        }
        assertEquals("offensive words reached the prediction strip", 0, leaked)
    }

    @Test
    fun `personal habits cannot bypass the offensive filter`() {
        val offensiveIndex = (0 until lexicon.size).first { index ->
            val word = lexicon.lowercaseAt(index)
            lexicon.isOffensive(index) && word.length <= 28 &&
                word.all { it in 'a'..'z' || it == '\'' }
        }
        val offensive = lexicon.lowercaseAt(offensiveIndex)
        val pairs = UserBigrams()
        repeat(8) { pairs.learn("hello", offensive) }
        val personal = TypingSuggester(lexicon, bigrams = TestBigrams.instance, userBigrams = pairs)

        assertTrue(
            "'$offensive' leaked from the personal model",
            personal.predict("hello", blockOffensive = true)
                .none { it.equals(offensive, ignoreCase = true) },
        )
        assertEquals(
            "the learned pair did not become available when the filter was disabled",
            offensive,
            personal.predict("hello", blockOffensive = false).first().lowercase(),
        )
    }
}
