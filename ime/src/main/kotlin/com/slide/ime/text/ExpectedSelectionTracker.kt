package com.slide.ime.text

internal data class EditorSelection(val start: Int, val end: Int)

private data class ExpectedSelectionChange(
    val from: EditorSelection?,
    val to: EditorSelection,
)

/**
 * Correlates delayed editor selection callbacks with mutations the IME already performed.
 *
 * Editors may coalesce several callbacks into the final position. Matching any later expected
 * position therefore retires all earlier positions as well. A mismatching callback remains an
 * external cursor move and must never be swallowed merely because some edit is still pending.
 */
internal class ExpectedSelectionTracker(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val pending = ArrayDeque<ExpectedSelectionChange>()
    private val recentlyRetired = ArrayDeque<ExpectedSelectionChange>()
    private var retiredDeadlineNanos = 0L

    fun expect(from: EditorSelection?, to: EditorSelection) {
        if (from == to) return
        val change = ExpectedSelectionChange(from, to)
        if (pending.lastOrNull() == change) return
        pending.addLast(change)
        while (pending.size > MAX_PENDING) pending.removeFirst()
    }

    fun consume(from: EditorSelection, to: EditorSelection): Boolean {
        expireRetired()

        // The editor may report every transition, or one callback from the first old position to
        // the final new position. Pick the longest continuous prefix compatible with this report.
        val first = pending.firstOrNull()
        if (first != null && (first.from == null || first.from == from)) {
            var previous = first.from
            var longestMatch = -1
            for (index in pending.indices) {
                val change = pending[index]
                if (change.from != null && previous != null && change.from != previous) {
                    break
                }
                previous = change.to
                if (change.to == to) longestMatch = index
            }
            if (longestMatch >= 0) {
                val removed = ArrayList<ExpectedSelectionChange>(longestMatch + 1)
                repeat(longestMatch + 1) { removed += pending.removeFirst() }
                if (removed.size > 1) {
                    // If this was the first uncoalesced callback, the remaining callbacks have
                    // already been posted. Accept their exact transitions briefly without leaving
                    // stale destinations able to swallow a later real cursor tap.
                    recentlyRetired.clear()
                    removed.drop(1).forEach(recentlyRetired::addLast)
                    retiredDeadlineNanos = nanoTime() + RETIRED_GRACE_NANOS
                }
                return true
            }
        }

        val retiredIndex = recentlyRetired.indexOfFirst { it.from == from && it.to == to }
        if (retiredIndex < 0) return false
        repeat(retiredIndex + 1) { recentlyRetired.removeFirst() }
        return true
    }

    /**
     * Where the outstanding chain ends, or null when nothing is outstanding.
     *
     * Every expectation is registered after its mutation has been sent, so the last one describes
     * where the editor already is — even while earlier callbacks in the chain are still arriving.
     * Caching an intermediate callback's position instead would measure the next edit against a
     * cursor the editor has already left.
     */
    fun pendingTarget(): EditorSelection? = pending.lastOrNull()?.to

    /**
     * Whether any mutation of ours is still waiting to be acknowledged.
     *
     * While this is false every edit the keyboard has made has already come back, so a callback
     * arriving now describes the editor as it stands rather than as it stood before something the
     * keyboard has since done.
     */
    fun hasPending(): Boolean = pending.isNotEmpty()

    fun invalidate() {
        pending.clear()
        recentlyRetired.clear()
        retiredDeadlineNanos = 0L
    }

    internal val size: Int get() = pending.size

    private fun expireRetired() {
        if (recentlyRetired.isNotEmpty() && nanoTime() > retiredDeadlineNanos) {
            recentlyRetired.clear()
        }
    }

    private companion object {
        const val MAX_PENDING = 32
        const val RETIRED_GRACE_NANOS = 100_000_000L
    }
}

/** Bounded editor evidence captured around a suspended final swipe decode. */
internal data class GestureEditorSnapshot(
    val cursor: EditorSelection?,
    val textBeforeCursor: String?,
    val textAfterCursor: String?,
) {
    fun matches(other: GestureEditorSnapshot): Boolean =
        cursor == other.cursor &&
            textBeforeCursor == other.textBeforeCursor &&
            textAfterCursor == other.textAfterCursor
}

/** Resolves a queued letter against shift state when the key is actually applied. */
internal fun resolveCharacterCase(text: String, shifted: Boolean): String =
    if (shifted) text.uppercase() else text.lowercase()

/** Multi-character literal keys such as `.com`, `AM`, and `PM` are not alphabetic keycaps. */
internal fun isCaseableCharacter(text: String): Boolean =
    text.length == 1 && text[0].isLetter()

/** Cursor prediction when composing text is replaced before a following swipe is committed. */
internal fun cursorAfterReplacement(
    cursor: Int,
    originalLength: Int,
    replacementLength: Int,
): Int = (cursor - originalLength + replacementLength).coerceAtLeast(0)
