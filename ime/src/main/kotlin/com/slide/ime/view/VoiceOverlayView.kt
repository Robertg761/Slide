package com.slide.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AnimationUtils
import com.slide.asr.VoiceInput
import com.slide.core.theme.KeyboardTheme
import com.slide.core.theme.Themes
import kotlin.math.PI
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
 * The microphone button and the waveform are the same thing in two states: a still circle before
 * and after, a moving wave while the microphone is open. Nothing else on screen moves, so movement
 * on its own is enough to say which of the two is happening.
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
            if (value != VoiceInput.State.Listening) level = 0f
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

    /**
     * Smoothed microphone level driving the height of the wave.
     *
     * Raw levels from a 100ms window jitter enough to look like a fault rather than a voice, so
     * each update pulls the drawn value part of the way towards the new one.
     */
    private var level = 0f

    fun setLevel(value: Float) {
        level += (value.coerceIn(0f, 1f) - level) * LEVEL_SMOOTHING
        // No invalidate: the animation is already redrawing every frame while listening, and
        // outside that state there is no wave for a level to change.
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val wavePath = Path()

    private var cancelBounds = floatArrayOf(0f, 0f, 0f, 0f)
    private var pressedCancel = false

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
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
    private var waveStartMs = 0L

    private val frame = object : Runnable {
        override fun run() {
            if (state != VoiceInput.State.Listening) return
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun syncAnimation() {
        removeCallbacks(frame)
        if (state != VoiceInput.State.Listening || !isAttachedToWindow) return
        waveStartMs = AnimationUtils.currentAnimationTimeMillis()
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

    /**
     * Draws the microphone level as a band of travelling waves.
     *
     * Three of them, at frequencies and speeds that share no common multiple, so the crossings
     * never settle into a repeating pattern the eye can latch onto — the difference between
     * something that looks alive and something that looks like a screensaver. One of the three runs
     * backwards, which is what makes them appear to fold through each other rather than slide.
     *
     * The waves keep a little height at silence. A dead flat line reads as a broken microphone,
     * whereas a shallow ripple reads as an open one waiting for a voice.
     */
    private fun drawWave(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val elapsed = (AnimationUtils.currentAnimationTimeMillis() - waveStartMs) / 1000f
        val amplitude = radius * (WAVE_IDLE_HEIGHT + level * (1f - WAVE_IDLE_HEIGHT))
        val halfSpan = min(centerX, radius * WAVE_SPAN)

        wavePaint.strokeWidth = dp(3f)
        wavePaint.color = keyboardTheme.accentBackground

        for (wave in WAVES) {
            wavePath.reset()
            var x = centerX - halfSpan
            while (x <= centerX + halfSpan) {
                // Position along the band, -1 at the left end and 1 at the right.
                val position = (x - centerX) / halfSpan
                // Tapers both ends to nothing so the band fades into the panel instead of being
                // cut off at an arbitrary edge.
                val envelope = sin(((position + 1f) / 2f) * PI).toFloat()
                val angle = position * wave.frequency * PI.toFloat() - elapsed * wave.speed
                val y = centerY + sin(angle.toDouble()).toFloat() *
                    amplitude * wave.height * envelope * envelope

                if (x == centerX - halfSpan) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
                x += WAVE_STEP_PX
            }

            wavePaint.alpha = wave.alpha
            canvas.drawPath(wavePath, wavePaint)
        }
    }

    private data class Wave(
        /** Half-cycles across the band. */
        val frequency: Float,
        /** Radians per second; negative travels the other way. */
        val speed: Float,
        /** Share of the full amplitude. */
        val height: Float,
        val alpha: Int,
    )

    // endregion

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(keyboardTheme.background)

        val centerX = width / 2f
        val centerY = height * MIC_CENTRE_FRACTION
        val baseRadius = min(width, height) * MIC_RADIUS_FRACTION

        if (state == VoiceInput.State.Listening) {
            drawWave(canvas, centerX, centerY, baseRadius)
        } else {
            fillPaint.color = keyboardTheme.accentBackground
            fillPaint.alpha = 255
            canvas.drawCircle(centerX, centerY, baseRadius, fillPaint)

            fillPaint.color = keyboardTheme.accentText
            MicGlyph.draw(canvas, fillPaint, centerX, centerY, baseRadius)
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

        val textWidth = textPaint.measureText(label)
        val padding = dp(18f)
        val centerX = width / 2f
        val centerY = height * CANCEL_CENTRE_FRACTION
        val halfHeight = dp(18f)

        cancelBounds = floatArrayOf(
            centerX - textWidth / 2f - padding,
            centerY - halfHeight,
            centerX + textWidth / 2f + padding,
            centerY + halfHeight,
        )

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

    private fun statusText(): String = errorText ?: when (state) {
        VoiceInput.State.Preparing -> "Getting ready…"
        VoiceInput.State.Listening -> "Listening — tap when you're done"
        VoiceInput.State.Transcribing -> "Transcribing…"
        VoiceInput.State.Idle -> "Tap to close"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedCancel = isInCancel(event.x, event.y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (pressedCancel && !isInCancel(event.x, event.y)) {
                    pressedCancel = false
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedCancel = false
                invalidate()
                return true
            }
        }

        val cancelled = pressedCancel && isInCancel(event.x, event.y)
        pressedCancel = false
        invalidate()

        // Tapping anywhere else finishes the dictation. While transcribing there is nothing to
        // finish, so only Cancel does anything.
        if (cancelled) {
            announceForAccessibility("Cancel voice typing")
            listener?.onVoiceDismissed(committed = false)
        } else if (state == VoiceInput.State.Listening) {
            announceForAccessibility("Finish voice typing")
            listener?.onVoiceDismissed(committed = true)
        } else if (state == VoiceInput.State.Idle) {
            announceForAccessibility("Close voice typing")
            listener?.onVoiceDismissed(committed = false)
        }
        return true
    }

    private fun isInCancel(x: Float, y: Float): Boolean =
        x in cancelBounds[0]..cancelBounds[2] && y in cancelBounds[1]..cancelBounds[3]

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.view.View"
        info.isFocusable = true
        info.contentDescription = accessibilityDescription()
    }

    private fun refreshAccessibilityDescription() {
        contentDescription = accessibilityDescription()
    }

    private fun accessibilityDescription(): String =
        "Voice typing. ${statusText()}. Cancel button. Tap the microphone area to finish when listening"

    private companion object {
        const val MIC_CENTRE_FRACTION = 0.38f
        const val MIC_RADIUS_FRACTION = 0.16f
        const val CANCEL_CENTRE_FRACTION = 0.82f

        const val LEVEL_SMOOTHING = 0.35f

        /** How far either side of centre the band reaches, as a multiple of the mic radius. */
        const val WAVE_SPAN = 3.2f

        /** Amplitude at silence, as a share of the full height, so the line still breathes. */
        const val WAVE_IDLE_HEIGHT = 0.12f

        /** Sampling interval along the band. Three pixels is below the eye's notice at this size. */
        const val WAVE_STEP_PX = 3f

        val WAVES = listOf(
            Wave(frequency = 2.0f, speed = 3.1f, height = 1.0f, alpha = 255),
            Wave(frequency = 3.0f, speed = -2.3f, height = 0.7f, alpha = 150),
            Wave(frequency = 4.5f, speed = 4.3f, height = 0.45f, alpha = 90),
        )
    }
}
