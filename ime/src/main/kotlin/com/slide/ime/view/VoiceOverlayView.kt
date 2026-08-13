package com.slide.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AnimationUtils
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.slide.asr.VoiceInput
import com.slide.core.theme.KeyboardTheme
import com.slide.core.theme.Themes
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * What the keyboard turns into while the user is dictating.
 *
 * It covers the keys rather than sitting beside them, for two reasons: there is nothing useful to
 * tap while speaking, and a microphone that stays open behind a keyboard the user has gone back to
 * typing on is exactly the sort of thing that makes people uninstall a keyboard. When this is
 * visible, Slide is listening; when it is not, it is not.
 *
 * The microphone stays visually anchored through every state. Speech drives the surrounding bars
 * while listening; model loading and inference use a rotating arc, so the panel never appears
 * frozen while it is doing work.
 */
class VoiceOverlayView(context: Context) : View(context) {

    fun interface Listener {
        /** [committed] is false when the user backed out and the audio should be discarded. */
        fun onVoiceDismissed(committed: Boolean)
    }

    var listener: Listener? = null

    var keyboardTheme: KeyboardTheme = Themes.Light
        set(value) {
            field = value
            invalidate()
        }

    var state: VoiceInput.State = VoiceInput.State.Idle
        set(value) {
            field = value
            if (value != VoiceInput.State.Listening) levelDynamics.reset()
            syncAnimation()
            refreshAccessibilityDescription()
            invalidate()
        }

    /** Overrides the status line when something has gone wrong. */
    var errorText: String? = null
        set(value) {
            field = value
            refreshAccessibilityDescription()
            invalidate()
        }

    private val levelDynamics = VoiceLevelDynamics()

    fun setLevel(value: Float) {
        levelDynamics.accept(value)
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arcBounds = RectF()

    private var cancelBounds = floatArrayOf(0f, 0f, 0f, 0f)
    private var pressedCancel = false
    private var pressStartedOnCancel = false

    /**
     * The finger that started the press, and the only one whose lift ends the dictation.
     *
     * Ending dictation is destructive either way — commit or discard — so a second contact
     * (the hand steadying the phone, a palm) must not be able to do it.
     */
    private var pressPointerId: Int? = null

    private val accessibilityHelper = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int =
            if (isInCancel(x, y)) A11Y_CANCEL else A11Y_MAIN

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            virtualViewIds += A11Y_MAIN
            virtualViewIds += A11Y_CANCEL
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat,
        ) {
            if (virtualViewId == A11Y_CANCEL) {
                updateCancelBounds()
                node.className = "android.widget.Button"
                node.contentDescription = "Cancel voice typing"
                node.isClickable = true
                node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
                node.setBoundsInParent(
                    Rect(cancelBounds[0].toInt(), cancelBounds[1].toInt(), cancelBounds[2].toInt(), cancelBounds[3].toInt()),
                )
                return
            }

            val actionable = state == VoiceInput.State.Listening || state == VoiceInput.State.Idle
            node.className = if (actionable) "android.widget.Button" else "android.widget.TextView"
            node.contentDescription = when (state) {
                VoiceInput.State.Listening -> "Finish voice typing. ${statusText()}"
                VoiceInput.State.Idle -> "Close voice typing. ${statusText()}"
                else -> statusText()
            }
            node.isClickable = actionable
            if (actionable) node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.setBoundsInParent(Rect(0, 0, width, (height * 0.72f).toInt()))
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?,
        ): Boolean {
            if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
            if (virtualViewId == A11Y_CANCEL) {
                listener?.onVoiceDismissed(committed = false)
                return true
            }
            return when (state) {
                VoiceInput.State.Listening -> {
                    listener?.onVoiceDismissed(committed = true)
                    true
                }
                VoiceInput.State.Idle -> {
                    listener?.onVoiceDismissed(committed = false)
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

    // region The wave

    /**
     * Wall-clock start of the current listening spell, in the animation timebase.
     *
     * The wave's phase comes from elapsed time rather than a per-frame counter so that it travels
     * at the same speed whether the view is being drawn at 60Hz or 120Hz, and does not lurch when
     * a frame is dropped.
     */
    private var animationStartMs = 0L
    private var previousFrameMs = 0L

    private val frame = object : Runnable {
        override fun run() {
            if (!state.animates) return
            val now = AnimationUtils.currentAnimationTimeMillis()
            if (state == VoiceInput.State.Listening) {
                levelDynamics.advance((now - previousFrameMs).coerceIn(0L, MAX_FRAME_GAP_MS))
            }
            previousFrameMs = now
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun syncAnimation() {
        removeCallbacks(frame)
        if (!state.animates || !isAttachedToWindow) return
        animationStartMs = AnimationUtils.currentAnimationTimeMillis()
        previousFrameMs = animationStartMs
        postOnAnimation(frame)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(frame)
    }

    private fun drawListeningIndicator(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val elapsed = (AnimationUtils.currentAnimationTimeMillis() - animationStartMs) / 1000f
        val level = levelDynamics.level

        // A translucent halo makes volume readable even in peripheral vision. It grows from a
        // steady open-microphone pulse, then the bars provide the detailed speech response.
        fillPaint.color = keyboardTheme.accentBackground
        fillPaint.alpha = (42 + level * 64).toInt()
        val pulse = (sin(elapsed * PI.toFloat() * 1.8f) + 1f) * 0.5f
        canvas.drawCircle(centerX, centerY, radius * (1.05f + level * 0.28f + pulse * 0.04f), fillPaint)

        indicatorPaint.color = keyboardTheme.accentBackground
        indicatorPaint.alpha = 235
        indicatorPaint.strokeWidth = dp(4f)
        val halfSpan = min(centerX - dp(16f), radius * BAR_SPAN)
        val spacing = halfSpan * 2f / (BAR_COUNT - 1)
        for (index in 0 until BAR_COUNT) {
            val position = index.toFloat() / (BAR_COUNT - 1) * 2f - 1f
            val envelope = 0.4f + 0.6f * cos(position * PI.toFloat() / 2f)
            val motion = 0.78f + 0.22f * sin(elapsed * 5.2f + index * 1.37f)
            val halfHeight = radius * (BAR_IDLE_HEIGHT + level * envelope * motion)
            val x = centerX - halfSpan + spacing * index
            canvas.drawLine(x, centerY - halfHeight, x, centerY + halfHeight, indicatorPaint)
        }

        drawMicrophoneButton(canvas, centerX, centerY, radius * 0.7f)
    }

    private fun drawProcessingIndicator(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        drawMicrophoneButton(canvas, centerX, centerY, radius * 0.78f)
        val elapsed = (AnimationUtils.currentAnimationTimeMillis() - animationStartMs) / 1000f
        indicatorPaint.style = Paint.Style.STROKE
        indicatorPaint.strokeWidth = dp(4f)
        indicatorPaint.color = keyboardTheme.accentBackground
        indicatorPaint.alpha = 230
        val arcRadius = radius * 1.12f
        arcBounds.set(centerX - arcRadius, centerY - arcRadius, centerX + arcRadius, centerY + arcRadius)
        canvas.drawArc(arcBounds, elapsed * 210f, 104f, false, indicatorPaint)
    }

    private fun drawMicrophoneButton(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        fillPaint.color = keyboardTheme.accentBackground
        fillPaint.alpha = 255
        canvas.drawCircle(centerX, centerY, radius, fillPaint)
        fillPaint.color = keyboardTheme.accentText
        MicGlyph.draw(canvas, fillPaint, centerX, centerY, radius)
    }

    // endregion

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(keyboardTheme.background)

        val centerX = width / 2f
        val centerY = height * MIC_CENTRE_FRACTION
        val baseRadius = min(width, height) * MIC_RADIUS_FRACTION

        when (state) {
            VoiceInput.State.Listening -> drawListeningIndicator(canvas, centerX, centerY, baseRadius)
            VoiceInput.State.Preparing,
            VoiceInput.State.Transcribing,
            -> drawProcessingIndicator(canvas, centerX, centerY, baseRadius)
            VoiceInput.State.Idle -> drawMicrophoneButton(canvas, centerX, centerY, baseRadius)
        }

        textPaint.color = keyboardTheme.keyText
        textPaint.textSize = sp(15f)
        textPaint.typeface = Typeface.DEFAULT
        // Placed off the circle's edge whether or not the circle is what is drawn, so that swapping
        // between the mic and the wave does not move the text.
        canvas.drawText(statusText(), centerX, centerY + baseRadius + dp(36f), textPaint)

        drawCancel(canvas)
    }

    private fun drawCancel(canvas: Canvas) {
        val label = "Cancel"
        textPaint.textSize = sp(14f)
        textPaint.color = keyboardTheme.suggestionText

        updateCancelBounds()
        val centerX = width / 2f
        val centerY = height * CANCEL_CENTRE_FRACTION
        val halfHeight = dp(18f)

        fillPaint.color = keyboardTheme.specialKeyBackground
        fillPaint.alpha = 255
        if (pressedCancel) {
            fillPaint.color = keyboardTheme.keyPressedOverlay
        }
        canvas.drawRoundRect(
            cancelBounds[0], cancelBounds[1], cancelBounds[2], cancelBounds[3],
            halfHeight, halfHeight, fillPaint,
        )

        val metrics = textPaint.fontMetrics
        canvas.drawText(label, centerX, centerY - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }

    private fun updateCancelBounds() {
        textPaint.textSize = sp(14f)
        val halfWidth = textPaint.measureText("Cancel") / 2f + dp(18f)
        val centerX = width / 2f
        val centerY = height * CANCEL_CENTRE_FRACTION
        val halfHeight = dp(18f)
        cancelBounds = floatArrayOf(
            centerX - halfWidth,
            centerY - halfHeight,
            centerX + halfWidth,
            centerY + halfHeight,
        )
    }

    private fun statusText(): String = errorText ?: when (state) {
        VoiceInput.State.Preparing -> "Loading offline speech…"
        VoiceInput.State.Listening -> "Listening offline · tap when you're done"
        VoiceInput.State.Transcribing -> "Transcribing on device…"
        VoiceInput.State.Idle -> "Tap to close"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressPointerId = event.getPointerId(0)
                pressedCancel = isInCancel(event.x, event.y)
                pressStartedOnCancel = pressedCancel
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val index = pressPointerId?.let(event::findPointerIndex) ?: return true
                if (index < 0) return true
                if (pressedCancel && !isInCancel(event.getX(index), event.getY(index))) {
                    pressedCancel = false
                    invalidate()
                }
                return true
            }

            // Extra fingers are not a second decision, and the deciding finger leaving while
            // another is still down is not a tap: both just clear the press.
            MotionEvent.ACTION_POINTER_DOWN -> return true

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pressPointerId) clearPress()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                clearPress()
                return true
            }

            MotionEvent.ACTION_UP -> Unit

            else -> return true
        }

        val index = event.actionIndex
        // Null once the deciding finger has already left through ACTION_POINTER_UP: whatever is
        // lifting now is a bystander, and a bystander does not finish the dictation.
        val owned = pressPointerId != null && event.getPointerId(index) == pressPointerId
        val cancelled = owned && pressStartedOnCancel && pressedCancel &&
            isInCancel(event.getX(index), event.getY(index))
        val mainActivated = owned && !pressStartedOnCancel
        clearPress()

        // Tapping anywhere else finishes the dictation. While transcribing there is nothing to
        // finish, so only Cancel does anything.
        if (cancelled) {
            announceForAccessibility("Cancel voice typing")
            listener?.onVoiceDismissed(committed = false)
        } else if (mainActivated && state == VoiceInput.State.Listening) {
            announceForAccessibility("Finish voice typing")
            listener?.onVoiceDismissed(committed = true)
        } else if (mainActivated && state == VoiceInput.State.Idle) {
            announceForAccessibility("Close voice typing")
            listener?.onVoiceDismissed(committed = false)
        }
        return true
    }

    private fun clearPress() {
        pressPointerId = null
        pressedCancel = false
        pressStartedOnCancel = false
        invalidate()
    }

    private fun isInCancel(x: Float, y: Float): Boolean =
        run {
            updateCancelBounds()
            x in cancelBounds[0]..cancelBounds[2] && y in cancelBounds[1]..cancelBounds[3]
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

    private fun accessibilityDescription(): String =
        "Voice typing. ${statusText()}. Cancel button. Tap the microphone area to finish when listening"

    private companion object {
        const val MIC_CENTRE_FRACTION = 0.38f
        const val MIC_RADIUS_FRACTION = 0.16f
        const val CANCEL_CENTRE_FRACTION = 0.82f
        const val A11Y_MAIN = 0
        const val A11Y_CANCEL = 1

        const val BAR_COUNT = 11
        const val BAR_SPAN = 3.15f
        const val BAR_IDLE_HEIGHT = 0.08f
        const val MAX_FRAME_GAP_MS = 64L
    }
}

private val VoiceInput.State.animates: Boolean
    get() = this == VoiceInput.State.Preparing ||
        this == VoiceInput.State.Listening ||
        this == VoiceInput.State.Transcribing
