package com.slide.engine.gesture

import com.slide.engine.HeldOutSentences
import com.slide.engine.TestBigrams
import com.slide.engine.TestLexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the sentence buys the swipe decoder, on sentences the model was never trained on.
 *
 * `GestureDecoderTest` traces words in isolation, which is the only way to judge the geometry but
 * leaves the decoder nothing to break a tie with. Some ties cannot be broken any other way:
 * [GestureFixtures.tracesIdentically] documents the case, where a word's distinguishing letter
 * lies on the straight line between its neighbours, and "typing" and "topping" become the same
 * gesture. Frequency alone always answers the same whichever the user meant. The preceding word
 * does not.
 *
 * Traces are synthetic, so this measures what context adds *given* the decoder's existing view of
 * a swipe — not what a real thumb would do. That is the right comparison here, because both arms
 * see exactly the same traces.
 */
class ContextualDecodeTest {

    private val lexicon = TestLexicon.instance
    private val keys = GestureFixtures.qwerty()

    private val withContext = GestureDecoder(lexicon, bigrams = TestBigrams.instance)
    private val withoutContext = GestureDecoder(lexicon)

    private data class Case(val word: String, val previous: String)

    /** One swiped word per sentence, always with a real word in front of it. */
    private val cases: List<Case> by lazy {
        val out = ArrayList<Case>()
        for ((n, sentence) in HeldOutSentences.instance.withIndex()) {
            if (out.size >= MAX_CASES) break
            val tokens = Regex("[a-z]+").findAll(sentence.lowercase()).map { it.value }.toList()
            if (tokens.size < 3) continue

            val start = 1 + n % (tokens.size - 1)
            for (offset in tokens.indices) {
                val at = start + offset
                if (at !in 1 until tokens.size) continue
                val word = tokens[at]
                val previous = tokens[at - 1]
                // Two letters is too short to be a swipe at all, and the decoder refuses it.
                if (word.length !in 3..12) continue
                if (lexicon.indexOf(word) < 0 || lexicon.indexOf(previous) < 0) continue
                out += Case(word, previous)
                break
            }
        }
        out
    }

    private fun topOne(decoder: GestureDecoder, case: Case, usePrevious: Boolean): String? {
        val points = GestureFixtures.trace(case.word, keys, jitter = 9f, smoothing = 3, seed = 7L)
        return decoder.decode(
            points = points,
            keys = keys,
            previousWord = if (usePrevious) case.previous else null,
        ).firstOrNull()?.word?.lowercase()
    }

    private fun accuracy(decoder: GestureDecoder, usePrevious: Boolean): Double =
        cases.count { topOne(decoder, it, usePrevious) == it.word }.toDouble() / cases.size

    @Test
    fun `context decodes more swipes than geometry alone`() {
        val base = accuracy(withoutContext, usePrevious = false)
        val contextual = accuracy(withContext, usePrevious = true)

        println("over ${cases.size} swipes traced from held-out sentences")
        println("  geometry only : %.1f%% top-1".format(base * 100))
        println("  with context  : %.1f%% top-1".format(contextual * 100))

        assertTrue(
            "context did not help: %.1f%% -> %.1f%%".format(base * 100, contextual * 100),
            contextual > base,
        )
    }

    /**
     * The words the geometry genuinely cannot separate, which is where the sentence has to do all
     * the work on its own.
     */
    @Test
    fun `separates words that trace the same path`() {
        val ambiguous = cases.filter { case ->
            val decoded = topOne(withoutContext, case, usePrevious = false)
            decoded != null && decoded != case.word &&
                GestureFixtures.tracesIdentically(case.word, decoded, keys)
        }

        val fixed = ambiguous.count { topOne(withContext, it, usePrevious = true) == it.word }
        println("identical-path cases: ${ambiguous.size}, context resolved $fixed")

        assertTrue("no identically-tracing cases in the sample, so this proves nothing", ambiguous.size > 5)
        assertTrue(
            "context resolved none of the ${ambiguous.size} identical-path swipes",
            fixed > ambiguous.size / 4,
        )
    }

    /** The model must change nothing when it is not consulted. */
    @Test
    fun `a decoder given no previous word behaves exactly as one with no model`() {
        for (case in cases.take(400)) {
            assertEquals(
                topOne(withoutContext, case, usePrevious = false),
                topOne(withContext, case, usePrevious = false),
            )
        }
    }

    private companion object {
        /** Decoding is far dearer than correcting, so this is capped to keep the suite quick. */
        const val MAX_CASES = 4000
    }
}
