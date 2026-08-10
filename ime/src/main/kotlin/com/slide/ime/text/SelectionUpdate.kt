package com.slide.ime.text

/** The parts of an InputMethodService selection callback that affect composing state. */
internal data class SelectionUpdate(
    val externalSelectionChanged: Boolean,
    val cursorLeftComposing: Boolean,
    val composingAtEnd: Boolean?,
) {
    companion object {
        fun evaluate(
            oldSelStart: Int,
            oldSelEnd: Int,
            newSelStart: Int,
            newSelEnd: Int,
            candidatesStart: Int,
            candidatesEnd: Int,
            selfEdit: Boolean,
            hasComposingText: Boolean,
        ): SelectionUpdate {
            val selectionChanged = oldSelStart != newSelStart || oldSelEnd != newSelEnd
            val externalSelectionChanged = !selfEdit && selectionChanged
            if (!hasComposingText) {
                return SelectionUpdate(externalSelectionChanged, false, null)
            }

            // A selection cannot safely keep a word-sized composing region, even if both ends of
            // the selection happen to lie inside it.
            if (newSelStart != newSelEnd) {
                return SelectionUpdate(externalSelectionChanged, true, null)
            }

            val editorReportedComposing = candidatesStart >= 0 && candidatesEnd >= 0
            if (editorReportedComposing) {
                val inside = newSelStart in candidatesStart..candidatesEnd
                return SelectionUpdate(
                    externalSelectionChanged = externalSelectionChanged,
                    cursorLeftComposing = !inside,
                    composingAtEnd = inside && newSelEnd == candidatesEnd,
                )
            }

            // Some editors always report -1 for composing bounds. A selection move caused by our
            // own setComposingText is safe; a visibly different cursor position caused elsewhere
            // is not. Keeping the latter would apply candidates to a region we can no longer locate.
            return SelectionUpdate(
                externalSelectionChanged = externalSelectionChanged,
                cursorLeftComposing = externalSelectionChanged,
                composingAtEnd = true.takeIf { selfEdit && selectionChanged },
            )
        }
    }
}
