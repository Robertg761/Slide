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
        val strokeCap = paint.strokeCap
        val strokeJoin = paint.strokeJoin

        // A small filled capsule is legible at toolbar size; the surrounding cradle stays light
        // so the complete glyph has the same optical weight as the other action icons.
        val halfWidth = radius * 0.25f
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawRoundRect(
            centerX - halfWidth,
            centerY - radius * 0.68f,
            centerX + halfWidth,
            centerY + radius * 0.08f,
            halfWidth,
            halfWidth,
            paint,
        )

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.12f
        val arc = radius * 0.52f
        canvas.drawArc(
            centerX - arc,
            centerY - radius * 0.24f,
            centerX + arc,
            centerY + radius * 0.60f,
            0f, 180f, false, paint,
        )
        canvas.drawLine(centerX, centerY + radius * 0.56f, centerX, centerY + radius * 0.76f, paint)
        canvas.drawLine(
            centerX - radius * 0.28f,
            centerY + radius * 0.76f,
            centerX + radius * 0.28f,
            centerY + radius * 0.76f,
            paint,
        )

        paint.style = style
        paint.strokeWidth = strokeWidth
        paint.strokeCap = strokeCap
        paint.strokeJoin = strokeJoin
    }
}
