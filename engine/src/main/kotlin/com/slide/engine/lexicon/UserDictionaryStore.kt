package com.slide.engine.lexicon

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Keeps a [UserDictionary] on disk, in the app's private storage and nowhere else.
 *
 * This is the most personal thing Slide holds — it is, fairly literally, a list of the words
 * someone uses that most people do not. It never leaves the device, is never bundled into a
 * backup that could carry it off one (see `allowBackup` handling), and is written as plain text so
 * that anyone who wants to read or delete it can.
 *
 * Writes go to a temporary file and are renamed into place, so a process killed mid-save loses the
 * newest word rather than the whole dictionary.
 */
class UserDictionaryStore(private val file: File, private val pairFile: File) {

    constructor(context: Context) : this(
        File(context.filesDir, FILE_NAME),
        File(context.filesDir, PAIR_FILE_NAME),
    )

    fun load(into: UserDictionary) {
        if (!file.exists()) return
        try {
            val restored = file.readLines().mapNotNull { line ->
                val separator = line.lastIndexOf('\t')
                if (separator <= 0) return@mapNotNull null
                val count = line.substring(separator + 1).toIntOrNull() ?: return@mapNotNull null
                line.substring(0, separator) to count
            }
            into.restore(restored)
            Log.i(TAG, "Restored ${restored.size} learned words")
        } catch (e: IOException) {
            // A corrupt personal dictionary is not worth refusing to type over.
            Log.w(TAG, "Could not read the learned words; starting empty", e)
        }
    }

    fun save(from: UserDictionary) {
        writeAtomically(file) { writer ->
            for ((word, count) in from.entries()) {
                writer.write(word)
                writer.write("\t")
                writer.write(count.toString())
                writer.newLine()
            }
        }
    }

    fun load(into: UserBigrams) {
        if (!pairFile.exists()) return
        try {
            val restored = pairFile.readLines().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 3) return@mapNotNull null
                val count = parts[2].toIntOrNull() ?: return@mapNotNull null
                Triple(parts[0], parts[1], count)
            }
            into.restore(restored)
            Log.i(TAG, "Restored ${restored.size} learned pairs")
        } catch (e: IOException) {
            Log.w(TAG, "Could not read the learned pairs; starting empty", e)
        }
    }

    fun save(from: UserBigrams) {
        writeAtomically(pairFile) { writer ->
            for ((previous, next, count) in from.entries()) {
                writer.write(previous)
                writer.write("\t")
                writer.write(next)
                writer.write("\t")
                writer.write(count.toString())
                writer.newLine()
            }
        }
    }

    /** Deletes everything learned, for the settings screen's benefit. */
    fun delete() {
        file.delete()
        pairFile.delete()
    }

    /**
     * Writes through a temporary file and renames it into place.
     *
     * A process killed mid-save then loses the newest word rather than the whole dictionary, which
     * for a file that only ever grows is the difference between a hiccup and starting again.
     */
    private fun writeAtomically(target: File, body: (java.io.BufferedWriter) -> Unit) {
        try {
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.bufferedWriter().use(body)
            if (!temporary.renameTo(target)) {
                temporary.delete()
                Log.w(TAG, "Could not move ${target.name} into place")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not save ${target.name}", e)
        }
    }

    private companion object {
        const val TAG = "SlideUserDict"
        const val FILE_NAME = "learned_words.txt"
        const val PAIR_FILE_NAME = "learned_pairs.txt"
    }
}
