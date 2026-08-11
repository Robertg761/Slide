package com.slide.engine.suggest

import com.slide.engine.gesture.GestureKeyMap
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * A small, device-local model of where this person's finger lands on each letter.
 *
 * Offsets are stored in key widths/heights, so changing keyboard height does not throw learning
 * away. Observations come only from words the user confirmed; [observe] also aligns corrections,
 * allowing the touch that produced `r` to teach the intended `t` without pretending insertions
 * had touches of their own.
 */
class SpatialTouchModel {

    data class Entry(
        val letter: Char,
        val count: Int,
        val meanX: Float,
        val meanY: Float,
        val m2X: Float,
        val m2Y: Float,
    )

    private val counts = IntArray(ALPHABET)
    private val meanX = FloatArray(ALPHABET)
    private val meanY = FloatArray(ALPHABET)
    private val m2X = FloatArray(ALPHABET)
    private val m2Y = FloatArray(ALPHABET)

    /** Distance from a touch to the personally calibrated centre of [letter]. */
    fun distance(letter: Char, x: Float, y: Float, keys: GestureKeyMap): Float? {
        val slot = letter.lowercaseChar() - 'a'
        if (slot !in 0 until ALPHABET || !keys.has(letter)) return null

        val dx = (x - keys.centerX(letter)) / keys.keyWidth - meanX[slot]
        val dy = (y - keys.centerY(letter)) / keys.keyHeight - meanY[slot]
        if (counts[slot] < MIN_VARIANCE_OBSERVATIONS) return hypot(dx, dy)

        // A key this person hits broadly should not be judged by the same tight circle as one they
        // hit consistently. Clamp both tails: a handful of near-identical taps must not make the
        // model brittle, and noisy history must not make a letter plausible from anywhere.
        val varianceX = (m2X[slot] / (counts[slot] - 1)).coerceIn(MIN_VARIANCE, MAX_VARIANCE)
        val varianceY = (m2Y[slot] / (counts[slot] - 1)).coerceIn(MIN_VARIANCE, MAX_VARIANCE)
        return sqrt(
            (dx * dx / varianceX + dy * dy / varianceY) * BASELINE_VARIANCE / 2f,
        )
    }

    /**
     * Learns from the alignment between what arrived from the keys and what the user confirmed.
     *
     * The dynamic-programming table is tiny (words are capped at 28 characters). Only aligned
     * source/target characters have physical touches; inserted intended characters are skipped.
     *
     * The alignment counts transpositions (optimal string alignment), because the corrector offers
     * them more cheaply than anything else and "gerat" → "great" is therefore a correction the user
     * can actually accept. A plain Levenshtein alignment has no transposition to spend, so it pays
     * for the same pair with two substitutions and hands the touch that produced `e` to `r` and the
     * touch that produced `r` to `e` — a whole key's error, learned in the wrong direction, on
     * exactly the input the corrector is most confident about. Transposed positions therefore learn
     * *nothing*: the two touches are honest, but which intended letter each one belongs to is the
     * one thing the alignment cannot say.
     */
    fun observe(
        typed: String,
        intended: String,
        touches: FloatArray,
        keys: GestureKeyMap,
    ): Int {
        val source = typed.lowercase()
        val target = intended.lowercase()
        if (source.isEmpty() || target.isEmpty() || touches.size < source.length * 2) return 0
        if (!source.all(::isWordCharacter) || !target.all(::isWordCharacter)) return 0

        val columns = target.length + 1
        val cost = IntArray((source.length + 1) * columns)
        for (i in 0..source.length) cost[i * columns] = i
        for (j in 0..target.length) cost[j] = j
        for (i in 1..source.length) {
            for (j in 1..target.length) {
                val substitution = if (source[i - 1] == target[j - 1]) 0 else 1
                var best = minOf(
                    cost[(i - 1) * columns + j] + 1,
                    cost[i * columns + j - 1] + 1,
                    cost[(i - 1) * columns + j - 1] + substitution,
                )
                if (isTransposition(source, target, i, j)) {
                    best = minOf(best, cost[(i - 2) * columns + j - 2] + 1)
                }
                cost[i * columns + j] = best
            }
        }

        var learned = 0
        var i = source.length
        var j = target.length
        while (i > 0 || j > 0) {
            // Checked before the diagonal: where a transposition is optimal it is strictly cheaper
            // than the two substitutions that would otherwise cover the same pair, so taking it
            // first never steals an alignment a match would have made.
            if (
                isTransposition(source, target, i, j) &&
                cost[i * columns + j] == cost[(i - 2) * columns + j - 2] + 1
            ) {
                i -= 2
                j -= 2
                continue
            }
            if (i > 0 && j > 0) {
                val substitution = if (source[i - 1] == target[j - 1]) 0 else 1
                if (cost[i * columns + j] == cost[(i - 1) * columns + j - 1] + substitution) {
                    val letter = target[j - 1]
                    val x = touches[(i - 1) * 2]
                    val y = touches[(i - 1) * 2 + 1]
                    if (letter in 'a'..'z' && x.isFinite() && y.isFinite() && keys.has(letter)) {
                        val dx = (x - keys.centerX(letter)) / keys.keyWidth
                        val dy = (y - keys.centerY(letter)) / keys.keyHeight
                        if (hypot(dx, dy) <= MAX_OBSERVATION_DISTANCE) {
                            add(letter, dx, dy)
                            learned++
                        }
                    }
                    i--
                    j--
                    continue
                }
            }
            if (i > 0 && cost[i * columns + j] == cost[(i - 1) * columns + j] + 1) {
                i--
            } else {
                j--
            }
        }
        return learned
    }

    /** Whether the two characters ending at [i]/[j] are the same pair in the opposite order. */
    private fun isTransposition(source: String, target: String, i: Int, j: Int): Boolean =
        i > 1 && j > 1 &&
            source[i - 1] == target[j - 2] &&
            source[i - 2] == target[j - 1] &&
            source[i - 1] != source[i - 2]

    fun entries(): List<Entry> = (0 until ALPHABET).mapNotNull { slot ->
        val count = counts[slot]
        if (count == 0) null else Entry(
            letter = 'a' + slot,
            count = count,
            meanX = meanX[slot],
            meanY = meanY[slot],
            m2X = m2X[slot],
            m2Y = m2Y[slot],
        )
    }

    fun restore(saved: List<Entry>) {
        clear()
        for (entry in saved) {
            val slot = entry.letter.lowercaseChar() - 'a'
            if (slot !in 0 until ALPHABET || entry.count <= 0) continue
            if (!entry.meanX.isFinite() || !entry.meanY.isFinite()) continue
            if (!entry.m2X.isFinite() || !entry.m2Y.isFinite()) continue
            counts[slot] = entry.count.coerceAtMost(MAX_COUNT)
            meanX[slot] = entry.meanX.coerceIn(-MAX_MEAN, MAX_MEAN)
            meanY[slot] = entry.meanY.coerceIn(-MAX_MEAN, MAX_MEAN)
            m2X[slot] = entry.m2X.coerceAtLeast(0f)
            m2Y[slot] = entry.m2Y.coerceAtLeast(0f)
        }
    }

    fun clear() {
        counts.fill(0)
        meanX.fill(0f)
        meanY.fill(0f)
        m2X.fill(0f)
        m2Y.fill(0f)
    }

    /**
     * Folds one observation into a letter's running mean and spread.
     *
     * Means are clamped to the same range [restore] enforces. Saving and reloading learned data
     * must not change how the keyboard behaves, and the snapshot the IME persists round-trips
     * through [entries]/[restore] — so a live mean the restore path would have clipped is a mean
     * that silently changes at the next process start.
     */
    private fun add(letter: Char, x: Float, y: Float) {
        val slot = letter - 'a'
        if (counts[slot] >= MAX_COUNT) {
            // A bounded exponential update lets the model follow a changed grip without allowing
            // a lifetime of history to make it immovable.
            val oldX = meanX[slot]
            val oldY = meanY[slot]
            meanX[slot] = (oldX + (x - oldX) / MAX_COUNT).coerceIn(-MAX_MEAN, MAX_MEAN)
            meanY[slot] = (oldY + (y - oldY) / MAX_COUNT).coerceIn(-MAX_MEAN, MAX_MEAN)
            m2X[slot] = (1f - 1f / MAX_COUNT) * m2X[slot] + (x - oldX) * (x - meanX[slot])
            m2Y[slot] = (1f - 1f / MAX_COUNT) * m2Y[slot] + (y - oldY) * (y - meanY[slot])
            return
        }

        val count = ++counts[slot]
        val deltaX = x - meanX[slot]
        val deltaY = y - meanY[slot]
        meanX[slot] = (meanX[slot] + deltaX / count).coerceIn(-MAX_MEAN, MAX_MEAN)
        meanY[slot] = (meanY[slot] + deltaY / count).coerceIn(-MAX_MEAN, MAX_MEAN)
        m2X[slot] += deltaX * (x - meanX[slot])
        m2Y[slot] += deltaY * (y - meanY[slot])
    }

    private fun isWordCharacter(character: Char): Boolean = character in 'a'..'z' || character == '\''

    private companion object {
        const val ALPHABET = 26
        const val MAX_COUNT = 128
        const val MIN_VARIANCE_OBSERVATIONS = 6
        const val BASELINE_VARIANCE = 0.09f
        const val MIN_VARIANCE = 0.04f
        const val MAX_VARIANCE = 0.20f
        const val MAX_MEAN = 0.65f
        const val MAX_OBSERVATION_DISTANCE = 1.35f
    }
}
