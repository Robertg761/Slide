package com.slide.core.emoji

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reads the packed emoji asset produced by `tools/build_emoji.py`.
 *
 * See the script's docstring for the byte layout.
 */
object EmojiLoader {

    const val ASSET_NAME = "emoji.bin"

    private const val TAG = "SlideEmoji"
    private const val MAGIC = 0x53454D4A // "SEMJ"
    private const val SUPPORTED_VERSION = 1

    /**
     * Loads the catalogue, or returns null if the asset is missing or unreadable.
     *
     * As with the lexicon, null is survivable: the emoji key stops working and everything else
     * about the keyboard carries on, which is far better than refusing to open. That is also why
     * every [Exception] is caught rather than [IOException] alone: a file of the right length
     * holding the wrong content fails an index check rather than a read, and this runs from an IME
     * coroutine with no exception handler above it. Errors still propagate.
     */
    fun load(context: Context): EmojiData? = try {
        context.assets.open(ASSET_NAME).use(::read)
    } catch (e: Exception) {
        Log.e(TAG, "Could not read $ASSET_NAME; the emoji picker will be unavailable", e)
        null
    }

    fun read(input: InputStream): EmojiData {
        val data = DataInputStream(input.buffered(BUFFER_SIZE))

        val magic = data.readInt()
        if (magic != MAGIC) {
            throw IOException("Not a Slide emoji catalogue: magic was 0x${magic.toString(16)}")
        }
        val version = data.readUnsignedByte()
        if (version != SUPPORTED_VERSION) {
            throw IOException("Emoji catalogue version $version, expected $SUPPORTED_VERSION")
        }

        val categoryCount = data.readUnsignedByte()
        if (categoryCount == 0) throw IOException("Emoji catalogue has no categories")
        val categories = List(categoryCount) { data.readShortString() }

        val entryCount = data.readUnsignedShort()
        if (entryCount == 0) throw IOException("Emoji catalogue has no entries")

        val emoji = arrayOfNulls<String>(entryCount)
        val categoryOf = ByteArray(entryCount)
        val variants = arrayOfNulls<Array<String>>(entryCount)
        val searchText = arrayOfNulls<String>(entryCount)

        for (index in 0 until entryCount) {
            val category = data.readUnsignedByte()
            if (category >= categoryCount) {
                throw IOException("Entry $index names category $category of $categoryCount")
            }
            categoryOf[index] = category.toByte()
            emoji[index] = data.readShortString()

            val variantCount = data.readUnsignedByte()
            if (variantCount != 0) {
                if (variantCount != EmojiData.TONE_COUNT) {
                    // The writer emits all five tones or none. A partial row would leave the
                    // long-press popup with gaps that mean nothing to whoever is looking at it.
                    throw IOException("Entry $index has $variantCount tones, expected 5 or 0")
                }
                variants[index] = Array(variantCount) { data.readShortString() }
            }

            val textLength = data.readUnsignedShort()
            val text = ByteArray(textLength)
            data.readFully(text)
            searchText[index] = String(text, Charsets.UTF_8)
        }

        @Suppress("UNCHECKED_CAST")
        return EmojiData(
            categories = categories,
            emoji = emoji as Array<String>,
            categoryOf = categoryOf,
            variants = variants,
            searchText = searchText as Array<String>,
        )
    }

    private fun DataInputStream.readShortString(): String {
        val bytes = ByteArray(readUnsignedByte())
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private const val BUFFER_SIZE = 1 shl 16
}
