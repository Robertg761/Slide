package com.slide.engine.lexicon

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reads the bigram asset produced by `tools/build_bigrams.py`.
 *
 * Successor indices are stored as varint deltas: they ascend within each context's run, and the
 * gaps between them are usually small, so most take a single byte where a raw index takes four.
 * That is most of the difference between a one-megabyte asset and a three-megabyte one.
 *
 * See the script's docstring for the byte layout.
 */
object BigramLoader {

    const val ASSET_NAME = "bigrams_en.bin"

    private const val TAG = "SlideBigrams"
    private const val MAGIC = 0x53424947 // "SBIG"
    private const val SUPPORTED_VERSION = 1

    /**
     * Loads the model, or returns null if the asset is missing, unreadable, or built against a
     * different lexicon.
     *
     * Null is survivable: every candidate simply scores as it did before there was a model at all,
     * so the keyboard corrects on spelling alone rather than refusing to correct.
     */
    fun load(context: Context, lexicon: Lexicon): Bigrams? = try {
        context.assets.open(ASSET_NAME).use { read(it, lexicon.size) }
    } catch (e: IOException) {
        Log.e(TAG, "Could not read $ASSET_NAME; corrections will not use context", e)
        null
    }

    fun read(input: InputStream, lexiconSize: Int): Bigrams {
        val data = DataInputStream(input.buffered(BUFFER_SIZE))

        val magic = data.readInt()
        if (magic != MAGIC) {
            throw IOException("Not a Slide bigram model: magic was 0x${magic.toString(16)}")
        }
        val version = data.readUnsignedByte()
        if (version != SUPPORTED_VERSION) {
            throw IOException("Bigram model version $version, expected $SUPPORTED_VERSION")
        }

        // Indices only mean anything against the lexicon they were built from. Loading a model
        // built against a different one would not fail, it would quietly score the wrong words.
        val wordCount = data.readInt()
        if (wordCount != lexiconSize) {
            throw IOException("Bigram model is for a $wordCount-word lexicon, this one has $lexiconSize")
        }

        val contextCount = data.readInt()
        val pairCount = data.readInt()
        val blockLength = data.readInt()
        if (contextCount <= 0 || pairCount < contextCount || blockLength < pairCount) {
            throw IOException("Header claims $contextCount contexts, $pairCount pairs, $blockLength bytes")
        }

        val contexts = IntArray(contextCount)
        for (i in 0 until contextCount) contexts[i] = data.readInt()

        val offsets = IntArray(contextCount + 1)
        for (i in 0..contextCount) offsets[i] = data.readInt()
        if (offsets[contextCount] != pairCount) {
            throw IOException("Offsets end at ${offsets[contextCount]}, expected $pairCount pairs")
        }

        val block = ByteArray(blockLength)
        data.readFully(block)

        val successors = IntArray(pairCount)
        var read = 0
        for (context in 0 until contextCount) {
            var previous = 0
            for (slot in offsets[context] until offsets[context + 1]) {
                var shift = 0
                var delta = 0
                while (true) {
                    if (read >= blockLength) throw IOException("Successor block ended early")
                    val byte = block[read++].toInt() and 0xFF
                    delta = delta or ((byte and 0x7F) shl shift)
                    if (byte < 0x80) break
                    shift += 7
                    if (shift > 28) throw IOException("Successor delta is not a valid varint")
                }
                previous += delta
                if (previous >= wordCount) {
                    throw IOException("Successor index $previous is outside the lexicon")
                }
                successors[slot] = previous
            }
        }

        val scores = ByteArray(pairCount)
        data.readFully(scores)

        return Bigrams(contexts, offsets, successors, scores)
    }

    private const val BUFFER_SIZE = 1 shl 16
}
