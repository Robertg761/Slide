package com.slide.core.theme

import androidx.annotation.ColorInt

/**
 * The complete colour surface of the keyboard.
 *
 * Every drawn element in every panel — keys, suggestion strip, emoji picker, clipboard, voice UI —
 * resolves its colour from this one object. Nothing anywhere should hardcode a colour; that is what
 * makes "themes apply everywhere" true by construction instead of by discipline.
 */
data class KeyboardTheme(
    val id: String,
    val name: String,
    val isDark: Boolean,

    @param:ColorInt val background: Int,
    @param:ColorInt val keyBackground: Int,
    @param:ColorInt val specialKeyBackground: Int,
    @param:ColorInt val accentBackground: Int,

    @param:ColorInt val keyText: Int,
    @param:ColorInt val specialKeyText: Int,
    @param:ColorInt val accentText: Int,
    @param:ColorInt val hintText: Int,

    @param:ColorInt val keyBorder: Int,
    @param:ColorInt val keyShadow: Int,
    @param:ColorInt val keyPressedOverlay: Int,

    @param:ColorInt val gestureTrail: Int,
    @param:ColorInt val popupBackground: Int,
    @param:ColorInt val popupText: Int,
    @param:ColorInt val popupSelectedBackground: Int,

    @param:ColorInt val suggestionText: Int,
    @param:ColorInt val suggestionHighlightText: Int,
    @param:ColorInt val divider: Int,
)
