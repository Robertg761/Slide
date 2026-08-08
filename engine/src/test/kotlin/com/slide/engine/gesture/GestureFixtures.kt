package com.slide.engine.gesture

import java.util.Random
import kotlin.math.hypot

/**
 * Synthetic swipes, so the decoder can be judged without a phone in hand.
 *
 * A generated trace is not a substitute for a real finger — it has none of the systematic bias a
 * thumb has, and it never hesitates or backtracks. What it does give is a large, repeatable set
 * of gestures with known intent, which is enough to catch outright decoding failures and to stop
 * tuning changes silently regressing accuracy. Real-device traces refine the numbers later.
 */
object GestureFixtures {

    const val KEYBOARD_WIDTH = 1080f
    const val KEY_WIDTH = KEYBOARD_WIDTH / 10f
    const val ROW_HEIGHT = 160f

    private val ROWS = listOf(
        "qwertyuiop" to 0.0f,
        "asdfghjkl" to 0.5f,
        "zxcvbnm" to 1.5f,
    )

    /** A conventional staggered QWERTY at roughly the proportions of a 1080px-wide phone. */
    fun qwerty(): GestureKeyMap {
        val builder = GestureKeyMap.Builder(KEY_WIDTH, ROW_HEIGHT)
        ROWS.forEachIndexed { rowIndex, (letters, offset) ->
            letters.forEachIndexed { columnIndex, letter ->
                builder.put(
                    letter = letter,
                    x = (columnIndex + offset + 0.5f) * KEY_WIDTH,
                    y = (rowIndex + 0.5f) * ROW_HEIGHT,
                )
            }
        }
        return requireNotNull(builder.buildOrNull()) { "QWERTY fixture should always build" }
    }

    /**
     * Traces [word] as a finger might.
     *
     * [jitter] is the standard deviation of positional noise, in pixels, standing in for an
     * unsteady thumb. [smoothing] rounds the corners: a real finger carries momentum through a
     * turn rather than pivoting exactly on a key centre, and a decoder that only ever sees sharp
     * corners in testing will look better than it is.
     */
    fun trace(
        word: String,
        keys: GestureKeyMap,
        jitter: Float = 0f,
        smoothing: Int = 0,
        spacing: Float = 9f,
        seed: Long = 42L,
    ): List<GesturePoint> {
        val corners = corners(word, keys)
        require(corners.isNotEmpty()) { "'$word' has no gestureable letters" }
        if (corners.size == 1) {
            return listOf(GesturePoint(corners[0].first, corners[0].second, 0L))
        }

        val random = Random(seed)
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()

        for (segment in 1 until corners.size) {
            val (fromX, fromY) = corners[segment - 1]
            val (toX, toY) = corners[segment]
            val length = hypot(toX - fromX, toY - fromY)
            val steps = maxOf(1, (length / spacing).toInt())

            // The final corner is emitted once at the end, so each segment stops just short of it.
            for (step in 0 until steps) {
                val t = step.toFloat() / steps
                xs.add(fromX + (toX - fromX) * t)
                ys.add(fromY + (toY - fromY) * t)
            }
        }
        xs.add(corners.last().first)
        ys.add(corners.last().second)

        smooth(xs, ys, smoothing)

        return List(xs.size) { i ->
            GesturePoint(
                x = xs[i] + (random.nextGaussian() * jitter).toFloat(),
                y = ys[i] + (random.nextGaussian() * jitter).toFloat(),
                // ~120Hz, which is what a modern digitiser reports.
                timeMs = (i * 8).toLong(),
            )
        }
    }

    /** The ideal path for a word: the polyline through its key centres, resampled. */
    fun template(word: String, keys: GestureKeyMap, samples: Int = 64): SampledTrace {
        val corners = corners(word, keys)
        val xs = FloatArray(corners.size) { corners[it].first }
        val ys = FloatArray(corners.size) { corners[it].second }
        return SampledTrace.resample(xs, ys, corners.size, samples)
    }

    /**
     * True when two words trace the same path, so no amount of geometry can tell them apart.
     *
     * This happens whenever a word's distinguishing letter sits on the straight line between its
     * neighbours: "typing" (t-y-p) and "topping" (t-o-p) are both a straight run along the top
     * row, because y and o both lie between t and p. Only a language model or finger velocity can
     * separate these.
     */
    fun tracesIdentically(a: String, b: String, keys: GestureKeyMap, tolerance: Float = 1f): Boolean {
        val left = template(a, keys)
        val right = template(b, keys)
        var total = 0f
        for (i in 0 until left.size) {
            total += hypot(left.xs[i] - right.xs[i], left.ys[i] - right.ys[i])
        }
        return total / left.size <= tolerance
    }

    /** Key centres the word passes through, with consecutive repeats collapsed. */
    private fun corners(word: String, keys: GestureKeyMap): List<Pair<Float, Float>> {
        val result = ArrayList<Pair<Float, Float>>()
        var previous = ' '
        for (raw in word) {
            val letter = raw.lowercaseChar()
            if (letter == previous || !keys.has(letter)) continue
            result.add(keys.centerX(letter) to keys.centerY(letter))
            previous = letter
        }
        return result
    }

    /** Repeated box blur along the path, which rounds corners without shifting the endpoints. */
    private fun smooth(xs: MutableList<Float>, ys: MutableList<Float>, passes: Int) {
        repeat(passes) {
            val sourceX = xs.toList()
            val sourceY = ys.toList()
            for (i in 1 until xs.size - 1) {
                xs[i] = (sourceX[i - 1] + sourceX[i] + sourceX[i + 1]) / 3f
                ys[i] = (sourceY[i - 1] + sourceY[i] + sourceY[i + 1]) / 3f
            }
        }
    }
}
