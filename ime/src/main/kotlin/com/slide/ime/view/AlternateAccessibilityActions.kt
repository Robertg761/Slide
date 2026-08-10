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
}
