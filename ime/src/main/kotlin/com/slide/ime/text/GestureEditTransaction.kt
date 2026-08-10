package com.slide.ime.text

internal data class GestureReplacementResult(
    val deleted: Boolean,
    val committed: Boolean,
    val restoredOriginal: Boolean,
) {
    val replaced: Boolean get() = deleted && committed
}

/** Executes an alternative replacement without appending after a rejected delete. */
internal object GestureEditTransaction {
    fun replace(
        original: String,
        replacement: String,
        deleteBeforeCursor: (Int) -> Boolean,
        commit: (String) -> Boolean,
    ): GestureReplacementResult {
        if (!deleteBeforeCursor(original.length)) {
            return GestureReplacementResult(false, false, false)
        }
        if (commit(replacement)) {
            return GestureReplacementResult(true, true, false)
        }
        return GestureReplacementResult(true, false, commit(original))
    }
}
