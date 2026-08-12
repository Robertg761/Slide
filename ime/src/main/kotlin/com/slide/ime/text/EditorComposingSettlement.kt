package com.slide.ime.text

/**
 * The two ways Android gives an IME to settle an editor-owned composing region.
 *
 * Kept outside the service so rejection paths can be exercised on the JVM. A false return from
 * both operations is authoritative: the caller must retain its composing state and must not apply
 * the separator, gesture, cursor move, or other edit that depended on the word being settled.
 */
internal object EditorComposingSettlement {
    data class FinishResult(
        val settled: Boolean,
        val corrected: Boolean,
        val appliedText: String,
        /** True only when the editor settled text for which Slide had no pending correction. */
        val learnTypedWord: Boolean,
        /** True when [appliedText] reflects an accepted choice that is safe to learn in context. */
        val learnAppliedPair: Boolean,
        /** At least one accepted editor operation can produce an edit callback. */
        val callbackPossible: Boolean,
    )

    data class AbandonResult(
        val settled: Boolean,
        /** At least one accepted editor operation can produce an edit callback. */
        val callbackPossible: Boolean,
    )

    data class SuggestionResult(
        val settled: Boolean,
        /** The editor accepted the replacement as composing text, even if it refused to settle. */
        val replacementApplied: Boolean,
        /** At least one accepted editor operation can produce an edit callback. */
        val callbackPossible: Boolean,
    )

    /**
     * Settles typed text, atomically committing a correction where possible.
     *
     * Committing the correction first avoids changing the live composing region before knowing it
     * can be settled. If that atomic replacement is rejected, finishing the unchanged typed text
     * is a safe degradation. With no correction, [finish] remains the least invasive first choice.
     */
    fun finish(
        typed: String,
        correction: String?,
        finish: () -> Boolean,
        commit: (String) -> Boolean,
    ): FinishResult {
        require(typed.isNotEmpty())
        if (correction != null) {
            if (commit(correction)) {
                return FinishResult(
                    settled = true,
                    corrected = true,
                    appliedText = correction,
                    learnTypedWord = false,
                    learnAppliedPair = true,
                    callbackPossible = true,
                )
            }
            val settledTyped = finish()
            return FinishResult(
                settled = settledTyped,
                corrected = false,
                appliedText = typed,
                learnTypedWord = false,
                learnAppliedPair = false,
                callbackPossible = settledTyped,
            )
        }

        val settled = finish() || commit(typed)
        return FinishResult(
            settled = settled,
            corrected = false,
            appliedText = typed,
            learnTypedWord = settled,
            learnAppliedPair = settled,
            callbackPossible = settled,
        )
    }

    /** Finishes unchanged text, falling back to an atomic commit of the same visible value. */
    fun abandon(
        typed: String,
        finish: () -> Boolean,
        commit: (String) -> Boolean,
    ): AbandonResult {
        require(typed.isNotEmpty())
        val settled = finish() || commit(typed)
        return AbandonResult(settled = settled, callbackPossible = settled)
    }

    /**
     * Replaces the active region with an explicitly selected candidate and settles it.
     *
     * Some editors require the composing value to be updated before they will finish it, while
     * others only implement commitText. If an editor accepts the replacement but rejects both
     * settlement calls, [replacementApplied] tells the caller to keep tracking that new live value.
     */
    fun commitSuggestion(
        replacement: String,
        setComposing: (String) -> Boolean,
        finish: () -> Boolean,
        commit: (String) -> Boolean,
    ): SuggestionResult {
        require(replacement.isNotEmpty())
        val replacementApplied = setComposing(replacement)
        val settled = if (replacementApplied) {
            finish() || commit(replacement)
        } else {
            commit(replacement)
        }
        return SuggestionResult(
            settled = settled,
            replacementApplied = replacementApplied,
            callbackPossible = replacementApplied || settled,
        )
    }
}
