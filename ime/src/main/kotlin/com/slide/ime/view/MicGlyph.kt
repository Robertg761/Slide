package com.slide.ime.view

import android.graphics.Canvas
import android.graphics.Paint

/**
 * Draws the microphone symbol.
 *
 * Drawn rather than shipped as a vector so it takes its colour from the active theme like every
 * other painted element, and scales to whatever size the caller has rather than to a fixed asset.
 * Shared by the suggestion strip's button and the dictation overlay so the two cannot drift apart.
 */
object MicGlyph {

    /**
     * Paints a microphone centred on ([centerX], [centerY]) fitting within [radius].
     *
     * Leaves [paint]'s style and stroke width as it found them, since callers reuse one paint for
     * everything they draw.
     */
    fun draw(canvas: Canvas, paint: Paint, centerX: Float, centerY: Float, radius: Float) {
        val style = paint.style
        val strokeWidth = paint.strokeWidth

        // The capsule body.
        val halfWidth = radius * 0.34f
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(
            centerX - halfWidth,
            centerY - radius * 0.55f,
            centerX + halfWidth,
            centerY + radius * 0.10f,
            halfWidth,
            halfWidth,
            paint,
        )

        // The cradle under it, and the short stem down to the base.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.12f
        val arc = radius * 0.58f
        canvas.drawArc(
            centerX - arc, centerY - arc, centerX + arc, centerY + arc,
            0f, 180f, false, paint,
        )
        canvas.drawLine(centerX, centerY + arc * 0.72f, centerX, centerY + radius * 0.72f, paint)
        canvas.drawLine(centerX - radius * 0.27f, centerY + radius * 0.72f, centerX + radius * 0.27f, centerY + radius * 0.72f, paint)

        paint.style = style
        paint.strokeWidth = strokeWidth
    }
}
