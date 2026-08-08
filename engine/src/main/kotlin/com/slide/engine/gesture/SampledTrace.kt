package com.slide.engine.gesture

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * A path resampled to a fixed number of points spaced evenly along its length.
 *
 * Raw touch points arrive at whatever rate the digitiser manages and bunch up wherever the finger
 * slowed down, so two traces of the same word are not comparable point-for-point. Resampling by
 * arc length removes speed from the comparison entirely: what is left is pure geometry, which is
 * what the shape and location channels want to measure.
 */
class SampledTrace private constructor(
    val xs: FloatArray,
    val ys: FloatArray,
) {

    val size: Int get() = xs.size

    /**
     * Translates the centroid to the origin and scales to unit root-mean-square radius.
     *
     * This is what makes the shape channel position- and size-blind, so that a small swipe near
     * the middle of the keyboard can still match a word whose ideal path is large and off to one
     * side. Absolute position is not lost -- the location channel measures it separately.
     */
    fun normalized(): SampledTrace {
        var meanX = 0f
        var meanY = 0f
        for (i in xs.indices) {
            meanX += xs[i]
            meanY += ys[i]
        }
        meanX /= size
        meanY /= size

        var sumSquares = 0f
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            val dy = ys[i] - meanY
            sumSquares += dx * dx + dy * dy
        }

        val radius = sqrt(sumSquares / size)
        // A trace with no extent at all (a tap held still) has no shape to normalise; leaving it
        // centred at the origin makes it maximally distant from every real word template.
        val scale = if (radius < EPSILON) 0f else 1f / radius

        val outX = FloatArray(size)
        val outY = FloatArray(size)
        for (i in xs.indices) {
            outX[i] = (xs[i] - meanX) * scale
            outY[i] = (ys[i] - meanY) * scale
        }
        return SampledTrace(outX, outY)
    }

    /** Straight-line length of the path, summed segment by segment. */
    fun pathLength(): Float {
        var total = 0f
        for (i in 1 until size) {
            total += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        }
        return total
    }

    companion object {
        private const val EPSILON = 1e-4f

        fun of(points: List<GesturePoint>, count: Int): SampledTrace {
            val xs = FloatArray(points.size)
            val ys = FloatArray(points.size)
            for (i in points.indices) {
                xs[i] = points[i].x
                ys[i] = points[i].y
            }
            return resample(xs, ys, points.size, count)
        }

        /**
         * Walks the polyline in equal arc-length steps, interpolating within whichever segment
         * each step lands in.
         */
        fun resample(xs: FloatArray, ys: FloatArray, length: Int, count: Int): SampledTrace {
            require(count >= 2) { "A resampled trace needs at least two points" }

            val outX = FloatArray(count)
            val outY = FloatArray(count)

            if (length == 0) return SampledTrace(outX, outY)
            if (length == 1) {
                outX.fill(xs[0])
                outY.fill(ys[0])
                return SampledTrace(outX, outY)
            }

            var total = 0f
            for (i in 1 until length) {
                total += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            }

            // Degenerate input: every sample landed on the same spot, so there is no length to
            // walk along and every output point is that spot.
            if (total < EPSILON) {
                outX.fill(xs[0])
                outY.fill(ys[0])
                return SampledTrace(outX, outY)
            }

            val step = total / (count - 1)
            outX[0] = xs[0]
            outY[0] = ys[0]

            var segment = 1
            var travelled = 0f
            var segmentStart = 0f

            for (out in 1 until count - 1) {
                val target = out * step

                // Advance until the segment we are on contains the target distance.
                while (segment < length) {
                    val segmentLength = hypot(xs[segment] - xs[segment - 1], ys[segment] - ys[segment - 1])
                    if (segmentStart + segmentLength >= target || segment == length - 1) {
                        travelled = segmentLength
                        break
                    }
                    segmentStart += segmentLength
                    segment++
                }

                val into = if (travelled < EPSILON) 0f else ((target - segmentStart) / travelled).coerceIn(0f, 1f)
                outX[out] = xs[segment - 1] + (xs[segment] - xs[segment - 1]) * into
                outY[out] = ys[segment - 1] + (ys[segment] - ys[segment - 1]) * into
            }

            outX[count - 1] = xs[length - 1]
            outY[count - 1] = ys[length - 1]
            return SampledTrace(outX, outY)
        }

        /** Builds a trace directly from already-sampled coordinates, for templates and tests. */
        fun wrap(xs: FloatArray, ys: FloatArray): SampledTrace = SampledTrace(xs, ys)
    }
}
