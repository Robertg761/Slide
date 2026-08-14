package com.slide.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.slide.core.theme.KeyboardTheme
import com.slide.core.theme.Themes

/**
 * The clipboard panel: pinned items first, then the recent history, shown over the keys.
 *
 * The view is deliberately dumb about clipboard state — the service hands it a snapshot each
 * time it opens or an item changes, and every user action goes back through the listener. That
 * keeps clipboard access, expiry, and persistence in exactly one place.
 */
class ClipboardPanelView(context: Context) : LinearLayout(context) {

    /** One display row. */
    data class Item(val text: String, val pinned: Boolean)

    interface Listener {
        fun onClipboardDismissed()

        /** The user tapped an item to paste it. */
        fun onClipboardItemPicked(text: String)

        fun onClipboardItemPinToggled(text: String, pinned: Boolean)

        fun onClipboardItemDeleted(text: String)
    }

    var listener: Listener? = null

    var keyboardTheme: KeyboardTheme = Themes.Light
        set(value) {
            field = value
            applyTheme()
        }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    private var items: List<Item> = emptyList()

    private val list = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(12f), dp(4f), dp(12f), dp(16f))
    }
    private val emptyLabel = TextView(context).apply {
        text = "Nothing copied yet. Text you copy will appear here for an hour; pin it to keep it."
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(24f), dp(24f), dp(24f), dp(24f))
    }
    private val titleLabel: TextView
    private val backButton = BackIconView(context).apply {
        contentDescription = "Close clipboard"
        isClickable = true
        isFocusable = true
        setOnClickListener { listener?.onClipboardDismissed() }
    }

    init {
        orientation = VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Clipboard"

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), 0, dp(12f), 0)
        }
        titleLabel = TextView(context).apply {
            text = "Clipboard"
            gravity = Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(backButton, LayoutParams(dp(48f), dp(48f)))
        header.addView(titleLabel, LayoutParams(0, dp(48f), 1f))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(48f)))

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        applyTheme()
    }

    /** Replaces the visible items. Called by the service with a fresh snapshot. */
    fun setItems(entries: List<Item>) {
        items = entries
        rebuildRows()
    }

    private fun rebuildRows() {
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(emptyLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            applyTheme()
            return
        }
        items.forEach { item -> list.addView(buildRow(item)) }
        applyTheme()
    }

    private fun buildRow(item: Item): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52f)
            setPadding(dp(14f), dp(6f), dp(6f), dp(6f))
        }

        val preview = TextView(context).apply {
            text = item.text
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isClickable = true
            isFocusable = true
            contentDescription = "Paste: ${item.text.take(80)}"
            setOnClickListener { listener?.onClipboardItemPicked(item.text) }
        }
        row.addView(preview, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val pinButton = PinIconView(context).apply {
            filled = item.pinned
            contentDescription = if (item.pinned) "Unpin" else "Pin"
            isClickable = true
            isFocusable = true
            setOnClickListener { listener?.onClipboardItemPinToggled(item.text, !item.pinned) }
        }
        row.addView(pinButton, LayoutParams(dp(44f), dp(44f)))

        val deleteButton = CrossIconView(context).apply {
            contentDescription = "Delete from clipboard history"
            isClickable = true
            isFocusable = true
            setOnClickListener { listener?.onClipboardItemDeleted(item.text) }
        }
        row.addView(deleteButton, LayoutParams(dp(44f), dp(44f)))

        return row
    }

    private fun applyTheme() {
        setBackgroundColor(keyboardTheme.background)
        titleLabel.setTextColor(keyboardTheme.keyText)
        emptyLabel.setTextColor(keyboardTheme.hintText)
        backButton.iconColor = keyboardTheme.keyText
        backButton.background = rounded(keyboardTheme.specialKeyBackground, dp(24f).toFloat())
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index) as? LinearLayout ?: continue
            row.background = rounded(keyboardTheme.keyBackground, dp(12f).toFloat())
            (row.layoutParams as? MarginLayoutParams)?.setMargins(0, dp(3f), 0, dp(3f))
            for (child in 0 until row.childCount) {
                when (val view = row.getChildAt(child)) {
                    is TextView -> view.setTextColor(keyboardTheme.keyText)
                    is PinIconView -> view.iconColor = keyboardTheme.keyText
                    is CrossIconView -> view.iconColor = keyboardTheme.hintText
                }
            }
        }
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }
}

/** Geometry-drawn pin marker: outline when unpinned, filled when pinned. */
private class PinIconView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
    }

    var iconColor: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var filled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = iconColor
        paint.style = if (filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = 4.5f * density
        canvas.drawCircle(centerX, centerY - 2f * density, radius, paint)
        paint.style = Paint.Style.STROKE
        canvas.drawLine(centerX, centerY + radius - 2f * density, centerX, centerY + 8f * density, paint)
    }
}

/** Geometry-drawn delete cross. */
private class CrossIconView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
    }

    var iconColor: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = iconColor
        val centerX = width / 2f
        val centerY = height / 2f
        val arm = 5f * density
        canvas.drawLine(centerX - arm, centerY - arm, centerX + arm, centerY + arm, paint)
        canvas.drawLine(centerX - arm, centerY + arm, centerX + arm, centerY - arm, paint)
    }
}
