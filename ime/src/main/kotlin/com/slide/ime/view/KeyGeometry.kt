package com.slide.ime.view

import com.slide.core.layout.Key
import com.slide.core.layout.KeyboardLayout

/** A key with its resolved on-screen bounds. Bounds are the full touch cell, gaps included. */
class PlacedKey(
    val key: Key,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Index of the row this key belongs to, for row-aware hit testing. */
    val row: Int,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    /** Squared distance from (x, y) to the nearest point of this key's cell; 0 when inside. */
    fun squaredDistanceTo(x: Float, y: Float): Float {
        val dx = when {
            x < left -> left - x
            x > right -> x - right
            else -> 0f
        }
        val dy = when {
            y < top -> top - y
            y > bottom -> y - bottom
            else -> 0f
        }
        return dx * dx + dy * dy
    }
}

object KeyGeometry {

    /**
     * Places every key of [layout] into [width] x [contentHeight], starting at [topOffset].
     *
     * Cells tile the surface without gaps so that no touch lands "between" keys — the visual gap is
     * applied at draw time by insetting, not by shrinking the touch target.
     */
    fun place(
        layout: KeyboardLayout,
        width: Float,
        contentHeight: Float,
        topOffset: Float = 0f,
    ): List<PlacedKey> {
        val placed = ArrayList<PlacedKey>(layout.rows.sumOf { it.keys.size })
        val totalHeightWeight = layout.rows.sumOf { it.heightWeight.toDouble() }.toFloat()
        if (totalHeightWeight <= 0f || width <= 0f || contentHeight <= 0f) return placed

        val unitWidth = width / layout.widthUnits
        var y = topOffset

        layout.rows.forEachIndexed { rowIndex, row ->
            val rowHeight = contentHeight * (row.heightWeight / totalHeightWeight)
            var x = row.leadingGap * unitWidth

            row.keys.forEach { key ->
                val keyWidth = key.widthWeight * unitWidth
                placed += PlacedKey(
                    key = key,
                    left = x,
                    top = y,
                    right = x + keyWidth,
                    bottom = y + rowHeight,
                    row = rowIndex,
                )
                x += keyWidth
            }
            y += rowHeight
        }
        return placed
    }

    /**
     * Finds the key under (x, y), falling back to the nearest key when the touch lands in the
     * leading/trailing gap of a row.
     *
     * The fallback is deliberately row-local and bounded. The view can contain intentional blank
     * space above or below the placed keys (most notably the user-configurable bottom padding),
     * and treating that as the nearest key makes a tap on empty chrome type a character. Likewise,
     * a pointer dragged well past a row must be able to cancel instead of snapping back to an edge
     * key no matter how far away it is.
     */
    fun hitTest(keys: List<PlacedKey>, x: Float, y: Float): PlacedKey? {
        keys.firstOrNull { it.contains(x, y) }?.let { return it }

        var nearest: PlacedKey? = null
        var nearestDistance = Float.POSITIVE_INFINITY
        for (candidate in keys) {
            if (y < candidate.top || y >= candidate.bottom) continue
            val distance = candidate.squaredDistanceTo(x, y)
            if (distance < nearestDistance) {
                nearest = candidate
                nearestDistance = distance
            }
        }
        val resolved = nearest ?: return null
        // y is already inside this row, so only horizontal distance remains. Half a key accepts
        // the largest built-in half-unit indent while still bounding the outer edge.
        val maximumFallback = resolved.width * MAX_FALLBACK_KEY_FRACTION
        return resolved.takeIf { nearestDistance <= maximumFallback * maximumFallback }
    }

    /** Keys whose cell the point falls within, restricted to gesture-eligible letter keys. */
    fun gestureKeyAt(keys: List<PlacedKey>, x: Float, y: Float): Key? =
        keys.firstOrNull { it.contains(x, y) && it.key.gestureEligible }?.key

    /** Half a key covers the deliberate half-unit row indents without accepting distant taps. */
    private const val MAX_FALLBACK_KEY_FRACTION = 0.5f
}
