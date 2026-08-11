package com.slide.engine.suggest

import com.slide.engine.TestLexicon
import com.slide.engine.gesture.GestureFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingSuggesterTest {

    private val keys = GestureFixtures.qwerty()
    private val suggester = TypingSuggester(TestLexicon.instance)

    private fun suggest(typed: String, blockOffensive: Boolean = true) =
        suggester.suggest(typed, keys, blockOffensive)

    private fun words(typed: String) = suggest(typed).words.map { it.word.lowercase() }

    // region Corrections

    /**
     * The typos here are one edit apiece, one of each kind the corrector generates: transposed,
     * substituted with a neighbouring key, doubled, and dropped.
     */
    @Test
    fun `corrects single-edit typos`() {
        val cases = mapOf(
            "teh" to "the", // transposition
            "adn" to "and",
            "thsi" to "this",
            "wjat" to "what", // j is next to h
            "abiut" to "about", // i is next to o
            "helllo" to "hello", // an extra letter
            "wrold" to "world",
        )

        for ((typo, intended) in cases) {
            assertEquals("'$typo' should correct to '$intended'", intended, suggest(typo).autocorrection)
        }
    }

    /**
     * "wont" and "cant" are deliberately absent: both are real words, and the rule that a word in
     * the dictionary is never rewritten outranks this one.
     */
    @Test
    fun `restores a missing apostrophe`() {
        for (typo in listOf("dont", "isnt", "didnt", "couldnt", "wouldnt", "havent")) {
            val corrected = suggest(typo).autocorrection
            assertEquals("'$typo' should regain its apostrophe", typo.dropLast(1) + "'" + typo.last(), corrected)
        }
    }

    @Test
    fun `restores only unambiguous short I contractions`() {
        val im = suggest("im")
        val ive = suggest("ive")
        assertEquals("I'm", im.autocorrection)
        assertEquals("I'm", im.words.first().word)
        assertEquals("I've", ive.autocorrection)
        assertEquals("I've", ive.words.first().word)

        // Both are ordinary dictionary words as typed, so guessing a contraction would be wrong.
        assertNull(suggest("id").autocorrection)
        assertNull(suggest("ill").autocorrection)
    }

    // endregion

    // region Refusing to correct

    /**
     * The single most important behaviour here. A keyboard that rewrites words the user typed
     * correctly is worse than one that never corrects anything at all.
     */
    @Test
    fun `never autocorrects a word in the dictionary`() {
        val handPicked = listOf(
            "the", "hello", "world", "keyboard", "its", "were", "cant", "well", "form",
            "then", "than", "your", "here", "ill", "wont", "id", "shed", "tho", "wan",
        ).filter { TestLexicon.instance.contains(it) }

        // Plus a wide sweep of the dictionary itself, because the words that get wrongly rewritten
        // are precisely the ones nobody thought to put in a hand-written list.
        val lexicon = TestLexicon.instance
        val sampled = (0 until lexicon.size step 37)
            .map { lexicon.lowercaseAt(it) }
            .filter { it.all { c -> c in 'a'..'z' } }

        for (word in handPicked + sampled) {
            assertNull("'$word' is a real word and must be left alone", suggest(word).autocorrection)
        }
    }

    /** An unfinished word is far likelier than a misspelled one when the fragment starts a word. */
    @Test
    fun `does not correct a prefix of a common word`() {
        for (fragment in listOf("hel", "wor", "keyb", "somet", "thin")) {
            assertNull("'$fragment' is unfinished, not wrong", suggest(fragment).autocorrection)
        }
    }

    @Test
    fun `does not correct words too short to judge`() {
        for (typo in listOf("hj", "sk", "zx")) {
            assertNull("'$typo' is too short to correct confidently", suggest(typo).autocorrection)
        }
    }

    @Test
    fun `does not correct text that is not a word being typed`() {
        for (input in listOf("", "12", "a1b", "hello world", "don't-stop")) {
            assertNull(suggest(input).autocorrection)
            if (input.isNotEmpty()) assertTrue(suggest(input).words.isEmpty() || input == "")
        }
    }

    /** Two edits away is outside what the corrector generates, and it must not guess wildly. */
    @Test
    fun `leaves a badly mangled word alone rather than guessing`() {
        val result = suggest("qzwxrv")
        assertNull(result.autocorrection)
    }

    // endregion

    // region The strip

    @Test
    fun `always offers what was typed`() {
        for (typed in listOf("hello", "teh", "hel", "qqqq", "Hello")) {
            assertTrue(
                "'$typed' should be offered verbatim, got ${suggest(typed).words}",
                suggest(typed).words.any { it.word == typed },
            )
        }
    }

    /**
     * What sits first is what space will produce, so a pending correction leads and the literal
     * sits beside it. Anything else would apply a change the user could not see coming.
     */
    @Test
    fun `puts a pending correction first and the typed word second`() {
        val result = suggest("teh")
        assertEquals("the", result.words[0].word.lowercase())
        assertEquals(WordSuggestion.Kind.Correction, result.words[0].kind)
        assertEquals("teh", result.words[1].word)
    }

    @Test
    fun `leads with the typed word when nothing is being corrected`() {
        val result = suggest("hello")
        assertEquals("hello", result.words[0].word.lowercase())
        assertNull(result.autocorrection)
    }

    @Test
    fun `offers completions for an unfinished word`() {
        val completions = words("hel")
        assertTrue("expected completions of 'hel', got $completions", completions.size > 1)
        assertTrue(
            "every completion should start with what was typed: $completions",
            completions.all { it.startsWith("hel") },
        )
    }

    @Test
    fun `fills the strip without repeating itself`() {
        for (typed in listOf("th", "hel", "teh", "abou", "keyboa")) {
            val result = words(typed)
            assertTrue("'$typed' returned more than the strip holds: $result", result.size <= 3)
            assertEquals("'$typed' repeated a suggestion: $result", result.distinct().size, result.size)
        }
    }

    /**
     * A penalty charged after a top-k has been truncated is not a penalty, it is a formality.
     *
     * The wordlist rates names by how often they appear in text, so a four-slot completion board
     * for "acc" fills with ACCA, ACCC and Accra, and one for "lyc" with LHC — all of which the
     * proper-noun penalty then pushes below ordinary words the board had already discarded. The
     * completions that would have won have to be scored before the board decides, exactly as
     * corrections are.
     */
    @Test
    fun `a penalised name does not evict the completion that outranks it`() {
        assertTrue("expected 'accept' among ${words("acc")}", "accept" in words("acc"))
        assertTrue("expected 'lychee' among ${words("lyc")}", "lychee" in words("lyc"))
    }

    /** The penalty is conditional, and reaching for shift is what says a name was meant. */
    @Test
    fun `a typed capital brings the names back`() {
        val marked = suggester.suggest("Holm", keys).words.map { it.word }
        assertTrue("expected 'Holmes' among $marked", "Holmes" in marked)
    }

    @Test
    fun `keeps the dictionary's own capitalisation`() {
        val suggestions = suggest("septembe").words.map { it.word }
        assertTrue("expected 'September', got $suggestions", "September" in suggestions)
    }

    // endregion

    // region Offensive words

    @Test
    fun `withholds offensive words unless asked for them`() {
        val lexicon = TestLexicon.instance
        val offensive = (0 until lexicon.size)
            .filter { lexicon.isOffensive(it) && lexicon.lowercaseAt(it).all { c -> c in 'a'..'z' } }
            .map { lexicon.lowercaseAt(it) }
            .filter { it.length >= 5 }
            .toSet()

        // Every prefix of an offensive word is a chance for the filter to leak one. What we care
        // about is that none of them ever does, not that any particular word wins its own race.
        // The typed word itself is exempt throughout: the strip never hides what the user wrote.
        val prefixes = offensive.map { it.dropLast(1) }
        fun suggestedFor(prefix: String, blockOffensive: Boolean) =
            suggester.suggest(prefix, keys, blockOffensive).words
                .filter { it.kind != WordSuggestion.Kind.Typed }
                .map { it.word.lowercase() }

        val leaked = prefixes.filter { prefix -> suggestedFor(prefix, true).any { it in offensive } }
        assertTrue("blocked words reached the strip for $leaked", leaked.isEmpty())

        val offered = prefixes.count { prefix -> suggestedFor(prefix, false).any { it in offensive } }
        assertTrue("the filter suppressed nothing, so it proves nothing", offered > 0)
    }

    /** Typing one deliberately is the user's business; quietly rewriting it is not. */
    @Test
    fun `does not correct away from an offensive word the user typed`() {
        val lexicon = TestLexicon.instance
        val offensive = (0 until lexicon.size)
            .filter {
                lexicon.isOffensive(it) &&
                    lexicon.lengthAt(it) >= 4 &&
                    lexicon.lowercaseAt(it).all { c -> c in 'a'..'z' }
            }
            .maxByOrNull { lexicon.frequencyAt(it) }
        val word = lexicon.lowercaseAt(offensive!!)

        assertNull("'$word' was typed on purpose", suggest(word).autocorrection)
    }

    // endregion

    @Test
    fun `stays well inside a frame per keystroke`() {
        // Every prefix of a long word, which is what a real keystroke sequence looks like.
        val inputs = listOf("keyboard", "something", "different", "tomorrow", "recieve", "helllo")
            .flatMap { word -> (1..word.length).map { word.take(it) } }

        repeat(20) { for (input in inputs) suggest(input) } // warm the JIT

        val start = System.nanoTime()
        val rounds = 50
        repeat(rounds) { for (input in inputs) suggest(input) }
        val perCallMs = (System.nanoTime() - start) / 1e6 / (rounds * inputs.size)

        // Generous for a desktop JVM; the point is to catch an algorithmic blow-up, not to predict
        // phone latency. This runs on the keypress path, so it has a frame to fit inside.
        assertTrue("Suggesting averaged %.3fms".format(perCallMs), perCallMs < 5.0)
        println("mean suggest: %.3fms over ${inputs.size} inputs".format(perCallMs))
    }

    @Test
    fun `survives every prefix of every word without throwing`() {
        val lexicon = TestLexicon.instance
        val stride = lexicon.size / 2000
        for (index in 0 until lexicon.size step maxOf(1, stride)) {
            val word = lexicon.lowercaseAt(index)
            for (length in 1..minOf(word.length, 12)) {
                val result = suggest(word.take(length))
                assertNotNull(result.words)
            }
        }
    }
}
