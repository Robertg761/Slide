package com.slide.engine.lexicon

import com.slide.engine.TestBigrams
import com.slide.engine.TestLexicon
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BigramLoaderTest {

    private val lexicon = TestLexicon.instance
    private val bigrams = TestBigrams.instance

    private val raw: ByteArray by lazy {
        File("src/main/assets/${BigramLoader.ASSET_NAME}").readBytes()
    }

    // region The asset

    @Test
    fun `reads the whole model`() {
        assertTrue("no contexts", bigrams.contextCount > 10_000)
        assertTrue("no pairs", bigrams.pairCount > 100_000)
        assertTrue("more contexts than pairs", bigrams.pairCount >= bigrams.contextCount)
    }

    @Test
    fun `scores pairs the corpus is certain to contain`() {
        for ((previous, next) in listOf(
            "of" to "the", "in" to "the", "i" to "am", "thank" to "you", "it" to "is",
        )) {
            val score = bigrams.score(lexicon.indexOf(previous), lexicon.indexOf(next))
            assertTrue("'$previous $next' scored $score", score > 0f)
            assertTrue("'$previous $next' scored $score", score <= 1f)
        }
    }

    /** A pair nobody has ever written must cost nothing rather than being invented. */
    @Test
    fun `is silent about pairs it does not know`() {
        assertEquals(0f, bigrams.score(lexicon.indexOf("the"), lexicon.indexOf("the")), 0f)
        assertEquals(0f, bigrams.score(-1, lexicon.indexOf("the")), 0f)
        assertEquals(0f, bigrams.score(lexicon.indexOf("the"), -1), 0f)
    }

    @Test
    fun `prefers the likelier continuation`() {
        val of = lexicon.indexOf("of")
        assertTrue(
            "'of the' should beat 'of elephant'",
            bigrams.score(of, lexicon.indexOf("the")) >
                bigrams.score(of, lexicon.indexOf("elephant")),
        )
    }

    @Test
    fun `every successor index is inside the lexicon`() {
        // The decoder indexes straight into the lexicon with whatever comes out of here, so a
        // stray index would be an out-of-bounds crash mid-keystroke rather than a bad suggestion.
        var checked = 0
        for (index in 0 until lexicon.size step 97) {
            if (!bigrams.hasContext(index)) continue
            for (candidate in 0 until lexicon.size step 1009) {
                assertTrue(bigrams.score(index, candidate) in 0f..1f)
                checked++
            }
        }
        assertTrue("nothing was checked", checked > 1000)
    }

    // endregion

    // region Rejecting bad files

    @Test
    fun `rejects a file that is not a bigram model`() {
        val error = assertThrows(IOException::class.java) {
            BigramLoader.read(ByteArrayInputStream("not a model at all".toByteArray()), lexicon.size)
        }
        assertTrue(error.message!!.contains("magic"))
    }

    @Test
    fun `rejects a version it does not understand`() {
        val tampered = raw.copyOf()
        tampered[4] = 99
        val error = assertThrows(IOException::class.java) {
            BigramLoader.read(ByteArrayInputStream(tampered), lexicon.size)
        }
        assertTrue(error.message!!.contains("version 99"))
    }

    /**
     * The failure this check exists for is silent: indices from one lexicon read against another
     * are all perfectly valid numbers, and would simply score the wrong words for ever.
     */
    @Test
    fun `rejects a model built against a different lexicon`() {
        val error = assertThrows(IOException::class.java) {
            BigramLoader.read(ByteArrayInputStream(raw), lexicon.size + 1)
        }
        assertTrue(error.message!!.contains("lexicon"))
    }

    @Test
    fun `rejects a truncated file`() {
        assertThrows(IOException::class.java) {
            BigramLoader.read(ByteArrayInputStream(raw.copyOf(raw.size / 2)), lexicon.size)
        }
    }

    // endregion

    @Test
    fun `looking up a candidate costs almost nothing`() {
        val contexts = (0 until lexicon.size).filter { bigrams.hasContext(it) }.take(400)
        val candidates = (0 until lexicon.size step 401).take(400)

        repeat(3) { for (c in contexts) for (n in candidates) bigrams.score(c, n) }

        val start = System.nanoTime()
        var sink = 0f
        repeat(5) { for (c in contexts) for (n in candidates) sink += bigrams.score(c, n) }
        val perLookupNs = (System.nanoTime() - start).toDouble() / (5.0 * contexts.size * candidates.size)

        assertNotEquals(Float.NaN, sink)
        println("mean bigram lookup: %.1fns".format(perLookupNs))
        // This runs for every candidate on every keystroke, so it has to be nearer a hash lookup
        // than a search. Generous for a desktop JVM; it is here to catch an algorithmic mistake.
        assertTrue("bigram lookup averaged %.1fns".format(perLookupNs), perLookupNs < 2000)
    }
}
