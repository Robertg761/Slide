package com.slide.engine.suggest

import com.slide.engine.HeldOutSentences
import com.slide.engine.TestBigrams
import com.slide.engine.TestLexicon
import com.slide.engine.gesture.GestureFixtures
import com.slide.engine.lexicon.UserBigrams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What learning someone's own word pairs does, and — just as importantly — what it does not.
 *
 * The aggregate arm splits the held-out sentences in two: the first half is treated as text this
 * person has already written and its pairs are fed to [UserBigrams] exactly as the keyboard would,
 * and correction is measured on the second half, which neither model has seen.
 *
 * ### What that arm can and cannot show
 *
 * It cannot show the benefit, and this is worth being plain about. Tatoeba sentences are unrelated
 * to one another, so the pairs that recur in them are ordinary English pairs — which the corpus
 * model already knows, leaving the personal model nothing to add. A real person is the most
 * repetitive corpus imaginable, and the pairs they repeat are exactly the ones no corpus has:
 * their friends' names, their jargon, their turns of phrase. None of that exists here.
 *
 * What it can show, and what it is kept for, is harm. An adaptive model that promotes candidates
 * on thin evidence damages correction everywhere, and that damage *is* visible in generic text.
 * It was visible: counting a pair seen once cost roughly twice as many wrong corrections as it
 * bought right ones, which is why `UserBigrams` ignores anything below a threshold. The assertion
 * below is therefore that adaptation does no harm, not that it helps — the help is demonstrated
 * directly instead, on the mechanism.
 */
class AdaptiveBigramTest {

    private val lexicon = TestLexicon.instance
    private val keys = GestureFixtures.qwerty()

    private val history = HeldOutSentences.instance.take(HeldOutSentences.instance.size / 2)
    private val future = HeldOutSentences.instance.drop(HeldOutSentences.instance.size / 2)

    /** The pairs a keyboard would have picked up while this person wrote [history]. */
    private val learned: UserBigrams by lazy {
        val model = UserBigrams(capacity = 200_000)
        for (sentence in history) {
            val tokens = Regex("[a-z']+").findAll(sentence.lowercase()).map { it.value }.toList()
            for (i in 1 until tokens.size) model.learn(tokens[i - 1], tokens[i])
        }
        model
    }

    private val adapted by lazy {
        TypingSuggester(lexicon, bigrams = TestBigrams.instance, userBigrams = learned)
    }
    private val plain = TypingSuggester(lexicon, bigrams = TestBigrams.instance)

    /** Typos built from the future half only, so nothing measured has been trained on. */
    private val cases: List<ContextualCases.Case> by lazy {
        ContextualCases.build(lexicon, sentences = future)
    }

    private fun accuracy(suggester: TypingSuggester): Pair<Double, Double> {
        var right = 0
        var wrong = 0
        for (case in cases) {
            val applied =
                suggester.suggest(case.typo, keys, previousWord = case.previous).autocorrection
            when {
                applied == null -> Unit
                applied.equals(case.intended, ignoreCase = true) -> right++
                else -> wrong++
            }
        }
        return right.toDouble() / cases.size to wrong.toDouble() / cases.size
    }

    @Test
    fun `adapting to earlier writing does not damage later writing`() {
        val (baseRight, baseWrong) = accuracy(plain)
        val (adaptedRight, adaptedWrong) = accuracy(adapted)

        println("${learned.size} pairs learned from ${history.size} sentences")
        println("over ${cases.size} typos in sentences neither model has seen")
        println("  corpus only : %.1f%% right, %.1f%% wrong".format(baseRight * 100, baseWrong * 100))
        println("  adapted     : %.1f%% right, %.1f%% wrong".format(adaptedRight * 100, adaptedWrong * 100))

        // Generic text is where an over-eager adaptive model does its damage, so this is the arm
        // that has to hold. Measured at a tenth of a point either way, which is noise; the bound
        // is loose enough not to be flaky and tight enough to catch a threshold set back to one,
        // which cost half a point of wrong corrections.
        assertTrue(
            "adaptation lost accuracy: %.1f%% -> %.1f%%".format(baseRight * 100, adaptedRight * 100),
            adaptedRight >= baseRight - 0.002,
        )
        assertTrue(
            "adaptation cost too many wrong corrections: %.1f%% -> %.1f%%"
                .format(baseWrong * 100, adaptedWrong * 100),
            adaptedWrong <= baseWrong + 0.003,
        )
    }

    /**
     * The mechanism itself, which is what actually justifies shipping this.
     *
     * Someone who sews writes "the hem" often. The corpus has never heard of them and says "home"
     * every time, because "home" is commoner in English than "hem" will ever be. After enough
     * sightings the habit wins — and only in the context it was learned in.
     */
    @Test
    fun `an established habit outweighs the corpus, in its own context only`() {
        val model = UserBigrams()
        val suggester = TypingSuggester(lexicon, bigrams = TestBigrams.instance, userBigrams = model)

        assertEquals("home", suggester.suggest("hme", keys, previousWord = "the").autocorrection)

        repeat(9) { model.learn("the", "hem") }
        assertEquals("hem", suggester.suggest("hme", keys, previousWord = "the").autocorrection)

        // Elsewhere, the corpus still decides.
        assertEquals("home", suggester.suggest("hme", keys, previousWord = "went").autocorrection)
        assertEquals("home", suggester.suggest("hme", keys).autocorrection)
    }

    /** A pair seen once or twice is not a habit, and must not be allowed to act like one. */
    @Test
    fun `a pair seen a couple of times changes nothing`() {
        val model = UserBigrams()
        val suggester = TypingSuggester(lexicon, bigrams = TestBigrams.instance, userBigrams = model)

        repeat(3) {
            model.learn("the", "hem")
            assertEquals(
                "a pair seen ${it + 1} time(s) already changed the answer",
                "home",
                suggester.suggest("hme", keys, previousWord = "the").autocorrection,
            )
        }
    }

    /** Pairs are evidence about a context, so they must not leak into unrelated ones. */
    @Test
    fun `a learned pair changes nothing without its context`() {
        val model = UserBigrams()
        val suggester = TypingSuggester(lexicon, bigrams = TestBigrams.instance, userBigrams = model)
        repeat(9) { model.learn("kubectl", "aply") }

        for (typo in listOf("teh", "adn", "thsi", "oce", "sould", "wrold")) {
            assertEquals(
                "'$typo' changed once an unrelated pair was learned",
                plain.suggest(typo, keys, previousWord = "the").autocorrection,
                suggester.suggest(typo, keys, previousWord = "the").autocorrection,
            )
        }
    }

    /**
     * A pair whose words are outside the lexicon must be harmless.
     *
     * Half the point of learning pairs is the ones involving words no dictionary has, but those
     * cannot be *ranked* — there is no lexicon index to attach a score to. They reach the user
     * through learned completions instead, and here must simply do nothing.
     */
    @Test
    fun `pairs of unknown words are harmless`() {
        val model = UserBigrams()
        val suggester = TypingSuggester(lexicon, bigrams = TestBigrams.instance, userBigrams = model)
        repeat(9) { model.learn("kubectl", "zzqxwv") }

        assertNull(suggester.suggest("zzqxwv", keys, previousWord = "kubectl").autocorrection)
        assertEquals(
            plain.suggest("teh", keys, previousWord = "kubectl").autocorrection,
            suggester.suggest("teh", keys, previousWord = "kubectl").autocorrection,
        )
    }
}
