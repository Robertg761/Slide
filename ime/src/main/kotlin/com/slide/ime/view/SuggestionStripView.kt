package com.slide.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.slide.core.theme.KeyboardTheme
import com.slide.core.theme.Themes
import kotlin.math.cos
import kotlin.math.sin

/**
 * The row of word candidates above the keys.
 *
 * The centre cell is the leading candidate and the side cells are alternatives. Keeping that
 * placement stable turns a wrong decode from a retyped word into one predictable tap.
 *
 * It draws itself rather than nesting TextViews. Three cells is a fixed, tiny layout, and doing it
 * in one onDraw keeps it consistent with [KeyboardView] and avoids a measure pass on every swipe.
 */
class SuggestionStripView(context: Context) : View(context) {

    interface Listener {
        /** Opens Slide's keyboard settings. */
        fun onSettingsRequested()

        /** [index] is the position in the list passed to [setSuggestions], 0 being the best. */
        fun onSuggestionPicked(index: Int, word: String)

        /**
         * A candidate was held down: the gesture for teaching the keyboard a word, or for taking
         * one back. Default no-op so existing embedders are unaffected.
         */
        fun onSuggestionHeld(index: Int, word: String) = Unit

        /** The microphone button was tapped. */
        fun onVoiceRequested()
    }

    var listener: Listener? = null

    /** False for password/secure and non-language editors. */
    var voiceEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            pressedIndex = -1
            micPressed = false
            settingsPressed = false
            refreshAccessibilityDescription()
            invalidate()
        }

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

    /** See [ellipsized]. */
    private var ellipsizedWidth = 0f
    private val ellipsizedNormal = HashMap<String, String>()
    private val ellipsizedBold = HashMap<String, String>()

    /** Reused across draws; `Paint.fontMetrics` allocates a fresh object per read. */
    private val reusableMetrics = Paint.FontMetrics()

    private var pressedIndex = -1
    private var micPressed = false
    private var settingsPressed = false
    private var emptyMessage = "Type or swipe for suggestions"

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    /** Set once a hold has fired, so releasing does not also pick the candidate. */
    private var longPressFired = false

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(16f)
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1f)
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }

    private val accessibilityHelper = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (y !in 0f..height.toFloat()) return INVALID_ID
            if (isOverSettings(x)) return A11Y_SETTINGS
            if (voiceEnabled && isOverMic(x)) return A11Y_MIC
            val index = indexAt(x)
            return if (index >= 0) A11Y_WORD_BASE + index else INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            virtualViewIds += A11Y_SETTINGS
            words.indices.forEach { virtualViewIds += A11Y_WORD_BASE + it }
            if (voiceEnabled) virtualViewIds += A11Y_MIC
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat,
        ) {
            node.className = "android.widget.Button"
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            if (virtualViewId == A11Y_SETTINGS) {
                node.contentDescription = "Keyboard settings"
                node.setBoundsInParent(Rect(0, 0, settingsWidth().toInt(), height))
                return
            }
            if (virtualViewId == A11Y_MIC) {
                node.contentDescription = "Voice typing"
                node.setBoundsInParent(Rect((width - micWidth()).toInt(), 0, width, height))
                return
            }

            val index = virtualViewId - A11Y_WORD_BASE
            val word = words.getOrNull(index).orEmpty()
            node.contentDescription = if (index == 0) "$word, best suggestion" else word
            node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
            val cellWidth = suggestionWidth() / MAX_VISIBLE
            val visualSlot = SuggestionPlacement.slotForCandidate(words.size, index)
            val contentLeft = suggestionLeft()
            node.setBoundsInParent(
                Rect(
                    (contentLeft + visualSlot * cellWidth).toInt(),
                    0,
                    (contentLeft + (visualSlot + 1) * cellWidth).toInt(),
                    height,
                ),
            )
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?,
        ): Boolean {
            if (virtualViewId == A11Y_SETTINGS) {
                if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
                listener?.onSettingsRequested()
                return true
            }
            if (virtualViewId == A11Y_MIC) {
                if (action != AccessibilityNodeInfo.ACTION_CLICK || !voiceEnabled) return false
                listener?.onVoiceRequested()
                return true
            }
            val index = virtualViewId - A11Y_WORD_BASE
            val word = words.getOrNull(index) ?: return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    listener?.onSuggestionPicked(index, word)
                    true
                }
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> {
                    listener?.onSuggestionHeld(index, word)
                    true
                }
                else -> false
            }
        }
    }

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
        refreshAccessibilityDescription()
    }

    /**
     * Shows up to three candidates, best first, or clears the strip when given none.
     *
     * Three rather than the decoder's five: past that the cells are too narrow to read at a glance
     * or to hit reliably, and the fourth and fifth candidates are almost never the intended word.
     */
    fun setSuggestions(candidates: List<String>) {
        val requested = candidates.take(MAX_VISIBLE)
        if (words.size == requested.size && words.indices.all { words[it] == requested[it] }) return
        // A pending hold captured a slot, not a word, and re-reads the list when it fires. Left
        // armed across a swap — the decoder finishing mid-hold — it would teach or forget whatever
        // word has since landed in that slot.
        cancelPendingLongPress()
        words.clear()
        ellipsizedNormal.clear()
        ellipsizedBold.clear()
        requested.forEach(words::add)
        pressedIndex = -1
        settingsPressed = false
        micPressed = false
        refreshAccessibilityDescription()
        accessibilityHelper.invalidateRoot()
        invalidate()
    }

    fun setEmptyMessage(message: String) {
        if (emptyMessage == message) return
        emptyMessage = message
        refreshAccessibilityDescription()
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
        drawSettingsButton(canvas)
        drawMicButton(canvas)
        if (words.isEmpty()) {
            if (emptyMessage.isNotEmpty()) {
                textPaint.color = keyboardTheme.hintText
                textPaint.typeface = Typeface.DEFAULT
                textPaint.textSize = minOf(sp(12f), suggestionWidth() * 0.04f)
                textPaint.getFontMetrics(reusableMetrics)
                canvas.drawText(
                    emptyMessage,
                    suggestionLeft() + suggestionWidth() / 2f,
                    height / 2f - (reusableMetrics.ascent + reusableMetrics.descent) / 2f,
                    textPaint,
                )
            }
            return
        }

        textPaint.textSize = sp(16f)
        val cellWidth = suggestionWidth() / MAX_VISIBLE
        // Vertically centre on the text's own middle rather than its baseline.
        textPaint.getFontMetrics(reusableMetrics)
        val baseline = height / 2f - (reusableMetrics.ascent + reusableMetrics.descent) / 2f

        // Keep the best candidate in the centre. That stable target is easier to scan and matches
        // the placement people have learned from mature mobile keyboards.
        for (slot in 0 until MAX_VISIBLE) {
            val index = SuggestionPlacement.candidateAtSlot(words.size, slot) ?: continue
            val left = suggestionLeft() + slot * cellWidth

            if (index == pressedIndex) {
                pressedPaint.color = keyboardTheme.keyPressedOverlay
                val inset = dp(4f)
                canvas.drawRoundRect(
                    left + inset,
                    inset,
                    left + cellWidth - inset,
                    height - inset,
                    dp(12f),
                    dp(12f),
                    pressedPaint,
                )
            }

            // The top choice is the one that gets committed, so it is marked as such: the user
            // should be able to tell at a glance whether the keyboard already agrees with them.
            // Accent colour on top of weight, because bold alone disappears at 16sp in peripheral
            // vision while a colour change reads without focusing on the strip.
            textPaint.color =
                if (index == 0) keyboardTheme.suggestionHighlightText else keyboardTheme.suggestionText
            textPaint.typeface = if (index == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

            canvas.drawText(
                ellipsized(words[index], cellWidth - dp(12f), bold = index == 0),
                left + cellWidth / 2f,
                baseline,
                textPaint,
            )
        }
        val inset = dp(10f)
        dividerPaint.color = keyboardTheme.divider
        for (slot in 1 until MAX_VISIBLE) {
            val x = suggestionLeft() + slot * cellWidth
            canvas.drawLine(x, inset, x, height - inset, dividerPaint)
        }
    }

    /** A persistent, thumb-sized route to the app's settings and setup screen. */
    private fun drawSettingsButton(canvas: Canvas) {
        val centerX = settingsWidth() / 2f
        val centerY = height / 2f

        pressedPaint.color = keyboardTheme.specialKeyBackground
        canvas.drawCircle(centerX, centerY, height * 0.36f, pressedPaint)
        if (settingsPressed) {
            pressedPaint.color = keyboardTheme.keyPressedOverlay
            canvas.drawCircle(centerX, centerY, height * 0.4f, pressedPaint)
        }

        // A conventional gear is immediately recognisable as settings. The previous sliders
        // glyph was visually tidy but ambiguous enough to hide this feature in plain sight.
        iconPaint.color = keyboardTheme.suggestionText
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = dp(2.4f)
        iconPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawCircle(centerX, centerY, dp(5.6f), iconPaint)
        val toothInner = dp(7f)
        val toothOuter = dp(9.3f)
        repeat(8) { index ->
            val angle = Math.toRadians(index * 45.0)
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            canvas.drawLine(
                centerX + dx * toothInner,
                centerY + dy * toothInner,
                centerX + dx * toothOuter,
                centerY + dy * toothOuter,
                iconPaint,
            )
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
        if (!voiceEnabled) return
        val centerX = width - micWidth() / 2f
        val centerY = height / 2f

        // A stable circular control separates voice input from suggestions without the hard
        // vertical divider that made the glyph look bolted onto the edge of the strip.
        pressedPaint.color = keyboardTheme.specialKeyBackground
        canvas.drawCircle(centerX, centerY, height * 0.36f, pressedPaint)
        if (micPressed) {
            pressedPaint.color = keyboardTheme.keyPressedOverlay
            canvas.drawCircle(centerX, centerY, height * 0.40f, pressedPaint)
        }

        textPaint.color = keyboardTheme.suggestionText
        MicGlyph.draw(canvas, textPaint, centerX, centerY, height * 0.25f)
    }

    /** Width reserved for the microphone button at the right edge. */
    private fun micWidth(): Float = height.toFloat()

    private fun settingsWidth(): Float = height.toFloat()

    private fun suggestionLeft(): Float = SuggestionStripLayout.suggestionLeft(height.toFloat())

    private fun suggestionWidth(): Float =
        SuggestionStripLayout.suggestionWidth(width.toFloat(), height.toFloat(), voiceEnabled)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settingsPressed = isOverSettings(event.x)
                micPressed = !settingsPressed && isOverMic(event.x)
                pressedIndex = if (settingsPressed || micPressed) -1 else indexAt(event.x)
                longPressFired = false
                if (pressedIndex >= 0) scheduleLongPress(pressedIndex)
                invalidate()
                return settingsPressed || micPressed || pressedIndex >= 0
            }

            MotionEvent.ACTION_MOVE -> {
                // Sliding off cancels the press, matching how the keys behave.
                val inBounds = event.y in 0f..height.toFloat()
                val stillOnSettings = settingsPressed && isOverSettings(event.x) && inBounds
                val stillOnMic = micPressed && isOverMic(event.x) && inBounds
                val stillOnWord = pressedIndex >= 0 && indexAt(event.x) == pressedIndex && inBounds
                if (
                    settingsPressed != stillOnSettings ||
                    micPressed != stillOnMic ||
                    (pressedIndex >= 0 && !stillOnWord)
                ) {
                    settingsPressed = stillOnSettings
                    micPressed = stillOnMic
                    if (!stillOnWord) {
                        pressedIndex = -1
                        cancelPendingLongPress()
                    }
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                val index = pressedIndex
                val settings = settingsPressed
                val mic = micPressed
                val held = longPressFired
                pressedIndex = -1
                settingsPressed = false
                micPressed = false
                cancelPendingLongPress()
                invalidate()

                if (settings) {
                    announceForAccessibility("Keyboard settings")
                    listener?.onSettingsRequested()
                } else if (mic) {
                    announceForAccessibility("Voice typing")
                    listener?.onVoiceRequested()
                } else if (index in words.indices && !held) {
                    announceForAccessibility("Suggestion ${words[index]}")
                    listener?.onSuggestionPicked(index, words[index])
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                settingsPressed = false
                micPressed = false
                cancelPendingLongPress()
                invalidate()
            }
        }
        return true
    }

    private fun scheduleLongPress(index: Int) {
        cancelPendingLongPress()
        val runnable = Runnable {
            if (index !in words.indices) return@Runnable
            longPressFired = true
            pressedIndex = -1
            invalidate()
            listener?.onSuggestionHeld(index, words[index])
        }
        longPressRunnable = runnable
        handler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelPendingLongPress() {
        longPressRunnable?.let(handler::removeCallbacks)
        longPressRunnable = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelPendingLongPress()
    }

    private fun isOverMic(x: Float): Boolean = voiceEnabled && x >= width - micWidth()

    private fun isOverSettings(x: Float): Boolean = x >= 0f && x < settingsWidth()

    private fun indexAt(x: Float): Int {
        if (words.isEmpty()) return -1
        val slot = SuggestionStripLayout.slotAt(
            x = x,
            width = width.toFloat(),
            height = height.toFloat(),
            voiceEnabled = voiceEnabled,
            slotCount = MAX_VISIBLE,
        ) ?: return -1
        return SuggestionPlacement.candidateAtSlot(words.size, slot) ?: -1
    }

    /** Trims a word that will not fit its cell, so it degrades to "extraordi…" rather than clipping. */
    private fun ellipsize(word: String, available: Float): String {
        if (textPaint.measureText(word) <= available) return word
        var end = word.length
        while (end > 1 && textPaint.measureText(word.substring(0, end) + "…") > available) end--
        return word.substring(0, end) + "…"
    }

    /**
     * [ellipsize], memoized per word and paint weight.
     *
     * The measure-and-trim loop runs string concatenations and text measurement inside [onDraw],
     * which repaints on every press highlight; the same three words do not need re-trimming each
     * frame. Cleared when the words change and when the cell width does.
     */
    private fun ellipsized(word: String, available: Float, bold: Boolean): String {
        if (available != ellipsizedWidth) {
            ellipsizedNormal.clear()
            ellipsizedBold.clear()
            ellipsizedWidth = available
        }
        val cache = if (bold) ellipsizedBold else ellipsizedNormal
        return cache.getOrPut(word) { ellipsize(word, available) }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.view.View"
        info.isFocusable = true
        info.contentDescription = accessibilityDescription()
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        accessibilityHelper.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    private fun refreshAccessibilityDescription() {
        contentDescription = accessibilityDescription()
        accessibilityHelper.invalidateRoot()
    }

    private fun accessibilityDescription(): String = buildList {
        if (words.isNotEmpty()) add("Suggestions: ${words.joinToString(", ")}")
        else if (emptyMessage.isNotEmpty()) add(emptyMessage)
        add("Keyboard settings button at the left")
        if (voiceEnabled) add("Voice typing button at the right")
    }.joinToString(". ")

    private companion object {
        const val MAX_VISIBLE = 3
        const val HEIGHT_DP = 48f
        const val A11Y_WORD_BASE = 0
        const val A11Y_MIC = 100
        const val A11Y_SETTINGS = 101
    }
}

/** Pure toolbar geometry, separated from Android drawing so edge hit targets stay regression-tested. */
internal object SuggestionStripLayout {
    fun suggestionLeft(height: Float): Float = height.coerceAtLeast(0f)

    fun suggestionWidth(width: Float, height: Float, voiceEnabled: Boolean): Float =
        (width - height.coerceAtLeast(0f) * if (voiceEnabled) 2f else 1f).coerceAtLeast(0f)

    fun slotAt(
        x: Float,
        width: Float,
        height: Float,
        voiceEnabled: Boolean,
        slotCount: Int,
    ): Int? {
        if (slotCount <= 0) return null
        val left = suggestionLeft(height)
        val contentWidth = suggestionWidth(width, height, voiceEnabled)
        if (contentWidth <= 0f || x < left || x >= left + contentWidth) return null
        return ((x - left) / (contentWidth / slotCount)).toInt().coerceIn(0, slotCount - 1)
    }
}

/** Pure candidate-to-cell mapping, kept separate so the centre-first interaction is testable. */
internal object SuggestionPlacement {
    private val THREE = intArrayOf(1, 0, 2)
    private val TWO = intArrayOf(1, 0, NONE)
    private val ONE = intArrayOf(NONE, 0, NONE)

    fun candidateAtSlot(candidateCount: Int, slot: Int): Int? {
        if (slot !in 0..2) return null
        val candidate = when (candidateCount.coerceIn(0, 3)) {
            1 -> ONE[slot]
            2 -> TWO[slot]
            3 -> THREE[slot]
            else -> NONE
        }
        return candidate.takeIf { it != NONE }
    }

    fun slotForCandidate(candidateCount: Int, candidateIndex: Int): Int {
        for (slot in 0..2) {
            if (candidateAtSlot(candidateCount, slot) == candidateIndex) return slot
        }
        return 1
    }

    private const val NONE = -1
}
