package com.slide.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AnimationUtils
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
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

enum class EnterAction { RETURN, GO, SEARCH, SEND, PREVIOUS, NEXT, DONE }

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

        /** Hands a deliberate swipe to the decoder; full-length misses intentionally type nothing. */
        fun onGestureComplete(points: List<GesturePoint>)

        /** A throttled partial trace, used to preview the likely word before finger-up. */
        fun onGesturePreview(points: List<GesturePoint>) = Unit

        /** Invalidates preview work; a normal lift can keep its latest candidates until final. */
        fun onGesturePreviewCancelled(clearCandidates: Boolean = true) = Unit

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

    /** Effective availability, after settings, editor policy, layout and decoder readiness. */
    var gestureTypingAvailable: Boolean = false

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
            completedGesturePoints.clear()
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
            clearSearchPress()
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
    private val keyGapV = dp(3.5f)
    private val cornerRadius = dp(12f)
    private val keyShadowOffset = dp(1f)

    /** Medium weight keeps 22sp letters crisp where regular reads spindly on a bare keycap. */
    private val keyLabelTypeface: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

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

    /** Reused across draws; `Paint.fontMetrics` allocates a fresh object per read. */
    private val reusableFontMetrics = Paint.FontMetrics()

    /**
     * Shifted key labels, memoized per label.
     *
     * [displayLabel] runs once per letter key per frame; calling `uppercase()` there allocates
     * ~30 strings every frame the keyboard draws shifted. The alphabet is small and stable, so
     * the map stays tiny and survives layout changes.
     */
    private val uppercaseLabels = HashMap<String, String>()
    private val trailPath = Path()
    private val iconPath = Path()

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private val previewPopup by lazy { KeyPreviewPopup(this) }
    private val alternatesPopup by lazy { AlternatesPopup(this) }

    /** State for one active finger. */
    private class Pointer(
        var placed: PlacedKey,
        val initialPlaced: PlacedKey,
        val downX: Float,
        val downY: Float,
        val downTime: Long,
        var lastX: Float = downX,
        var longPressFired: Boolean = false,
        var repeatFired: Boolean = false,
        var cursorMove: Boolean = false,
        var deleteWordGesture: Boolean = false,
        var slidOff: Boolean = false,
        var cancelled: Boolean = false,
    )

    private val pointers = HashMap<Int, Pointer>()

    /**
     * Keys whose pressed overlay is still fading out, by release time.
     *
     * Keyed by the layout's [Key] rather than the placed cell because geometry is recomputed on
     * layout passes, and a fade that survives one must follow the key, not a stale rectangle.
     */
    private val fadingPresses = HashMap<Key, Long>()

    /** Pointer id that owns the current gesture, or null when not gesturing. */
    private var gesturePointerId: Int? = null
    private val gesturePoints = ArrayList<GesturePoint>()
    private val completedGesturePoints = ArrayList<GesturePoint>(TRAIL_POINTS)

    /**
     * Length of [gesturePoints] as a running sum, accumulated as points arrive.
     *
     * The preview gate needs the path length every tick and the finish path needs it once more;
     * re-walking the whole polyline each time made those checks O(points) on a buffer that grows
     * for the entire swipe.
     */
    private var gesturePathLength = 0f
    private var completedGestureTime = 0L
    private var gestureStartTime = 0L
    private var lastGesturePreviewTime = 0L

    /**
     * Pending long presses, keyed by the finger that armed them.
     *
     * A single slot for the whole view meant any other pointer's press or lift disarmed a
     * pending accent popup — on a two-thumb layout, that is most of them. Scoped like the
     * repeat runnable, which already tracks its owner.
     */
    private val longPressRunnables = HashMap<Int, Runnable>()

    /** Finger that opened the alternates popup; only it may commit or dismiss the selection. */
    private var alternatesPointerId: Int? = null

    /** Finger whose key the single shared preview window is currently showing. */
    private var previewPointerId: Int? = null

    private var repeatRunnable: Runnable? = null
    private var repeatPointerId: Int? = null

    /** Finger that owns the emoji-search header, or null while no finger is in it. */
    private var searchPointerId: Int? = null
    private var pressedSearchResult = -1
    private var pressedSearchClose = false
    private var cursorRemainderPx = 0f

    private val accessibilityHelper = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (searchMode && y < searchHeaderHeight()) {
                if (searchCloseAt(x, y)) return A11Y_SEARCH_CLOSE
                val result = searchResultAt(x, y)
                return if (result >= 0) A11Y_SEARCH_RESULT_BASE + result else INVALID_ID
            }
            val index = placedKeys.indexOfFirst { it.contains(x, y) }
            return if (index >= 0) A11Y_KEY_BASE + index else INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            placedKeys.indices.forEach { virtualViewIds += A11Y_KEY_BASE + it }
            if (searchMode) {
                virtualViewIds += A11Y_SEARCH_CLOSE
                searchResults.indices.forEach { virtualViewIds += A11Y_SEARCH_RESULT_BASE + it }
            }
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat,
        ) {
            node.className = "android.widget.Button"
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            when {
                virtualViewId == A11Y_SEARCH_CLOSE -> {
                    node.contentDescription = "Close emoji search"
                    node.setBoundsInParent(
                        Rect(width - dp(48f).toInt(), 0, width, dp(48f).toInt()),
                    )
                }
                virtualViewId >= A11Y_SEARCH_RESULT_BASE -> {
                    val index = virtualViewId - A11Y_SEARCH_RESULT_BASE
                    val emoji = searchResults.getOrNull(index).orEmpty()
                    node.contentDescription = "Emoji $emoji"
                    val cellWidth = width / MAX_SEARCH_RESULTS.toFloat()
                    node.setBoundsInParent(
                        Rect(
                            (index * cellWidth).toInt(),
                            dp(48f).toInt(),
                            ((index + 1) * cellWidth).toInt(),
                            searchHeaderHeight().toInt(),
                        ),
                    )
                }
                else -> {
                    val index = virtualViewId - A11Y_KEY_BASE
                    val placed = placedKeys.getOrNull(index)
                    if (placed == null) {
                        node.contentDescription = "Unavailable key"
                        node.setBoundsInParent(Rect(0, 0, 1, 1))
                        return
                    }
                    node.contentDescription = accessibilityLabel(placed.key)
                    node.setBoundsInParent(
                        Rect(placed.left.toInt(), placed.top.toInt(), placed.right.toInt(), placed.bottom.toInt()),
                    )
                    alternatesFor(placed.key).drop(1)
                        .take(AlternateAccessibilityActions.size)
                        .forEachIndexed { alternateIndex, alternate ->
                        val actionId = AlternateAccessibilityActions.idAt(alternateIndex) ?: return@forEachIndexed
                        node.addAction(
                            AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                                actionId,
                                "Type $alternate",
                            ),
                        )
                    }
                    if (placed.key.type == KeyType.SHIFT) {
                        node.isCheckable = true
                        node.isChecked = shiftState != ShiftState.OFF
                    }
                }
            }
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?,
        ): Boolean {
            val alternateIndex = AlternateAccessibilityActions.indexOf(action)
            if (alternateIndex >= 0) {
                val placed = placedKeys.getOrNull(virtualViewId - A11Y_KEY_BASE) ?: return false
                val alternate = alternatesFor(placed.key)
                    .drop(1)
                    .getOrNull(alternateIndex) ?: return false
                listener?.onKeyDown(placed.key)
                listener?.onKeyCommit(placed.key, alternate)
                return true
            }
            if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
            when {
                virtualViewId == A11Y_SEARCH_CLOSE -> listener?.onSearchClosed()
                virtualViewId >= A11Y_SEARCH_RESULT_BASE -> {
                    val index = virtualViewId - A11Y_SEARCH_RESULT_BASE
                    val emoji = searchResults.getOrNull(index) ?: return false
                    listener?.onSearchEmojiPicked(emoji)
                }
                else -> {
                    val placed = placedKeys.getOrNull(virtualViewId - A11Y_KEY_BASE) ?: return false
                    listener?.onKeyDown(placed.key)
                    listener?.onKeyCommit(placed.key, outputFor(placed.key))
                }
            }
            return true
        }
    }

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
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
        val header = if (searchMode) searchHeaderHeight() else 0f
        val height = topPadding + header + contentHeight + bottomInset()
        setMeasuredDimension(width, height.roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeGeometry(w, h)
        accessibilityHelper.invalidateRoot()
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
        updateSystemGestureExclusion(width)
        accessibilityHelper.invalidateRoot()
    }

    /**
     * Keeps swipes that start on the outermost letter columns out of Android's back gesture.
     *
     * With gesture navigation, a finger landing on `q` or `p` and dragging inward sits inside the
     * system's edge-trigger zone, and without an exclusion request the launch of every corner
     * swipe is stolen by back navigation before this view ever sees it. Only the letter rows are
     * excluded — they are where swipe typing starts, and they fit well inside the system's 200 dp
     * per-edge budget — so the footer keeps its default behaviour.
     */
    private fun updateSystemGestureExclusion(viewWidth: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val letters = placedKeys.filter { placed ->
            placed.key.gestureEligible &&
                placed.key.type == KeyType.CHARACTER &&
                placed.key.outputText.length == 1 &&
                placed.key.outputText[0].lowercaseChar() in 'a'..'z'
        }
        if (letters.isEmpty() || viewWidth <= 0) {
            setSystemGestureExclusionRects(emptyList())
            return
        }
        val top = letters.minOf { it.top }.toInt()
        val bottom = letters.maxOf { it.bottom }.toInt()
        // Cover each edge column outright rather than a thin strip: the trigger zone is measured
        // from the physical edge, and the finger lands on the key itself.
        val reach = (viewWidth * GESTURE_EXCLUSION_WIDTH_FACTOR).toInt().coerceAtLeast(1)
        val rects = listOf(
            Rect(0, top, reach, bottom),
            Rect(viewWidth - reach, top, viewWidth, bottom),
        )
        setSystemGestureExclusionRects(rects)
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

        val now = AnimationUtils.currentAnimationTimeMillis()
        fadingPresses.values.removeAll { now - it >= PRESS_FADE_MS }

        placedKeys.forEach { placed ->
            val held = pointers.any { (pointerId, pointer) ->
                KeyPressRouting.isVisuallyHeld(
                    isGesturePointer = pointerId == gesturePointerId,
                    cancelled = pointer.cancelled,
                    isPlacedKey = pointer.placed === placed,
                )
            }
            val overlay = if (held) {
                1f
            } else {
                fadingPresses[placed.key]?.let { released ->
                    1f - (now - released) / PRESS_FADE_MS.toFloat()
                } ?: 0f
            }
            drawKey(canvas, placed, overlay)
        }
        if (fadingPresses.isNotEmpty()) postInvalidateOnAnimation()

        if (gesturePointerId != null) {
            drawGestureTrail(canvas, gesturePoints, alpha = 215)
        } else if (completedGesturePoints.isNotEmpty()) {
            val elapsed = SystemClock.uptimeMillis() - completedGestureTime
            if (elapsed < COMPLETED_TRAIL_FADE_MS) {
                val progress = elapsed.toFloat() / COMPLETED_TRAIL_FADE_MS
                drawGestureTrail(
                    canvas,
                    completedGesturePoints,
                    alpha = (215f * (1f - progress)).toInt(),
                )
                postInvalidateOnAnimation()
            } else {
                completedGesturePoints.clear()
            }
        }
    }

    /** Marks a key as just released, so its pressed overlay decays instead of blinking off. */
    private fun beginPressFade(key: Key) {
        fadingPresses[key] = AnimationUtils.currentAnimationTimeMillis()
    }

    /** @param pressOverlay 1 while held, decaying to 0 across the release fade. */
    private fun drawKey(canvas: Canvas, placed: PlacedKey, pressOverlay: Float) {
        val key = placed.key
        val compactSurface =
            !settings.showKeyBorders && KeySurfaceStyle.usesCompactSurface(key.type)
        val surfaceInsetV = if (compactSurface) dp(8f) else keyGapV
        reusableRect.set(
            placed.left + keyGapH,
            placed.top + surfaceInsetV,
            placed.right - keyGapH,
            placed.bottom - surfaceInsetV,
        )

        // Gboard's "key borders" setting controls the visible keycap, not merely a one-pixel
        // outline. With it off, letters and standalone glyphs sit directly on the keyboard while
        // the space, mode, and enter targets retain compact pill surfaces.
        val drawSurface = KeySurfaceStyle.drawsSurface(key.type, settings.showKeyBorders)
        if (drawSurface) {
            // A hand-drawn under-edge shadow rather than setShadowLayer, which hardware canvases
            // only honour for shapes from API 28. One offset rect is enough to lift the cap.
            reusableRect.offset(0f, keyShadowOffset)
            fillPaint.color = keyboardTheme.keyShadow
            canvas.drawRoundRect(reusableRect, cornerRadius, cornerRadius, fillPaint)
            reusableRect.offset(0f, -keyShadowOffset)
            fillPaint.color = backgroundColorFor(key)
            canvas.drawRoundRect(reusableRect, cornerRadius, cornerRadius, fillPaint)
        }

        if (pressOverlay > 0f) {
            fillPaint.color = keyboardTheme.keyPressedOverlay
            fillPaint.alpha = (Color.alpha(keyboardTheme.keyPressedOverlay) * pressOverlay).roundToInt()
            canvas.drawRoundRect(reusableRect, cornerRadius, cornerRadius, fillPaint)
        }

        if (settings.showKeyBorders && drawSurface) {
            borderPaint.color = keyboardTheme.keyBorder
            canvas.drawRoundRect(reusableRect, cornerRadius, cornerRadius, borderPaint)
        }

        if (key.type == KeyType.SPACE) {
            drawSpaceLabel(canvas, placed)
            return
        }

        if (isActionIcon(key.type)) {
            drawActionIcon(canvas, key.type, placed)
            return
        }

        labelPaint.color = textColorFor(key)
        labelPaint.typeface = keyLabelTypeface
        val maxText = min(placed.width * if (key.type == KeyType.CHARACTER) 0.65f else 0.8f, placed.height * 0.58f)
        labelPaint.textSize = min(if (key.type == KeyType.CHARACTER) sp(22f) else sp(15f), maxText)
        labelPaint.getFontMetrics(reusableFontMetrics)
        val baseline = placed.centerY -
            (reusableFontMetrics.ascent + reusableFontMetrics.descent) / 2f
        canvas.drawText(displayLabel(key), placed.centerX, baseline, labelPaint)

        val hint = KeyHintStyle.visibleHint(key, settings.showNumberRow)
        if (hint != null) {
            hintPaint.color = keyboardTheme.hintText
            hintPaint.textSize = min(sp(10f), placed.width * 0.24f)
            canvas.drawText(hint, placed.right - keyGapH - dp(8f), placed.top + keyGapV + dp(13f), hintPaint)
        }
    }

    /** One compact optical box and stroke weight keeps every action glyph in the same family. */
    private fun drawActionIcon(canvas: Canvas, type: KeyType, placed: PlacedKey) {
        val oldStyle = labelPaint.style
        val oldStroke = labelPaint.strokeWidth
        val oldCap = labelPaint.strokeCap
        val oldJoin = labelPaint.strokeJoin
        labelPaint.color = textColorFor(placed.key)
        labelPaint.style = Paint.Style.STROKE
        labelPaint.strokeWidth = ActionIconStyle.strokeWidth(density)
        labelPaint.strokeCap = Paint.Cap.ROUND
        labelPaint.strokeJoin = Paint.Join.ROUND
        val r = ActionIconStyle.radius(placed.width, placed.height)
        val x = placed.centerX
        val y = placed.centerY
        when (type) {
            KeyType.SHIFT -> {
                // The inactive shift is a compact outline, matching the visual weight of
                // backspace. Filling the same path communicates the active state without adding
                // a large rectangular keycap behind it.
                if (shiftState != ShiftState.OFF && !settings.showKeyBorders) {
                    labelPaint.style = Paint.Style.FILL
                }
                iconPath.reset()
                iconPath.moveTo(x, y - r * .88f)
                iconPath.lineTo(x - r * .72f, y - r * .06f)
                iconPath.lineTo(x - r * .31f, y - r * .06f)
                iconPath.lineTo(x - r * .31f, y + r * .62f)
                iconPath.lineTo(x + r * .31f, y + r * .62f)
                iconPath.lineTo(x + r * .31f, y - r * .06f)
                iconPath.lineTo(x + r * .72f, y - r * .06f)
                iconPath.close()
                canvas.drawPath(iconPath, labelPaint)
                labelPaint.style = Paint.Style.STROKE
                if (shiftState == ShiftState.LOCKED) {
                    canvas.drawLine(
                        x - r * .32f,
                        y + r * .88f,
                        x + r * .32f,
                        y + r * .88f,
                        labelPaint,
                    )
                }
            }
            KeyType.DELETE -> {
                iconPath.reset()
                iconPath.moveTo(x - r * .88f, y)
                iconPath.lineTo(x - r * .38f, y - r * .52f)
                iconPath.lineTo(x + r * .82f, y - r * .52f)
                iconPath.lineTo(x + r * .82f, y + r * .52f)
                iconPath.lineTo(x - r * .38f, y + r * .52f)
                iconPath.close()
                canvas.drawPath(iconPath, labelPaint)
                canvas.drawLine(x + r * .02f, y - r * .22f, x + r * .40f, y + r * .22f, labelPaint)
                canvas.drawLine(x + r * .40f, y - r * .22f, x + r * .02f, y + r * .22f, labelPaint)
            }
            KeyType.ENTER -> drawEnterIcon(canvas, x, y, r)
            KeyType.EMOJI -> {
                canvas.drawCircle(x, y, r * .68f, labelPaint)
                labelPaint.style = Paint.Style.FILL
                canvas.drawCircle(x - r * .24f, y - r * .14f, r * .075f, labelPaint)
                canvas.drawCircle(x + r * .24f, y - r * .14f, r * .075f, labelPaint)
                labelPaint.style = Paint.Style.STROKE
                canvas.drawArc(
                    x - r * .34f,
                    y - r * .03f,
                    x + r * .34f,
                    y + r * .36f,
                    12f,
                    156f,
                    false,
                    labelPaint,
                )
            }
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
                canvas.drawLine(x - r * .72f, y + r * .10f, x + r * .52f, y + r * .10f, labelPaint)
                canvas.drawLine(x - r * .72f, y + r * .10f, x - r * .30f, y - r * .30f, labelPaint)
                canvas.drawLine(x - r * .72f, y + r * .10f, x - r * .30f, y + r * .50f, labelPaint)
                canvas.drawLine(x + r * .52f, y + r * .10f, x + r * .52f, y - r * .58f, labelPaint)
            }
            EnterAction.SEARCH -> {
                canvas.drawCircle(x - r * .13f, y - r * .13f, r * .46f, labelPaint)
                canvas.drawLine(x + r * .20f, y + r * .20f, x + r * .67f, y + r * .67f, labelPaint)
            }
            EnterAction.SEND -> {
                iconPath.reset()
                iconPath.moveTo(x - r * .85f, y - r * .72f)
                iconPath.lineTo(x + r * .85f, y)
                iconPath.lineTo(x - r * .85f, y + r * .72f)
                iconPath.lineTo(x - r * .38f, y)
                iconPath.close()
                canvas.drawPath(iconPath, labelPaint)
            }
            EnterAction.GO, EnterAction.NEXT -> {
                canvas.drawLine(x-r*.8f, y, x+r*.62f, y, labelPaint)
                canvas.drawLine(x+r*.62f, y, x+r*.18f, y-r*.42f, labelPaint)
                canvas.drawLine(x+r*.62f, y, x+r*.18f, y+r*.42f, labelPaint)
            }
            EnterAction.PREVIOUS -> {
                canvas.drawLine(x+r*.8f, y, x-r*.62f, y, labelPaint)
                canvas.drawLine(x-r*.62f, y, x-r*.18f, y-r*.42f, labelPaint)
                canvas.drawLine(x-r*.62f, y, x-r*.18f, y+r*.42f, labelPaint)
            }
            EnterAction.DONE -> {
                canvas.drawLine(x-r*.75f, y, x-r*.15f, y+r*.5f, labelPaint)
                canvas.drawLine(x-r*.15f, y+r*.5f, x+r*.82f, y-r*.58f, labelPaint)
            }
        }
    }

    private fun isActionIcon(type: KeyType): Boolean = when (type) {
        KeyType.SHIFT, KeyType.DELETE, KeyType.ENTER, KeyType.EMOJI -> true
        else -> false
    }

    private fun drawSpaceLabel(canvas: Canvas, placed: PlacedKey) {
        labelPaint.color = keyboardTheme.hintText
        labelPaint.typeface = keyLabelTypeface
        labelPaint.textSize = min(sp(12f), placed.width * 0.28f)
        labelPaint.getFontMetrics(reusableFontMetrics)
        val baseline = placed.centerY -
            (reusableFontMetrics.ascent + reusableFontMetrics.descent) / 2f
        canvas.drawText(keyboardLayout.label, placed.centerX, baseline, labelPaint)
    }

    private fun drawGestureTrail(
        canvas: Canvas,
        points: List<GesturePoint>,
        alpha: Int,
    ) {
        if (points.size < 2 || alpha <= 0) return

        // Preserve the full visible motion and soften the sampled polyline into one continuous
        // stroke. The taper is drawn as a handful of smoothed chunks that share endpoints and
        // round caps — thin and faint at the tail, full weight under the finger — because true
        // per-segment alpha made fast traces look dashed, while discarding most of the path made
        // a long glide look as though it had stopped tracking the finger. [alpha] scales the whole
        // taper down as the completed trail fades after lift.
        val fade = alpha / TRAIL_HEAD_ALPHA
        val start = (points.size - TRAIL_POINTS).coerceAtLeast(0)
        val span = points.lastIndex - start
        trailPaint.color = keyboardTheme.gestureTrail
        for (segment in 0 until TRAIL_SEGMENTS) {
            val from = start + span * segment / TRAIL_SEGMENTS
            val to = start + span * (segment + 1) / TRAIL_SEGMENTS
            if (to <= from) continue

            // 0 at the trail's tail, 1 under the finger.
            val head = (segment + 1f) / TRAIL_SEGMENTS
            trailPaint.strokeWidth = dp(TRAIL_TAIL_WIDTH_DP + (TRAIL_HEAD_WIDTH_DP - TRAIL_TAIL_WIDTH_DP) * head)
            trailPaint.alpha =
                ((TRAIL_TAIL_ALPHA + (TRAIL_HEAD_ALPHA - TRAIL_TAIL_ALPHA) * head) * fade).roundToInt()

            trailPath.reset()
            trailPath.moveTo(points[from].x, points[from].y)
            for (i in from + 1 until to) {
                val point = points[i]
                val next = points[i + 1]
                trailPath.quadTo(point.x, point.y, (point.x + next.x) / 2f, (point.y + next.y) / 2f)
            }
            trailPath.lineTo(points[to].x, points[to].y)
            canvas.drawPath(trailPath, trailPaint)
        }

        // Keep a bright head attached to the most recent sample, then fade it with the completed
        // path after lift. The trail reads as motion instead of a static line left on the keys.
        val end = points.last()
        trailPaint.alpha = alpha
        trailPaint.style = Paint.Style.FILL
        canvas.drawCircle(end.x, end.y, dp(3.7f), trailPaint)
        trailPaint.style = Paint.Style.STROKE
    }

    private fun backgroundColorFor(key: Key): Int = when (key.type) {
        KeyType.ENTER -> keyboardTheme.accentBackground
        KeyType.SHIFT -> if (shiftState != ShiftState.OFF) keyboardTheme.accentBackground else keyboardTheme.specialKeyBackground
        KeyType.CHARACTER, KeyType.SPACE -> keyboardTheme.keyBackground
        else -> keyboardTheme.specialKeyBackground
    }

    private fun textColorFor(key: Key): Int = when (key.type) {
        KeyType.ENTER -> keyboardTheme.accentText
        KeyType.SHIFT -> if (settings.showKeyBorders && shiftState != ShiftState.OFF) {
            keyboardTheme.accentText
        } else {
            keyboardTheme.specialKeyText
        }
        KeyType.CHARACTER, KeyType.SPACE -> keyboardTheme.keyText
        else -> keyboardTheme.specialKeyText
    }

    private fun displayLabel(key: Key): String = when (key.type) {
        KeyType.SHIFT -> when (shiftState) {
            ShiftState.OFF -> "⇧"
            ShiftState.SHIFTED -> "⬆"
            ShiftState.LOCKED -> "⇪"
        }
        KeyType.CHARACTER ->
            if (shiftState != ShiftState.OFF) {
                uppercaseLabels.getOrPut(key.label) { key.label.uppercase() }
            } else {
                key.label
            }
        else -> key.label
    }

    /** The text a key commits, accounting for shift. */
    private fun outputFor(key: Key): String =
        if (
            key.type == KeyType.CHARACTER &&
            key.outputText.codePointCount(0, key.outputText.length) == 1 &&
            key.outputText.firstOrNull()?.isLetter() == true &&
            shiftState != ShiftState.OFF
        ) {
            key.outputText.uppercase()
        } else {
            key.outputText
        }

    // endregion

    // region Emoji search

    private fun searchHeaderHeight(): Float = dp(SEARCH_HEADER_DP)

    private fun drawSearchHeader(canvas: Canvas) {
        val header = searchHeaderHeight()
        fillPaint.color = keyboardTheme.specialKeyBackground
        canvas.drawRect(0f, 0f, width.toFloat(), header, fillPaint)

        labelPaint.color = if (searchQuery.isEmpty()) keyboardTheme.hintText else keyboardTheme.specialKeyText
        labelPaint.textSize = min(sp(16f), width * 0.055f)
        labelPaint.typeface = android.graphics.Typeface.DEFAULT
        labelPaint.textAlign = Paint.Align.LEFT
        val query = if (searchQuery.isEmpty()) "Search emoji" else searchQuery
        val queryBaseline = dp(29f)
        canvas.drawText(query, dp(16f), queryBaseline, labelPaint)

        val closeSize = dp(48f)
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = keyboardTheme.specialKeyText
        labelPaint.textSize = sp(25f)
        canvas.drawText("×", width - closeSize / 2f, dp(30f), labelPaint)

        linePaint.color = keyboardTheme.divider
        linePaint.strokeWidth = dp(1f)
        canvas.drawLine(0f, dp(48f), width.toFloat(), dp(48f), linePaint)

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
                dp(76f),
                labelPaint,
            )
            return
        }

        emojiPaint.textSize = min(sp(25f), dp(34f))
        val rowTop = dp(48f)
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
            emojiPaint.getFontMetrics(reusableFontMetrics)
            canvas.drawText(
                emoji,
                index * cellWidth + cellWidth / 2f,
                rowTop + dp(24f) - (reusableFontMetrics.ascent + reusableFontMetrics.descent) / 2f,
                emojiPaint,
            )
        }
    }

    private fun searchResultAt(x: Float, y: Float): Int {
        val header = searchHeaderHeight()
        if (y < dp(48f) || y >= header) return -1
        val index = (x / (width / MAX_SEARCH_RESULTS.toFloat())).toInt()
        return index.takeIf { it in searchResults.indices } ?: -1
    }

    private fun searchCloseAt(x: Float, y: Float): Boolean =
        y in 0f..dp(48f) && x >= width - dp(48f)

    // endregion

    // region Touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (searchMode && handleSearchHeaderTouch(event)) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                handlePointerDown(
                    event.getPointerId(index),
                    event.getX(index),
                    event.getY(index),
                    event.eventTime,
                )
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
                    if (gesturePointerId == pointerId || (gestureTypingAvailable && pointers.size == 1)) {
                        for (h in 0 until event.historySize) {
                            handlePointerMove(
                                pointerId,
                                event.getHistoricalX(index, h),
                                event.getHistoricalY(index, h),
                                event.getHistoricalEventTime(h),
                            )
                        }
                    }
                    handlePointerMove(pointerId, event.getX(index), event.getY(index), event.eventTime)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                handlePointerUp(
                    event.getPointerId(index),
                    event.getX(index),
                    event.getY(index),
                    event.eventTime,
                )
            }

            MotionEvent.ACTION_CANCEL -> cancelAllPointers()
        }
        return true
    }

    /**
     * Routes the emoji-search header, which is a second touch target layered over the keys.
     *
     * Returns true when the header consumed the event. The header owns exactly one finger:
     * a second finger landing in it is swallowed rather than handed to the key hit-test —
     * points within its footprint overlap the top keyboard row, so falling through can type a
     * top-row letter into the query. Fingers on the keys keep working while the header is
     * held, and only the header's own finger can resolve it, whether it lifts as ACTION_UP
     * or, with a key still down, as ACTION_POINTER_UP.
     */
    private fun handleSearchHeaderTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                return when (
                    SearchHeaderRouting.onPointerDown(searchPointerId, inHeader = y < searchHeaderHeight())
                ) {
                    SearchHeaderRouting.Down.PASS_TO_KEYS -> false
                    SearchHeaderRouting.Down.SWALLOW -> true
                    SearchHeaderRouting.Down.CLAIM -> {
                        searchPointerId = event.getPointerId(index)
                        pressedSearchClose = searchCloseAt(x, y)
                        pressedSearchResult = if (pressedSearchClose) -1 else searchResultAt(x, y)
                        invalidate()
                        true
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val owner = searchPointerId ?: return false
                val index = event.findPointerIndex(owner)
                if (index < 0) return false
                val x = event.getX(index)
                val y = event.getY(index)
                if (pressedSearchClose && !searchCloseAt(x, y)) pressedSearchClose = false
                val next = if (pressedSearchClose) -1 else searchResultAt(x, y)
                if (next != pressedSearchResult) pressedSearchResult = next
                invalidate()
                // Other fingers may be pressing keys, so the key layer still sees this move.
                return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val lifted = event.getPointerId(event.actionIndex)
                if (!SearchHeaderRouting.resolvesOnLift(searchPointerId, lifted)) return false
                val close = pressedSearchClose
                val result = pressedSearchResult
                clearSearchPress()
                if (close) listener?.onSearchClosed()
                else if (result in searchResults.indices) listener?.onSearchEmojiPicked(searchResults[result])
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                clearSearchPress()
                // The keys are cancelled by the same event.
                return false
            }
        }
        return false
    }

    private fun clearSearchPress() {
        searchPointerId = null
        pressedSearchClose = false
        pressedSearchResult = -1
        invalidate()
    }

    private fun handlePointerDown(pointerId: Int, x: Float, y: Float, eventTime: Long) {
        // A contact that arrives while a swipe is in flight — the other thumb, a palm — is not a
        // key press: sounding it, previewing it, or committing it on lift all interrupt the swipe.
        // Left out of [pointers] entirely, so its own move and lift are ignored too.
        if (PointerOwnership.ignoresKeyDown(gesturePointerId)) return
        val placed = KeyGeometry.hitTest(placedKeys, x, y) ?: return
        completedGesturePoints.clear()
        val pointer = Pointer(placed, placed, x, y, eventTime)
        pointers[pointerId] = pointer
        cursorRemainderPx = 0f

        listener?.onKeyDown(placed.key)
        showPreviewFor(pointerId, placed)
        scheduleLongPress(pointerId, placed)

        // Backspace commits on release unless repeat has begun. This preserves the user's selection
        // while they decide whether the press is a tap, a hold, or the delete-word swipe.
        scheduleRepeat(pointerId, placed)
        invalidate()
    }

    private fun handlePointerMove(pointerId: Int, x: Float, y: Float, eventTime: Long) {
        val pointer = pointers[pointerId] ?: return

        if (gesturePointerId == pointerId) {
            appendGesturePoint(x, y, eventTime)
            invalidate()
            return
        }

        val travelled = hypot(x - pointer.downX, y - pointer.downY)

        if (!searchMode && pointer.placed.key.type == KeyType.SPACE && gesturePointerId == null && pointers.size == 1) {
            val dx = x - pointer.downX
            val dy = y - pointer.downY
            if (!pointer.cursorMove && abs(dx) > touchSlop * GESTURE_SLOP_FACTOR && abs(dx) > abs(dy) * 1.15f) {
                pointer.cursorMove = true
                cancelPendingLongPress(pointerId)
                cancelRepeat(pointerId)
                dismissPreviewFor(pointerId)
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
                cancelPendingLongPress(pointerId)
                cancelRepeat(pointerId)
                dismissPreviewFor(pointerId)
            }
            if (pointer.deleteWordGesture) {
                invalidate()
                return
            }
        }

        // A swipe that starts on a letter key becomes a gesture; anything else stays a key press.
        if (gestureTypingAvailable &&
            gesturePointerId == null &&
            pointers.size == 1 &&
            !pointer.longPressFired &&
            !alternatesPopup.isShowing &&
            pointer.placed.key.gestureEligible &&
            travelled > touchSlop * GESTURE_SLOP_FACTOR
        ) {
            beginGesture(pointerId, pointer)
            appendGesturePoint(x, y, eventTime)
            invalidate()
            return
        }

        if (
            pointer.longPressFired &&
            PointerOwnership.ownsPopup(alternatesPopup.isShowing, alternatesPointerId, pointerId)
        ) {
            alternatesPopup.updateSelection(x, y)
            return
        }

        // Slide-off: the finger moved to a different key before lifting.
        if (travelled > touchSlop) {
            val nowOver = KeyGeometry.hitTest(placedKeys, x, y)
            if (nowOver == null && !pointer.cancelled) {
                cancelPendingLongPress(pointerId)
                cancelRepeat(pointerId)
                beginPressFade(pointer.placed.key)
                dismissPreviewFor(pointerId)
                pointer.cancelled = true
                pointer.slidOff = true
                invalidate()
            } else if (nowOver != null && (pointer.cancelled || nowOver !== pointer.placed)) {
                val changedKey = nowOver !== pointer.placed
                val cancellation = KeyPressRouting.pendingCancellationForRollover(changedKey)
                // A hold belongs to the key it began on. Letting either callback survive a direct
                // A-to-B slide can open A's alternates over B or repeat Backspace while the finger
                // visibly rests on another key.
                if (cancellation.longPress) cancelPendingLongPress(pointerId)
                if (cancellation.repeat) cancelRepeat(pointerId)
                if (!pointer.cancelled) beginPressFade(pointer.placed.key)
                pointer.placed = nowOver
                pointer.cancelled = false
                pointer.slidOff = true
                showPreviewFor(pointerId, nowOver)
                invalidate()
            }
        }
    }

    private fun handlePointerUp(pointerId: Int, x: Float, y: Float, eventTime: Long) {
        val pointer = pointers.remove(pointerId) ?: return
        cancelPendingLongPress(pointerId)
        cancelRepeat(pointerId)
        releasePreview(pointerId)

        // ACTION_UP carries the authoritative release coordinates. A sparse event stream can move
        // from a key into empty padding without an intervening MOVE, so re-run the bounded hit test
        // here instead of trusting the last sampled cell and committing a stale key.
        if (
            gesturePointerId != pointerId &&
            !pointer.cursorMove &&
            !pointer.deleteWordGesture &&
            !pointer.longPressFired
        ) {
            val releaseOver = KeyGeometry.hitTest(placedKeys, x, y)
            val release = KeyPressRouting.resolveRelease(
                hasValidKey = releaseOver != null,
                isCurrentKey = releaseOver === pointer.placed,
                movedBeyondSlop = hypot(x - pointer.downX, y - pointer.downY) > touchSlop,
                wasSlidOff = pointer.slidOff,
            )
            pointer.cancelled = release.cancelled
            pointer.slidOff = release.slidOff
            if (release.retarget && releaseOver != null) pointer.placed = releaseOver
        }
        if (
            KeyPressRouting.shouldFadeOnRelease(
                cancelled = pointer.cancelled,
                isGesturePointer = gesturePointerId == pointerId,
            )
        ) {
            beginPressFade(pointer.placed.key)
        }

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
            appendGesturePoint(x, y, eventTime)
            finishGesture(pointer)
            invalidate()
            return
        }

        // Only the finger that opened the popup can answer it. Another finger's lift used to
        // commit the popup's selection against its own, unrelated key — and left the owning
        // finger with nothing to commit when it eventually lifted.
        if (PointerOwnership.ownsPopup(alternatesPopup.isShowing, alternatesPointerId, pointerId)) {
            val selection = alternatesPopup.selected
            dismissAlternates()
            if (selection != null) {
                listener?.onKeyCommit(pointer.placed.key, selection)
                invalidate()
                return
            }
        }

        // A press whose auto-repeat fired must not commit the key a second time on release.
        if (!pointer.cancelled && !pointer.longPressFired && !pointer.repeatFired) {
            announceForAccessibility(accessibilityLabel(pointer.placed.key))
            listener?.onKeyCommit(
                pointer.placed.key,
                outputFor(pointer.placed.key),
                if (pointer.slidOff) x else pointer.downX,
                if (pointer.slidOff) y else pointer.downY,
            )
        }
        invalidate()
    }

    private fun cancelAllPointers() {
        pointers.clear()
        cancelAllPendingLongPresses()
        cancelRepeat()
        previewPopup.dismiss()
        previewPointerId = null
        dismissAlternates()
        abandonGesture()
        completedGesturePoints.clear()
        invalidate()
    }

    private fun showPreviewFor(pointerId: Int, placed: PlacedKey) {
        if (!settings.showKeyPreview) return
        if (placed.key.type != KeyType.CHARACTER) {
            dismissPreviewFor(pointerId)
            return
        }
        previewPointerId = pointerId
        previewPopup.show(keyboardTheme, displayLabel(placed.key), placed)
    }

    /** Hides the one shared preview window, but only on behalf of the finger showing in it. */
    private fun dismissPreviewFor(pointerId: Int) {
        if (!PointerOwnership.ownsPreview(previewPointerId, pointerId)) return
        previewPopup.dismiss()
        previewPointerId = null
    }

    /**
     * Releases the preview on a lift, handing it back to a finger that is still down.
     *
     * There is one preview window for the whole view, so a rollover lift — the trailing thumb
     * leaving while the leading one still rests on a letter — used to blank the preview of a key
     * that is very much still pressed.
     */
    private fun releasePreview(pointerId: Int) {
        if (!PointerOwnership.ownsPreview(previewPointerId, pointerId)) return
        previewPopup.dismiss()
        previewPointerId = null
        val survivor = pointers.entries
            .filter { (id, pointer) ->
                id != pointerId && PointerOwnership.mayInheritPreview(
                    isGesturePointer = id == gesturePointerId,
                    cancelled = pointer.cancelled,
                    isCharacter = pointer.placed.key.type == KeyType.CHARACTER,
                    longPressFired = pointer.longPressFired,
                    cursorMove = pointer.cursorMove,
                    deleteWordGesture = pointer.deleteWordGesture,
                )
            }
            .maxByOrNull { (_, pointer) -> pointer.downTime }
            ?: return
        showPreviewFor(survivor.key, survivor.value.placed)
    }

    private fun dismissAlternates() {
        alternatesPopup.dismiss()
        alternatesPointerId = null
    }

    // endregion

    // region Long press and repeat

    private fun scheduleLongPress(pointerId: Int, placed: PlacedKey) {
        if (placed.key.alternates.isEmpty()) return
        cancelPendingLongPress(pointerId)
        val runnable = Runnable {
            longPressRunnables.remove(pointerId)
            val pointer = pointers[pointerId] ?: return@Runnable
            // One popup window, one owner: a second finger's hold must not steal a popup the
            // first finger is still choosing from.
            if (!PointerOwnership.mayOpenPopup(alternatesPopup.isShowing, alternatesPointerId, pointerId)) {
                return@Runnable
            }
            pointer.longPressFired = true
            dismissPreviewFor(pointerId)
            val alternates = alternatesFor(placed.key)
            alternatesPointerId = pointerId
            alternatesPopup.show(keyboardTheme, alternates, placed)
        }
        longPressRunnables[pointerId] = runnable
        handler.postDelayed(runnable, longPressTimeout)
    }

    /** Alternates always include the base character, so the popup can be dismissed by re-selecting it. */
    private fun alternatesFor(key: Key): List<String> {
        val base = if (shiftState != ShiftState.OFF) key.label.uppercase() else key.label
        val rest = key.alternates.map { if (shiftState != ShiftState.OFF) it.uppercase() else it }
        return (listOf(base) + rest).distinct()
    }

    private fun cancelPendingLongPress(pointerId: Int) {
        longPressRunnables.remove(pointerId)?.let(handler::removeCallbacks)
    }

    private fun cancelAllPendingLongPresses() {
        longPressRunnables.values.forEach(handler::removeCallbacks)
        longPressRunnables.clear()
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
        cancelPendingLongPress(pointerId)
        cancelRepeat(pointerId)
        dismissPreviewFor(pointerId)
        // The gesture pointer's key stops drawing as held the moment the swipe begins, so hand it
        // to the release fade rather than letting the highlight vanish between two frames.
        beginPressFade(pointer.placed.key)
        gesturePointerId = pointerId
        completedGesturePoints.clear()
        gestureStartTime = pointer.downTime
        lastGesturePreviewTime = pointer.downTime
        gesturePoints.clear()
        gesturePathLength = 0f
        gesturePoints += GesturePoint(pointer.downX, pointer.downY, 0L)
    }

    private fun appendGesturePoint(x: Float, y: Float, eventTime: Long) {
        val elapsed = (eventTime - gestureStartTime)
            .coerceAtLeast(gesturePoints.lastOrNull()?.timeMs ?: 0L)
        val last = gesturePoints.lastOrNull()
        if (last != null && last.x == x && last.y == y && last.timeMs == elapsed) return
        if (last != null) gesturePathLength += hypot(x - last.x, y - last.y)
        gesturePoints += GesturePoint(x, y, elapsed)

        if (
            gesturePoints.size >= MIN_GESTURE_POINTS &&
            eventTime - lastGesturePreviewTime >= GESTURE_PREVIEW_INTERVAL_MS &&
            gesturePathLength >=
            (gesturePointerId?.let(pointers::get)?.placed?.width ?: 0f) *
            MIN_GESTURE_PREVIEW_PATH_FACTOR
        ) {
            lastGesturePreviewTime = eventTime
            listener?.onGesturePreview(ArrayList(gesturePoints))
        }
    }

    /**
     * Ends a deliberate swipe, or treats a short wander as the key press it began as.
     *
     * A full-length gesture with no candidate must not type its first letter: that is surprising,
     * destructive output and is what made a decoder miss look as though glide typing had vanished.
     */
    private fun finishGesture(pointer: Pointer) {
        val points = ArrayList(gesturePoints)
        val decodable = points.size >= MIN_GESTURE_POINTS &&
            gesturePathLength >= pointer.placed.width * MIN_GESTURE_PATH_FACTOR
        abandonGesture(clearPreviewCandidates = !decodable)
        if (decodable) {
            val trailStart = (points.size - TRAIL_POINTS).coerceAtLeast(0)
            completedGesturePoints.clear()
            completedGesturePoints.addAll(points.subList(trailStart, points.size))
            completedGestureTime = SystemClock.uptimeMillis()
            listener?.onGestureComplete(points)
        } else {
            listener?.onKeyCommit(
                pointer.initialPlaced.key,
                outputFor(pointer.initialPlaced.key),
                pointer.downX,
                pointer.downY,
            )
        }
    }

    private fun abandonGesture(clearPreviewCandidates: Boolean = true) {
        if (gesturePointerId != null) {
            listener?.onGesturePreviewCancelled(clearPreviewCandidates)
        }
        gesturePointerId = null
        gesturePoints.clear()
        gesturePathLength = 0f
    }

    // endregion

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAllPointers()
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

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.inputmethodservice.KeyboardView"
        info.isFocusable = true
        info.contentDescription = accessibilityDescription()
    }

    private fun refreshAccessibilityDescription() {
        contentDescription = accessibilityDescription()
        accessibilityHelper.invalidateRoot()
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
    }

    private companion object {
        const val TRAIL_POINTS = 192

        /**
         * How far inward from each screen edge the back-gesture exclusion reaches, as a share of
         * the view's width. One letter column plus its padding is the need; a wider claim buys
         * nothing and eats into the system's per-edge budget.
         */
        const val GESTURE_EXCLUSION_WIDTH_FACTOR = 0.15f

        /**
         * The trail's taper, drawn as a few chunks rather than a per-point gradient. Six is enough
         * that the steps hide inside the stroke's own antialiasing.
         */
        const val TRAIL_SEGMENTS = 6
        const val TRAIL_HEAD_WIDTH_DP = 5.5f
        const val TRAIL_TAIL_WIDTH_DP = 2.25f
        const val TRAIL_HEAD_ALPHA = 215f
        const val TRAIL_TAIL_ALPHA = 50f

        /**
         * How long a released key's highlight takes to decay. Long enough to register as a glow,
         * short enough that rapid typing never shows more than a couple of trailing keys lit.
         */
        const val PRESS_FADE_MS = 140L

        /**
         * The least a swipe must be to be worth decoding, mirroring `DecoderConfig`'s own floors.
         *
         * Anything below either of these is refused by the decoder anyway, and a swipe the decoder
         * refuses commits nothing at all — so the view has to recognise the same cases and treat
         * them as the keypress they really were.
         */
        const val MIN_GESTURE_POINTS = 6
        const val MIN_GESTURE_PATH_FACTOR = 0.9f
        const val MIN_GESTURE_PREVIEW_PATH_FACTOR = 1.1f
        const val GESTURE_PREVIEW_INTERVAL_MS = 100L
        const val COMPLETED_TRAIL_FADE_MS = 140L
        const val GESTURE_SLOP_FACTOR = 1.4f
        const val MAX_SEARCH_RESULTS = 6
        const val SEARCH_HEADER_DP = 96f
        const val A11Y_KEY_BASE = 0
        const val A11Y_SEARCH_RESULT_BASE = 10_000
        const val A11Y_SEARCH_CLOSE = 20_000
        /**
         * Auto-repeat pacing for backspace.
         *
         * This is the pause before the key starts running, and it has to be long enough that an
         * ordinary tap never repeats. From
         * there it ramps hard: holding backspace is nearly always an intent to clear a whole
         * phrase, and the old ramp needed a dozen deletes and over a second and a half to reach
         * full speed, which is most of a short sentence spent waiting.
         */
        const val REPEAT_INITIAL_DELAY_MS = 300L
        const val REPEAT_MIN_DELAY_MS = 25L
        const val REPEAT_ACCELERATION = 0.66f
    }
}

/**
 * Who owns the keyboard's single-slot touch state.
 *
 * The keyboard has one preview window, one alternates popup and one search header but any number
 * of fingers. Kept outside the View so the ownership rules — the part that was wrong, not the
 * Android plumbing — stay regression-tested.
 */
internal object PointerOwnership {

    /** True when [pointerId] may commit or dismiss the alternates popup on its lift. */
    fun ownsPopup(popupShowing: Boolean, owner: Int?, pointerId: Int): Boolean =
        popupShowing && owner == pointerId

    /**
     * True when a long press that has just come due may open its popup.
     *
     * A popup another finger is still choosing from is not up for grabs; without this, the second
     * thumb's rescheduled hold reopened the popup over its own key mid-selection.
     */
    fun mayOpenPopup(popupShowing: Boolean, owner: Int?, pointerId: Int): Boolean =
        !popupShowing || owner == null || owner == pointerId

    /** True when [pointerId] may hide or hand over the shared key preview. */
    fun ownsPreview(owner: Int?, pointerId: Int): Boolean = owner == null || owner == pointerId

    /** A surviving contact may inherit the shared preview only while it remains a live key tap. */
    fun mayInheritPreview(
        isGesturePointer: Boolean,
        cancelled: Boolean,
        isCharacter: Boolean,
        longPressFired: Boolean,
        cursorMove: Boolean,
        deleteWordGesture: Boolean,
    ): Boolean =
        !isGesturePointer &&
            !cancelled &&
            isCharacter &&
            !longPressFired &&
            !cursorMove &&
            !deleteWordGesture

    /** A contact that arrives while a swipe is in flight is not a key press. */
    fun ignoresKeyDown(gesturePointerId: Int?): Boolean = gesturePointerId != null
}

/** Rollover and final-release policy, separated from MotionEvent plumbing for JVM coverage. */
internal object KeyPressRouting {
    data class PendingCancellation(
        val longPress: Boolean,
        val repeat: Boolean,
    )

    data class Release(
        val cancelled: Boolean,
        val slidOff: Boolean,
        val retarget: Boolean,
    )

    /** A pending hold/repeat is owned by its original key, not the cell rolled onto. */
    fun pendingCancellationForRollover(overDifferentKey: Boolean): PendingCancellation =
        PendingCancellation(
            longPress = overDifferentKey,
            repeat = overDifferentKey,
        )

    /** Padding-cancelled contacts fade their old key even while the finger remains down. */
    fun isVisuallyHeld(
        isGesturePointer: Boolean,
        cancelled: Boolean,
        isPlacedKey: Boolean,
    ): Boolean = !isGesturePointer && !cancelled && isPlacedKey

    /** Slide-off and gesture transitions already began their fade; lifting must not restart it. */
    fun shouldFadeOnRelease(cancelled: Boolean, isGesturePointer: Boolean): Boolean =
        !cancelled && !isGesturePointer

    /**
     * ACTION_UP is authoritative. A valid final cell revives a press cancelled by an earlier move,
     * including the sparse padding-to-original-key case where it is identical to the tracked key.
     */
    fun resolveRelease(
        hasValidKey: Boolean,
        isCurrentKey: Boolean,
        movedBeyondSlop: Boolean,
        wasSlidOff: Boolean,
    ): Release {
        if (!hasValidKey) {
            return Release(cancelled = true, slidOff = wasSlidOff, retarget = false)
        }
        return Release(
            cancelled = false,
            slidOff = wasSlidOff || movedBeyondSlop,
            // ACTION_UP is authoritative even if the final hop back to another key is within the
            // original down slop. Once a prior rollover occurred, keeping the stale tracked cell
            // would commit B for an A -> B -> A sequence that visibly ends on A.
            retarget = !isCurrentKey && (movedBeyondSlop || wasSlidOff),
        )
    }
}

/**
 * How the emoji-search header claims fingers.
 *
 * The header is layered over the top-row key region, so a header touch that is not claimed here
 * can become a stray letter in the search query.
 */
internal object SearchHeaderRouting {

    enum class Down {
        /** No finger held the header: this one takes it. */
        CLAIM,

        /** The header already has a finger; swallow this one rather than type with it. */
        SWALLOW,

        /** Below the header — the keys get it. */
        PASS_TO_KEYS,
    }

    fun onPointerDown(headerOwner: Int?, inHeader: Boolean): Down = when {
        !inHeader -> Down.PASS_TO_KEYS
        headerOwner == null -> Down.CLAIM
        else -> Down.SWALLOW
    }

    /** True when a lift — ACTION_UP or ACTION_POINTER_UP alike — resolves the header press. */
    fun resolvesOnLift(headerOwner: Int?, pointerId: Int): Boolean =
        headerOwner != null && headerOwner == pointerId
}

/** Visual keycap policy separated from Canvas work so theme settings cannot regress silently. */
internal object KeySurfaceStyle {
    fun drawsSurface(type: KeyType, showKeyBorders: Boolean): Boolean = showKeyBorders ||
        type == KeyType.SPACE ||
        type == KeyType.ENTER ||
        type == KeyType.SYMBOLS ||
        type == KeyType.SYMBOLS_ALT ||
        type == KeyType.ALPHA

    fun usesCompactSurface(type: KeyType): Boolean =
        type == KeyType.SPACE ||
            type == KeyType.ENTER ||
            type == KeyType.SYMBOLS ||
            type == KeyType.SYMBOLS_ALT ||
            type == KeyType.ALPHA
}

/**
 * Beside a real number row a digit hint is redundant, so the key surfaces the symbol its
 * long-press popup leads with instead, as Gboard does; punctuation hints remain useful as-is.
 */
internal object KeyHintStyle {
    fun visibleHint(key: Key, showNumberRow: Boolean): String? {
        val hint = key.hint ?: return null
        if (!showNumberRow || hint.length != 1 || !hint[0].isDigit()) return hint
        return key.alternates.firstOrNull { it.length == 1 && !it[0].isLetterOrDigit() }
    }
}

/** Shared optical measurements for the five action icons in the key row and toolbar. */
internal object ActionIconStyle {
    fun radius(width: Float, height: Float): Float = minOf(width, height) * 0.235f

    fun strokeWidth(density: Float): Float = 1.65f * density
}
