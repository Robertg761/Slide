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
import com.slide.core.layout.Key
import com.slide.core.layout.KeyType
import com.slide.core.layout.KeyboardLayout
import com.slide.core.layout.Layouts
import com.slide.core.settings.KeyboardSettings
import com.slide.core.theme.KeyboardTheme
import com.slide.core.theme.Themes
import com.slide.engine.gesture.GestureKeyMap
import com.slide.engine.gesture.GesturePoint
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class ShiftState { OFF, SHIFTED, LOCKED }

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

        /** Fired when a key press resolves into input. */
        fun onKeyCommit(key: Key, text: String)

        /** Fired when a swipe completes. The decoder consumes this; see docs/technical-decisions.md. */
        fun onGestureComplete(points: List<GesturePoint>)
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
            field = value
            requestLayout()
            invalidate()
        }

    var shiftState: ShiftState = ShiftState.OFF
        set(value) {
            field = value
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
        var longPressFired: Boolean = false,
        var repeatFired: Boolean = false,
    )

    private val pointers = HashMap<Int, Pointer>()

    /** Pointer id that owns the current gesture, or null when not gesturing. */
    private var gesturePointerId: Int? = null
    private val gesturePoints = ArrayList<GesturePoint>()
    private var gestureStartTime = 0L

    private var longPressRunnable: Runnable? = null
    private var repeatRunnable: Runnable? = null

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
        val contentHeight = height - topPadding - bottomInset()
        placedKeys = KeyGeometry.place(
            layout = effective,
            width = width.toFloat(),
            contentHeight = contentHeight,
            topOffset = topPadding,
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed || placedKeys.isEmpty()) recomputeGeometry(width, height)
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
        labelPaint.textSize = if (key.type == KeyType.CHARACTER) sp(22f) else sp(15f)
        val metrics = labelPaint.fontMetrics
        val baseline = placed.centerY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(displayLabel(key), placed.centerX, baseline, labelPaint)

        val hint = key.hint
        if (hint != null && !settings.showNumberRow) {
            hintPaint.color = keyboardTheme.hintText
            canvas.drawText(hint, placed.right - keyGapH - dp(8f), placed.top + keyGapV + dp(13f), hintPaint)
        }
    }

    /** Larger geometric icons stay crisp and visually centred at every keyboard height. */
    private fun drawActionIcon(canvas: Canvas, type: KeyType, placed: PlacedKey) {
        val oldStyle = labelPaint.style
        val oldStroke = labelPaint.strokeWidth
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
            }
            KeyType.DELETE -> {
                val p = Path().apply { moveTo(x-r, y); lineTo(x-r*.42f, y-r*.62f); lineTo(x+r, y-r*.62f); lineTo(x+r, y+r*.62f); lineTo(x-r*.42f, y+r*.62f); close() }
                canvas.drawPath(p, labelPaint); canvas.drawLine(x-r*.02f, y-r*.25f, x+r*.45f, y+r*.25f, labelPaint); canvas.drawLine(x+r*.45f, y-r*.25f, x-r*.02f, y+r*.25f, labelPaint)
            }
            KeyType.ENTER -> { canvas.drawLine(x-r, y, x+r*.65f, y, labelPaint); canvas.drawLine(x-r, y, x-r*.38f, y-r*.38f, labelPaint); canvas.drawLine(x-r, y, x-r*.38f, y+r*.38f, labelPaint); canvas.drawLine(x+r*.65f, y, x+r*.65f, y-r*.68f, labelPaint) }
            KeyType.EMOJI -> { canvas.drawCircle(x, y, r*.78f, labelPaint); canvas.drawCircle(x-r*.28f, y-r*.16f, dp(1.3f), labelPaint); canvas.drawCircle(x+r*.28f, y-r*.16f, dp(1.3f), labelPaint); canvas.drawArc(x-r*.4f, y-r*.1f, x+r*.4f, y+r*.42f, 15f, 150f, false, labelPaint) }
            else -> Unit
        }
        labelPaint.style = oldStyle; labelPaint.strokeWidth = oldStroke
    }

    private fun drawSpaceLabel(canvas: Canvas, placed: PlacedKey) {
        labelPaint.color = keyboardTheme.hintText
        labelPaint.textSize = sp(12f)
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
        KeyType.CHARACTER, KeyType.SPACE -> keyboardTheme.keyBackground
        else -> keyboardTheme.specialKeyBackground
    }

    private fun textColorFor(key: Key): Int = when (key.type) {
        KeyType.ENTER -> keyboardTheme.accentText
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

    // region Touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                handlePointerDown(event.getPointerId(index), event.getX(index), event.getY(index))
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    handlePointerMove(event.getPointerId(index), event.getX(index), event.getY(index))
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
        pointers[pointerId] = Pointer(placed, x, y, System.currentTimeMillis())

        listener?.onKeyDown(placed.key)
        showPreviewFor(placed)
        scheduleLongPress(pointerId, placed)
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
                showPreviewFor(nowOver)
                invalidate()
            }
        }
    }

    private fun handlePointerUp(pointerId: Int, x: Float, y: Float) {
        val pointer = pointers.remove(pointerId) ?: return
        cancelPendingLongPress()
        cancelRepeat()
        previewPopup.dismiss()

        if (gesturePointerId == pointerId) {
            appendGesturePoint(x, y)
            finishGesture()
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

        // A press that already acted — an auto-repeat that fired, or an alternates popup dismissed
        // without a selection — must not commit the key a second time on release.
        if (!pointer.longPressFired && !pointer.repeatFired) {
            listener?.onKeyCommit(pointer.placed.key, outputFor(pointer.placed.key))
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

    private fun finishGesture() {
        val points = ArrayList(gesturePoints)
        abandonGesture()
        if (points.size >= MIN_GESTURE_POINTS) listener?.onGestureComplete(points)
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

    private companion object {
        const val TRAIL_POINTS = 48
        const val MIN_GESTURE_POINTS = 4
        const val GESTURE_SLOP_FACTOR = 1.4f
        // Gboard-like: the first repeat arrives quickly after the initial delete, then ramps.
        const val REPEAT_INITIAL_DELAY_MS = 280L
        const val REPEAT_MIN_DELAY_MS = 45L
        const val REPEAT_ACCELERATION = 0.85f
    }
}
