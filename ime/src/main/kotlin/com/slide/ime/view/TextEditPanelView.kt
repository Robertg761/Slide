package com.slide.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import com.slide.core.theme.KeyboardTheme
import com.slide.core.theme.Themes

/**
 * Cursor and selection controls shown over the keys — Gboard's "text editing" panel.
 *
 * Everything here is expressed as an intent the service turns into editor operations, because
 * only the service can see the `InputConnection`. The arrows repeat while held, exactly like
 * Backspace on the main keyboard, since walking a cursor across a sentence one tap at a time is
 * the panel's most common job.
 */
class TextEditPanelView(context: Context) : LinearLayout(context) {

    /** One editing intent per control; the service owns what each does to the editor. */
    enum class Action { Left, Right, Up, Down, SelectAll, Copy, Cut, Paste, Delete }

    interface Listener {
        fun onTextEditDismissed()

        fun onTextEditAction(action: Action)

        /** The Select toggle changed; while on, arrow actions extend the selection. */
        fun onTextEditSelectingChanged(selecting: Boolean)
    }

    var listener: Listener? = null

    var keyboardTheme: KeyboardTheme = Themes.Light
        set(value) {
            field = value
            applyTheme()
        }

    /** Whether arrows currently extend the selection. Owned here, read by the service. */
    var selecting: Boolean = false
        private set

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    private val handler = Handler(Looper.getMainLooper())
    private val labels = mutableListOf<TextView>()
    private val buttons = mutableListOf<View>()
    private val arrowViews = mutableListOf<ArrowButton>()
    private lateinit var selectToggle: TextView

    private val backButton = BackIconView(context).apply {
        contentDescription = "Close text editing"
        isClickable = true
        isFocusable = true
        setOnClickListener { listener?.onTextEditDismissed() }
    }

    init {
        orientation = VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Text editing"

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), 0, dp(12f), 0)
        }
        header.addView(backButton, LayoutParams(dp(48f), dp(48f)))
        header.addView(
            label("Edit text", 18f, bold = true),
            LayoutParams(0, dp(48f), 1f),
        )
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(48f)))

        // Three columns: selection controls, the arrow cluster, clipboard actions. Weights keep
        // the cluster centred whatever the keyboard's width or height setting.
        val body = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(10f), dp(4f), dp(10f), dp(10f))
        }
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val selectColumn = column(body)
        selectToggle = textButton(selectColumn, "Select") {
            selecting = !selecting
            applySelectToggle()
            listener?.onTextEditSelectingChanged(selecting)
        }
        textButton(selectColumn, "Select all") { listener?.onTextEditAction(Action.SelectAll) }

        val arrowColumn = column(body)
        val upRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        val midRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        arrowColumn.addView(upRow, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        arrowColumn.addView(midRow, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        spacer(upRow)
        arrowButton(upRow, ArrowButton.Direction.UP, "Move up", Action.Up)
        spacer(upRow)
        arrowButton(midRow, ArrowButton.Direction.LEFT, "Move left", Action.Left)
        arrowButton(midRow, ArrowButton.Direction.DOWN, "Move down", Action.Down)
        arrowButton(midRow, ArrowButton.Direction.RIGHT, "Move right", Action.Right)

        val actionColumn = column(body)
        textButton(actionColumn, "Copy") { listener?.onTextEditAction(Action.Copy) }
        textButton(actionColumn, "Cut") { listener?.onTextEditAction(Action.Cut) }
        textButton(actionColumn, "Paste") { listener?.onTextEditAction(Action.Paste) }
        textButton(actionColumn, "Delete") { listener?.onTextEditAction(Action.Delete) }

        applyTheme()
    }

    /** Clears transient state when the panel is opened or dismissed. */
    fun reset() {
        if (selecting) {
            selecting = false
            applySelectToggle()
        }
        arrowViews.forEach(ArrowButton::cancelRepeat)
    }

    private fun label(text: String, sizeSp: Float, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            labels += this
        }

    private fun column(parent: LinearLayout): LinearLayout =
        LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            parent.addView(this, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }

    private fun spacer(parent: LinearLayout) {
        parent.addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    private fun textButton(parent: LinearLayout, text: String, onClick: () -> Unit): TextView {
        val button = TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isClickable = true
            isFocusable = true
            contentDescription = text
            setOnClickListener { onClick() }
        }
        labels += button
        buttons += button
        parent.addView(
            button,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
            },
        )
        return button
    }

    private fun arrowButton(
        parent: LinearLayout,
        direction: ArrowButton.Direction,
        description: String,
        action: Action,
    ) {
        val button = ArrowButton(context, direction) { listener?.onTextEditAction(action) }.apply {
            contentDescription = description
            isClickable = true
            isFocusable = true
        }
        arrowViews += button
        buttons += button
        parent.addView(
            button,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
            },
        )
    }

    private fun applySelectToggle() {
        selectToggle.text = if (selecting) "Selecting…" else "Select"
        selectToggle.contentDescription =
            if (selecting) "Stop selecting" else "Select: arrows extend the selection"
        applyTheme()
    }

    private fun applyTheme() {
        setBackgroundColor(keyboardTheme.background)
        labels.forEach { it.setTextColor(keyboardTheme.keyText) }
        backButton.iconColor = keyboardTheme.keyText
        backButton.background = rounded(keyboardTheme.specialKeyBackground, dp(24f).toFloat())
        buttons.forEach { view ->
            val highlighted = view === selectToggle && selecting
            view.background = rounded(
                if (highlighted) keyboardTheme.accentBackground else keyboardTheme.keyBackground,
                dp(12f).toFloat(),
            )
            if (view === selectToggle) {
                selectToggle.setTextColor(
                    if (highlighted) keyboardTheme.accentText else keyboardTheme.keyText,
                )
            }
        }
        arrowViews.forEach { it.iconColor = keyboardTheme.keyText }
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }
}

/**
 * A geometry-drawn arrow that fires on press and repeats while held.
 *
 * Drawn rather than taken from the font for the same reason as the settings panel's back arrow:
 * OEM fonts differ on which triangle glyphs exist and how they sit on the baseline.
 */
private class ArrowButton(
    context: Context,
    private val direction: Direction,
    private val onFire: () -> Unit,
) : View(context) {

    enum class Direction { LEFT, RIGHT, UP, DOWN }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f * density
    }
    private val handler = Handler(Looper.getMainLooper())
    private var repeating: Runnable? = null

    var iconColor: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                onFire()
                scheduleRepeat()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                cancelRepeat()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // The action already fired on the down event; this exists so accessibility clicks work too.
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun scheduleRepeat() {
        val runnable = object : Runnable {
            override fun run() {
                onFire()
                handler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        repeating = runnable
        handler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    fun cancelRepeat() {
        repeating?.let(handler::removeCallbacks)
        repeating = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelRepeat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = iconColor
        val centerX = width / 2f
        val centerY = height / 2f
        val arm = 6f * density
        when (direction) {
            Direction.LEFT -> {
                canvas.drawLine(centerX + arm / 2f, centerY - arm, centerX - arm / 2f, centerY, paint)
                canvas.drawLine(centerX - arm / 2f, centerY, centerX + arm / 2f, centerY + arm, paint)
            }
            Direction.RIGHT -> {
                canvas.drawLine(centerX - arm / 2f, centerY - arm, centerX + arm / 2f, centerY, paint)
                canvas.drawLine(centerX + arm / 2f, centerY, centerX - arm / 2f, centerY + arm, paint)
            }
            Direction.UP -> {
                canvas.drawLine(centerX - arm, centerY + arm / 2f, centerX, centerY - arm / 2f, paint)
                canvas.drawLine(centerX, centerY - arm / 2f, centerX + arm, centerY + arm / 2f, paint)
            }
            Direction.DOWN -> {
                canvas.drawLine(centerX - arm, centerY - arm / 2f, centerX, centerY + arm / 2f, paint)
                canvas.drawLine(centerX, centerY + arm / 2f, centerX + arm, centerY - arm / 2f, paint)
            }
        }
    }

    private companion object {
        const val REPEAT_INTERVAL_MS = 120L
    }
}
