package com.slide.engine.suggest

import com.slide.engine.TestBigrams
import com.slide.engine.TestLexicon
import com.slide.engine.gesture.GestureFixtures
import com.slide.engine.lexicon.UserDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What learning a word actually changes about correction.
 *
 * Personal vocabulary cannot be judged by a corpus — it is personal by definition — so what is
 * tested here is the property that matters instead: once the keyboard has been told a word is a
 * word, it stops arguing about it. That is the difference between a keyboard that adapts and one
 * that spends for ever rewriting somebody's name.
 */
class LearnedWordTest {

    private val lexicon = TestLexicon.instance
    private val keys = GestureFixtures.qwerty()
    private val learned = UserDictionary()

    private val suggester = TypingSuggester(
        lexicon,
        bigrams = TestBigrams.instance,
        userDictionary = learned,
    )

    /** Words the shipped dictionary does not have, each a single edit from one it does. */
    private val unknown = listOf("kubectl", "docekr", "gboad", "roberg", "slid")
        .filter { lexicon.indexOf(it) < 0 }

    @Test
    fun `an unknown word one edit from a real one is corrected before it is learned`() {
        val corrected = unknown.filter { suggester.suggest(it, keys).autocorrection != null }
        assertTrue(
            "none of $unknown was corrected, so learning has nothing to prevent",
            corrected.isNotEmpty(),
        )
    }

    /**
     * The whole point. A word the user has established is theirs is never rewritten again, however
     * confidently the corpus disagrees.
     */
    @Test
    fun `a learned word is never corrected away`() {
        for (word in unknown) {
            learned.learn(word, weight = 2)
        }
        for (word in unknown) {
            assertNull(
                "'$word' was learned and still got rewritten",
                suggester.suggest(word, keys).autocorrection,
            )
        }
    }

    /** Half-learned is not learned: a word seen once is still a likely typo. */
    @Test
    fun `a word seen only once is still corrected`() {
        val victim = unknown.first { suggester.suggest(it, keys).autocorrection != null }
        learned.learn(victim)
        assertNotNull(
            "'$victim' was defended after a single sighting",
            suggester.suggest(victim, keys).autocorrection,
        )
    }

    @Test
    fun `forgetting a word puts it back in reach of correction`() {
        val victim = unknown.first { suggester.suggest(it, keys).autocorrection != null }
        learned.learn(victim, weight = 2)
        assertNull(suggester.suggest(victim, keys).autocorrection)

        learned.forget(victim)
        assertNotNull(
            "'$victim' was forgotten but is still defended",
            suggester.suggest(victim, keys).autocorrection,
        )
    }

    @Test
    fun `a learned word is offered as a completion`() {
        learned.learn("kubernetes", weight = 3)
        val offered = suggester.suggest("kuber", keys).words.map { it.word }
        assertTrue("expected 'kubernetes' among $offered", "kubernetes" in offered)
    }

    /**
     * Learning must not disturb the shipped dictionary. A corpus word stays exactly as it was,
     * whatever the user has taught the keyboard alongside it.
     */
    @Test
    fun `learning does not change what happens to ordinary words`() {
        val plain = TypingSuggester(lexicon, bigrams = TestBigrams.instance)
        learned.learn("kubectl", weight = 5)
        learned.learn("robertg", weight = 5)

        for (typo in listOf("teh", "adn", "thsi", "wjat", "helllo", "oce", "sould")) {
            assertEquals(
                "'$typo' behaved differently once unrelated words had been learned",
                plain.suggest(typo, keys).autocorrection,
                suggester.suggest(typo, keys).autocorrection,
            )
        }
    }
}
