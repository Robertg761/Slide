package com.slide.engine.suggest

import com.slide.engine.TestLexicon
import com.slide.engine.gesture.GestureFixtures
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How often autocorrect fires, over generated typos rather than hand-picked ones.
 *
 * `TypingSuggesterTest` pins seven typos everyone agrees about. That is not enough to notice the
 * failure this test exists to catch: the corrector finding the right word, putting it in the strip,
 * and then declining to apply it. Under the original margin that happened to 2,332 of 11,943 cases
 * — including "htis", "thjs", "drom" and "witth", which is to say typos of the words people type
 * most — and it read as a keyboard that simply never corrected anything.
 *
 * Both bounds matter and they pull opposite ways, so both are asserted. Sweeping either is
 * `CorrectionSweepTest`.
 */
class CorrectionRateTest {

    private val lexicon = TestLexicon.instance
    private val keys = GestureFixtures.qwerty()
    private val suggester = TypingSuggester(lexicon)
    private val cases = TypoCorpus.build(lexicon)

    @Test
    fun `corrects the great majority of single-edit typos`() {
        val counts = cases.groupingBy {
            TypoCorpus.outcome(it, suggester.suggest(it.typo, keys).autocorrection)
        }.eachCount()

        val right = counts[TypoCorpus.Outcome.RIGHT] ?: 0
        val wrong = counts[TypoCorpus.Outcome.WRONG] ?: 0
        val rate = right.toDouble() / cases.size
        val wrongRate = wrong.toDouble() / cases.size
        println("correction rate %.1f%% right, %.1f%% wrong, over ${cases.size} typos"
            .format(rate * 100, wrongRate * 100))

        // Measured at 87.7% with the tuned defaults, against 76.6% before the margin was fixed and
        // 84.7% before insertions were repriced. The floor sits below that so ordinary lexicon or
        // scoring changes do not trip it, but above the older figures so neither regression can
        // come back unnoticed. Note this is the no-context path: the model is not consulted here.
        assertTrue("only %.1f%% of single-edit typos were corrected".format(rate * 100), rate > 0.85)

        // The other half of the bargain. Rewriting a word into the wrong one is the failure this
        // whole subsystem is most afraid of, so it gets a ceiling of its own.
        assertTrue(
            "%.1f%% of typos were corrected to the wrong word".format(wrongRate * 100),
            wrongRate < 0.012,
        )
    }

    /**
     * No edit kind may be left behind by a change that improves the average.
     *
     * These pull against each other, which is the point of measuring them separately: insertions
     * and substitutions compete to explain the same typo, so making dropped letters cheaper to fix
     * takes accuracy from mis-hit keys. Dropped letters remain the hardest and most ambiguous —
     * "ther" is equally "there", "their" and "other" — so their floor stays the lowest.
     */
    @Test
    fun `every edit kind is corrected at a reasonable rate`() {
        val floors = mapOf(
            TypoCorpus.Kind.TRANSPOSITION to 0.92,
            TypoCorpus.Kind.SUBSTITUTION to 0.76,
            TypoCorpus.Kind.DOUBLED to 0.93,
            TypoCorpus.Kind.DROPPED to 0.70,
        )

        for ((kind, floor) in floors) {
            val ofKind = cases.filter { it.kind == kind }
            val right = ofKind.count {
                TypoCorpus.outcome(it, suggester.suggest(it.typo, keys).autocorrection) ==
                    TypoCorpus.Outcome.RIGHT
            }
            val rate = right.toDouble() / ofKind.size
            println("$kind %.1f%% (%d/%d)".format(rate * 100, right, ofKind.size))
            assertTrue(
                "$kind fell to %.1f%%, below its floor of %.0f%%".format(rate * 100, floor * 100),
                rate > floor,
            )
        }
    }

    /**
     * A key that bounced is the least ambiguous slip there is, and used to be priced above a
     * neighbour-key substitution of the final letter — so "largee" became "larger", "sidde" became
     * "sided" and "partt" became "party".
     *
     * The claim asserted is the one that matters: a doubled key is never read as a *different*
     * word. Whether each also clears the confidence margin is a separate question and a softer
     * one — declining to correct "largee" leaves the user with a visible non-word and the right
     * answer in the strip, which is a far better failure than silently writing "larger".
     */
    @Test
    fun `a bounced key is never read as a different word`() {
        val doubled = mapOf(
            "largee" to "large",
            "sidde" to "side",
            "partt" to "part",
            "helllo" to "hello",
            "lasst" to "last",
            "theem" to "them",
            "areaa" to "area",
            "plaace" to "place",
        )

        for ((typo, intended) in doubled) {
            val corrected = suggester.suggest(typo, keys).autocorrection
            assertTrue(
                "'$typo' was rewritten to '$corrected' rather than '$intended' or left alone",
                corrected == null || intended.equals(corrected, ignoreCase = true),
            )
        }

        // These eight are the residual hard cases — they were taken from the failure log, and each
        // has a same-cost rival ("theem" is one transposition from "theme"). What proves the
        // pricing works is the population, not them: see the DOUBLED floor in the test above, which
        // the change moved from 88% to 98%.
        val bounced = cases.filter { it.kind == TypoCorpus.Kind.DOUBLED }
        val fired = bounced.count {
            TypoCorpus.outcome(it, suggester.suggest(it.typo, keys).autocorrection) ==
                TypoCorpus.Outcome.RIGHT
        }
        val rewritten = bounced.count {
            TypoCorpus.outcome(it, suggester.suggest(it.typo, keys).autocorrection) ==
                TypoCorpus.Outcome.WRONG
        }
        println("bounced keys: $fired un-doubled, $rewritten rewritten, of ${bounced.size}")
        assertTrue(
            "$rewritten of ${bounced.size} bounced keys became a different word",
            rewritten.toDouble() / bounced.size < 0.01,
        )
    }
}
