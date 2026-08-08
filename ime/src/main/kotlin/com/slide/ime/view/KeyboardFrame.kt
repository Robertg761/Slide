package com.slide.ime.view

import android.content.Context
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.core.view.WindowInsetsCompat

/**
 * Holds the keys and the panels that cover them.
 *
 * Two jobs, both about height.
 *
 * It takes its own height from the keys alone and gives every other child exactly that height. A
 * plain [FrameLayout] measuring `wrap_content` would instead grow to its tallest child, and a panel
 * asking to fill its parent measures against the whole screen — so the emoji picker would open at
 * full height and push the app's content off the top, which is the one thing putting the panels over
 * the keys was meant to avoid.
 *
 * It also reserves the navigation bar below all of them, so the picker's footer clears the gesture
 * pill for the same reason the bottom key row does. That belongs here rather than in [KeyboardView]
 * because it is a property of the window's bottom edge, not of the keys.
 */
class KeyboardFrame(context: Context) : FrameLayout(context) {

    private var navigationInset = 0

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val compat = WindowInsetsCompat.toWindowInsetsCompat(insets, this)
        // tappableElement is the region the system intercepts touches in, which on One UI is taller
        // than the navigation bar itself because of the IME switcher and hide buttons. Taking the
        // larger of the two keeps the bottom row fully ours to touch.
        val bottom = maxOf(
            compat.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
            compat.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom,
        )
        if (bottom != navigationInset) {
            navigationInset = bottom
            requestLayout()
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val keys = getChildAt(0)
        if (keys == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        measureChild(keys, widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val keysHeight = keys.measuredHeight

        val exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val exactHeight = MeasureSpec.makeMeasureSpec(keysHeight, MeasureSpec.EXACTLY)
        for (index in 1 until childCount) {
            val panel = getChildAt(index)
            if (panel.visibility != GONE) panel.measure(exactWidth, exactHeight)
        }

        // The children are laid out from the top at the height measured above, so the inset is left
        // over as empty space at the bottom rather than being something any of them can draw into.
        setMeasuredDimension(width, keysHeight + navigationInset)
    }
}
