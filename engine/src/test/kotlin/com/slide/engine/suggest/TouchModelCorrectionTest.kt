package com.slide.engine.suggest

import com.slide.engine.HeldOutSentences
import com.slide.engine.TestBigrams
import com.slide.engine.TestLexicon
import com.slide.engine.gesture.GestureFixtures
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What knowing where the finger landed is worth.
 *
 * Both arms are identical but for one thing: whether [TypingSuggester] is told the touch positions.
 * The typing itself is generated once and shared, so the two see exactly the same keystrokes and
 * exactly the same mis-hits.
 *
 * ### What this does and does not show
 *
 * The mis-hits are real in the sense that matters — they come out of the geometry, from a finger
 * that landed on the wrong side of a key boundary, not from a list of substitutions written by
 * hand. So the *mechanism* is faithful and the direction of the result is trustworthy.
 *
 * The magnitude is not, and should not be quoted as if it were. Real fingers are not isotropic
 * Gaussians: thumbs drift low and toward the holding hand, error grows toward the screen edges,
 * and none of that is here. The right way to settle the number is touch logs from a device, which
 * Slide does not collect. Reported across a range of sigmas rather than at one, so what is visible
 * is the shape of the effect rather than a single figure that would invite over-reading.
 */
class TouchModelCorrectionTest {

    private val lexicon = TestLexicon.instance
    private val keys = GestureFixtures.qwerty()

    private val suggester = TypingSuggester(lexicon, bigrams = TestBigrams.instance)

    private data class Case(
        val pressed: String,
        val intended: String,
        val previous: String,
        val touches: FloatArray,
    )

    /**
     * Words typed with a wobbly finger, keeping only those that came out wrong.
     *
     * Accurately typed words are excluded because they are all in the dictionary and so are never
     * corrected at all; including them would drown the measurement in cases where both arms
     * trivially agree. They are checked separately below.
     */
    private fun misHits(sigma: Float, limit: Int = 3000): List<Case> {
        val random = Random(11L)
        val out = ArrayList<Case>()
        for ((n, sentence) in HeldOutSentences.instance.withIndex()) {
            if (out.size >= limit) continue
            val tokens = Regex("[a-z]+").findAll(sentence.lowercase()).map { it.value }.toList()
            if (tokens.size < 3) continue

            val at = 1 + n % (tokens.size - 1)
            val word = tokens[at]
            val previous = tokens[at - 1]
            if (word.length !in 4..9) continue
            if (lexicon.indexOf(word) < 0 || lexicon.indexOf(previous) < 0) continue

            val typed = TouchFixtures.type(word, keys, sigma, random) ?: continue
            if (typed.pressed == word) continue
            // A mis-hit that is itself a word is protected by a rule this is not measuring.
            if (lexicon.contains(typed.pressed)) continue

            out += Case(typed.pressed, word, previous, typed.touches)
        }
        return out
    }

    private fun accuracy(cases: List<Case>, useTouch: Boolean): Pair<Double, Double> {
        var right = 0
        var wrong = 0
        for (case in cases) {
            val applied = suggester.suggest(
                typed = case.pressed,
                keys = keys,
                previousWord = case.previous,
                touchPoints = if (useTouch) case.touches else null,
            ).autocorrection
            when {
                applied == null -> Unit
                applied.equals(case.intended, ignoreCase = true) -> right++
                else -> wrong++
            }
        }
        return right.toDouble() / cases.size to wrong.toDouble() / cases.size
    }

    @Test
    fun `knowing where the finger landed corrects more mis-hits`() {
        var improvedAt = 0
        val sigmas = listOf(0.25f, 0.30f, 0.35f, 0.40f)

        for (sigma in sigmas) {
            val cases = misHits(sigma)
            val (blindRight, blindWrong) = accuracy(cases, useTouch = false)
            val (touchRight, touchWrong) = accuracy(cases, useTouch = true)

            println(
                "sigma %.2f over %5d mis-hits:  letters only %5.1f%%/%4.1f%%   with touch %5.1f%%/%4.1f%%"
                    .format(
                        sigma, cases.size,
                        blindRight * 100, blindWrong * 100,
                        touchRight * 100, touchWrong * 100,
                    ),
            )
            assertTrue("no mis-hits generated at sigma $sigma", cases.size > 200)
            val rightGain = touchRight - blindRight
            val wrongIncrease = maxOf(0.0, touchWrong - blindWrong)
            if (rightGain > 0.0) improvedAt++
            assertTrue(
                "touch at sigma $sigma added more wrong corrections than right ones",
                rightGain > wrongIncrease,
            )
            assertTrue(
                "touch at sigma $sigma corrected %.1f%% to the wrong word".format(touchWrong * 100),
                // This sample is conditioned on the key already being wrong, so an absolute
                // ceiling mostly measures how destructive the generated noise was. Guard the
                // causal comparison instead: touch evidence may not materially increase errors.
                touchWrong < blindWrong + 0.02,
            )
        }

        assertEquals(
            "the touch model did not help at every level of sloppiness",
            sigmas.size,
            improvedAt,
        )
    }

    /**
     * The other half: a word typed accurately must be unaffected.
     *
     * A touch model that improved mis-hits by making the keyboard twitchier about clean typing
     * would be a bad trade dressed up as a good one.
     */
    @Test
    fun `accurately typed words are untouched by the touch model`() {
        val random = Random(3L)
        var checked = 0
        for (sentence in HeldOutSentences.instance.take(4000)) {
            val word = Regex("[a-z]{4,9}").find(sentence.lowercase())?.value ?: continue
            if (lexicon.indexOf(word) < 0) continue
            val typed = TouchFixtures.type(word, keys, sigma = 0.12f, random = random) ?: continue
            if (typed.pressed != word) continue

            assertEquals(
                "'$word' was treated differently once the touches were known",
                suggester.suggest(word, keys).autocorrection,
                suggester.suggest(word, keys, touchPoints = typed.touches).autocorrection,
            )
            checked++
        }
        assertTrue("nothing was checked", checked > 500)
    }

    /** A short or absent touch array must be ignored rather than read past its end. */
    @Test
    fun `a malformed touch array is ignored`() {
        val word = "wrold"
        val expected = suggester.suggest(word, keys).autocorrection
        assertEquals(expected, suggester.suggest(word, keys, touchPoints = FloatArray(0)).autocorrection)
        assertEquals(expected, suggester.suggest(word, keys, touchPoints = FloatArray(4)).autocorrection)
        assertEquals(
            expected,
            suggester.suggest(word, keys, touchPoints = FloatArray(word.length * 2) { Float.NaN })
                .autocorrection,
        )
    }
}
