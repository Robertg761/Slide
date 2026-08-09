package com.slide.engine.suggest

import com.slide.engine.TestBigrams
import com.slide.engine.TestLexicon
import com.slide.engine.gesture.GestureFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the sentence buys, measured on sentences the model has never seen.
 *
 * `CorrectionRateTest` judges typos in isolation, which is the only fair way to judge spelling
 * evidence but is not how anyone types. Here each typo sits where it was actually written, with
 * the real preceding word, and the same corpus is run twice — once with the model consulted and
 * once without — so the difference is attributable to context and to nothing else.
 *
 * The held-out sentences come from `tools/build_bigrams.py`, which reserves a tenth of the corpus
 * by sentence id and trains on the rest.
 */
class ContextualCorrectionTest {

    private val lexicon = TestLexicon.instance
    private val keys = GestureFixtures.qwerty()

    private val withContext = TypingSuggester(lexicon, bigrams = TestBigrams.instance)
    private val withoutContext = TypingSuggester(lexicon)

    private val cases = ContextualCases.build(lexicon)

    private fun rate(suggester: TypingSuggester, usePrevious: Boolean): Pair<Double, Double> {
        var right = 0
        var wrong = 0
        for (case in cases) {
            val applied = suggester.suggest(
                typed = case.typo,
                keys = keys,
                previousWord = if (usePrevious) case.previous else null,
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
    fun `context corrects more typos than spelling alone`() {
        val (baseRight, baseWrong) = rate(withoutContext, usePrevious = false)
        val (contextRight, contextWrong) = rate(withContext, usePrevious = true)

        println("over ${cases.size} typos in held-out sentences")
        println("  spelling only : %.1f%% right, %.1f%% wrong".format(baseRight * 100, baseWrong * 100))
        println("  with context  : %.1f%% right, %.1f%% wrong".format(contextRight * 100, contextWrong * 100))

        // Measured at 84.4% -> 92.4%. The floor is a fraction of that, because the point is to
        // catch the model being disconnected or built wrong, not to pin the exact gain.
        assertTrue(
            "context did not help: %.1f%% -> %.1f%%".format(baseRight * 100, contextRight * 100),
            contextRight > baseRight + 0.04,
        )

        // The gain must not be bought with mistakes. Context makes the corrector bolder, so it can
        // cost a little accuracy on the cases it newly acts on; what matters is the exchange rate,
        // which is measured at better than twenty right corrections per new wrong one.
        val gained = contextRight - baseRight
        val cost = contextWrong - baseWrong
        assertTrue(
            "context bought %.1f points of accuracy with %.1f points of mistakes"
                .format(gained * 100, cost * 100),
            cost <= 0 || gained / cost > 10.0,
        )
        assertTrue("context made too many mistakes outright: %.1f%%".format(contextWrong * 100), contextWrong < 0.012)
    }

    /**
     * The model must change nothing when it is not consulted.
     *
     * Everything else here is a comparison between two arms, and that only means anything if the
     * arms differ solely in whether the sentence was available.
     */
    @Test
    fun `a suggester given no previous word behaves exactly as one with no model`() {
        for (case in cases.take(2000)) {
            assertEquals(
                "'${case.typo}' differed with no context supplied",
                withoutContext.suggest(case.typo, keys).autocorrection,
                withContext.suggest(case.typo, keys, previousWord = null).autocorrection,
            )
        }
    }

    /** An unknown preceding word is a gap in the model, not a reason to behave differently. */
    @Test
    fun `an unknown previous word falls back to spelling alone`() {
        for (case in cases.take(500)) {
            assertEquals(
                withContext.suggest(case.typo, keys, previousWord = null).autocorrection,
                withContext.suggest(case.typo, keys, previousWord = "zzqxwv").autocorrection,
            )
        }
    }

    /**
     * Worked examples, taken from the held-out set rather than invented.
     *
     * Every one of these has several real words a single edit away and no clear favourite among
     * them on frequency. What is asserted is that all of them come out right in a sentence; how
     * many *need* the sentence to get there is reported but only loosely bounded, because it falls
     * whenever the spelling model improves on its own — as it did when insertions were repriced,
     * which took four of these seven off the sentence's hands.
     */
    @Test
    fun `reads the sentence where spelling alone could not decide`() {
        val worked = listOf(
            Triple("at", "ocne", "once"),
            Triple("my", "hroat", "throat"),
            Triple("without", "efort", "effort"),
            Triple("don't", "hae", "have"),
            Triple("car", "wshed", "washed"),
            Triple("these", "dats", "days"),
            Triple("of", "yor", "your"),
        )

        var readFromSentence = 0
        for ((previous, typo, intended) in worked) {
            val alone = withoutContext.suggest(typo, keys).autocorrection
            val inSentence = withContext.suggest(typo, keys, previousWord = previous).autocorrection
            println("'$previous $typo' -> alone '$alone', in sentence '$inSentence'")

            assertEquals(
                "'$previous $typo' should reach '$intended'",
                intended,
                inSentence?.lowercase(),
            )
            if (!intended.equals(alone, ignoreCase = true)) readFromSentence++
        }

        println("$readFromSentence of ${worked.size} needed the sentence")
        assertTrue(
            "none of these needed the sentence, so they no longer illustrate anything",
            readFromSentence >= 2,
        )
    }
}
