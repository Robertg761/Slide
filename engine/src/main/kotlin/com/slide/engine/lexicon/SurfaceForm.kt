package com.slide.engine.lexicon

/**
 * Case-insensitive identity with evidence-backed presentation.
 *
 * A single shifted letter must not permanently rename an established word, but useful spellings
 * such as `iPhoneX` and `Whitmore` must survive normalisation. The current surface and one exact
 * challenger therefore retain bounded observation counts. Ties keep the incumbent; a competing
 * form has to be observed more often before it takes over.
 */
internal class SurfaceForm private constructor(
    val value: String,
    private val valueEvidence: Int,
    private val challenger: String?,
    private val challengerEvidence: Int,
) {

    /** Returns a new snapshot with [candidate] observed [times] more times. */
    fun observe(candidate: String, times: Int = 1): SurfaceForm {
        if (times <= 0) return this

        var selected = value
        var selectedCount = valueEvidence
        var other = challenger
        var otherCount = challengerEvidence

        repeat(times) {
            if (candidate == selected) {
                selectedCount = minOf(MAX_EVIDENCE, selectedCount + 1)
                return@repeat
            }

            if (candidate != other) {
                other = candidate
                otherCount = 0
            }
            if (otherCount >= DECAY_AT) {
                // Ceiling division is essential. With incumbent B=9 and challenger A=8, another
                // A should make the decayed evidence 5-5 and retain B, not floor both to 4 and let
                // the same observation promote A immediately.
                selectedCount = (selectedCount + 1) / 2
                otherCount = (otherCount + 1) / 2
            }
            otherCount++

            if (otherCount > selectedCount) {
                val oldSelected = selected
                val oldSelectedCount = selectedCount
                selected = candidate
                selectedCount = otherCount
                other = oldSelected
                otherCount = oldSelectedCount
            }
        }
        return SurfaceForm(selected, selectedCount, other, otherCount)
    }

    /** Combines restored duplicate rows without treating either spelling as intrinsically better. */
    fun merge(other: SurfaceForm): SurfaceForm {
        var merged = observe(other.value, other.valueEvidence)
        val alternate = other.challenger
        if (alternate != null) merged = merged.observe(alternate, other.challengerEvidence)
        return merged
    }

    companion object {
        fun first(value: String): SurfaceForm = SurfaceForm(value, 1, null, 0)

        /** Old persistence has no variant counts; restore confidence in the saved winner. */
        fun restored(value: String, totalCount: Int): SurfaceForm = SurfaceForm(
            value,
            minOf(totalCount.coerceAtLeast(1), MAX_EVIDENCE),
            null,
            0,
        )

        private const val DECAY_AT = 8
        private const val MAX_EVIDENCE = 255
    }
}
