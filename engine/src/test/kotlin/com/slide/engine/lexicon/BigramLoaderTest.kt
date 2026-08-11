package com.slide.engine.lexicon

import com.slide.engine.TestBigrams
import com.slide.engine.TestLexicon
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
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
            BigramLoader.read(ByteArrayInputStream("not a model at all".toByteArray()), lexicon)
        }
        assertTrue(error.message!!.contains("magic"))
    }

    @Test
    fun `rejects a version it does not understand`() {
        val tampered = raw.copyOf()
        tampered[4] = 99
        val error = assertThrows(IOException::class.java) {
            BigramLoader.read(ByteArrayInputStream(tampered), lexicon)
        }
        assertTrue(error.message!!.contains("version 99"))
    }

    /**
     * The failure this check exists for is silent: indices from one lexicon read against another
     * are all perfectly valid numbers, and would simply score the wrong words for ever.
     */
    @Test
    fun `rejects a model built against a different lexicon`() {
        val tampered = raw.copyOf()
        ByteBuffer.wrap(tampered, WORD_COUNT_OFFSET, Int.SIZE_BYTES).putInt(lexicon.size + 1)
        val error = assertThrows(IOException::class.java) {
            BigramLoader.read(ByteArrayInputStream(tampered), lexicon)
        }
        assertTrue(error.message!!.contains("lexicon"))
    }

    @Test
    fun `rejects a changed lexicon even when its word count is identical`() {
        val lexiconBytes = File("src/main/assets/${LexiconLoader.ASSET_NAME}").readBytes()
        val changedBytes = lexiconBytes.copyOf()
        // The first front-coded word starts after the 17-byte header and its two length bytes.
        // Changing that byte preserves every header count while changing the index-to-word map.
        changedBytes[FIRST_WORD_OFFSET] =
            if (changedBytes[FIRST_WORD_OFFSET] == 'a'.code.toByte()) 'b'.code.toByte()
            else 'a'.code.toByte()
        val changedLexicon = LexiconLoader.read(ByteArrayInputStream(changedBytes))
        assertEquals(lexicon.size, changedLexicon.size)

        val error = assertThrows(IOException::class.java) {
            BigramLoader.read(ByteArrayInputStream(raw), changedLexicon)
        }
        assertTrue(error.message!!.contains("fingerprint"))
    }

    @Test
    fun `rejects a truncated file`() {
        assertThrows(IOException::class.java) {
            BigramLoader.read(ByteArrayInputStream(raw.copyOf(raw.size / 2)), lexicon)
        }
    }

    /**
     * A negative offset is the crash: the successor loop writes at `successors[offset]`, so the
     * model used to take the keyboard down with an out-of-bounds write rather than load without
     * context. `load` only promises to survive an [IOException].
     */
    @Test
    fun `rejects an offset table that goes negative`() {
        val error = assertThrows(IOException::class.java) {
            BigramLoader.read(ByteArrayInputStream(withOffset(1, -1)), lexicon)
        }
        assertTrue(error.message!!, error.message!!.contains("backwards"))
    }

    /**
     * This one never crashed, which is worse: offsets that go backwards but stay inside the array
     * simply hand one context another context's successors, and every correction after that is
     * quietly scored against words the corpus never saw follow.
     */
    @Test
    fun `rejects an offset table that goes backwards`() {
        val buffer = ByteBuffer.wrap(raw)
        val first = buffer.getInt(offsetsPosition() + Int.SIZE_BYTES)
        val second = buffer.getInt(offsetsPosition() + 2 * Int.SIZE_BYTES)
        assertTrue("the asset's own offsets do not ascend", second >= first)

        val error = assertThrows(IOException::class.java) {
            // Swapping a neighbouring pair leaves every value in range and the table still ending
            // at pairCount, so only an ordering check can tell.
            BigramLoader.read(ByteArrayInputStream(withOffset(1, second, 2, first)), lexicon)
        }
        assertTrue(error.message!!, error.message!!.contains("backwards"))
    }

    /**
     * A successor index is a varint, and a varint that runs to five bytes reaches the sign bit.
     * The resulting negative index passed the "outside the lexicon" check, was stored, and then
     * crashed `Lexicon.wordAt` on the keystroke that surfaced the suggestion — a load failure
     * turning into a crash several keypresses away is exactly what these loaders must not do.
     */
    @Test
    fun `rejects a successor index that decodes as negative`() {
        val tampered = raw.copyOf()
        // 8 shl 28 is Int.MIN_VALUE: four continuation bytes contributing nothing, then the byte
        // that lands on the sign bit.
        val varint = byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x08)
        varint.copyInto(tampered, blockPosition())

        val error = assertThrows(IOException::class.java) {
            BigramLoader.read(ByteArrayInputStream(tampered), lexicon)
        }
        // Naming the index proves this is the sign check failing rather than some later mishap.
        assertTrue(error.message!!, error.message!!.contains("${Int.MIN_VALUE}"))
    }

    /** Overwrites entries of the offsets table, given as index-to-value pairs. */
    private fun withOffset(vararg indexThenValue: Int): ByteArray {
        val tampered = raw.copyOf()
        val buffer = ByteBuffer.wrap(tampered)
        val position = offsetsPosition()
        for (i in indexThenValue.indices step 2) {
            buffer.putInt(position + indexThenValue[i] * Int.SIZE_BYTES, indexThenValue[i + 1])
        }
        return tampered
    }

    /** The offsets table follows the header and the context ids. */
    private fun offsetsPosition(): Int {
        val contextCount = ByteBuffer.wrap(raw).getInt(CONTEXT_COUNT_OFFSET)
        return HEADER_BYTES + contextCount * Int.SIZE_BYTES
    }

    /** The varint block follows the offsets table, whose last entry is the pair count. */
    private fun blockPosition(): Int {
        val contextCount = ByteBuffer.wrap(raw).getInt(CONTEXT_COUNT_OFFSET)
        return offsetsPosition() + (contextCount + 1) * Int.SIZE_BYTES
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

    private companion object {
        const val WORD_COUNT_OFFSET = 5
        const val FIRST_WORD_OFFSET = 19

        /** Magic, version, word count, lexicon fingerprint, then three counts. */
        const val CONTEXT_COUNT_OFFSET = 4 + 1 + 4 + 32
        const val HEADER_BYTES = CONTEXT_COUNT_OFFSET + 3 * Int.SIZE_BYTES
    }
}
