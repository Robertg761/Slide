package com.slide.ime.view

import com.slide.ime.R

/** App-owned action IDs used to expose long-press key alternatives to accessibility services. */
internal object AlternateAccessibilityActions {
    private val ids = intArrayOf(
        R.id.accessibility_type_alternate_1,
        R.id.accessibility_type_alternate_2,
        R.id.accessibility_type_alternate_3,
        R.id.accessibility_type_alternate_4,
        R.id.accessibility_type_alternate_5,
        R.id.accessibility_type_alternate_6,
        R.id.accessibility_type_alternate_7,
        R.id.accessibility_type_alternate_8,
        R.id.accessibility_type_alternate_9,
    )

    val size: Int get() = ids.size

    fun idAt(index: Int): Int? = ids.getOrNull(index)

    fun indexOf(actionId: Int): Int = ids.indexOf(actionId)

    fun snapshot(): List<Int> = ids.toList()

    /**
     * Resolves the alternate a performed action commits: the promise captured when the node was
     * advertised, or — for an action that was never populated under the current tree — the live
     * lookup. The first entry of [liveAlternates] is the key's primary output, already covered by
     * ACTION_CLICK, so the fallback skips it exactly like node population does.
     *
     * The promise wins on purpose: [android.view.accessibility.AccessibilityNodeInfo] labels are
     * snapshots, and [KeyboardView.alternatesFor] is shift-dependent. Honouring a fresh lookup
     * could commit a different character than the label the user selected against.
     */
    fun resolveAdvertised(
        promised: String?,
        alternateIndex: Int,
        liveAlternates: List<String>,
    ): String? = promised ?: liveAlternates.drop(1).getOrNull(alternateIndex)
}
