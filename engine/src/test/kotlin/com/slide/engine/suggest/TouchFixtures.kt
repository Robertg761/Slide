package com.slide.engine.suggest

import com.slide.engine.gesture.GestureKeyMap
import java.util.Random
import kotlin.math.hypot

/**
 * Typing simulated as fingers rather than as letters.
 *
 * Every other corpus here starts from a word and edits the *letters* — swap two, drop one, hit the
 * key next door. That is fine for judging spelling evidence but it cannot judge a touch model at
 * all, because it never produces a touch. This one works the way a keyboard actually does: a
 * position is sampled for each letter the user meant, and whichever key that position falls in is
 * the key that gets pressed. Mis-hits emerge from the geometry instead of being written in.
 *
 * That ordering is what keeps the measurement honest. The corrector is handed the touch that
 * *produced* the pressed letter, which is exactly what a real keyboard has, rather than a touch
 * derived from the answer. What the simulation cannot supply is a real distribution: fingers are
 * not isotropic Gaussians, and real thumbs carry a systematic bias — low, and toward the hand
 * holding the phone — that this has none of. So the shape of the result is trustworthy and the
 * exact magnitude is not; see the caveats on the test that uses it.
 */
object TouchFixtures {

    /** One word as it came out, with where each key was actually touched. */
    data class Typed(val pressed: String, val touches: FloatArray) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /**
     * @param sigma spread of the touch around a key's centre, in key widths. Larger is sloppier
     *   typing and produces more mis-hits.
     */
    fun type(word: String, keys: GestureKeyMap, sigma: Float, random: Random): Typed? {
        val touches = FloatArray(word.length * 2)
        val pressed = StringBuilder(word.length)

        for ((index, intended) in word.withIndex()) {
            if (!keys.has(intended)) return null
            val x = keys.centerX(intended) + (random.nextGaussian() * sigma * keys.keyWidth).toFloat()
            val y = keys.centerY(intended) + (random.nextGaussian() * sigma * keys.keyHeight).toFloat()

            // Whichever key that position lands in is the key the keyboard reports. This is the
            // step that turns a wobbly finger into a typo, and it is deliberately the only step:
            // nothing here decides to make a mistake.
            val hit = nearestLetter(keys, x, y) ?: return null

            touches[index * 2] = x
            touches[index * 2 + 1] = y
            pressed.append(hit)
        }
        return Typed(pressed.toString(), touches)
    }

    private fun nearestLetter(keys: GestureKeyMap, x: Float, y: Float): Char? {
        var best: Char? = null
        var bestDistance = Float.MAX_VALUE
        for (letter in 'a'..'z') {
            if (!keys.has(letter)) continue
            // Scaled per axis so a tall key is not unfairly easy to miss vertically.
            val dx = (keys.centerX(letter) - x) / keys.keyWidth
            val dy = (keys.centerY(letter) - y) / keys.keyHeight
            val distance = hypot(dx, dy)
            if (distance < bestDistance) {
                bestDistance = distance
                best = letter
            }
        }
        return best
    }
}
