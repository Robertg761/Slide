package com.slide.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import com.slide.core.theme.KeyboardTheme
import kotlin.math.roundToInt

/** Enlarged label shown above the key currently under the finger. */
class KeyPreviewPopup(private val anchor: View) {

    private val density = anchor.resources.displayMetrics.density
    private val label = TextView(anchor.context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        setPadding((14 * density).roundToInt(), (8 * density).roundToInt(), (14 * density).roundToInt(), (12 * density).roundToInt())
    }
    private val window = PopupWindow(label, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        isTouchable = false
        isFocusable = false
        isClippingEnabled = false
        elevation = 6 * density
    }

    fun show(theme: KeyboardTheme, text: String, key: PlacedKey) {
        label.text = text
        label.setTextColor(theme.popupText)
        label.textSize = 28f
        label.background = GradientDrawable().apply {
            setColor(theme.popupBackground)
            cornerRadius = 10 * density
        }

        label.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupWidth = maxOf(label.measuredWidth, (key.width * 1.1f).roundToInt())
        val popupHeight = label.measuredHeight

        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val x = location[0] + (key.centerX - popupWidth / 2f).roundToInt()
        val y = location[1] + key.top.roundToInt() - popupHeight - (4 * density).roundToInt()

        if (window.isShowing) {
            window.update(x, y, popupWidth, popupHeight)
        } else {
            window.width = popupWidth
            window.height = popupHeight
            window.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
    }

    fun dismiss() {
        if (window.isShowing) window.dismiss()
    }
}

/**
 * Long-press popup offering a key's alternate characters.
 *
 * The popup is display-only: [KeyboardView] owns the touch stream and drives selection through
 * [updateSelection], so there is exactly one place that interprets finger movement.
 */
class AlternatesPopup(private val anchor: View) {

    private val density = anchor.resources.displayMetrics.density
    private val content = AlternatesView(anchor.context)
    private val window = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        isTouchable = false
        isFocusable = false
        isClippingEnabled = false
        elevation = 8 * density
    }

    val isShowing: Boolean get() = window.isShowing

    /** Character currently under the finger, or null if the finger has moved off the popup. */
    val selected: String? get() = content.selectedText

    fun show(theme: KeyboardTheme, alternates: List<String>, key: PlacedKey) {
        if (alternates.isEmpty()) return

        content.configure(theme, alternates, (key.width).coerceAtLeast(40 * density))
        val popupWidth = content.desiredWidth.roundToInt()
        val popupHeight = content.desiredHeight.roundToInt()

        val location = IntArray(2)
        anchor.getLocationInWindow(location)

        // Keep the popup on screen when the key sits near an edge.
        val maxX = anchor.width - popupWidth
        val rawX = (key.centerX - popupWidth / 2f).roundToInt()
        val x = location[0] + rawX.coerceIn(0, maxOf(0, maxX))
        val y = location[1] + key.top.roundToInt() - popupHeight - (4 * density).roundToInt()

        // Selection is driven by KeyboardView's view-local touch coordinates, so the popup's own
        // origin must be stored in that same space — not the window space used for positioning.
        content.originX = rawX.coerceIn(0, maxOf(0, maxX)).toFloat()
        content.selectIndex(alternates.indexOf(key.key.label).takeIf { it >= 0 } ?: 0)

        if (window.isShowing) {
            window.update(x, y, popupWidth, popupHeight)
        } else {
            window.width = popupWidth
            window.height = popupHeight
            window.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
    }

    /** @param x finger position in the anchor view's local coordinate space. */
    fun updateSelection(x: Float, y: Float) {
        content.updateSelection(x, y)
    }

    fun dismiss() {
        content.selectedText = null
        if (window.isShowing) window.dismiss()
    }

    private class AlternatesView(context: Context) : View(context) {

        private val density = resources.displayMetrics.density
        private val cellPadding = 6 * density
        private var cellWidth = 44 * density
        private var cellHeight = 52 * density

        private var items: List<String> = emptyList()
        private var theme: KeyboardTheme? = null
        private var selectedIndex = 0

        var originX: Float = 0f
        var selectedText: String? = null

        val desiredWidth: Float get() = items.size * cellWidth + cellPadding * 2
        val desiredHeight: Float get() = cellHeight + cellPadding * 2

        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }

        fun configure(theme: KeyboardTheme, alternates: List<String>, keyWidth: Float) {
            this.theme = theme
            this.items = alternates
            this.cellWidth = keyWidth.coerceIn(40 * density, 64 * density)
            backgroundPaint.color = theme.popupBackground
            selectionPaint.color = theme.popupSelectedBackground
            textPaint.color = theme.popupText
            textPaint.textSize = 22 * density
            requestLayout()
            invalidate()
        }

        fun selectIndex(index: Int) {
            selectedIndex = index.coerceIn(0, maxOf(0, items.lastIndex))
            selectedText = items.getOrNull(selectedIndex)
            invalidate()
        }

        fun updateSelection(x: Float, y: Float) {
            if (items.isEmpty()) return
            val localX = x - originX - cellPadding
            val index = (localX / cellWidth).toInt().coerceIn(0, items.lastIndex)
            selectedIndex = index
            selectedText = items[index]
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(desiredWidth.roundToInt(), desiredHeight.roundToInt())
        }

        override fun onDraw(canvas: Canvas) {
            val theme = theme ?: return
            val radius = 10 * density
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, backgroundPaint)

            val metrics = textPaint.fontMetrics
            val baselineOffset = (metrics.descent + metrics.ascent) / 2f

            items.forEachIndexed { index, text ->
                val left = cellPadding + index * cellWidth
                val cell = RectF(left, cellPadding, left + cellWidth, cellPadding + cellHeight)
                if (index == selectedIndex) {
                    canvas.drawRoundRect(cell, radius * 0.7f, radius * 0.7f, selectionPaint)
                    textPaint.color = theme.accentText
                } else {
                    textPaint.color = theme.popupText
                }
                canvas.drawText(text, cell.centerX(), cell.centerY() - baselineOffset, textPaint)
            }
        }
    }
}
