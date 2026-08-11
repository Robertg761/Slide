package com.slide.engine.lexicon

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reads the packed lexicon asset produced by `tools/build_lexicon.py`.
 *
 * The asset is front-coded: each entry stores only the suffix that differs from the entry before
 * it. That halves the file, and costs nothing to undo because we read straight through once.
 *
 * See the script's docstring for the byte layout.
 */
object LexiconLoader {

    const val ASSET_NAME = "lexicon_en.bin"

    private const val TAG = "SlideLexicon"
    private const val MAGIC = 0x534C4558 // "SLEX"
    private const val SUPPORTED_VERSION = 1

    /**
     * Loads the lexicon, or returns null if the asset is missing or unreadable.
     *
     * Null is a survivable outcome: the keyboard still types, it just cannot decode gestures.
     * That is a much better failure than refusing to open at all — which is also why every
     * [Exception] is caught rather than [IOException] alone. A file of the right length holding
     * the wrong bytes fails an index check, not a read, and this is called from an IME coroutine
     * with nothing above it to catch anything. Errors (out of memory and friends) still propagate.
     */
    fun load(context: Context): Lexicon? = try {
        context.assets.open(ASSET_NAME).use(::read)
    } catch (e: Exception) {
        Log.e(TAG, "Could not read $ASSET_NAME; gesture typing will be unavailable", e)
        null
    }

    fun read(input: InputStream): Lexicon {
        val data = DataInputStream(input.buffered(BUFFER_SIZE))

        val magic = data.readInt()
        if (magic != MAGIC) {
            throw IOException("Not a Slide lexicon: magic was 0x${magic.toString(16)}")
        }
        val version = data.readUnsignedByte()
        if (version != SUPPORTED_VERSION) {
            throw IOException("Lexicon version $version, expected $SUPPORTED_VERSION")
        }

        val wordCount = data.readInt()
        val blockLength = data.readInt()
        // Front coding shrinks the file but expands on decode, so the character count cannot be
        // derived from the block length; the writer records it for us.
        val charCount = data.readInt()
        if (wordCount <= 0 || blockLength <= 0 || charCount < wordCount) {
            throw IOException("Lexicon header claims $wordCount words, $blockLength bytes, $charCount chars")
        }

        val block = ByteArray(blockLength)
        data.readFully(block)

        val chars = CharArray(charCount)
        val offsets = IntArray(wordCount + 1)

        var read = 0
        var written = 0
        for (index in 0 until wordCount) {
            offsets[index] = written

            // The two length bytes and the suffix they introduce all have to be inside the block,
            // and the word they build has to fit the character count the header promised. Every
            // one of these is an index into an array on the next line, so a corrupt file that
            // happens to be the right length would otherwise crash the keyboard rather than
            // disable gesture typing.
            if (read + 2 > blockLength) {
                throw IOException("Entry $index starts past the end of the block")
            }
            val shared = block[read].toInt() and 0xFF
            val suffixLength = block[read + 1].toInt() and 0xFF
            read += 2
            if (read + suffixLength > blockLength) {
                throw IOException("Entry $index claims a $suffixLength-byte suffix past the block")
            }

            // The shared prefix is carried forward from the previous word, which sits immediately
            // behind us in the same buffer. For the first entry that span is empty, so a non-zero
            // prefix there is caught by the same check.
            val previousStart = if (index == 0) 0 else offsets[index - 1]
            if (shared > written - previousStart) {
                // A prefix longer than the word it refers to means the file is corrupt. Failing
                // here beats silently emitting garbage words into the user's messages.
                throw IOException("Entry $index claims a $shared-char prefix of a shorter word")
            }
            if (written + shared + suffixLength > charCount) {
                throw IOException("Entry $index runs past the $charCount characters the header declared")
            }

            for (i in 0 until shared) {
                chars[written++] = chars[previousStart + i]
            }
            // Every word is ASCII by construction (see WORD_RE in the build script), so the UTF-8
            // bytes map one-to-one onto chars without a decoder.
            for (i in 0 until suffixLength) {
                chars[written++] = (block[read + i].toInt() and 0xFF).toChar()
            }
            read += suffixLength
        }
        offsets[wordCount] = written

        val frequencies = ByteArray(wordCount)
        data.readFully(frequencies)
        val flags = ByteArray(wordCount)
        data.readFully(flags)

        return Lexicon(chars, offsets, frequencies, flags)
    }

    private const val BUFFER_SIZE = 1 shl 16
}
