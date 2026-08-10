package com.slide.ime.text

internal data class GestureDeletionResult(
    val requestedUnits: Int,
    val deletedUnits: Int,
    val usedUnitFallback: Boolean,
) {
    val fullyDeleted: Boolean get() = deletedUnits == requestedUnits
    val changedEditor: Boolean get() = deletedUnits > 0
}

/** Uses bounded one-unit deletes when an editor rejects the verified whole-swipe range delete. */
internal object GestureDeleteTransaction {
    fun delete(
        units: Int,
        deleteRange: (Int) -> Boolean,
        deleteUnit: () -> Boolean,
    ): GestureDeletionResult {
        require(units > 0)
        if (deleteRange(units)) return GestureDeletionResult(units, units, false)

        var deleted = 0
        while (deleted < units && deleteUnit()) deleted++
        return GestureDeletionResult(units, deleted, true)
    }
}
