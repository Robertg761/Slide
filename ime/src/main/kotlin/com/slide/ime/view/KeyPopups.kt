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
import kotlin.math.min
import kotlin.math.roundToInt

/** Enlarged label shown above the key currently under the finger. */
class KeyPreviewPopup(private val anchor: View) {

    private val density = anchor.resources.displayMetrics.density
    private val location = IntArray(2)
    private val background = GradientDrawable().apply {
        cornerRadius = 12 * density
    }
    private val label = TextView(anchor.context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        setPadding(
            (14 * density).roundToInt(),
            (8 * density).roundToInt(),
            (14 * density).roundToInt(),
            (12 * density).roundToInt(),
        )
        background = this@KeyPreviewPopup.background
    }
    private val window = PopupWindow(label, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        isTouchable = false
        isFocusable = false
        isClippingEnabled = false
        elevation = 8 * density
    }

    fun show(theme: KeyboardTheme, text: String, key: PlacedKey) {
        label.text = text
        label.setTextColor(theme.popupText)
        label.textSize = 28f
        background.setColor(theme.popupBackground)
        // Elevation alone vanishes on dark themes, where the popup and the keys share a surface
        // colour; the same hairline the keycaps use keeps the popup's edge legible everywhere.
        background.setStroke(density.roundToInt().coerceAtLeast(1), theme.keyBorder)

        label.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupWidth = maxOf(label.measuredWidth, (key.width * 1.18f).roundToInt())
        val popupHeight = label.measuredHeight

        anchor.getLocationInWindow(location)
        val maxX = anchor.width - popupWidth
        val localX = (key.centerX - popupWidth / 2f).roundToInt().coerceIn(0, maxOf(0, maxX))
        val x = location[0] + localX
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
    private val location = IntArray(2)
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

        val edgeMargin = 4 * density
        content.configure(
            theme = theme,
            alternates = alternates,
            preferredCellWidth = key.width,
            maximumWidth = (anchor.width - edgeMargin * 2f).coerceAtLeast(1f),
        )
        val popupWidth = content.desiredWidth.roundToInt()
        val popupHeight = content.desiredHeight.roundToInt()

        anchor.getLocationInWindow(location)

        // Keep the popup on screen when the key sits near an edge.
        val minX = edgeMargin.roundToInt()
        val maxX = anchor.width - popupWidth - minX
        val rawX = (key.centerX - popupWidth / 2f).roundToInt()
        val localX = rawX.coerceIn(minX, maxOf(minX, maxX))
        val localY = key.top.roundToInt() - popupHeight - (4 * density).roundToInt()
        val x = location[0] + localX
        val y = location[1] + localY

        // Selection is driven by KeyboardView's view-local touch coordinates, so the popup's own
        // origin must be stored in that same space — not the window space used for positioning.
        content.originX = localX.toFloat()
        content.originY = localY.toFloat()
        content.selectionBottom = key.bottom - localY
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
        private val selectionMargin = 12 * density
        private var cellWidth = 44 * density
        private var cellHeight = 52 * density

        private var items: List<String> = emptyList()
        private var theme: KeyboardTheme? = null
        private var selectedIndex = 0

        var originX: Float = 0f
        var originY: Float = 0f
        var selectionBottom: Float = 0f
        var selectedText: String? = null

        val desiredWidth: Float get() = items.size * cellWidth + cellPadding * 2
        val desiredHeight: Float get() = cellHeight + cellPadding * 2

        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        private val reusableRect = RectF()
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }

        fun configure(
            theme: KeyboardTheme,
            alternates: List<String>,
            preferredCellWidth: Float,
            maximumWidth: Float,
        ) {
            this.theme = theme
            this.items = alternates
            this.cellWidth = AlternatePopupGeometry.cellWidth(
                preferredCellWidth = preferredCellWidth,
                maximumWidth = maximumWidth,
                itemCount = alternates.size,
                horizontalPadding = cellPadding * 2f,
                minimumCellWidth = 24 * density,
                maximumCellWidth = 56 * density,
            )
            backgroundPaint.color = theme.popupBackground
            selectionPaint.color = theme.popupSelectedBackground
            borderPaint.color = theme.keyBorder
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
            val localX = x - originX
            val localY = y - originY
            if (
                localX < -selectionMargin ||
                localX > desiredWidth + selectionMargin ||
                localY < -selectionMargin ||
                localY > selectionBottom + selectionMargin
            ) {
                selectedText = null
                invalidate()
                return
            }
            val cellX = (localX - cellPadding).coerceIn(0f, items.size * cellWidth - 1f)
            val index = (cellX / cellWidth).toInt().coerceIn(0, items.lastIndex)
            selectedIndex = index
            selectedText = items[index]
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(desiredWidth.roundToInt(), desiredHeight.roundToInt())
        }

        override fun onDraw(canvas: Canvas) {
            val theme = theme ?: return
            val radius = 12 * density
            reusableRect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(reusableRect, radius, radius, backgroundPaint)
            // Same rationale as the key preview: elevation is invisible against a same-coloured
            // dark keyboard, so the edge is drawn. Inset half a stroke to keep it unclipped.
            val edge = borderPaint.strokeWidth / 2f
            reusableRect.inset(edge, edge)
            canvas.drawRoundRect(reusableRect, radius - edge, radius - edge, borderPaint)

            val metrics = textPaint.fontMetrics
            val baselineOffset = (metrics.descent + metrics.ascent) / 2f

            items.forEachIndexed { index, text ->
                val left = cellPadding + index * cellWidth
                reusableRect.set(left, cellPadding, left + cellWidth, cellPadding + cellHeight)
                if (index == selectedIndex) {
                    canvas.drawRoundRect(reusableRect, radius * 0.7f, radius * 0.7f, selectionPaint)
                    textPaint.color = theme.accentText
                } else {
                    textPaint.color = theme.popupText
                }
                canvas.drawText(
                    text,
                    reusableRect.centerX(),
                    reusableRect.centerY() - baselineOffset,
                    textPaint,
                )
            }
        }
    }
}

/** Width policy kept outside Android drawing so oversized accent menus stay regression-tested. */
internal object AlternatePopupGeometry {
    fun cellWidth(
        preferredCellWidth: Float,
        maximumWidth: Float,
        itemCount: Int,
        horizontalPadding: Float,
        minimumCellWidth: Float,
        maximumCellWidth: Float,
    ): Float {
        if (itemCount <= 0) return 0f
        val usableWidth = (maximumWidth - horizontalPadding).coerceAtLeast(1f)
        val widthThatFits = usableWidth / itemCount
        val preferred = preferredCellWidth.coerceIn(minimumCellWidth, maximumCellWidth)
        return min(preferred, widthThatFits).coerceAtLeast(1f)
    }
}
