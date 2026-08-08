package com.slide.engine.gesture

import kotlin.math.hypot

/**
 * Where each letter sits on the current keyboard, in view pixels.
 *
 * The decoder needs key positions but must not depend on how keys are drawn or hit-tested, so
 * `:ime` builds one of these from its own placed geometry and hands it over. Keeping the two apart
 * means the decoder can be exercised against a synthetic grid in unit tests, with no Android
 * framework and no real keyboard view involved.
 *
 * Only the 26 ASCII letters are represented; digits, punctuation and modifiers are not gestured.
 */
class GestureKeyMap private constructor(
    private val centerX: FloatArray,
    private val centerY: FloatArray,
    private val present: BooleanArray,
    /** Nominal key width, used as the natural unit for every tolerance in the decoder. */
    val keyWidth: Float,
    val keyHeight: Float,
) {

    fun has(letter: Char): Boolean {
        val i = index(letter)
        return i >= 0 && present[i]
    }

    fun centerX(letter: Char): Float = centerX[index(letter)]

    fun centerY(letter: Char): Float = centerY[index(letter)]

    fun distanceTo(letter: Char, x: Float, y: Float): Float {
        val i = index(letter)
        return hypot(centerX[i] - x, centerY[i] - y)
    }

    /** Letters nearest to a point, closest first, limited to those within [maxDistance]. */
    fun lettersNear(x: Float, y: Float, maxDistance: Float, limit: Int): CharArray {
        // A 26-entry selection sort beats allocating and sorting a list, and this runs on the
        // touch-up path where the user is already waiting to see a word.
        val chosen = CharArray(limit)
        val chosenDistance = FloatArray(limit) { Float.MAX_VALUE }
        var count = 0

        for (i in 0 until ALPHABET) {
            if (!present[i]) continue
            val distance = hypot(centerX[i] - x, centerY[i] - y)
            if (distance > maxDistance) continue

            var slot = minOf(count, limit - 1)
            if (distance >= chosenDistance[slot]) continue
            while (slot > 0 && distance < chosenDistance[slot - 1]) {
                chosen[slot] = chosen[slot - 1]
                chosenDistance[slot] = chosenDistance[slot - 1]
                slot--
            }
            chosen[slot] = ('a' + i)
            chosenDistance[slot] = distance
            if (count < limit) count++
        }

        return chosen.copyOf(count)
    }

    private fun index(letter: Char): Int {
        val lower = letter.lowercaseChar()
        return if (lower in 'a'..'z') lower - 'a' else -1
    }

    class Builder(private val keyWidth: Float, private val keyHeight: Float) {
        private val centerX = FloatArray(ALPHABET)
        private val centerY = FloatArray(ALPHABET)
        private val present = BooleanArray(ALPHABET)

        fun put(letter: Char, x: Float, y: Float): Builder {
            val lower = letter.lowercaseChar()
            if (lower in 'a'..'z') {
                val i = lower - 'a'
                centerX[i] = x
                centerY[i] = y
                present[i] = true
            }
            return this
        }

        /** Null when the layout has too few letters to decode against, e.g. the symbols layer. */
        fun buildOrNull(): GestureKeyMap? {
            if (present.count { it } < MINIMUM_LETTERS) return null
            return GestureKeyMap(centerX, centerY, present, keyWidth, keyHeight)
        }
    }

    companion object {
        private const val ALPHABET = 26

        /**
         * Below this many letters the layout is not a usable alphabetic keyboard, and decoding
         * against it would produce confident nonsense rather than no answer.
         */
        private const val MINIMUM_LETTERS = 20
    }
}
