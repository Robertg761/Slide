package com.slide.ime

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * The keyboard's clipboard memory: a bounded recent history plus user-pinned items.
 *
 * Recency is deliberately ephemeral — recent items live in memory, expire after
 * [EXPIRY_MS] like Gboard's, and die with the process. Only pins, which the user
 * explicitly asked to keep, are written to disk, and they go under [Context.noBackupFilesDir]
 * so clipboard contents can never ride a cloud backup or device transfer.
 *
 * Clips flagged sensitive by their source (password managers set
 * [ClipDescription.EXTRA_IS_SENSITIVE]) are never recorded at all.
 */
internal class ClipboardHistory(
    private val context: Context,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : ClipboardManager.OnPrimaryClipChangedListener {

    data class Entry(val text: String, val pinned: Boolean, val recordedAt: Long)

    private val recents = ArrayDeque<Entry>()
    private val pins = mutableListOf<String>()
    private var pinsLoaded = false
    private var listening = false

    private val manager: ClipboardManager?
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    /** Starts recording clipboard changes. Safe to call repeatedly. */
    fun startListening() {
        if (listening) return
        manager?.addPrimaryClipChangedListener(this)
        listening = true
        // Whatever is on the clipboard when the keyboard starts is the item the user most
        // plausibly wants to paste; without this the panel starts empty until the next copy.
        recordCurrentClip()
    }

    fun stopListening() {
        if (!listening) return
        manager?.removePrimaryClipChangedListener(this)
        listening = false
    }

    override fun onPrimaryClipChanged() {
        recordCurrentClip()
    }

    /** Pinned first, then unexpired recents, newest first. */
    fun entries(): List<Entry> {
        ensurePinsLoaded()
        pruneExpired()
        val pinnedEntries = pins.map { Entry(it, pinned = true, recordedAt = 0L) }
        val pinnedTexts = pins.toHashSet()
        return pinnedEntries + recents.filterNot { it.text in pinnedTexts }
    }

    fun pin(text: String) {
        ensurePinsLoaded()
        // The record separator cannot be allowed into a stored pin; stripping a control
        // character from pasteable text is a better failure than corrupting the pin file.
        val storable = text.replace(RECORD_SEPARATOR, "")
        if (storable.isEmpty() || storable in pins) return
        pins.add(0, storable)
        while (pins.size > MAX_PINNED) pins.removeAt(pins.size - 1)
        savePins()
    }

    fun unpin(text: String) {
        ensurePinsLoaded()
        if (!pins.remove(text)) return
        savePins()
    }

    fun isPinned(text: String): Boolean {
        ensurePinsLoaded()
        return text in pins
    }

    /** Removes one item wherever it lives — recents, pins, or both. */
    fun remove(text: String) {
        ensurePinsLoaded()
        recents.removeAll { it.text == text }
        if (pins.remove(text)) savePins()
    }

    private fun recordCurrentClip() {
        val clipboard = manager ?: return
        val clip = try {
            clipboard.primaryClip
        } catch (e: SecurityException) {
            // Some OEM builds throw instead of returning null when access is denied.
            Log.w(TAG, "Clipboard read denied", e)
            null
        } ?: return
        if (clip.description?.isSensitive() == true) return
        val item = clip.getItemAt(0) ?: return
        // coerceToText resolves URIs and styled spans to something committable.
        val text = item.coerceToText(context).toString()
        if (text.isBlank() || text.length > MAX_CLIP_CHARS) return

        recents.removeAll { it.text == text }
        recents.addFirst(Entry(text, pinned = false, recordedAt = clock()))
        while (recents.size > MAX_RECENT) recents.removeLast()
    }

    private fun pruneExpired() {
        val now = clock()
        recents.removeAll { now - it.recordedAt > EXPIRY_MS }
    }

    private fun ClipDescription.isSensitive(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return true
        }
        // Pre-33 password managers used the same key by literal name.
        return extras?.getBoolean(LEGACY_SENSITIVE_KEY) == true
    }

    private fun pinsFile(): File = File(context.noBackupFilesDir, PINS_FILE)

    private fun ensurePinsLoaded() {
        if (pinsLoaded) return
        pinsLoaded = true
        val file = pinsFile()
        val stored = try {
            if (file.isFile) file.readText() else return
        } catch (e: IOException) {
            Log.w(TAG, "Could not read pinned clips", e)
            return
        }
        stored.split(RECORD_SEPARATOR)
            .filter { it.isNotEmpty() }
            .take(MAX_PINNED)
            .forEach { pins += it }
    }

    private fun savePins() {
        // Small file, rare writes (only on an explicit pin/unpin tap); written in place with a
        // rename so a mid-write kill cannot leave a truncated pin list.
        val file = pinsFile()
        val temporary = File(file.parentFile, "$PINS_FILE.tmp")
        try {
            temporary.writeText(pins.joinToString(RECORD_SEPARATOR))
            if (!temporary.renameTo(file)) throw IOException("rename failed")
        } catch (e: IOException) {
            Log.w(TAG, "Could not save pinned clips", e)
            temporary.delete()
        }
    }

    private companion object {
        const val TAG = "SlideIME"
        const val PINS_FILE = "clipboard_pins.txt"
        const val LEGACY_SENSITIVE_KEY = "android.content.extra.IS_SENSITIVE"

        /** Matching Gboard: an unpinned clip is offered for an hour, then quietly dropped. */
        const val EXPIRY_MS = 60L * 60L * 1000L

        const val MAX_RECENT = 10
        const val MAX_PINNED = 10

        /** A clip longer than this is almost certainly a document, not a paste candidate. */
        const val MAX_CLIP_CHARS = 10_000

        /** A control character; [pin] strips it from stored text so records cannot be forged. */
        const val RECORD_SEPARATOR = "\u001E"
    }
}
