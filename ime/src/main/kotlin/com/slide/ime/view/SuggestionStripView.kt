package com.slide.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.slide.core.theme.KeyboardTheme
import com.slide.core.theme.Themes

/**
 * The row of word candidates above the keys.
 *
 * The decoder is right about 96% of the time on its first choice but essentially always has the
 * intended word somewhere in its top five, so the value here is almost entirely in the alternatives:
 * this strip is what turns a wrong decode from a retyped word into a single tap.
 *
 * It draws itself rather than nesting TextViews. Three cells is a fixed, tiny layout, and doing it
 * in one onDraw keeps it consistent with [KeyboardView] and avoids a measure pass on every swipe.
 */
class SuggestionStripView(context: Context) : View(context) {

    interface Listener {
        /** [index] is the position in the list passed to [setSuggestions], 0 being the best. */
        fun onSuggestionPicked(index: Int, word: String)

        /** The microphone button was tapped. */
        fun onVoiceRequested()
    }

    var listener: Listener? = null

    var keyboardTheme: KeyboardTheme = Themes.Light
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val words = ArrayList<String>(MAX_VISIBLE)
    private var pressedIndex = -1
    private var micPressed = false

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(16f)
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1f)
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Shows up to three candidates, best first, or clears the strip when given none.
     *
     * Three rather than the decoder's five: past that the cells are too narrow to read at a glance
     * or to hit reliably, and the fourth and fifth candidates are almost never the intended word.
     */
    fun setSuggestions(candidates: List<String>) {
        words.clear()
        candidates.take(MAX_VISIBLE).forEach(words::add)
        pressedIndex = -1
        invalidate()
    }

    fun clear() = setSuggestions(emptyList())

    val isEmpty: Boolean get() = words.isEmpty()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The strip keeps its height even when empty. Letting it collapse would resize the whole
        // input view on every swipe, shoving the keys up and down under the user's thumb.
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(dp(HEIGHT_DP).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(keyboardTheme.background)
        drawMicButton(canvas)
        if (words.isEmpty()) return

        val cellWidth = suggestionWidth() / MAX_VISIBLE
        // Vertically centre on the text's own middle rather than its baseline.
        val metrics = textPaint.fontMetrics
        val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f

        for (index in words.indices) {
            val left = index * cellWidth

            if (index == pressedIndex) {
                pressedPaint.color = keyboardTheme.keyPressedOverlay
                canvas.drawRect(left, 0f, left + cellWidth, height.toFloat(), pressedPaint)
            }

            // The top choice is the one that gets committed, so it is marked as such: the user
            // should be able to tell at a glance whether the keyboard already agrees with them.
            textPaint.color = if (index == 0) {
                keyboardTheme.suggestionHighlightText
            } else {
                keyboardTheme.suggestionText
            }
            textPaint.typeface = if (index == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

            canvas.drawText(
                ellipsize(words[index], cellWidth - dp(12f)),
                left + cellWidth / 2f,
                baseline,
                textPaint,
            )

            if (index > 0) {
                val inset = dp(10f)
                dividerPaint.color = keyboardTheme.divider
                canvas.drawLine(left, inset, left, height - inset, dividerPaint)
            }
        }
    }

    /**
     * The microphone lives here rather than in the bottom key row, as in Gboard.
     *
     * The alternative is taking width from the space bar, which is the most-hit key on the
     * keyboard and the one that can least afford to shrink. Here it is always reachable and never
     * competes with a letter.
     */
    private fun drawMicButton(canvas: Canvas) {
        val centerX = width - micWidth() / 2f
        val centerY = height / 2f

        if (micPressed) {
            pressedPaint.color = keyboardTheme.keyPressedOverlay
            canvas.drawCircle(centerX, centerY, height * 0.4f, pressedPaint)
        }

        dividerPaint.color = keyboardTheme.divider
        val inset = dp(10f)
        val boundary = width - micWidth()
        canvas.drawLine(boundary, inset, boundary, height - inset, dividerPaint)

        textPaint.color = keyboardTheme.suggestionText
        MicGlyph.draw(canvas, textPaint, centerX, centerY, height * 0.34f)
    }

    /** Width reserved for the microphone button at the right edge. */
    private fun micWidth(): Float = height.toFloat()

    private fun suggestionWidth(): Float = width - micWidth()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                micPressed = isOverMic(event.x)
                pressedIndex = if (micPressed) -1 else indexAt(event.x)
                invalidate()
                return micPressed || pressedIndex >= 0
            }

            MotionEvent.ACTION_MOVE -> {
                // Sliding off cancels the press, matching how the keys behave.
                val inBounds = event.y in 0f..height.toFloat()
                val stillOnMic = micPressed && isOverMic(event.x) && inBounds
                val stillOnWord = pressedIndex >= 0 && indexAt(event.x) == pressedIndex && inBounds
                if (micPressed != stillOnMic || (pressedIndex >= 0 && !stillOnWord)) {
                    micPressed = stillOnMic
                    if (!stillOnWord) pressedIndex = -1
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                val index = pressedIndex
                val mic = micPressed
                pressedIndex = -1
                micPressed = false
                invalidate()

                if (mic) {
                    listener?.onVoiceRequested()
                } else if (index in words.indices) {
                    listener?.onSuggestionPicked(index, words[index])
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                micPressed = false
                invalidate()
            }
        }
        return true
    }

    private fun isOverMic(x: Float): Boolean = x >= width - micWidth()

    private fun indexAt(x: Float): Int {
        if (words.isEmpty() || isOverMic(x)) return -1
        val index = (x / (suggestionWidth() / MAX_VISIBLE)).toInt()
        return if (index in words.indices) index else -1
    }

    /** Trims a word that will not fit its cell, so it degrades to "extraordi…" rather than clipping. */
    private fun ellipsize(word: String, available: Float): String {
        if (textPaint.measureText(word) <= available) return word
        var end = word.length
        while (end > 1 && textPaint.measureText(word.substring(0, end) + "…") > available) end--
        return word.substring(0, end) + "…"
    }

    private companion object {
        const val MAX_VISIBLE = 3
        const val HEIGHT_DP = 44f

        /** Leaves the glyph comfortably inside its square without looking lost in it. */
        const val MIC_GLYPH_FRACTION = 0.30f
    }
}
