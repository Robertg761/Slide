package com.slide.engine.gesture

import com.slide.engine.TestLexicon
import com.slide.engine.lexicon.Lexicon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureDecoderTest {

    private val keys = GestureFixtures.qwerty()
    private val decoder = GestureDecoder(lexicon)

    /** Words chosen to cover short and long, straight-line and zigzag, and repeated letters. */
    private val corpus = listOf(
        "hello", "world", "keyboard", "swipe", "the", "and", "you", "that", "have",
        "about", "would", "there", "their", "which", "people", "because", "little",
        "morning", "tomorrow", "different", "something", "important", "letter",
        "coffee", "google", "phone", "message", "typing", "great", "thanks",
    )

    private fun topWords(word: String, jitter: Float, smoothing: Int, seed: Long = 42L): List<String> {
        val points = GestureFixtures.trace(word, keys, jitter, smoothing, seed = seed)
        return decoder.decode(points, keys).map { it.word.lowercase() }
    }

    @Test
    fun `decodes a clean trace exactly`() {
        for (word in corpus) {
            val results = topWords(word, jitter = 0f, smoothing = 0)
            val winner = results.firstOrNull()

            // Some words are geometrically indistinguishable from a neighbour -- see
            // GestureFixtures.tracesIdentically. Requiring top-1 there would be demanding
            // something no shape-based decoder can deliver; it must still rank the word highly.
            if (winner != null && winner != word && GestureFixtures.tracesIdentically(word, winner, keys)) {
                assertTrue(
                    "'$word' traces identically to '$winner' but fell outside the top three: $results",
                    word in results.take(3),
                )
                continue
            }

            assertEquals("Clean trace for '$word' decoded as $results", word, winner)
        }
    }

    @Test
    fun `deterministic decoder ranks common apostrophe contraction from its alphabetic path`() {
        val results = topWords("that's", jitter = 9f, smoothing = 3)

        assertEquals("Noisy t-h-a-t-s trace decoded as $results", "that's", results.firstOrNull())
        assertTrue("Unrelated proper noun should not survive a clear contraction trace: $results", "teresa" !in results)
    }

    @Test
    fun `decodes a realistic trace`() {
        val missed = corpus.filter { word ->
            topWords(word, jitter = 9f, smoothing = 3).firstOrNull() != word
        }
        assertTrue("Top-1 misses with realistic noise: $missed", missed.size <= corpus.size / 10)
    }

    @Test
    fun `keeps the intended word in the top five under heavy noise`() {
        val missed = corpus.filter { word -> word !in topWords(word, jitter = 18f, smoothing = 4) }
        assertTrue("Fell out of top-5 under heavy noise: $missed", missed.size <= corpus.size / 10)
    }

    @Test
    fun `is stable across different random hands`() {
        for (seed in 1L..8L) {
            val missed = corpus.filter { word ->
                topWords(word, jitter = 9f, smoothing = 3, seed = seed).firstOrNull() != word
            }
            assertTrue("Seed $seed missed: $missed", missed.size <= corpus.size / 5)
        }
    }

    @Test
    fun `frequency separates words that trace identically`() {
        // "hello" and "helo" collapse to the same path, so only the language channel can choose.
        val results = topWords("hello", jitter = 0f, smoothing = 0)
        assertEquals("hello", results.first())
    }

    @Test
    fun `ignores taps and twitches`() {
        val tap = listOf(GesturePoint(300f, 80f, 0L), GesturePoint(301f, 81f, 8L))
        assertTrue(decoder.decode(tap, keys).isEmpty())

        val twitch = List(12) { GesturePoint(300f + it * 0.5f, 80f, it * 8L) }
        assertTrue(decoder.decode(twitch, keys).isEmpty())
    }

    @Test
    fun `returns nothing rather than guessing when the trace starts off the letters`() {
        val points = List(20) { GesturePoint(-500f, -500f + it * 10f, it * 8L) }
        assertTrue(decoder.decode(points, keys).isEmpty())
    }

    /**
     * A word whose letters collapse to a single key centre has no path to compare against, so it
     * scores negative infinity — which is not "the worst candidate" but "not a candidate". The
     * board must not seat it merely because it has an empty slot, and the classic decoder is what
     * the user sees until the neural model loads, and forever if it never does.
     */
    @Test
    fun `a word with no scorable shape is never offered`() {
        // A tight loop on one key: too long to be dismissed as a twitch, and the only word it can
        // spell is the degenerate "mm".
        val radius = keys.keyWidth * 0.45f
        val loop = List(24) { i ->
            val angle = i / 24f * 2f * PI.toFloat()
            GesturePoint(
                x = keys.centerX('m') + radius * cos(angle),
                y = keys.centerY('m') + radius * sin(angle),
                timeMs = i * 8L,
            )
        }

        val results = decoder.decode(loop, keys)
        // Without this the test could pass by the loop never reaching the scorer at all: it is
        // "mm" and one other word that survive pruning here, and only "mm" is unscorable.
        assertTrue("the loop must survive pruning for this to test anything", decoder.lastScoredCount > 0)
        assertTrue(
            "a single-corner word reached the strip: $results",
            results.none { it.word.lowercase() == "mm" },
        )
        assertTrue(
            "no candidate may carry a non-finite score: $results",
            results.all { it.score.isFinite() },
        )
    }

    @Test
    fun `blocks offensive words only when asked`() {
        // Pick a long, purely alphabetic one: short words and apostrophe forms collapse to traces
        // shared by dozens of other words, which would test ranking rather than the filter.
        val offensive = (0 until lexicon.size)
            .filter { index ->
                lexicon.isOffensive(index) &&
                    lexicon.lengthAt(index) >= 6 &&
                    lexicon.lowercaseAt(index).all { it in 'a'..'z' }
            }
            .maxByOrNull { lexicon.frequencyAt(it) }
        assertTrue("No offensive word found to test with", offensive != null)
        val word = lexicon.lowercaseAt(offensive!!)

        // A wider result window than the default five, so this asserts on the filter and not on
        // whether the word happened to win its neighbourhood.
        val wide = GestureDecoder(lexicon, DecoderConfig(maxResults = 25))
        val points = GestureFixtures.trace(word, keys)
        val blocked = wide.decode(points, keys, blockOffensive = true).map { it.word.lowercase() }
        val allowed = wide.decode(points, keys, blockOffensive = false).map { it.word.lowercase() }

        assertTrue("'$word' should be withheld when blocking, got $blocked", word !in blocked)
        assertTrue("'$word' should be offered when not blocking, got $allowed", word in allowed)
    }

    @Test
    fun `pruning keeps the scored set small`() {
        for (word in corpus) {
            decoder.decode(GestureFixtures.trace(word, keys, jitter = 9f, smoothing = 3), keys)
            assertTrue(
                "Pruning let ${decoder.lastScoredCount} words through for '$word'",
                decoder.lastScoredCount < 2000,
            )
        }
    }

    @Test
    fun `decodes fast enough to feel instant`() {
        val traces = corpus.map { GestureFixtures.trace(it, keys, jitter = 9f, smoothing = 3) }
        repeat(3) { traces.forEach { decoder.decode(it, keys) } } // warm up the JIT

        val started = System.nanoTime()
        repeat(10) { traces.forEach { decoder.decode(it, keys) } }
        val perDecodeMs = (System.nanoTime() - started) / 1e6 / (10 * traces.size)

        // Generous for a desktop JVM: the point is to catch an algorithmic blow-up, not to
        // predict phone latency. Real timings come from the device.
        assertTrue("Decoding averaged %.2fms".format(perDecodeMs), perDecodeMs < 25.0)
        println("mean decode: %.2fms over ${traces.size} words".format(perDecodeMs))
    }

    private companion object {
        val lexicon: Lexicon get() = TestLexicon.instance
    }
}
