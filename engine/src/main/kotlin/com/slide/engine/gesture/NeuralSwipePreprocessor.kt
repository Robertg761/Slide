package com.slide.engine.gesture

import kotlin.math.roundToInt

/** Tensor-ready, normalized representation of one swipe and its current keyboard layout. */
internal data class NeuralSwipeInput(
    val features: FloatArray,
    val layoutKeys: FloatArray,
    val layoutMask: BooleanArray,
)

/** Preprocessing used by the FUTO Swipe encoder, kept separate so it can be unit-tested on JVM. */
internal object NeuralSwipePreprocessor {
    private const val INPUT_POINTS = 64
    private const val MAX_KEYS = 64
    private const val HZ_60_MS = 1000.0 / 60.0

    fun prepare(points: List<GesturePoint>, keys: GestureKeyMap): NeuralSwipeInput? {
        if (points.size < 6) return null

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var letters = 0
        for (letter in 'a'..'z') {
            if (!keys.has(letter)) continue
            minX = minOf(minX, keys.centerX(letter))
            maxX = maxOf(maxX, keys.centerX(letter))
            minY = minOf(minY, keys.centerY(letter))
            maxY = maxOf(maxY, keys.centerY(letter))
            letters++
        }
        if (letters < 20) return null

        val left = minX - keys.keyWidth / 2f
        val top = minY - keys.keyHeight / 2f
        val width = maxX - minX + keys.keyWidth
        val height = maxY - minY + keys.keyHeight
        if (width <= 0f || height <= 0f) return null

        fun nx(x: Float) = ((x - left) / width).coerceIn(0f, 1f)
        fun ny(y: Float) = ((y - top) / height).coerceIn(0f, 1f)

        val rawX = FloatArray(points.size) { nx(points[it].x) }
        val rawY = FloatArray(points.size) { ny(points[it].y) }
        val rawT = LongArray(points.size) { points[it].timeMs }
        val sampled = resample(rawX, rawY, rawT)

        // ExecuTorch uses row-major storage: all 64 x samples, then all 64 y samples.
        val features = FloatArray(INPUT_POINTS * 2)
        sampled.first.copyInto(features, 0)
        sampled.second.copyInto(features, INPUT_POINTS)

        val layout = FloatArray(MAX_KEYS * 2)
        val mask = BooleanArray(MAX_KEYS)
        for (slot in 0 until 26) {
            val letter = 'a' + slot
            if (!keys.has(letter)) continue
            layout[slot * 2] = nx(keys.centerX(letter))
            layout[slot * 2 + 1] = ny(keys.centerY(letter))
            mask[slot] = true
        }
        return NeuralSwipeInput(features, layout, mask)
    }

    private fun resample(
        xs: FloatArray,
        ys: FloatArray,
        times: LongArray,
    ): Pair<FloatArray, FloatArray> {
        val duration = (times.last() - times.first()).coerceAtLeast(0L)
        if (duration <= 1L) return resampleByIndex(xs, ys, INPUT_POINTS)

        val count60 = maxOf(2, (duration / HZ_60_MS).roundToInt() + 1)
        val x60 = FloatArray(count60)
        val y60 = FloatArray(count60)
        var cursor = 0
        for (sample in 0 until count60) {
            val target = times.first() + duration.toDouble() * sample / (count60 - 1)
            while (cursor + 1 < times.size && times[cursor + 1] <= target) cursor++
            if (cursor + 1 >= times.size) {
                x60[sample] = xs.last()
                y60[sample] = ys.last()
                continue
            }

            // Android can batch several historical coordinates at the same timestamp. Skip that
            // zero-duration run so interpolation never divides by zero.
            var next = cursor + 1
            while (next + 1 < times.size && times[next] <= times[cursor]) next++
            val span = (times[next] - times[cursor]).coerceAtLeast(1L)
            val fraction = ((target - times[cursor]) / span).coerceIn(0.0, 1.0).toFloat()
            x60[sample] = xs[cursor] + (xs[next] - xs[cursor]) * fraction
            y60[sample] = ys[cursor] + (ys[next] - ys[cursor]) * fraction
        }
        return resampleByIndex(x60, y60, INPUT_POINTS)
    }

    private fun resampleByIndex(
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
    ): Pair<FloatArray, FloatArray> {
        val outX = FloatArray(count)
        val outY = FloatArray(count)
        if (xs.size == 1) {
            outX.fill(xs[0])
            outY.fill(ys[0])
            return outX to outY
        }
        for (i in 0 until count) {
            val position = i.toDouble() * (xs.size - 1) / (count - 1)
            val low = position.toInt()
            val high = minOf(low + 1, xs.lastIndex)
            val fraction = (position - low).toFloat()
            outX[i] = xs[low] + (xs[high] - xs[low]) * fraction
            outY[i] = ys[low] + (ys[high] - ys[low]) * fraction
        }
        return outX to outY
    }
}
