package com.slide.engine.lexicon

import android.content.Context
import android.util.Log
import com.slide.engine.suggest.SpatialTouchModel
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

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
class UserDictionaryStore(
    private val file: File,
    private val pairFile: File,
    /** Staging lives outside Android's backup domain; tests default to the target directory. */
    private val temporaryDirectory: File = file.absoluteFile.parentFile
        ?: throw IllegalArgumentException("Learned-word file has no parent directory"),
    private val spatialFile: File = File(
        file.absoluteFile.parentFile
            ?: throw IllegalArgumentException("Learned-word file has no parent directory"),
        SPATIAL_FILE_NAME,
    ),
) {

    /** Serialises separate Store instances that address the same learned-data files. */
    private val operationLock = operationLocks.computeIfAbsent(
        "${file.absoluteFile.toPath().normalize()}\u0000${pairFile.absoluteFile.toPath().normalize()}" +
            "\u0000${spatialFile.absoluteFile.toPath().normalize()}",
    ) { Any() }
    private val deletionMarker = File(temporaryDirectory, CLEAR_PENDING_FILE_NAME)

    constructor(context: Context) : this(
        File(context.filesDir, FILE_NAME),
        File(context.filesDir, PAIR_FILE_NAME),
        context.noBackupFilesDir,
    )

    fun load(into: UserDictionary) {
        synchronized(operationLock) {
            if (deletionPending()) {
                into.clear()
                Log.i(TAG, "Learned words withheld while deletion is pending")
                return
            }
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
    }

    /** Persists [from], returning false when the previous on-disk copy had to be left untouched. */
    fun save(from: UserDictionary): Boolean =
        synchronized(operationLock) {
            if (deletionPending()) return@synchronized false
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
        synchronized(operationLock) {
            if (deletionPending()) {
                into.clear()
                Log.i(TAG, "Learned pairs withheld while deletion is pending")
                return
            }
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
    }

    /** Persists [from], returning false when the previous on-disk copy had to be left untouched. */
    fun save(from: UserBigrams): Boolean =
        synchronized(operationLock) {
            if (deletionPending()) return@synchronized false
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

    fun load(into: SpatialTouchModel) {
        synchronized(operationLock) {
            if (deletionPending()) {
                into.clear()
                Log.i(TAG, "Learned touch offsets withheld while deletion is pending")
                return
            }
            if (!spatialFile.exists()) return
            try {
                val restored = spatialFile.readLines().mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size != 6 || parts[0].length != 1) return@mapNotNull null
                    val count = parts[1].toIntOrNull() ?: return@mapNotNull null
                    val meanX = parts[2].toFloatOrNull() ?: return@mapNotNull null
                    val meanY = parts[3].toFloatOrNull() ?: return@mapNotNull null
                    val m2X = parts[4].toFloatOrNull() ?: return@mapNotNull null
                    val m2Y = parts[5].toFloatOrNull() ?: return@mapNotNull null
                    SpatialTouchModel.Entry(parts[0][0], count, meanX, meanY, m2X, m2Y)
                }
                into.restore(restored)
                Log.i(TAG, "Restored ${restored.size} learned touch offsets")
            } catch (e: IOException) {
                Log.w(TAG, "Could not read learned touch offsets; starting empty", e)
            }
        }
    }

    fun save(from: SpatialTouchModel): Boolean =
        synchronized(operationLock) {
            if (deletionPending()) return@synchronized false
            writeAtomically(spatialFile) { writer ->
                for (entry in from.entries()) {
                    writer.write(entry.letter.toString())
                    writer.write("\t${entry.count}\t${entry.meanX}\t${entry.meanY}")
                    writer.write("\t${entry.m2X}\t${entry.m2Y}")
                    writer.newLine()
                }
            }
        }

    /**
     * Durably records a clear request before attempting to remove any personal data.
     *
     * A true result means the no-backup marker was flushed to disk. Cleanup here is deliberately
     * best-effort and the marker is deliberately retained: a process death or one failed deletion
     * can then never make a surviving learned file eligible to load again. The IME calls
     * [completePendingDeletion] under its learned-data mutex to finish the transaction.
     */
    fun requestDeletion(): Boolean = synchronized(operationLock) {
        if (!persistDeletionMarker()) return@synchronized false
        if (!deleteLearnedData()) Log.w(TAG, "Learned-data deletion remains pending")
        true
    }

    /**
     * Completes a previously requested deletion, removing the marker last.
     *
     * Safe to call at every startup: without a marker this is a no-op. A false result leaves the
     * marker in place, so loads and saves continue to fail closed until a later retry succeeds.
     */
    fun completePendingDeletion(): Boolean = synchronized(operationLock) {
        if (!deletionPending()) return@synchronized true
        if (!deleteLearnedData()) {
            Log.w(TAG, "Could not complete learned-data deletion")
            return@synchronized false
        }
        val removed = deleteIfPresent(deletionMarker)
        if (!removed) Log.w(TAG, "Could not clear learned-data deletion marker")
        removed
    }

    /** Deletes the payload and every known save residue, but never the deletion marker. */
    private fun deleteLearnedData(): Boolean {
        var succeeded = true
        for (target in listOf(file, pairFile, spatialFile)) {
            if (!deleteIfPresent(target)) succeeded = false
            if (!deleteTemporaryFiles(target)) succeeded = false
        }
        return succeeded
    }

    /**
     * Writes through a temporary file and renames it into place.
     *
     * A process killed mid-save then loses the newest word rather than the whole dictionary, which
     * for a file that only ever grows is the difference between a hiccup and starting again.
     */
    private fun writeAtomically(target: File, body: (BufferedWriter) -> Unit): Boolean {
        var temporary: File? = null
        try {
            if (!temporaryDirectory.isDirectory && !temporaryDirectory.mkdirs()) {
                throw IOException("Temporary directory is unavailable")
            }

            // A unique name matters because word and pair saves may overlap when the IME is
            // stopped while a debounce is still finishing. A shared `.tmp` lets one save delete
            // or rename the other save's data. Android supplies noBackupFilesDir here, so a
            // process killed before the finally block cannot leave personal text for backup.
            temporary = File.createTempFile("${target.name}.", ".tmp", temporaryDirectory)
            FileOutputStream(temporary).use { stream ->
                BufferedWriter(OutputStreamWriter(stream, StandardCharsets.UTF_8)).use { writer ->
                    body(writer)
                    writer.flush()
                    stream.fd.sync()
                }
            }

            // A separate process is not expected (the app and IME share one), but this second
            // check also makes an externally-created marker fail closed before replacement.
            if (deletionPending()) return false

            try {
                Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                // The app's private files normally live on one filesystem and support atomic
                // rename. Replacement is still collision-safe on unusual filesystems that do not.
                Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
            }
            // The bytes were synced above, but the rename that made them the dictionary lives in
            // the directory, and that is a separate write. Without this a power cut can leave the
            // old file — or no file — behind data we have already told the caller is saved.
            syncDirectory(target.absoluteFile.parentFile)
            return true
        } catch (e: IOException) {
            Log.w(TAG, "Could not save ${target.name}", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not save ${target.name}", e)
        } finally {
            temporary?.delete()
        }
        return false
    }

    /** Creates (or re-syncs) the marker before any destructive work begins. */
    private fun persistDeletionMarker(): Boolean = try {
        if (!temporaryDirectory.isDirectory && !temporaryDirectory.mkdirs()) {
            throw IOException("No-backup directory is unavailable")
        }
        FileOutputStream(deletionMarker, true).use { stream ->
            if (deletionMarker.length() == 0L) {
                stream.write(CLEAR_PENDING_CONTENT)
                stream.flush()
            }
            stream.fd.sync()
        }
        // The marker's own contents are durable now, but the directory entry that makes it exist
        // is not, and everything after this point deletes personal data on the strength of it. A
        // power cut between the two is exactly how a cleared dictionary comes back on reboot.
        syncDirectory(deletionMarker.absoluteFile.parentFile)
        true
    } catch (e: IOException) {
        Log.w(TAG, "Could not persist learned-data deletion marker", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Could not persist learned-data deletion marker", e)
        false
    }

    /**
     * Flushes a directory's own entries, so a rename or a creation survives losing power.
     *
     * Opening a directory read-only and forcing the channel is the portable spelling of `fsync(2)`
     * on a directory; filesystems that will not have it just say so, and a best-effort sync is
     * still strictly better than the plain rename this replaces. (Android's own `AtomicFile` skips
     * this step entirely, which is why it is spelled out here rather than borrowed.)
     */
    private fun syncDirectory(directory: File?) {
        if (directory == null) return
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            Log.d(TAG, "Could not sync ${directory.name}; the rename may not be durable yet", e)
        } catch (e: SecurityException) {
            Log.d(TAG, "Could not sync ${directory.name}; the rename may not be durable yet", e)
        } catch (e: UnsupportedOperationException) {
            Log.d(TAG, "Could not sync ${directory.name}; the rename may not be durable yet", e)
        }
    }

    /** Any uncertainty about the marker is treated as pending, which is the privacy-safe side. */
    private fun deletionPending(): Boolean = try {
        deletionMarker.exists()
    } catch (_: SecurityException) {
        true
    }

    private fun deleteTemporaryFiles(target: File): Boolean {
        val prefix = "${target.name}."
        val directories = linkedSetOf(
            temporaryDirectory.absoluteFile,
            target.absoluteFile.parentFile,
        ).filterNotNull()
        var succeeded = true

        for (directory in directories) {
            val candidates = try {
                directory.listFiles { candidate ->
                    candidate.name.startsWith(prefix) && candidate.name.endsWith(TEMP_SUFFIX)
                }
            } catch (_: SecurityException) {
                succeeded = false
                null
            }
            if (candidates == null) {
                // File.listFiles also returns null for directory I/O failures. Unless the directory
                // is proven absent, residue enumeration was not proven complete and the marker
                // must remain.
                val exists = try {
                    directory.exists()
                } catch (_: SecurityException) {
                    true
                }
                if (exists) succeeded = false
                continue
            }
            for (candidate in candidates) {
                if (!deleteIfPresent(candidate)) succeeded = false
            }
        }
        return succeeded
    }

    /** Treats a concurrent disappearance as success while still reporting a real refusal. */
    private fun deleteIfPresent(target: File): Boolean = try {
        !target.exists() || target.delete() || !target.exists()
    } catch (_: SecurityException) {
        false
    }

    private companion object {
        const val TAG = "SlideUserDict"
        const val FILE_NAME = "learned_words.txt"
        const val PAIR_FILE_NAME = "learned_pairs.txt"
        const val SPATIAL_FILE_NAME = "learned_touch_offsets.txt"
        const val TEMP_SUFFIX = ".tmp"
        const val CLEAR_PENDING_FILE_NAME = "learned_data.clear_pending"
        val CLEAR_PENDING_CONTENT = "clear\n".toByteArray(StandardCharsets.US_ASCII)

        /** App activity and IME service share a process but may construct separate Store objects. */
        val operationLocks = ConcurrentHashMap<String, Any>()
    }
}
