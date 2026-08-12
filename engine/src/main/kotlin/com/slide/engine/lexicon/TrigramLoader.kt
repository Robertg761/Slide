package com.slide.engine.lexicon

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Reads the two-word-context model produced by `tools/build_bigrams.py`. */
object TrigramLoader {
    const val ASSET_NAME = "trigrams_en.bin"

    private const val TAG = "SlideTrigrams"
    private const val MAGIC = 0x53545249 // STRI
    private const val VERSION = 1
    private const val FINGERPRINT_BYTES = 32

    /**
     * Loads the model, or returns null when it cannot be read or does not match the lexicon.
     *
     * As with [BigramLoader], every [Exception] is caught rather than [IOException] alone: a file
     * of the right length holding the wrong bytes fails an index check, and losing two-word
     * context has to stay better than a keyboard that dies whenever it opens.
     */
    fun load(context: Context, lexicon: Lexicon): Trigrams? = try {
        context.assets.open(ASSET_NAME).use { read(it, lexicon) }
    } catch (e: Exception) {
        Log.e(TAG, "Could not read $ASSET_NAME; using one-word context", e)
        null
    }

    fun read(input: InputStream, lexicon: Lexicon): Trigrams {
        val data = DataInputStream(input.buffered(1 shl 16))
        if (data.readInt() != MAGIC) throw IOException("Not a Slide trigram model")
        val version = data.readUnsignedByte()
        if (version != VERSION) throw IOException("Trigram model version $version, expected $VERSION")
        val wordCount = data.readInt()
        if (wordCount != lexicon.size) throw IOException("Trigram lexicon size does not match")
        val fingerprint = ByteArray(FINGERPRINT_BYTES).also(data::readFully)
        if (!matchesLexicon(lexicon, fingerprint)) throw IOException("Trigram lexicon hash does not match")

        val contextCount = data.readInt()
        val tripleCount = data.readInt()
        val blockLength = data.readInt()
        if (
            contextCount !in 1..MAX_CONTEXT_COUNT ||
            tripleCount !in contextCount..MAX_TRIPLE_COUNT ||
            blockLength !in tripleCount..minOf(MAX_BLOCK_LENGTH, tripleCount * MAX_VARINT_BYTES)
        ) {
            throw IOException("Invalid trigram header")
        }
        val contexts = LongArray(contextCount)
        for (index in contexts.indices) {
            val context = data.readLong()
            val older = (context ushr Int.SIZE_BITS).toInt()
            val previous = context.toInt()
            if (older !in 0 until wordCount || previous !in 0 until wordCount) {
                throw IOException("Trigram context ($older, $previous) is outside the lexicon")
            }
            if (index > 0 && context <= contexts[index - 1]) {
                throw IOException("Trigram contexts are not strictly ordered at index $index")
            }
            contexts[index] = context
        }
        val offsets = IntArray(contextCount + 1) { data.readInt() }
        if (offsets.last() != tripleCount) throw IOException("Invalid trigram offsets")
        // Same reasoning as the bigram table: offsets that go backwards are still in range, so
        // they mis-attribute successors silently instead of failing. See BigramLoader.read.
        if (offsets[0] != 0) throw IOException("Trigram offsets start at ${offsets[0]}, expected 0")
        for (i in 1..contextCount) {
            if (offsets[i] <= offsets[i - 1]) {
                throw IOException("Trigram offset $i goes backwards or stalls")
            }
        }
        val block = ByteArray(blockLength).also(data::readFully)

        val successors = IntArray(tripleCount)
        var read = 0
        for (context in 0 until contextCount) {
            var previous = 0
            for (slot in offsets[context] until offsets[context + 1]) {
                var shift = 0
                var delta = 0
                while (true) {
                    if (read >= block.size) throw IOException("Trigram successor block ended early")
                    val byte = block[read++].toInt() and 0xFF
                    if (shift == 28 && (byte and 0xF8) != 0) {
                        throw IOException("Trigram successor varint overflows 32 bits")
                    }
                    delta = delta or ((byte and 0x7F) shl shift)
                    if (byte < 0x80) {
                        if (shift > 0 && byte == 0) {
                            throw IOException("Trigram successor varint is not canonical")
                        }
                        break
                    }
                    shift += 7
                    if (shift > 28) throw IOException("Invalid trigram successor varint")
                }
                val prior = previous
                previous += delta
                // Negative too: the fifth byte of a varint reaches the sign bit. See BigramLoader.
                if (previous < 0 || previous >= wordCount) {
                    throw IOException("Trigram successor $previous is outside the lexicon")
                }
                if (slot > offsets[context] && previous <= prior) {
                    throw IOException("Trigram successors are not strictly ordered in context $context")
                }
                successors[slot] = previous
            }
        }
        if (read != blockLength) {
            throw IOException("Trigram successor block has ${blockLength - read} unused bytes")
        }
        val scores = ByteArray(tripleCount).also(data::readFully)
        if (scores.any { it == 0.toByte() }) throw IOException("Trigram model contains a zero score")
        if (data.read() != -1) throw IOException("Trigram model has trailing data")
        return Trigrams(contexts, offsets, successors, scores)
    }

    private fun matchesLexicon(lexicon: Lexicon, expected: ByteArray): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        for (index in 0 until lexicon.size) {
            for (position in 0 until lexicon.lengthAt(index)) {
                digest.update(lexicon.charAt(index, position).code.toByte())
            }
            digest.update(0)
        }
        return MessageDigest.isEqual(digest.digest(), expected)
    }

    private const val MAX_CONTEXT_COUNT = 2_000_000
    private const val MAX_TRIPLE_COUNT = 20_000_000
    private const val MAX_BLOCK_LENGTH = 100 * 1024 * 1024
    private const val MAX_VARINT_BYTES = 5
}
