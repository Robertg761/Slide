package com.slide.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.OverScroller
import com.slide.core.emoji.EmojiData
import com.slide.core.theme.KeyboardTheme
import com.slide.core.theme.Themes
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The emoji picker: a tab strip, a scrolling grid, and a row to get back to the keys.
 *
 * It sits over the keys rather than replacing them, for the same reason the voice overlay does —
 * the input view keeps its height, so opening the picker never shoves the app's content up or down.
 *
 * Each tab is its own page rather than one long scroll with sticky headers. With nine categories
 * and no search, a page you can reach in one tap and scroll to the end of is easier to use than a
 * two-thousand-cell list where every tap is a jump into the middle of it.
 *
 * Like [KeyboardView] it draws itself in one pass. A grid of ~1900 cells built out of TextViews
 * would cost a measure pass and a few hundred view objects for something that is, on screen, a
 * regular lattice of single glyphs.
 */
class EmojiPanelView(context: Context) : View(context) {

    interface Listener {
        fun onEmojiPicked(emoji: String)

        /**
         * A skin tone was chosen from a long-press.
         *
         * It becomes the default for every emoji from now on, as in Gboard: nobody wants to pick
         * their own skin tone once per emoji.
         */
        fun onSkinTonePicked(tone: Int)

        fun onEmojiBackspace()

        /** The "ABC" button was tapped. */
        fun onEmojiPanelClosed()

        /** The search tab was tapped. */
        fun onEmojiSearchRequested() = Unit
    }

    var listener: Listener? = null

    var keyboardTheme: KeyboardTheme = Themes.Light
        set(value) {
            field = value
            invalidate()
        }

    /** The catalogue, or null until it has finished loading. */
    var data: EmojiData? = null
        set(value) {
            field = value
            invalidatePage()
            reset()
            refreshAccessibilityDescription()
            invalidate()
        }

    /**
     * Entry indices per category that the system font can actually draw.
     *
     * Supplied rather than computed here because working it out means asking the font about every
     * emoji in the catalogue, and that belongs off the main thread. Empty means "not filtered yet",
     * in which case every entry is shown — better a rare tofu than an empty picker.
     */
    var renderable: Array<IntArray> = emptyArray()
        set(value) {
            field = value
            invalidatePage()
            refreshAccessibilityDescription()
            invalidate()
        }

    var recents: List<String> = emptyList()
        set(value) {
            field = value
            invalidatePage()
            // Redrawing while the user is looking at another tab is harmless; yanking the grid out
            // from under a scroll on the recents tab is not, so the offset is left alone.
            refreshAccessibilityDescription()
            invalidate()
        }

    var skinTone: Int = EmojiData.TONE_DEFAULT
        set(value) {
            field = value
            invalidatePage()
            refreshAccessibilityDescription()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(24f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(14f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1f) }

    private val popupRect = RectF()

    private var selectedTab = FIRST_CATEGORY_TAB
    private var scrollY = 0f
    private var pressedIndex = -1
    private var pressedTab = -1
    private var pressedBack = false
    private var pressedBackspace = false

    /** Catalogue entry whose tone row is open, or -1. */
    private var popupIndex = -1

    /** Where that entry sits on the current page, which is what the popup is positioned over. */
    private var popupPosition = -1
    private var popupTone = EmojiData.TONE_DEFAULT

    /**
     * The tone the row was opened on, which is the slot placed under the finger.
     *
     * Held separately from [popupTone] because that one follows the finger, and a row that moved
     * with it would be impossible to aim at.
     */
    private var popupAnchorTone = EmojiData.TONE_DEFAULT

    private var pageCache: List<String>? = null

    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private var scrolling = false
    private var downY = 0f

    /**
     * Repeats the footer's backspace while it is held, accelerating as it goes.
     *
     * Without it, clearing a line of emoji is one tap per character, and emoji are exactly the
     * thing people delete several of at a time.
     */
    private val backspaceRepeat = object : Runnable {
        override fun run() {
            if (!pressedBackspace) return
            listener?.onEmojiBackspace()
            repeatDelay = max(MIN_REPEAT_MS, (repeatDelay * REPEAT_DECAY).toLong())
            postDelayed(this, repeatDelay)
        }
    }
    private var repeatDelay = FIRST_REPEAT_MS

    private val longPress = Runnable {
        // pressedIndex is a position on the page; tones are a property of the catalogue entry
        // underneath it, and on the recents tab the two have nothing to do with each other.
        val entry = entryAt(pressedIndex)
        if (entry >= 0 && data?.hasVariants(entry) == true) {
            popupIndex = entry
            popupPosition = pressedIndex
            // Opens on whatever is already the default, so lifting without sliding changes nothing.
            popupTone = if (skinTone in 0 until EmojiData.TONE_COUNT) skinTone else EmojiData.TONE_DEFAULT
            popupAnchorTone = popupTone
            pressedIndex = -1
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        refreshAccessibilityDescription()
    }

    // region Layout

    private fun tabHeight() = dp(TAB_HEIGHT_DP)
    private fun footerHeight() = dp(FOOTER_HEIGHT_DP)
    private fun gridTop() = tabHeight()
    private fun gridHeight() = max(0f, height - tabHeight() - footerHeight())

    private fun columns(): Int = max(MIN_COLUMNS, (width / dp(CELL_DP)).toInt())

    private fun cellSize(): Float = width / columns().toFloat()

    /** Tab count is the recents tab, one per category, and a final search tab. */
    private fun tabCount(): Int = (data?.categories?.size ?: 0) + 2

    private fun searchTab(): Int = tabCount() - 1

    /**
     * The emoji shown on the current page, already filtered and toned.
     *
     * Cached, because this is read by the draw pass, by every touch move and by every frame of a
     * fling. Rebuilding it each time would mean re-toning a few hundred entries into a few hundred
     * fresh strings per frame — the allocation churn this view exists to avoid.
     */
    private fun page(): List<String> {
        pageCache?.let { return it }

        val catalogue = data
        val built = when {
            catalogue == null -> emptyList()
            selectedTab == RECENTS_TAB -> recents
            selectedTab == searchTab() -> emptyList()
            else -> {
                val category = selectedTab - 1
                val indices = renderable.getOrNull(category) ?: catalogue.indicesIn(category)
                indices.map { catalogue.toned(it, skinTone) }
            }
        }
        pageCache = built
        return built
    }

    /** Called by everything the page is derived from: the tab, the tone, and the data behind it. */
    private fun invalidatePage() {
        pageCache = null
    }

    /** Catalogue index for a position on the current page, or -1 for a recent with no home. */
    private fun entryAt(position: Int): Int {
        val catalogue = data ?: return -1
        if (selectedTab == searchTab()) return -1
        if (selectedTab != RECENTS_TAB) {
            val category = selectedTab - 1
            val indices = renderable.getOrNull(category) ?: catalogue.indicesIn(category)
            return indices.getOrElse(position) { -1 }
        }
        return recents.getOrNull(position)?.let(catalogue::indexOf) ?: -1
    }

    private fun maxScroll(): Float {
        val rows = ceil(page().size / columns().toFloat())
        return max(0f, rows * cellSize() - gridHeight())
    }

    private fun resetScroll() {
        scroller.forceFinished(true)
        scrollY = 0f
    }

    // endregion

    // region Drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(keyboardTheme.background)
        if (data == null) return

        drawTabs(canvas)
        drawGrid(canvas)
        drawFooter(canvas)
        if (popupIndex >= 0) drawTonePopup(canvas)
    }

    private fun drawTabs(canvas: Canvas) {
        val catalogue = data ?: return
        val count = tabCount()
        val tabWidth = width / count.toFloat()
        val bottom = tabHeight()

        emojiPaint.textSize = sp(TAB_ICON_SP)
        val metrics = emojiPaint.fontMetrics
        val baseline = bottom / 2f - (metrics.ascent + metrics.descent) / 2f

        for (tab in 0 until count) {
            val left = tab * tabWidth
            if (tab == pressedTab) {
                fillPaint.color = keyboardTheme.keyPressedOverlay
                canvas.drawRect(left, 0f, left + tabWidth, bottom, fillPaint)
            }
            val icon = when {
                tab == RECENTS_TAB -> RECENTS_ICON
                tab == searchTab() -> SEARCH_ICON
                else -> tabIcon(catalogue.categories[tab - 1])
            }
            canvas.drawText(icon, left + tabWidth / 2f, baseline, emojiPaint)

            if (tab == selectedTab) {
                // An underline rather than a filled tab: the icons are colourful enough already,
                // and a coloured block behind one of them reads as another emoji.
                fillPaint.color = keyboardTheme.accentBackground
                val inset = tabWidth * 0.22f
                canvas.drawRect(
                    left + inset,
                    bottom - dp(2.5f),
                    left + tabWidth - inset,
                    bottom,
                    fillPaint,
                )
            }
        }

        linePaint.color = keyboardTheme.divider
        canvas.drawLine(0f, bottom, width.toFloat(), bottom, linePaint)
    }

    private fun drawGrid(canvas: Canvas) {
        val entries = page()
        if (entries.isEmpty()) {
            drawEmptyPage(canvas)
            return
        }

        val cell = cellSize()
        val top = gridTop()
        val columns = columns()

        canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), top + gridHeight())

        emojiPaint.textSize = sp(EMOJI_SP)
        val metrics = emojiPaint.fontMetrics
        val centreOffset = -(metrics.ascent + metrics.descent) / 2f

        // Only the rows actually on screen are drawn. The alternative is 1900 drawText calls per
        // frame during a fling, which is the difference between a smooth scroll and a slideshow.
        val firstRow = max(0, (scrollY / cell).toInt())
        val lastRow = min(
            ceil(entries.size / columns.toFloat()).toInt() - 1,
            ((scrollY + gridHeight()) / cell).toInt(),
        )

        for (row in firstRow..lastRow) {
            for (column in 0 until columns) {
                val position = row * columns + column
                if (position >= entries.size) break

                val x = column * cell
                val y = top + row * cell - scrollY

                if (position == pressedIndex) {
                    fillPaint.color = keyboardTheme.keyPressedOverlay
                    canvas.drawRoundRect(
                        x + dp(2f), y + dp(2f), x + cell - dp(2f), y + cell - dp(2f),
                        dp(6f), dp(6f), fillPaint,
                    )
                }
                canvas.drawText(entries[position], x + cell / 2f, y + cell / 2f + centreOffset, emojiPaint)
            }
        }
        canvas.restore()
    }

    private fun drawEmptyPage(canvas: Canvas) {
        labelPaint.color = keyboardTheme.hintText
        labelPaint.textSize = sp(14f)
        val centre = gridTop() + gridHeight() / 2f
        val metrics = labelPaint.fontMetrics
        canvas.drawText(
            if (selectedTab == RECENTS_TAB) EMPTY_RECENTS else EMPTY_CATEGORY,
            width / 2f,
            centre - (metrics.ascent + metrics.descent) / 2f,
            labelPaint,
        )
    }

    private fun drawFooter(canvas: Canvas) {
        val top = height - footerHeight()

        linePaint.color = keyboardTheme.divider
        canvas.drawLine(0f, top, width.toFloat(), top, linePaint)

        val backWidth = backButtonWidth()
        if (pressedBack) {
            fillPaint.color = keyboardTheme.keyPressedOverlay
            canvas.drawRect(0f, top, backWidth, height.toFloat(), fillPaint)
        }
        if (pressedBackspace) {
            fillPaint.color = keyboardTheme.keyPressedOverlay
            canvas.drawRect(width - backWidth, top, width.toFloat(), height.toFloat(), fillPaint)
        }

        labelPaint.color = keyboardTheme.specialKeyText
        val metrics = labelPaint.fontMetrics
        val baseline = top + footerHeight() / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(BACK_LABEL, backWidth / 2f, baseline, labelPaint)

        drawBackspace(canvas, width - backWidth / 2f, top + footerHeight() / 2f)
    }

    /** The same glyph the delete key draws, so the two read as the same button. */
    private fun drawBackspace(canvas: Canvas, centreX: Float, centreY: Float) {
        val w = dp(11f)
        val h = dp(8f)
        linePaint.color = keyboardTheme.specialKeyText
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = dp(1.6f)

        val path = android.graphics.Path().apply {
            moveTo(centreX - w, centreY)
            lineTo(centreX - w * 0.4f, centreY - h)
            lineTo(centreX + w, centreY - h)
            lineTo(centreX + w, centreY + h)
            lineTo(centreX - w * 0.4f, centreY + h)
            close()
        }
        canvas.drawPath(path, linePaint)

        val cross = dp(3.2f)
        val crossX = centreX + w * 0.25f
        canvas.drawLine(crossX - cross, centreY - cross, crossX + cross, centreY + cross, linePaint)
        canvas.drawLine(crossX - cross, centreY + cross, crossX + cross, centreY - cross, linePaint)

        linePaint.style = Paint.Style.FILL
        linePaint.strokeWidth = dp(1f)
    }

    /**
     * The forms offered by the tone row: the emoji as the catalogue holds it, then its five tones.
     *
     * The untoned form leads so there is a way back to it. Without it the row is a one-way door —
     * one long-press anywhere would tint every emoji in the picker for good, since the tone chosen
     * here becomes the default for all of them.
     */
    private fun popupForms(catalogue: EmojiData): List<String> =
        listOf(catalogue.emojiAt(popupIndex)) + catalogue.variantsAt(popupIndex)

    private fun drawTonePopup(canvas: Canvas) {
        val catalogue = data ?: return
        val forms = popupForms(catalogue)
        if (forms.size <= 1) return

        layoutTonePopup()
        fillPaint.color = keyboardTheme.popupBackground
        val radius = dp(8f)
        canvas.drawRoundRect(popupRect, radius, radius, fillPaint)

        val slot = popupRect.width() / forms.size
        emojiPaint.textSize = sp(EMOJI_SP)
        val metrics = emojiPaint.fontMetrics
        val centreY = popupRect.centerY() - (metrics.ascent + metrics.descent) / 2f

        for (index in forms.indices) {
            val left = popupRect.left + index * slot
            // Slot 0 is the untoned form, so the tone it stands for is one behind its position.
            if (index - 1 == popupTone) {
                fillPaint.color = keyboardTheme.popupSelectedBackground
                canvas.drawRoundRect(
                    left + dp(2f), popupRect.top + dp(2f),
                    left + slot - dp(2f), popupRect.bottom - dp(2f),
                    dp(6f), dp(6f), fillPaint,
                )
            }
            canvas.drawText(forms[index], left + slot / 2f, centreY, emojiPaint)
        }
    }

    /**
     * Puts the tone row above the pressed cell, shifted inward when it would run off an edge.
     *
     * Six cells wide is most of a phone's width, so an emoji near either margin would otherwise
     * open a popup with half its tones off screen.
     */
    private fun layoutTonePopup() {
        val cell = cellSize()
        val columns = columns()
        val row = popupPosition / columns
        val column = popupPosition % columns

        val popupWidth = min(width.toFloat(), cell * POPUP_SLOTS)
        val cellLeft = column * cell
        // The slot the row opened on goes under the finger, so releasing without sliding picks what
        // was already the default rather than whichever tone happens to sit in the middle.
        val anchor = (popupAnchorTone + 1 + 0.5f) * (popupWidth / POPUP_SLOTS)
        val left = (cellLeft + cell / 2f - anchor).coerceIn(0f, width - popupWidth)

        val cellTop = gridTop() + row * cell - scrollY
        val popupHeight = cell
        // Above the cell where a thumb is not covering it, unless that is off the top of the grid.
        val top = (cellTop - popupHeight).coerceAtLeast(gridTop())
        popupRect.set(left, top, left + popupWidth, top + popupHeight)
    }

    private fun tabIcon(category: String): String = TAB_ICONS[category] ?: RECENTS_ICON

    private fun backButtonWidth(): Float = width * BACK_BUTTON_FRACTION

    // endregion

    // region Touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(event)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_UP -> onUp(event)
            MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }

    private fun onDown(event: MotionEvent) {
        scroller.forceFinished(true)
        downY = event.y
        scrolling = false
        velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }

        when {
            popupIndex >= 0 -> popupTone = toneAt(event.x)
            event.y < tabHeight() -> pressedTab = tabAt(event.x)
            event.y >= height - footerHeight() -> {
                pressedBack = event.x < backButtonWidth()
                pressedBackspace = event.x >= width - backButtonWidth()
                if (pressedBackspace) {
                    // The first delete lands on press rather than on release, as the delete key
                    // does, so holding it feels like one continuous action.
                listener?.onEmojiBackspace()
                announceForAccessibility("Backspace")
                repeatDelay = FIRST_REPEAT_MS
                    postDelayed(backspaceRepeat, repeatDelay)
                }
            }

            else -> {
                pressedIndex = positionAt(event.x, event.y)
                if (pressedIndex >= 0) postDelayed(longPress, LONG_PRESS_MS)
            }
        }
        invalidate()
    }

    private fun onMove(event: MotionEvent) {
        velocityTracker?.addMovement(event)

        if (popupIndex >= 0) {
            val tone = toneAt(event.x)
            if (tone != popupTone) {
                popupTone = tone
                invalidate()
            }
            return
        }

        if (!scrolling && abs(event.y - downY) > touchSlop && pressedTab < 0 &&
            !pressedBack && !pressedBackspace
        ) {
            scrolling = true
            removeCallbacks(longPress)
            pressedIndex = -1
        }

        if (scrolling) {
            // Clamped rather than over-scrolled: a rubber-band would fight the app's own scroll
            // gesture in a list that the keyboard is sitting on top of.
            scrollY = (scrollY - (event.y - downY)).coerceIn(0f, maxScroll())
            downY = event.y
            invalidate()
            return
        }

        // Sliding off a button cancels it, as everywhere else on the keyboard.
        val inFooter = event.y >= height - footerHeight()
        if (pressedBackspace && (!inFooter || event.x < width - backButtonWidth())) {
            removeCallbacks(backspaceRepeat)
            pressedBackspace = false
            invalidate()
        }
        if (pressedBack && (!inFooter || event.x >= backButtonWidth())) {
            pressedBack = false
            invalidate()
        }
        if (pressedTab >= 0 && tabAt(event.x) != pressedTab) {
            pressedTab = -1
            invalidate()
        }
        if (pressedIndex >= 0 && positionAt(event.x, event.y) != pressedIndex) {
            removeCallbacks(longPress)
            pressedIndex = -1
            invalidate()
        }
    }

    private fun onUp(event: MotionEvent) {
        removeCallbacks(longPress)

        if (popupIndex >= 0) {
            val catalogue = data
            val tone = popupTone
            val entry = popupIndex
            popupIndex = -1
            popupPosition = -1
            popupTone = EmojiData.TONE_DEFAULT
            invalidate()
            // TONE_DEFAULT is a choice like any other here: it is how the user goes back to the
            // untoned emoji once a tone has been set.
            if (catalogue != null && tone in EmojiData.TONE_DEFAULT until EmojiData.TONE_COUNT) {
                listener?.onSkinTonePicked(tone)
                listener?.onEmojiPicked(catalogue.toned(entry, tone))
            }
            releaseTracker()
            return
        }

        if (scrolling) {
            fling()
            releaseTracker()
            scrolling = false
            return
        }

        val tab = pressedTab
        val back = pressedBack
        val backspace = pressedBackspace
        val position = pressedIndex
        cancelTouch()

        when {
            tab == searchTab() -> {
                announceForAccessibility("Search emoji")
                listener?.onEmojiSearchRequested()
            }

            tab in 0 until tabCount() -> {
                selectedTab = tab
                invalidatePage()
                resetScroll()
                refreshAccessibilityDescription()
                announceForAccessibility(tabDescription(tab))
                invalidate()
            }

            back -> {
                announceForAccessibility("Back to keyboard")
                listener?.onEmojiPanelClosed()
            }
            // The backspace already fired on the way down, and kept firing for as long as it was
            // held, so there is nothing left for it to do here.
            backspace -> Unit
            position >= 0 -> page().getOrNull(position)?.let {
                announceForAccessibility("Emoji $it")
                listener?.onEmojiPicked(it)
            }
        }
    }

    private fun fling() {
        val tracker = velocityTracker ?: return
        tracker.computeCurrentVelocity(1000, maxFlingVelocity)
        val velocity = -tracker.yVelocity
        if (abs(velocity) < dp(MIN_FLING_DP_PER_S)) return

        scroller.fling(
            0, scrollY.roundToInt(),
            0, velocity.roundToInt(),
            0, 0,
            0, maxScroll().roundToInt(),
        )
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        scrollY = scroller.currY.toFloat().coerceIn(0f, maxScroll())
        postInvalidateOnAnimation()
    }

    private fun cancelTouch() {
        removeCallbacks(longPress)
        removeCallbacks(backspaceRepeat)
        pressedIndex = -1
        pressedTab = -1
        pressedBack = false
        pressedBackspace = false
        releaseTracker()
        invalidate()
    }

    private fun releaseTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun tabAt(x: Float): Int {
        val count = tabCount()
        if (count == 0) return -1
        val tab = (x / (width / count.toFloat())).toInt()
        return if (tab in 0 until count) tab else -1
    }

    /** Tone under a touch in the popup row, [EmojiData.TONE_DEFAULT] over its leading untoned slot. */
    private fun toneAt(x: Float): Int {
        if (!popupRect.contains(x, popupRect.centerY())) return popupTone
        val slot = popupRect.width() / POPUP_SLOTS
        val position = ((x - popupRect.left) / slot).toInt().coerceIn(0, POPUP_SLOTS - 1)
        return position - 1
    }

    /** Grid position under a touch, or -1 when it lands outside the grid or past the last cell. */
    private fun positionAt(x: Float, y: Float): Int {
        val top = gridTop()
        if (y < top || y >= top + gridHeight()) return -1

        val cell = cellSize()
        val column = (x / cell).toInt()
        if (column !in 0 until columns()) return -1
        val row = ((y - top + scrollY) / cell).toInt()

        val position = row * columns() + column
        return if (position in page().indices) position else -1
    }

    // endregion

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(longPress)
        removeCallbacks(backspaceRepeat)
        releaseTracker()
    }

    /**
     * Returns the picker to how it should look when it opens: top of the page, no popup, and on
     * the recents tab whenever there is anything in it.
     */
    fun reset() {
        popupIndex = -1
        popupPosition = -1
        popupTone = EmojiData.TONE_DEFAULT
        selectedTab = if (recents.isEmpty()) FIRST_CATEGORY_TAB else RECENTS_TAB
        invalidatePage()
        cancelTouch()
        resetScroll()
        refreshAccessibilityDescription()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.view.View"
        info.isFocusable = true
        info.contentDescription = accessibilityDescription()
    }

    private fun refreshAccessibilityDescription() {
        contentDescription = accessibilityDescription()
    }

    private fun tabDescription(tab: Int): String = when {
        tab == RECENTS_TAB -> "Recent emoji"
        tab == searchTab() -> "Search emoji"
        else -> "Emoji category ${data?.categories?.getOrNull(tab - 1).orEmpty()}"
    }

    private fun accessibilityDescription(): String {
        val pageDescription = when {
            data == null -> "Emoji picker loading"
            selectedTab == RECENTS_TAB -> "Recent emoji"
            selectedTab == searchTab() -> "Emoji search"
            else -> tabDescription(selectedTab)
        }
        return "$pageDescription. Search emoji tab. ABC returns to keyboard. Backspace deletes emoji"
    }

    private companion object {
        const val TAB_HEIGHT_DP = 48f
        const val FOOTER_HEIGHT_DP = 52f

        /** Roughly a thumb's width, which sets how many columns fit. */
        const val CELL_DP = 48f
        const val MIN_COLUMNS = 6

        const val EMOJI_SP = 24f
        const val TAB_ICON_SP = 17f

        /** The five skin tones, plus the untoned form the row leads with. */
        const val POPUP_SLOTS = EmojiData.TONE_COUNT + 1

        const val BACK_BUTTON_FRACTION = 0.2f
        const val LONG_PRESS_MS = 350L

        /** Long enough that a normal tap never repeats, then accelerating to a comfortable rate. */
        const val FIRST_REPEAT_MS = 400L
        const val MIN_REPEAT_MS = 55L
        const val REPEAT_DECAY = 0.82f

        /** Below this a fling is indistinguishable from letting go mid-drag. */
        const val MIN_FLING_DP_PER_S = 80f

        const val RECENTS_TAB = 0
        const val FIRST_CATEGORY_TAB = 1

        const val BACK_LABEL = "ABC"
        const val EMPTY_RECENTS = "Emoji you pick will show up here"
        const val EMPTY_CATEGORY = "No emoji available"
        const val RECENTS_ICON = "🕐"
        const val SEARCH_ICON = "⌕"

        /** Keyed by the short category names the build script writes into the asset. */
        val TAB_ICONS = mapOf(
            "Smileys" to "😀",
            "People" to "👋",
            "Nature" to "🐱",
            "Food" to "🍕",
            "Travel" to "✈️",
            "Activities" to "⚽",
            "Objects" to "💡",
            "Symbols" to "❤️",
            "Flags" to "🏁",
        )
    }
}
