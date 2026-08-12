package com.slide.engine.lexicon

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

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
    private const val SUPPORTED_VERSION = 2
    private const val LEXICON_FINGERPRINT_BYTES = 32

    /**
     * Loads the model, or returns null if the asset is missing, unreadable, or built against a
     * different lexicon.
     *
     * Null is survivable: every candidate simply scores as it did before there was a model at all,
     * so the keyboard corrects on spelling alone rather than refusing to correct. Catching every
     * [Exception] rather than [IOException] alone keeps it survivable for a file that is the right
     * length but the wrong content, which fails an index check rather than a read; there is no
     * exception handler on the IME scope this runs in. Errors still propagate.
     */
    fun load(context: Context, lexicon: Lexicon): Bigrams? = try {
        context.assets.open(ASSET_NAME).use { read(it, lexicon) }
    } catch (e: Exception) {
        Log.e(TAG, "Could not read $ASSET_NAME; corrections will not use context", e)
        null
    }

    fun read(input: InputStream, lexicon: Lexicon): Bigrams {
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
        if (wordCount != lexicon.size) {
            throw IOException("Bigram model is for a $wordCount-word lexicon, this one has ${lexicon.size}")
        }
        val lexiconFingerprint = ByteArray(LEXICON_FINGERPRINT_BYTES)
        data.readFully(lexiconFingerprint)
        if (!matchesLexiconFingerprint(lexicon, lexiconFingerprint)) {
            throw IOException("Bigram model lexicon fingerprint does not match the loaded lexicon")
        }

        val contextCount = data.readInt()
        val pairCount = data.readInt()
        val blockLength = data.readInt()
        if (
            contextCount !in 1..minOf(wordCount, MAX_CONTEXT_COUNT) ||
            pairCount !in contextCount..MAX_PAIR_COUNT ||
            blockLength !in pairCount..minOf(MAX_BLOCK_LENGTH, pairCount * MAX_VARINT_BYTES)
        ) {
            throw IOException("Header claims $contextCount contexts, $pairCount pairs, $blockLength bytes")
        }

        val contexts = IntArray(contextCount)
        for (i in 0 until contextCount) {
            val context = data.readInt()
            if (context !in 0 until wordCount) {
                throw IOException("Context $context is outside the lexicon")
            }
            if (i > 0 && context <= contexts[i - 1]) {
                throw IOException("Contexts are not strictly ordered at index $i")
            }
            contexts[i] = context
        }

        val offsets = IntArray(contextCount + 1)
        for (i in 0..contextCount) offsets[i] = data.readInt()
        if (offsets[contextCount] != pairCount) {
            throw IOException("Offsets end at ${offsets[contextCount]}, expected $pairCount pairs")
        }
        // Each context's successors are the half-open range between neighbouring offsets, and
        // `Bigrams.score` binary-searches inside it. Offsets that go backwards stay in range and
        // so never crash: they quietly hand one context another's successors, for ever. Checking
        // the table once here is the only place that can tell the difference.
        if (offsets[0] != 0) throw IOException("Offsets start at ${offsets[0]}, expected 0")
        for (i in 1..contextCount) {
            if (offsets[i] <= offsets[i - 1]) {
                throw IOException(
                    "Offset $i goes backwards or stalls: ${offsets[i - 1]} then ${offsets[i]}",
                )
            }
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
                    if (shift == 28 && (byte and 0xF8) != 0) {
                        throw IOException("Successor delta overflows a 32-bit varint")
                    }
                    delta = delta or ((byte and 0x7F) shl shift)
                    if (byte < 0x80) {
                        if (shift > 0 && byte == 0) {
                            throw IOException("Successor delta uses a non-canonical varint")
                        }
                        break
                    }
                    shift += 7
                    if (shift > 28) throw IOException("Successor delta is not a valid varint")
                }
                val prior = previous
                previous += delta
                // Negative as well as too large: a varint may run to five bytes, and the fifth
                // carries the sign bit, so a corrupt one decodes to a negative index that this
                // check used to wave through. Nothing crashes here — it crashes later, in
                // `wordAt` on the keypress that reads the suggestion.
                if (previous < 0 || previous >= wordCount) {
                    throw IOException("Successor index $previous is outside the lexicon")
                }
                if (slot > offsets[context] && previous <= prior) {
                    throw IOException("Successors are not strictly ordered in context $context")
                }
                successors[slot] = previous
            }
        }
        if (read != blockLength) {
            throw IOException("Successor block has ${blockLength - read} unused bytes")
        }

        val scores = ByteArray(pairCount)
        data.readFully(scores)
        if (scores.any { it == 0.toByte() }) throw IOException("Bigram model contains a zero score")
        if (data.read() != -1) throw IOException("Bigram model has trailing data")

        return Bigrams(contexts, offsets, successors, scores)
    }

    /**
     * Hashes the exact index order without allocating a String for each of the 160k words.
     *
     * Both asset builders guarantee lowercase ASCII. NUL cannot occur in a word, so delimiting
     * each one with it keeps sequences such as ("ab", "c") distinct from ("a", "bc").
     */
    private fun matchesLexiconFingerprint(lexicon: Lexicon, expected: ByteArray): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(FINGERPRINT_BUFFER_BYTES)
        var buffered = 0

        fun append(value: Byte) {
            if (buffered == buffer.size) {
                digest.update(buffer)
                buffered = 0
            }
            buffer[buffered++] = value
        }

        for (index in 0 until lexicon.size) {
            for (position in 0 until lexicon.lengthAt(index)) {
                append(lexicon.charAt(index, position).code.toByte())
            }
            append(0)
        }
        if (buffered > 0) digest.update(buffer, 0, buffered)
        return MessageDigest.isEqual(digest.digest(), expected)
    }

    private const val BUFFER_SIZE = 1 shl 16
    private const val FINGERPRINT_BUFFER_BYTES = 1 shl 12
    private const val MAX_CONTEXT_COUNT = 2_000_000
    private const val MAX_PAIR_COUNT = 10_000_000
    private const val MAX_BLOCK_LENGTH = 50 * 1024 * 1024
    private const val MAX_VARINT_BYTES = 5
}
