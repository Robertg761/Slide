package com.slide.engine.lexicon

import com.slide.engine.TestLexicon
import com.slide.engine.TestTrigrams
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrigramLoaderTest {

    private val lexicon = TestLexicon.instance
    private val trigrams = TestTrigrams.instance
    private val raw: ByteArray by lazy {
        File("src/main/assets/${TrigramLoader.ASSET_NAME}").readBytes()
    }

    @Test
    fun `reads the whole shipped model`() {
        assertTrue("no contexts", trigrams.contextCount > 100_000)
        assertTrue("no triples", trigrams.tripleCount > trigrams.contextCount)
    }

    @Test
    fun `rejects a context component outside the lexicon`() {
        val tampered = raw.copyOf()
        ByteBuffer.wrap(tampered).putInt(HEADER_BYTES, lexicon.size)

        val error = assertThrows(IOException::class.java) {
            TrigramLoader.read(ByteArrayInputStream(tampered), lexicon)
        }
        assertTrue(error.message!!, error.message!!.contains("outside"))
    }

    @Test
    fun `rejects contexts that are not strictly ordered`() {
        val tampered = raw.copyOf()
        val buffer = ByteBuffer.wrap(tampered)
        val first = buffer.getLong(HEADER_BYTES)
        val second = buffer.getLong(HEADER_BYTES + Long.SIZE_BYTES)
        buffer.putLong(HEADER_BYTES, second)
        buffer.putLong(HEADER_BYTES + Long.SIZE_BYTES, first)

        val error = assertThrows(IOException::class.java) {
            TrigramLoader.read(ByteArrayInputStream(tampered), lexicon)
        }
        assertTrue(error.message!!, error.message!!.contains("ordered"))
    }

    @Test
    fun `rejects an offset table that stalls`() {
        val tampered = raw.copyOf()
        ByteBuffer.wrap(tampered).putInt(offsetsPosition() + Int.SIZE_BYTES, 0)

        val error = assertThrows(IOException::class.java) {
            TrigramLoader.read(ByteArrayInputStream(tampered), lexicon)
        }
        assertTrue(error.message!!, error.message!!.contains("stalls"))
    }

    @Test
    fun `rejects a semantically empty zero score`() {
        val tampered = raw.copyOf()
        tampered[scoresPosition()] = 0

        val error = assertThrows(IOException::class.java) {
            TrigramLoader.read(ByteArrayInputStream(tampered), lexicon)
        }
        assertTrue(error.message!!, error.message!!.contains("zero score"))
    }

    @Test
    fun `rejects trailing data`() {
        val error = assertThrows(IOException::class.java) {
            TrigramLoader.read(ByteArrayInputStream(raw + byteArrayOf(0x42)), lexicon)
        }
        assertTrue(error.message!!, error.message!!.contains("trailing"))
    }

    private fun offsetsPosition(): Int {
        val contextCount = ByteBuffer.wrap(raw).getInt(CONTEXT_COUNT_OFFSET)
        return HEADER_BYTES + contextCount * Long.SIZE_BYTES
    }

    private fun blockPosition(): Int {
        val contextCount = ByteBuffer.wrap(raw).getInt(CONTEXT_COUNT_OFFSET)
        return offsetsPosition() + (contextCount + 1) * Int.SIZE_BYTES
    }

    private fun scoresPosition(): Int {
        val blockLength = ByteBuffer.wrap(raw).getInt(BLOCK_LENGTH_OFFSET)
        return blockPosition() + blockLength
    }

    private companion object {
        const val CONTEXT_COUNT_OFFSET = 4 + 1 + 4 + 32
        const val BLOCK_LENGTH_OFFSET = CONTEXT_COUNT_OFFSET + 2 * Int.SIZE_BYTES
        const val HEADER_BYTES = CONTEXT_COUNT_OFFSET + 3 * Int.SIZE_BYTES
    }
}
