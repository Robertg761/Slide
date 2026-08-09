package com.slide.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import com.slide.core.layout.Key
import com.slide.core.layout.KeyType
import com.slide.core.layout.KeyboardLayout
import com.slide.core.layout.Layouts
import com.slide.core.settings.KeyboardSettings
import com.slide.core.theme.KeyboardTheme
import com.slide.core.theme.Themes
import com.slide.engine.gesture.GestureKeyMap
import com.slide.engine.gesture.GesturePoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

enum class ShiftState { OFF, SHIFTED, LOCKED }

enum class EnterAction { RETURN, GO, SEARCH, SEND, NEXT, DONE }

/**
 * The key grid.
 *
 * Drawn on a Canvas rather than composed, because a keyboard needs exact control over the touch
 * stream (multi-pointer rollover, slide-off, gesture capture) and a predictable frame budget.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        /** Fired the moment a key is touched — drives haptics and sound, not text. */
        fun onKeyDown(key: Key)

        /**
         * Fired when a key press resolves into input.
         *
         * [touchX] and [touchY] are where the finger first landed, in this view's pixels, or NaN
         * when the input did not come from a key press the user aimed — an auto-repeat, or a
         * character chosen from a long-press popup. The corrector uses it to price a mis-hit by
         * how close the finger came to the key it was reaching for, rather than by how far apart
         * two keys happen to be.
         */
        fun onKeyCommit(key: Key, text: String, touchX: Float = Float.NaN, touchY: Float = Float.NaN)

        /** Fired when a swipe completes. The decoder consumes this; see docs/technical-decisions.md. */
        fun onGestureComplete(points: List<GesturePoint>)

        /** Fired while the user slides horizontally across the space bar. */
        fun onCursorMove(steps: Int) = Unit

        /** Fired when the user swipes left from Backspace to delete a preceding word. */
        fun onDeleteWordGesture() = Unit

        /** Search-mode callbacks; default implementations preserve existing embedders. */
        fun onSearchQueryChanged(query: String) = Unit
        fun onSearchEmojiPicked(emoji: String) = Unit
        fun onSearchClosed() = Unit
    }

    var listener: Listener? = null

    var keyboardTheme: KeyboardTheme = Themes.Light
        set(value) {
            field = value
            invalidate()
        }

    var settings: KeyboardSettings = KeyboardSettings()
        set(value) {
            val heightChanged = field.keyHeightScale != value.keyHeightScale ||
                field.bottomPaddingDp != value.bottomPaddingDp ||
                field.showNumberRow != value.showNumberRow
            field = value
            if (heightChanged) requestLayout()
            invalidate()
        }

    var keyboardLayout: KeyboardLayout = Layouts.QwertyEn
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            // A layer switch normally keeps the same bounds. Recompute immediately as well as
            // requesting layout; otherwise the old layer's cells remain in the hit-test map until
            // some unrelated size change, which makes ?123 look like it did nothing.
            if (width > 0 && height > 0) recomputeGeometry(width, height)
            invalidate()
        }

    var shiftState: ShiftState = ShiftState.OFF
        set(value) {
            if (field == value) return
            field = value
            refreshAccessibilityDescription()
            invalidate()
        }

    var enterAction: EnterAction = EnterAction.RETURN
        set(value) {
            field = value
            refreshAccessibilityDescription()
            invalidate()
        }

    /** When true, the keyboard's top strip is an internal emoji search input. */
    var searchMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                shiftState = ShiftState.OFF
                keyboardLayout = Layouts.QwertyEn
            }
            requestLayout()
            if (width > 0 && height > 0) recomputeGeometry(width, height)
            refreshAccessibilityDescription()
            invalidate()
        }

    var searchQuery: String = ""
        set(value) {
            field = value
            refreshAccessibilityDescription()
            invalidate()
        }

    var searchResults: List<String> = emptyList()
        set(value) {
            field = value.take(MAX_SEARCH_RESULTS)
            refreshAccessibilityDescription()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val baseRowHeight = dp(52f)
    private val topPadding = dp(4f)
    private val keyGapH = dp(3f)
    private val keyGapV = dp(5f)
    private val cornerRadius = dp(8f)

    private var placedKeys: List<PlacedKey> = emptyList()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(10f)
    }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1f)
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val reusableRect = RectF()
    private val trailPath = Path()

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private val previewPopup by lazy { KeyPreviewPopup(this) }
    private val alternatesPopup by lazy { AlternatesPopup(this) }

    /** State for one active finger. */
    private class Pointer(
        var placed: PlacedKey,
        val downX: Float,
        val downY: Float,
        val downTime: Long,
        var lastX: Float = downX,
        var longPressFired: Boolean = false,
        var repeatFired: Boolean = false,
        /** Repeatable keys act on touch-down, so the release must not act a second time. */
        var committedOnDown: Boolean = false,
        var cursorMove: Boolean = false,
        var deleteWordGesture: Boolean = false,
    )

    private val pointers = HashMap<Int, Pointer>()

    /** Pointer id that owns the current gesture, or null when not gesturing. */
    private var gesturePointerId: Int? = null
    private val gesturePoints = ArrayList<GesturePoint>()
    private var gestureStartTime = 0L

    private var longPressRunnable: Runnable? = null
    private var repeatRunnable: Runnable? = null
    private var repeatPointerId: Int? = null
    private var searchPointerActive = false
    private var pressedSearchResult = -1
    private var pressedSearchClose = false
    private var cursorRemainderPx = 0f

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        refreshAccessibilityDescription()
    }

    // region Measurement and layout

    /**
     * User-configured breathing room below the keys.
     *
     * The navigation bar is not part of this. [KeyboardFrame] reserves that below the keyboard on
     * behalf of every panel that sits over it, so it is not this view's to account for.
     */
    private fun bottomInset(): Float = dp(settings.bottomPaddingDp)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val effective = Layouts.withNumberRow(keyboardLayout, settings.showNumberRow)
        val rowUnits = effective.rows.sumOf { it.heightWeight.toDouble() }.toFloat()
        val contentHeight = rowUnits * baseRowHeight * settings.keyHeightScale
        val height = topPadding + contentHeight + bottomInset()
        setMeasuredDimension(width, height.roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeGeometry(w, h)
    }

    private fun recomputeGeometry(width: Int, height: Int) {
        val effective = Layouts.withNumberRow(keyboardLayout, settings.showNumberRow)
        val header = if (searchMode) searchHeaderHeight() else 0f
        val contentHeight = (height - topPadding - bottomInset() - header).coerceAtLeast(1f)
        placedKeys = KeyGeometry.place(
            layout = effective,
            width = width.toFloat(),
            contentHeight = contentHeight,
            topOffset = topPadding + header,
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Layout changes and layer changes can both arrive with unchanged bounds. Always derive the
        // cells from the current layout so drawing and hit testing cannot drift apart.
        recomputeGeometry(width, height)
    }

    /**
     * The letter positions the decoder should score against, taken from the keys as actually
     * drawn rather than from an idealised grid.
     *
     * Returns null on layouts with too few letters to decode against, such as the symbols layer,
     * so a swipe there produces nothing instead of confident nonsense.
     */
    fun gestureKeyMap(): GestureKeyMap? {
        val letters = placedKeys.filter { placed ->
            placed.key.type == KeyType.CHARACTER &&
                placed.key.gestureEligible &&
                placed.key.outputText.length == 1 &&
                placed.key.outputText[0].lowercaseChar() in 'a'..'z'
        }
        if (letters.isEmpty()) return null

        // Letter keys are uniform within a row but the rows are staggered, so average rather than
        // trusting any one key to represent the whole keyboard.
        val keyWidth = letters.sumOf { it.width.toDouble() }.toFloat() / letters.size
        val keyHeight = letters.sumOf { it.height.toDouble() }.toFloat() / letters.size

        val builder = GestureKeyMap.Builder(keyWidth, keyHeight)
        letters.forEach { placed ->
            builder.put(placed.key.outputText[0], placed.centerX, placed.centerY)
        }
        return builder.buildOrNull()
    }

    // endregion

    // region Drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(keyboardTheme.background)

        if (searchMode) drawSearchHeader(canvas)

        placedKeys.forEach { placed ->
            drawKey(canvas, placed, pressed = pointers.values.any { it.placed === placed })
        }

        if (gesturePointerId != null) drawGestureTrail(canvas)
    }

    private fun drawKey(canvas: Canvas, placed: PlacedKey, pressed: Boolean) {
        val key = placed.key
        reusableRect.set(
            placed.left + keyGapH,
            placed.top + keyGapV,
            placed.right - keyGapH,
            placed.bottom - keyGapV,
        )

        fillPaint.color = backgroundColorFor(key)
        canvas.drawRoundRect(reusableRect, cornerRadius, cornerRadius, fillPaint)

        if (pressed) {
            fillPaint.color = keyboardTheme.keyPressedOverlay
            canvas.drawRoundRect(reusableRect, cornerRadius, cornerRadius, fillPaint)
        }

        if (settings.showKeyBorders) {
            borderPaint.color = keyboardTheme.keyBorder
            canvas.drawRoundRect(reusableRect, cornerRadius, cornerRadius, borderPaint)
        }

        if (key.type == KeyType.SPACE) {
            drawSpaceLabel(canvas, placed)
            return
        }

        if (key.type in setOf(KeyType.SHIFT, KeyType.DELETE, KeyType.ENTER, KeyType.EMOJI)) {
            drawActionIcon(canvas, key.type, placed)
            return
        }

        labelPaint.color = textColorFor(key)
        val maxText = min(placed.width * if (key.type == KeyType.CHARACTER) 0.65f else 0.8f, placed.height * 0.58f)
        labelPaint.textSize = min(if (key.type == KeyType.CHARACTER) sp(22f) else sp(15f), maxText)
        val metrics = labelPaint.fontMetrics
        val baseline = placed.centerY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(displayLabel(key), placed.centerX, baseline, labelPaint)

        val hint = key.hint
        if (hint != null && !settings.showNumberRow) {
            hintPaint.color = keyboardTheme.hintText
            hintPaint.textSize = min(sp(10f), placed.width * 0.24f)
            canvas.drawText(hint, placed.right - keyGapH - dp(8f), placed.top + keyGapV + dp(13f), hintPaint)
        }
    }

    /** Larger geometric icons stay crisp and visually centred at every keyboard height. */
    private fun drawActionIcon(canvas: Canvas, type: KeyType, placed: PlacedKey) {
        val oldStyle = labelPaint.style
        val oldStroke = labelPaint.strokeWidth
        val oldCap = labelPaint.strokeCap
        val oldJoin = labelPaint.strokeJoin
        labelPaint.color = textColorFor(placed.key)
        labelPaint.style = Paint.Style.STROKE
        labelPaint.strokeWidth = dp(2.1f)
        labelPaint.strokeCap = Paint.Cap.ROUND
        labelPaint.strokeJoin = Paint.Join.ROUND
        val r = minOf(placed.width, placed.height) * 0.29f
        val x = placed.centerX; val y = placed.centerY
        when (type) {
            KeyType.SHIFT -> {
                val p = Path().apply { moveTo(x, y-r); lineTo(x-r*.78f, y-r*.1f); lineTo(x-r*.38f, y-r*.1f); lineTo(x-r*.38f, y+r); lineTo(x+r*.38f, y+r); lineTo(x+r*.38f, y-r*.1f); lineTo(x+r*.78f, y-r*.1f); close() }
                canvas.drawPath(p, labelPaint)
                if (shiftState == ShiftState.LOCKED) canvas.drawCircle(x, y-r*.42f, dp(1.8f), labelPaint)
                if (shiftState == ShiftState.SHIFTED) canvas.drawLine(x-r*.35f, y+r*.72f, x+r*.35f, y+r*.72f, labelPaint)
            }
            KeyType.DELETE -> {
                val p = Path().apply { moveTo(x-r, y); lineTo(x-r*.42f, y-r*.62f); lineTo(x+r, y-r*.62f); lineTo(x+r, y+r*.62f); lineTo(x-r*.42f, y+r*.62f); close() }
                canvas.drawPath(p, labelPaint); canvas.drawLine(x-r*.02f, y-r*.25f, x+r*.45f, y+r*.25f, labelPaint); canvas.drawLine(x+r*.45f, y-r*.25f, x-r*.02f, y+r*.25f, labelPaint)
            }
            KeyType.ENTER -> drawEnterIcon(canvas, x, y, r)
            KeyType.EMOJI -> { canvas.drawCircle(x, y, r*.78f, labelPaint); canvas.drawCircle(x-r*.28f, y-r*.16f, dp(1.3f), labelPaint); canvas.drawCircle(x+r*.28f, y-r*.16f, dp(1.3f), labelPaint); canvas.drawArc(x-r*.4f, y-r*.1f, x+r*.4f, y+r*.42f, 15f, 150f, false, labelPaint) }
            else -> Unit
        }
        labelPaint.style = oldStyle
        labelPaint.strokeWidth = oldStroke
        labelPaint.strokeCap = oldCap
        labelPaint.strokeJoin = oldJoin
    }

    private fun drawEnterIcon(canvas: Canvas, x: Float, y: Float, r: Float) {
        when (enterAction) {
            EnterAction.RETURN -> {
                canvas.drawLine(x-r, y, x+r*.65f, y, labelPaint)
                canvas.drawLine(x-r, y, x-r*.38f, y-r*.38f, labelPaint)
                canvas.drawLine(x-r, y, x-r*.38f, y+r*.38f, labelPaint)
                canvas.drawLine(x+r*.65f, y, x+r*.65f, y-r*.68f, labelPaint)
            }
            EnterAction.SEARCH -> {
                canvas.drawCircle(x-r*.18f, y-r*.12f, r*.52f, labelPaint)
                canvas.drawLine(x+r*.2f, y+r*.26f, x+r*.72f, y+r*.78f, labelPaint)
            }
            EnterAction.SEND -> {
                val p = Path().apply {
                    moveTo(x-r*.85f, y-r*.72f); lineTo(x+r*.85f, y); lineTo(x-r*.85f, y+r*.72f)
                    lineTo(x-r*.38f, y); close()
                }
                canvas.drawPath(p, labelPaint)
            }
            EnterAction.GO, EnterAction.NEXT -> {
                canvas.drawLine(x-r*.8f, y, x+r*.62f, y, labelPaint)
                canvas.drawLine(x+r*.62f, y, x+r*.18f, y-r*.42f, labelPaint)
                canvas.drawLine(x+r*.62f, y, x+r*.18f, y+r*.42f, labelPaint)
            }
            EnterAction.DONE -> {
                canvas.drawLine(x-r*.75f, y, x-r*.15f, y+r*.5f, labelPaint)
                canvas.drawLine(x-r*.15f, y+r*.5f, x+r*.82f, y-r*.58f, labelPaint)
            }
        }
    }

    private fun drawSpaceLabel(canvas: Canvas, placed: PlacedKey) {
        labelPaint.color = keyboardTheme.hintText
        labelPaint.textSize = min(sp(12f), placed.width * 0.28f)
        val metrics = labelPaint.fontMetrics
        val baseline = placed.centerY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(keyboardLayout.label, placed.centerX, baseline, labelPaint)
    }

    private fun drawGestureTrail(canvas: Canvas) {
        if (gesturePoints.size < 2) return

        // Only the recent tail is drawn, and it tapers, so the trail reads as motion rather than
        // as a static scribble. The full path is still retained for decoding.
        val visible = gesturePoints.takeLast(TRAIL_POINTS)
        trailPaint.color = keyboardTheme.gestureTrail

        for (i in 1 until visible.size) {
            val progress = i.toFloat() / visible.size
            trailPath.reset()
            trailPath.moveTo(visible[i - 1].x, visible[i - 1].y)
            trailPath.lineTo(visible[i].x, visible[i].y)
            trailPaint.alpha = (progress * 220f).toInt().coerceIn(0, 255)
            trailPaint.strokeWidth = dp(2f) + dp(4f) * progress
            canvas.drawPath(trailPath, trailPaint)
        }
    }

    private fun backgroundColorFor(key: Key): Int = when (key.type) {
        KeyType.ENTER -> keyboardTheme.accentBackground
        KeyType.SHIFT -> if (shiftState != ShiftState.OFF) keyboardTheme.accentBackground else keyboardTheme.specialKeyBackground
        KeyType.CHARACTER, KeyType.SPACE -> keyboardTheme.keyBackground
        else -> keyboardTheme.specialKeyBackground
    }

    private fun textColorFor(key: Key): Int = when (key.type) {
        KeyType.ENTER -> keyboardTheme.accentText
        KeyType.SHIFT -> if (shiftState != ShiftState.OFF) keyboardTheme.accentText else keyboardTheme.specialKeyText
        KeyType.CHARACTER, KeyType.SPACE -> keyboardTheme.keyText
        else -> keyboardTheme.specialKeyText
    }

    private fun displayLabel(key: Key): String = when (key.type) {
        KeyType.SHIFT -> when (shiftState) {
            ShiftState.OFF -> "⇧"
            ShiftState.SHIFTED -> "⬆"
            ShiftState.LOCKED -> "⇪"
        }
        KeyType.CHARACTER -> if (shiftState != ShiftState.OFF) key.label.uppercase() else key.label
        else -> key.label
    }

    /** The text a key commits, accounting for shift. */
    private fun outputFor(key: Key): String =
        if (key.type == KeyType.CHARACTER && shiftState != ShiftState.OFF) {
            key.outputText.uppercase()
        } else {
            key.outputText
        }

    // endregion

    // region Emoji search

    private fun searchHeaderHeight(): Float = dp(68f)

    private fun drawSearchHeader(canvas: Canvas) {
        val header = searchHeaderHeight()
        fillPaint.color = keyboardTheme.specialKeyBackground
        canvas.drawRect(0f, 0f, width.toFloat(), header, fillPaint)

        labelPaint.color = if (searchQuery.isEmpty()) keyboardTheme.hintText else keyboardTheme.specialKeyText
        labelPaint.textSize = min(sp(16f), width * 0.055f)
        labelPaint.typeface = android.graphics.Typeface.DEFAULT
        labelPaint.textAlign = Paint.Align.LEFT
        val query = if (searchQuery.isEmpty()) "Search emoji" else searchQuery
        canvas.drawText(query, dp(16f), dp(24f), labelPaint)

        val closeSize = dp(48f)
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = keyboardTheme.specialKeyText
        labelPaint.textSize = sp(25f)
        canvas.drawText("×", width - closeSize / 2f, dp(25f), labelPaint)

        linePaint.color = keyboardTheme.divider
        linePaint.strokeWidth = dp(1f)
        canvas.drawLine(0f, header - dp(1f), width.toFloat(), header - dp(1f), linePaint)

        val results = searchResults.take(MAX_SEARCH_RESULTS)
        if (results.isEmpty()) {
            labelPaint.color = keyboardTheme.hintText
            labelPaint.textSize = min(sp(12f), width * 0.04f)
            canvas.drawText(
                if (searchQuery.isBlank()) "Type a word to search" else "No matching emoji",
                width / 2f,
                header - dp(18f),
                labelPaint,
            )
            return
        }

        emojiPaint.textSize = min(sp(25f), dp(34f))
        val rowTop = header - dp(42f)
        val cellWidth = width / MAX_SEARCH_RESULTS.toFloat()
        results.forEachIndexed { index, emoji ->
            if (index == pressedSearchResult) {
                fillPaint.color = keyboardTheme.keyPressedOverlay
                canvas.drawRoundRect(
                    index * cellWidth + dp(2f), rowTop + dp(2f),
                    (index + 1) * cellWidth - dp(2f), header - dp(2f),
                    dp(8f), dp(8f), fillPaint,
                )
            }
            val metrics = emojiPaint.fontMetrics
            canvas.drawText(emoji, index * cellWidth + cellWidth / 2f, rowTop + dp(21f) - (metrics.ascent + metrics.descent) / 2f, emojiPaint)
        }
    }

    private fun searchResultAt(x: Float, y: Float): Int {
        val header = searchHeaderHeight()
        if (y < header - dp(44f) || y >= header) return -1
        val index = (x / (width / MAX_SEARCH_RESULTS.toFloat())).toInt()
        return index.takeIf { it in searchResults.indices } ?: -1
    }

    private fun searchCloseAt(x: Float, y: Float): Boolean =
        y in 0f..dp(48f) && x >= width - dp(48f)

    // endregion

    // region Touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (searchMode && (searchPointerActive || event.actionMasked == MotionEvent.ACTION_DOWN && event.y < searchHeaderHeight())) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    searchPointerActive = true
                    pressedSearchClose = searchCloseAt(event.x, event.y)
                    pressedSearchResult = if (pressedSearchClose) -1 else searchResultAt(event.x, event.y)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pressedSearchClose && !searchCloseAt(event.x, event.y)) pressedSearchClose = false
                    val next = if (pressedSearchClose) -1 else searchResultAt(event.x, event.y)
                    if (next != pressedSearchResult) pressedSearchResult = next
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val close = pressedSearchClose
                    val result = pressedSearchResult
                    searchPointerActive = false
                    pressedSearchClose = false
                    pressedSearchResult = -1
                    invalidate()
                    if (close) listener?.onSearchClosed()
                    else if (result in searchResults.indices) listener?.onSearchEmojiPicked(searchResults[result])
                }
                MotionEvent.ACTION_CANCEL -> {
                    searchPointerActive = false
                    pressedSearchClose = false
                    pressedSearchResult = -1
                    invalidate()
                }
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                handlePointerDown(event.getPointerId(index), event.getX(index), event.getY(index))
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(index)
                    // Android batches the samples it took between frames into one MOVE event and
                    // exposes all but the newest through the history. Reading only the newest
                    // throws away most of a fast swipe: what reaches the decoder is then a coarse
                    // polyline whose straight segments cut corners the finger actually rounded,
                    // which both prunes away the intended word and flatters its neighbours.
                    // Replayed for a pointer that could still become a gesture as well as one that
                    // already is, so the run-up between touch-down and the slop threshold is not
                    // the one stretch of the swipe that gets thrown away.
                    if (gesturePointerId == pointerId || (settings.gestureTypingEnabled && pointers.size == 1)) {
                        for (h in 0 until event.historySize) {
                            handlePointerMove(
                                pointerId,
                                event.getHistoricalX(index, h),
                                event.getHistoricalY(index, h),
                            )
                        }
                    }
                    handlePointerMove(pointerId, event.getX(index), event.getY(index))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                handlePointerUp(event.getPointerId(index), event.getX(index), event.getY(index))
            }

            MotionEvent.ACTION_CANCEL -> cancelAllPointers()
        }
        return true
    }

    private fun handlePointerDown(pointerId: Int, x: Float, y: Float) {
        val placed = KeyGeometry.hitTest(placedKeys, x, y) ?: return
        val pointer = Pointer(placed, x, y, System.currentTimeMillis())
        pointers[pointerId] = pointer
        cursorRemainderPx = 0f

        listener?.onKeyDown(placed.key)
        showPreviewFor(placed)
        scheduleLongPress(pointerId, placed)

        // A repeatable key — backspace — acts the moment it is touched rather than on release.
        // Waiting for the lift puts the whole press duration between the tap and the character
        // disappearing, which is what makes deleting feel sluggish however fast the repeat is.
        if (placed.key.repeatable) {
            pointer.committedOnDown = true
            listener?.onKeyCommit(placed.key, placed.key.outputText)
        }
        scheduleRepeat(pointerId, placed)
        invalidate()
    }

    private fun handlePointerMove(pointerId: Int, x: Float, y: Float) {
        val pointer = pointers[pointerId] ?: return

        if (gesturePointerId == pointerId) {
            appendGesturePoint(x, y)
            invalidate()
            return
        }

        val travelled = hypot(x - pointer.downX, y - pointer.downY)

        if (!searchMode && pointer.placed.key.type == KeyType.SPACE && gesturePointerId == null && pointers.size == 1) {
            val dx = x - pointer.downX
            val dy = y - pointer.downY
            if (!pointer.cursorMove && abs(dx) > touchSlop * GESTURE_SLOP_FACTOR && abs(dx) > abs(dy) * 1.15f) {
                pointer.cursorMove = true
                cancelPendingLongPress()
                cancelRepeat(pointerId)
                previewPopup.dismiss()
                pointer.lastX = x
            }
            if (pointer.cursorMove) {
                cursorRemainderPx += x - pointer.lastX
                pointer.lastX = x
                val stepPx = dp(16f)
                val steps = (cursorRemainderPx / stepPx).toInt()
                if (steps != 0) {
                    listener?.onCursorMove(steps)
                    cursorRemainderPx -= steps * stepPx
                }
                invalidate()
                return
            }
        }

        if (!searchMode && pointer.placed.key.type == KeyType.DELETE && gesturePointerId == null && pointers.size == 1) {
            val dx = x - pointer.downX
            val dy = y - pointer.downY
            if (!pointer.deleteWordGesture && dx < -touchSlop * GESTURE_SLOP_FACTOR && abs(dx) > abs(dy) * 1.15f) {
                pointer.deleteWordGesture = true
                cancelPendingLongPress()
                cancelRepeat(pointerId)
                previewPopup.dismiss()
            }
            if (pointer.deleteWordGesture) {
                invalidate()
                return
            }
        }

        // A swipe that starts on a letter key becomes a gesture; anything else stays a key press.
        if (settings.gestureTypingEnabled &&
            gesturePointerId == null &&
            pointers.size == 1 &&
            !pointer.longPressFired &&
            !alternatesPopup.isShowing &&
            pointer.placed.key.gestureEligible &&
            travelled > touchSlop * GESTURE_SLOP_FACTOR
        ) {
            beginGesture(pointerId, pointer)
            appendGesturePoint(x, y)
            invalidate()
            return
        }

        if (alternatesPopup.isShowing && pointer.longPressFired) {
            alternatesPopup.updateSelection(x, y)
            return
        }

        // Slide-off: the finger moved to a different key before lifting.
        if (travelled > touchSlop) {
            val nowOver = KeyGeometry.hitTest(placedKeys, x, y)
            if (nowOver != null && nowOver !== pointer.placed) {
                cancelPendingLongPress()
                cancelRepeat()
                pointer.placed = nowOver
                // Sliding off is how a mis-hit gets corrected, so the key landed on still has to
                // commit on release even though the key left behind already acted on touch-down.
                pointer.committedOnDown = false
                showPreviewFor(nowOver)
                invalidate()
            }
        }
    }

    private fun handlePointerUp(pointerId: Int, x: Float, y: Float) {
        val pointer = pointers.remove(pointerId) ?: return
        cancelPendingLongPress()
        cancelRepeat(pointerId)
        previewPopup.dismiss()

        if (pointer.cursorMove) {
            cursorRemainderPx = 0f
            invalidate()
            return
        }
        if (pointer.deleteWordGesture) {
            listener?.onDeleteWordGesture()
            invalidate()
            return
        }

        if (gesturePointerId == pointerId) {
            appendGesturePoint(x, y)
            finishGesture(pointer)
            invalidate()
            return
        }

        if (alternatesPopup.isShowing) {
            val selection = alternatesPopup.selected
            alternatesPopup.dismiss()
            if (selection != null) {
                listener?.onKeyCommit(pointer.placed.key, selection)
                invalidate()
                return
            }
        }

        // A press that already acted — a repeatable key handled on touch-down, an auto-repeat that
        // fired, or an alternates popup dismissed without a selection — must not commit the key a
        // second time on release.
        if (!pointer.longPressFired && !pointer.repeatFired && !pointer.committedOnDown) {
            announceForAccessibility(accessibilityLabel(pointer.placed.key))
            listener?.onKeyCommit(
                pointer.placed.key,
                outputFor(pointer.placed.key),
                pointer.downX,
                pointer.downY,
            )
        }
        invalidate()
    }

    private fun cancelAllPointers() {
        pointers.clear()
        cancelPendingLongPress()
        cancelRepeat()
        previewPopup.dismiss()
        alternatesPopup.dismiss()
        abandonGesture()
        invalidate()
    }

    private fun showPreviewFor(placed: PlacedKey) {
        if (!settings.showKeyPreview) return
        if (placed.key.type != KeyType.CHARACTER) {
            previewPopup.dismiss()
            return
        }
        previewPopup.show(keyboardTheme, displayLabel(placed.key), placed)
    }

    // endregion

    // region Long press and repeat

    private fun scheduleLongPress(pointerId: Int, placed: PlacedKey) {
        if (placed.key.alternates.isEmpty()) return
        cancelPendingLongPress()
        val runnable = Runnable {
            val pointer = pointers[pointerId] ?: return@Runnable
            pointer.longPressFired = true
            previewPopup.dismiss()
            val alternates = alternatesFor(placed.key)
            alternatesPopup.show(keyboardTheme, alternates, placed)
        }
        longPressRunnable = runnable
        handler.postDelayed(runnable, longPressTimeout)
    }

    /** Alternates always include the base character, so the popup can be dismissed by re-selecting it. */
    private fun alternatesFor(key: Key): List<String> {
        val base = if (shiftState != ShiftState.OFF) key.label.uppercase() else key.label
        val rest = key.alternates.map { if (shiftState != ShiftState.OFF) it.uppercase() else it }
        return (listOf(base) + rest).distinct()
    }

    private fun cancelPendingLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun scheduleRepeat(pointerId: Int, placed: PlacedKey) {
        if (!placed.key.repeatable) return
        cancelRepeat()
        repeatPointerId = pointerId
        var delay = REPEAT_INITIAL_DELAY_MS
        val runnable = object : Runnable {
            override fun run() {
                pointers[pointerId]?.repeatFired = true
                listener?.onKeyCommit(placed.key, placed.key.outputText)
                delay = (delay * REPEAT_ACCELERATION).toLong().coerceAtLeast(REPEAT_MIN_DELAY_MS)
                handler.postDelayed(this, delay)
            }
        }
        repeatRunnable = runnable
        handler.postDelayed(runnable, REPEAT_INITIAL_DELAY_MS)
    }

    private fun cancelRepeat() {
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
        repeatPointerId = null
    }

    private fun cancelRepeat(pointerId: Int) {
        if (repeatPointerId == pointerId) cancelRepeat()
    }

    // endregion

    // region Gesture

    private fun beginGesture(pointerId: Int, pointer: Pointer) {
        cancelPendingLongPress()
        cancelRepeat()
        previewPopup.dismiss()
        gesturePointerId = pointerId
        gestureStartTime = System.currentTimeMillis()
        gesturePoints.clear()
        gesturePoints += GesturePoint(pointer.downX, pointer.downY, 0L)
    }

    private fun appendGesturePoint(x: Float, y: Float) {
        gesturePoints += GesturePoint(x, y, System.currentTimeMillis() - gestureStartTime)
    }

    /**
     * Ends a swipe, or falls back to the key it started on when there is nothing to decode.
     *
     * A path this short is a press that wandered — the finger crossed the slop threshold and lifted
     * again. Treating it as a failed gesture discards it, which silently eats a keystroke the user
     * did make; the thresholds here mirror the decoder's own so that anything it would refuse
     * becomes a keypress rather than nothing at all.
     */
    private fun finishGesture(pointer: Pointer) {
        val points = ArrayList(gesturePoints)
        abandonGesture()

        val decodable = points.size >= MIN_GESTURE_POINTS &&
            pathLength(points) >= pointer.placed.width * MIN_GESTURE_PATH_FACTOR
        if (decodable) {
            listener?.onGestureComplete(points)
        } else {
            listener?.onKeyCommit(
                pointer.placed.key,
                outputFor(pointer.placed.key),
                pointer.downX,
                pointer.downY,
            )
        }
    }

    private fun pathLength(points: List<GesturePoint>): Float {
        var total = 0f
        for (i in 1 until points.size) {
            total += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        return total
    }

    private fun abandonGesture() {
        gesturePointerId = null
        gesturePoints.clear()
    }

    // endregion

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAllPointers()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.inputmethodservice.KeyboardView"
        info.isFocusable = true
        info.contentDescription = accessibilityDescription()
    }

    private fun refreshAccessibilityDescription() {
        contentDescription = accessibilityDescription()
    }

    private fun accessibilityDescription(): String {
        val layer = if (searchMode) "emoji search" else keyboardLayout.label
        val shift = when (shiftState) {
            ShiftState.OFF -> "shift off"
            ShiftState.SHIFTED -> "shift on"
            ShiftState.LOCKED -> "caps lock on"
        }
        return if (searchMode) {
            "Slide keyboard, emoji search. ${if (searchQuery.isEmpty()) "Search field empty" else "Search: $searchQuery"}. " +
                "${searchResults.size} results. Swipe across the keys to enter a search term."
        } else {
            "Slide keyboard, $layer, $shift. Double tap Shift for caps lock. Swipe Space to move the cursor."
        }
    }

    private fun accessibilityLabel(key: Key): String = when (key.type) {
        KeyType.CHARACTER -> displayLabel(key)
        KeyType.SHIFT -> when (shiftState) {
            ShiftState.OFF -> "Shift off"
            ShiftState.SHIFTED -> "Shift on"
            ShiftState.LOCKED -> "Caps lock on"
        }
        KeyType.DELETE -> "Backspace"
        KeyType.SPACE -> "Space"
        KeyType.ENTER -> enterAction.name.lowercase().replaceFirstChar(Char::uppercaseChar)
        KeyType.SYMBOLS -> "Symbols"
        KeyType.SYMBOLS_ALT -> "More symbols"
        KeyType.ALPHA -> "Letters"
        KeyType.EMOJI -> "Emoji"
        KeyType.MIC -> "Voice typing"
        KeyType.SETTINGS -> "Settings"
        KeyType.GLOBE -> "Input method switcher"
    }

    private companion object {
        const val TRAIL_POINTS = 48

        /**
         * The least a swipe must be to be worth decoding, mirroring `DecoderConfig`'s own floors.
         *
         * Anything below either of these is refused by the decoder anyway, and a swipe the decoder
         * refuses commits nothing at all — so the view has to recognise the same cases and treat
         * them as the keypress they really were.
         */
        const val MIN_GESTURE_POINTS = 6
        const val MIN_GESTURE_PATH_FACTOR = 0.9f
        const val GESTURE_SLOP_FACTOR = 1.4f
        const val MAX_SEARCH_RESULTS = 6
        /**
         * Auto-repeat pacing for backspace.
         *
         * The first delete has already happened on touch-down, so this is the pause before the key
         * starts running, and it has to be long enough that an ordinary tap never repeats. From
         * there it ramps hard: holding backspace is nearly always an intent to clear a whole
         * phrase, and the old ramp needed a dozen deletes and over a second and a half to reach
         * full speed, which is most of a short sentence spent waiting.
         */
        const val REPEAT_INITIAL_DELAY_MS = 300L
        const val REPEAT_MIN_DELAY_MS = 25L
        const val REPEAT_ACCELERATION = 0.66f
    }
}
