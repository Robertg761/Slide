package com.slide.engine.suggest

import com.slide.engine.TestBigrams
import com.slide.engine.TestLexicon
import com.slide.engine.TestTrigrams
import com.slide.engine.gesture.GestureFixtures
import org.junit.Assert.assertTrue
import org.junit.Test

class TrigramContextTest {
    private val lexicon = TestLexicon.instance
    private val keys = GestureFixtures.qwerty()
    private val cases = ContextualCases.build(lexicon).filter { it.older != null }
    private val bigram = TypingSuggester(lexicon, bigrams = TestBigrams.instance)
    private val trigram = TypingSuggester(
        lexicon,
        bigrams = TestBigrams.instance,
        trigrams = TestTrigrams.instance,
    )

    @Test
    fun `two preceding words improve held-out correction without a bad trade`() {
        fun outcomes(suggester: TypingSuggester, useOlder: Boolean): Pair<Int, Int> {
            var right = 0
            var wrong = 0
            for (case in cases) {
                val applied = suggester.suggest(
                    typed = case.typo,
                    keys = keys,
                    previousWord = case.previous,
                    previousPreviousWord = case.older.takeIf { useOlder },
                ).autocorrection
                when {
                    applied == null -> Unit
                    applied.equals(case.intended, ignoreCase = true) -> right++
                    else -> wrong++
                }
            }
            return right to wrong
        }

        val (bigramRight, bigramWrong) = outcomes(bigram, useOlder = false)
        val (trigramRight, trigramWrong) = outcomes(trigram, useOlder = true)
        println(
            "two-word context over ${cases.size}: " +
                "$bigramRight/$bigramWrong -> $trigramRight/$trigramWrong right/wrong",
        )
        val gained = trigramRight - bigramRight
        val addedWrong = trigramWrong - bigramWrong
        assertTrue("two-word context did not add any correct decisions", gained > 0)
        assertTrue(
            "two-word context gained $gained correct but added $addedWrong wrong",
            addedWrong <= 0 || gained >= addedWrong * 4,
        )
    }
}
