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

    fun load(context: Context, lexicon: Lexicon): Trigrams? = try {
        context.assets.open(ASSET_NAME).use { read(it, lexicon) }
    } catch (e: IOException) {
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
        if (contextCount <= 0 || tripleCount < contextCount || blockLength < tripleCount) {
            throw IOException("Invalid trigram header")
        }
        val contexts = LongArray(contextCount) { data.readLong() }
        val offsets = IntArray(contextCount + 1) { data.readInt() }
        if (offsets.last() != tripleCount) throw IOException("Invalid trigram offsets")
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
                    delta = delta or ((byte and 0x7F) shl shift)
                    if (byte < 0x80) break
                    shift += 7
                    if (shift > 28) throw IOException("Invalid trigram successor varint")
                }
                previous += delta
                if (previous >= wordCount) throw IOException("Trigram successor outside lexicon")
                successors[slot] = previous
            }
        }
        val scores = ByteArray(tripleCount).also(data::readFully)
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
}
