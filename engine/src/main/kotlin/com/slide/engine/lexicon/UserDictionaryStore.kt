package com.slide.engine.lexicon

import android.content.Context
import android.util.Log
import com.slide.engine.gesture.GestureAdaptation
import com.slide.engine.gesture.GestureAdaptationSnapshot
import com.slide.engine.gesture.GestureAlternativePreference
import com.slide.engine.gesture.GestureRejectionPreference
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
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps personalized language, touch, and gesture models in private app storage and nowhere else.
 *
 * This is the most personal thing Slide holds — it is, fairly literally, a list of the words
 * someone uses that most people do not. It never leaves the device, is never bundled into a
 * backup that could carry it off one (see `allowBackup` handling). Words and pairs remain readable
 * plain text; gesture preferences use salted fingerprints so that file is not itself word history.
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
    private val gestureAdaptationFile: File = File(
        file.absoluteFile.parentFile
            ?: throw IllegalArgumentException("Learned-word file has no parent directory"),
        GESTURE_ADAPTATION_FILE_NAME,
    ),
    /** Injectable only so durability failures can be exercised on the host JVM. */
    private val directorySync: (File?) -> Boolean = ::syncDirectory,
) {

    /** Serialises separate Store instances that address the same learned-data files. */
    private val operationLock = operationLocks.computeIfAbsent(
        "${file.absoluteFile.toPath().normalize()}\u0000${pairFile.absoluteFile.toPath().normalize()}" +
            "\u0000${spatialFile.absoluteFile.toPath().normalize()}" +
            "\u0000${gestureAdaptationFile.absoluteFile.toPath().normalize()}",
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

    /** Persists [from], returning false when replacement or its durability could not be proved. */
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

    /** Persists [from], returning false when replacement or its durability could not be proved. */
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

    fun load(into: GestureAdaptation) {
        synchronized(operationLock) {
            if (deletionPending()) {
                into.clear()
                Log.i(TAG, "Gesture adaptation withheld while deletion is pending")
                return
            }
            if (!gestureAdaptationFile.exists()) return
            try {
                val lines = gestureAdaptationFile.useLines { source ->
                    source.take(MAX_GESTURE_ADAPTATION_LINES).toList()
                }
                val version = lines.firstValue("version")?.toIntOrNull() ?: return
                val salt = lines.firstValue("salt") ?: return
                val epoch = lines.firstValue("epoch")?.toLongOrNull() ?: return
                val alternatives = lines.mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size != 5 || parts[0] != "alternative") return@mapNotNull null
                    val rejected = parts[1].toUnsignedLongOrNull() ?: return@mapNotNull null
                    val chosen = parts[2].toUnsignedLongOrNull() ?: return@mapNotNull null
                    val strength = parts[3].toIntOrNull() ?: return@mapNotNull null
                    val lastEpoch = parts[4].toLongOrNull() ?: return@mapNotNull null
                    GestureAlternativePreference(rejected, chosen, strength, lastEpoch)
                }
                val rejections = lines.mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size != 4 || parts[0] != "rejection") return@mapNotNull null
                    val fingerprint = parts[1].toUnsignedLongOrNull() ?: return@mapNotNull null
                    val strength = parts[2].toIntOrNull() ?: return@mapNotNull null
                    val lastEpoch = parts[3].toLongOrNull() ?: return@mapNotNull null
                    GestureRejectionPreference(fingerprint, strength, lastEpoch)
                }
                val restored = into.restore(
                    GestureAdaptationSnapshot(version, salt, epoch, alternatives, rejections),
                )
                if (restored) {
                    Log.i(TAG, "Restored ${alternatives.size + rejections.size} gesture preferences")
                } else {
                    Log.w(TAG, "Could not validate gesture adaptation; starting empty")
                }
            } catch (e: IOException) {
                Log.w(TAG, "Could not read gesture adaptation; starting empty", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not read gesture adaptation; starting empty", e)
            }
        }
    }

    fun save(from: GestureAdaptation): Boolean =
        synchronized(operationLock) {
            if (deletionPending()) return@synchronized false
            val snapshot = from.snapshot()
            writeAtomically(gestureAdaptationFile) { writer ->
                writer.write("version\t${snapshot.version}")
                writer.newLine()
                writer.write("salt\t${snapshot.saltHex}")
                writer.newLine()
                writer.write("epoch\t${snapshot.epoch}")
                writer.newLine()
                for (entry in snapshot.alternatives) {
                    writer.write("alternative\t${entry.rejectedFingerprint.toUnsignedHex()}")
                    writer.write("\t${entry.chosenFingerprint.toUnsignedHex()}")
                    writer.write("\t${entry.strength}\t${entry.lastEpoch}")
                    writer.newLine()
                }
                for (entry in snapshot.rejections) {
                    writer.write("rejection\t${entry.fingerprint.toUnsignedHex()}")
                    writer.write("\t${entry.strength}\t${entry.lastEpoch}")
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
    fun requestDeletion(): Boolean {
        // Publish only after the marker and payload transaction have left fail-closed state, and do
        // not invoke external listeners while the payload lock is held. Keeping both file phases in
        // one critical section also prevents a fast IME completion from removing the marker before
        // this request finishes, then having this request delete newly learned post-clear data.
        val generation = synchronized(operationLock) {
            if (!persistDeletionMarker()) return false
            val requestGeneration = deletionRequestGeneration.incrementAndGet()
            if (!deleteLearnedData()) Log.w(TAG, "Learned-data deletion remains pending")
            requestGeneration
        }
        for (listener in deletionRequestListeners) {
            try {
                listener(generation)
            } catch (error: RuntimeException) {
                // One live consumer must not make a successfully persisted user request look like
                // it failed, nor prevent the remaining consumers from purging their snapshots.
                Log.w(TAG, "Could not notify a learned-data deletion listener", error)
            }
        }
        return true
    }

    /** Whether a durable clear request still exists, treating read uncertainty as pending. */
    fun hasPendingDeletion(): Boolean = synchronized(operationLock) { deletionPending() }

    /**
     * Observes deletion requests made in this application process.
     *
     * MainActivity and the IME run in the default process. Registration is synchronous, so a live
     * IME cannot miss the signal even when publishing the settings epoch fails afterward. A service
     * that was not alive needs no signal: its startup load obeys the durable on-disk marker.
     */
    fun addDeletionRequestListener(listener: (Long) -> Unit): () -> Unit {
        deletionRequestListeners += listener
        return { deletionRequestListeners -= listener }
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
        if (!deleteIfPresent(deletionMarker)) {
            Log.w(TAG, "Could not clear learned-data deletion marker")
            return@synchronized false
        }
        if (!directorySync(deletionMarker.absoluteFile.parentFile)) {
            // The payload deletes are durable already, so either on-disk outcome is private: the
            // marker removal survives, or it rolls back and deletion is retried. Recreate the
            // marker in the live namespace as well, however, so this process cannot start saving
            // new learning while the marker's durable state is uncertain.
            persistDeletionMarker()
            Log.w(TAG, "Could not make learned-data deletion completion durable")
            return@synchronized false
        }
        true
    }

    /** Deletes and syncs the payload and every known save residue, but never the marker. */
    private fun deleteLearnedData(): Boolean {
        var succeeded = true
        val affectedDirectories = linkedSetOf<File>()
        for (target in listOf(file, pairFile, spatialFile, gestureAdaptationFile)) {
            target.absoluteFile.parentFile?.let(affectedDirectories::add)
            affectedDirectories += temporaryDirectoriesFor(target)
            if (!deleteIfPresent(target)) succeeded = false
            if (!deleteTemporaryFiles(target)) succeeded = false
        }
        // Only after every unlink has been attempted do we force the directory entries. The
        // marker stays in place if any directory cannot prove those removals durable.
        for (directory in affectedDirectories) {
            if (!directorySync(directory)) succeeded = false
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
            val renamedDirectories = linkedSetOf<File>()
            target.absoluteFile.parentFile?.let(renamedDirectories::add)
            checkNotNull(temporary).absoluteFile.parentFile?.let(renamedDirectories::add)
            var renameDurable = true
            for (directory in renamedDirectories) {
                if (!directorySync(directory)) renameDurable = false
            }
            if (!renameDurable) {
                throw IOException("Could not make the ${target.name} rename durable")
            }
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
        if (!directorySync(deletionMarker.absoluteFile.parentFile)) {
            throw IOException("Could not make the learned-data deletion marker durable")
        }
        true
    } catch (e: IOException) {
        Log.w(TAG, "Could not persist learned-data deletion marker", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Could not persist learned-data deletion marker", e)
        false
    }

    /** Any uncertainty about the marker is treated as pending, which is the privacy-safe side. */
    private fun deletionPending(): Boolean = try {
        deletionMarker.exists()
    } catch (_: SecurityException) {
        true
    }

    private fun deleteTemporaryFiles(target: File): Boolean {
        val prefix = "${target.name}."
        var succeeded = true

        for (directory in temporaryDirectoriesFor(target)) {
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

    private fun temporaryDirectoriesFor(target: File): Set<File> =
        linkedSetOf<File>().apply {
            add(temporaryDirectory.absoluteFile)
            target.absoluteFile.parentFile?.let(::add)
        }

    /** Treats a concurrent disappearance as success while still reporting a real refusal. */
    private fun deleteIfPresent(target: File): Boolean = try {
        !target.exists() || target.delete() || !target.exists()
    } catch (_: SecurityException) {
        false
    }

    companion object {
        private const val TAG = "SlideUserDict"
        private const val FILE_NAME = "learned_words.txt"
        private const val PAIR_FILE_NAME = "learned_pairs.txt"
        private const val SPATIAL_FILE_NAME = "learned_touch_offsets.txt"
        private const val GESTURE_ADAPTATION_FILE_NAME = "learned_gesture_adaptation.txt"
        private const val MAX_GESTURE_ADAPTATION_LINES = 1_024
        private const val TEMP_SUFFIX = ".tmp"
        private const val CLEAR_PENDING_FILE_NAME = "learned_data.clear_pending"
        private val CLEAR_PENDING_CONTENT = "clear\n".toByteArray(StandardCharsets.US_ASCII)

        /** App activity and IME service share a process but may construct separate Store objects. */
        private val operationLocks = ConcurrentHashMap<String, Any>()

        private val deletionRequestGeneration = AtomicLong()
        private val deletionRequestListeners = CopyOnWriteArraySet<(Long) -> Unit>()

        /** Cheap correlation token for the settings-epoch path; performs no filesystem IO. */
        fun latestDeletionRequestGeneration(): Long = deletionRequestGeneration.get()
    }
}

private fun List<String>.firstValue(key: String): String? = firstNotNullOfOrNull { line ->
    val parts = line.split('\t')
    parts.getOrNull(1)?.takeIf { parts.size == 2 && parts[0] == key }
}

private fun String.toUnsignedLongOrNull(): Long? = try {
    if (length !in 1..16 || any { it !in "0123456789abcdefABCDEF" }) null
    else java.lang.Long.parseUnsignedLong(this, 16)
} catch (_: NumberFormatException) {
    null
}

private fun Long.toUnsignedHex(): String = java.lang.Long.toUnsignedString(this, 16).padStart(16, '0')

/**
 * Forces a directory's own entries, so a rename, unlink, or marker creation survives power loss.
 *
 * Opening a directory read-only and forcing its channel is the JVM spelling of `fsync(2)` on a
 * directory. Failure is deliberately visible to the caller: deletion may only leave fail-closed
 * state, never silently report durability that the filesystem did not provide.
 */
private fun syncDirectory(directory: File?): Boolean {
    if (directory == null) return false
    return try {
        if (!directory.exists()) return true
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        true
    } catch (e: IOException) {
        Log.w("SlideUserDict", "Could not sync ${directory.name}", e)
        false
    } catch (e: SecurityException) {
        Log.w("SlideUserDict", "Could not sync ${directory.name}", e)
        false
    } catch (e: UnsupportedOperationException) {
        Log.w("SlideUserDict", "Could not sync ${directory.name}", e)
        false
    }
}
