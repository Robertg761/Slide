package com.slide.engine.suggest

import com.slide.engine.TestLexicon
import com.slide.engine.gesture.GestureFixtures
import org.junit.Ignore
import org.junit.Test

/**
 * Grid search over the corrector's decision thresholds, the counterpart to `TuningSweepTest`.
 *
 * Ignored by default: it is a tuning instrument, not a regression test. Run it deliberately when
 * the scoring model or the guards change:
 *
 *     ./gradlew :engine:testDebugUnitTest --tests '*CorrectionSweepTest*'
 *
 * having dropped the @Ignore locally.
 *
 * What it prints is a trade, not a score, and the two columns must be read together. Every
 * threshold that raises the share of typos corrected also raises the share corrected to the wrong
 * word, and the defaults were chosen at the point where that exchange rate turns: from a margin of
 * 0.15 down to 0.08 buys roughly six right corrections per new wrong one, and past 0.08 the same
 * spend buys about two.
 *
 * Note what is *not* at stake in either column. A word already in the dictionary is protected by a
 * separate rule that none of these thresholds touch, so no setting here can rewrite a correctly
 * spelled word. What a looser threshold risks is a deliberate non-word — a name, an abbreviation,
 * something slangy — being taken for a typo. That is real, and the corpus cannot measure it, so it
 * is a reason to stop at the knee rather than past it.
 */
@Ignore("Tuning instrument; run by hand when the correction model changes")
class CorrectionSweepTest {

    private val lexicon = TestLexicon.instance
    private val keys = GestureFixtures.qwerty()
    private val cases = TypoCorpus.build(lexicon)

    private fun measure(config: SuggesterConfig): Triple<Double, Double, Double> {
        val suggester = TypingSuggester(lexicon, config)
        var right = 0
        var wrong = 0
        var none = 0
        for (case in cases) {
            when (TypoCorpus.outcome(case, suggester.suggest(case.typo, keys).autocorrection)) {
                TypoCorpus.Outcome.RIGHT -> right++
                TypoCorpus.Outcome.WRONG -> wrong++
                TypoCorpus.Outcome.NONE -> none++
            }
        }
        val n = cases.size.toDouble()
        return Triple(right / n, wrong / n, none / n)
    }

    @Test
    fun `sweep`() {
        println("corpus ${cases.size} generated single-edit typos")
        println()

        println("margin sweep (right / wrong / left alone)")
        for (margin in listOf(0.15f, 0.12f, 0.10f, 0.08f, 0.06f, 0.04f, 0.02f, 0.0f)) {
            val (right, wrong, none) = measure(SuggesterConfig(autocorrectMargin = margin))
            println(
                "  margin %.2f  right %5.1f%%  wrong %4.1f%%  none %5.1f%%"
                    .format(margin, right * 100, wrong * 100, none * 100),
            )
        }
        println()

        println("doubled-letter cost x margin")
        for (doubled in listOf(0.6f, 0.5f, 0.45f, 0.4f, 0.35f, 0.3f)) {
            val row = listOf(0.15f, 0.10f, 0.08f, 0.06f).joinToString("  ") { margin ->
                val (right, wrong, _) = measure(
                    SuggesterConfig(autocorrectMargin = margin, doubledLetterCost = doubled),
                )
                "m=%.2f %4.1f/%3.1f".format(margin, right * 100, wrong * 100)
            }
            println("  doubled=%.2f".format(doubled) + "  " + row)
        }
        println()

        println("other thresholds, at the tuned margin")
        for (frequency in listOf(10, 25, 40, 60, 100)) {
            val (right, wrong, _) = measure(SuggesterConfig(minAutocorrectFrequency = frequency))
            println("  minFrequency %3d  right %5.1f%%  wrong %4.1f%%".format(frequency, right * 100, wrong * 100))
        }
        for (guard in listOf(50, 100, 200, 255)) {
            val (right, wrong, _) = measure(SuggesterConfig(prefixGuardFrequency = guard))
            println("  prefixGuard  %3d  right %5.1f%%  wrong %4.1f%%".format(guard, right * 100, wrong * 100))
        }
        for (cost in listOf(0.6f, 0.7f, 0.8f, 0.9f, 1.0f)) {
            val (right, wrong, _) = measure(SuggesterConfig(maxAutocorrectCost = cost))
            println("  maxCost     %.2f  right %5.1f%%  wrong %4.1f%%".format(cost, right * 100, wrong * 100))
        }
    }
}
