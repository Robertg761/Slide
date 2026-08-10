package com.slide.core.theme

import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.core.graphics.ColorUtils

/**
 * Built-in themes.
 *
 * Most presets are derived from a single seed colour by [solidTheme], so adding a new colour theme
 * is one line rather than eighteen hand-picked hex values.
 */
object Themes {

    const val ID_LIGHT = "light"
    const val ID_DARK = "dark"
    const val ID_DYNAMIC = "dynamic"

    val Light = KeyboardTheme(
        id = ID_LIGHT,
        name = "Light",
        isDark = false,
        background = 0xFFEEEFF2.toInt(),
        keyBackground = 0xFFFFFFFF.toInt(),
        specialKeyBackground = 0xFFDADCE0.toInt(),
        accentBackground = 0xFF1558B0.toInt(),
        keyText = 0xFF202124.toInt(),
        specialKeyText = 0xFF202124.toInt(),
        accentText = 0xFFFFFFFF.toInt(),
        hintText = 0xFF5F6368.toInt(),
        keyBorder = 0x1A000000,
        keyPressedOverlay = 0x1F000000,
        gestureTrail = 0xFF1558B0.toInt(),
        popupBackground = 0xFFFFFFFF.toInt(),
        popupText = 0xFF202124.toInt(),
        popupSelectedBackground = 0xFF1558B0.toInt(),
        suggestionText = 0xFF3C4043.toInt(),
        suggestionHighlightText = 0xFF1558B0.toInt(),
        divider = 0x1F000000,
    )

    val Dark = KeyboardTheme(
        id = ID_DARK,
        name = "Dark",
        isDark = true,
        background = 0xFF202124.toInt(),
        keyBackground = 0xFF3C4043.toInt(),
        specialKeyBackground = 0xFF2D2E31.toInt(),
        accentBackground = 0xFF8AB4F8.toInt(),
        keyText = 0xFFE8EAED.toInt(),
        specialKeyText = 0xFFE8EAED.toInt(),
        accentText = 0xFF202124.toInt(),
        hintText = 0xFF9AA0A6.toInt(),
        keyBorder = 0x1FFFFFFF,
        keyPressedOverlay = 0x33FFFFFF,
        gestureTrail = 0xFF8AB4F8.toInt(),
        popupBackground = 0xFF3C4043.toInt(),
        popupText = 0xFFE8EAED.toInt(),
        popupSelectedBackground = 0xFF8AB4F8.toInt(),
        suggestionText = 0xFFE8EAED.toInt(),
        suggestionHighlightText = 0xFF8AB4F8.toInt(),
        divider = 0x1FFFFFFF,
    )

    /** Colour presets, in the order they appear in the theme picker. */
    val presets: List<KeyboardTheme> = listOf(
        Light,
        Dark,
        solidTheme("cobalt", "Cobalt", 0xFF1B3A6B.toInt(), dark = true),
        solidTheme("forest", "Forest", 0xFF1E3B2F.toInt(), dark = true),
        solidTheme("plum", "Plum", 0xFF3B2340.toInt(), dark = true),
        solidTheme("slate", "Slate", 0xFF2B2F33.toInt(), dark = true),
        solidTheme("sand", "Sand", 0xFFE7E0D4.toInt(), dark = false),
        solidTheme("rose", "Rose", 0xFFF3DDE0.toInt(), dark = false),
        solidTheme("mint", "Mint", 0xFFDCEDE4.toInt(), dark = false),
    )

    fun byId(id: String): KeyboardTheme? = presets.firstOrNull { it.id == id }

    /**
     * Resolves the theme to draw with, honouring dynamic colour and system dark mode.
     *
     * @param themeId the user's chosen theme id, or [ID_DYNAMIC] for Material You
     * @param systemInDarkMode whether the system is currently in dark mode
     * @param followSystem legacy fallback for an unknown id. Explicit preset selections always win.
     */
    fun resolve(
        context: Context,
        themeId: String,
        systemInDarkMode: Boolean,
        followSystem: Boolean,
    ): KeyboardTheme = if (themeId == ID_DYNAMIC) {
        dynamic(context, systemInDarkMode)
            ?: if (systemInDarkMode) Dark else Light
    } else {
        resolvePreset(themeId, systemInDarkMode, followSystem)
    }

    /** Pure preset branch, split out so explicit-selection semantics stay JVM-testable. */
    internal fun resolvePreset(
        themeId: String,
        systemInDarkMode: Boolean,
        followSystem: Boolean,
    ): KeyboardTheme {
        val resolvedId = ThemeSelection.presetId(
            requestedId = themeId,
            knownIds = presets.mapTo(linkedSetOf(), KeyboardTheme::id),
            systemInDarkMode = systemInDarkMode,
            followSystem = followSystem,
        )
        return byId(resolvedId) ?: Light
    }

    /**
     * Material You theme built from the system wallpaper palette. Returns null below API 31,
     * where the system colour resources do not exist.
     */
    fun dynamic(context: Context, dark: Boolean): KeyboardTheme? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val res = context.resources
        fun c(id: Int) = res.getColor(id, context.theme)

        return if (dark) {
            KeyboardTheme(
                id = ID_DYNAMIC,
                name = "Dynamic",
                isDark = true,
                background = c(android.R.color.system_neutral1_900),
                keyBackground = c(android.R.color.system_neutral2_700),
                specialKeyBackground = c(android.R.color.system_neutral1_800),
                accentBackground = c(android.R.color.system_accent1_200),
                keyText = c(android.R.color.system_neutral1_50),
                specialKeyText = c(android.R.color.system_neutral1_50),
                accentText = c(android.R.color.system_accent1_900),
                hintText = c(android.R.color.system_neutral2_300),
                keyBorder = 0x1FFFFFFF,
                keyPressedOverlay = 0x33FFFFFF,
                gestureTrail = c(android.R.color.system_accent1_200),
                popupBackground = c(android.R.color.system_neutral2_700),
                popupText = c(android.R.color.system_neutral1_50),
                popupSelectedBackground = c(android.R.color.system_accent1_200),
                suggestionText = c(android.R.color.system_neutral1_50),
                suggestionHighlightText = c(android.R.color.system_accent1_200),
                divider = 0x1FFFFFFF,
            )
        } else {
            KeyboardTheme(
                id = ID_DYNAMIC,
                name = "Dynamic",
                isDark = false,
                background = c(android.R.color.system_neutral2_100),
                keyBackground = c(android.R.color.system_neutral1_50),
                specialKeyBackground = c(android.R.color.system_neutral2_200),
                accentBackground = c(android.R.color.system_accent1_600),
                keyText = c(android.R.color.system_neutral1_900),
                specialKeyText = c(android.R.color.system_neutral1_900),
                accentText = c(android.R.color.system_accent1_0),
                hintText = c(android.R.color.system_neutral2_600),
                keyBorder = 0x1A000000,
                keyPressedOverlay = 0x1F000000,
                gestureTrail = c(android.R.color.system_accent1_600),
                popupBackground = c(android.R.color.system_neutral1_50),
                popupText = c(android.R.color.system_neutral1_900),
                popupSelectedBackground = c(android.R.color.system_accent1_600),
                suggestionText = c(android.R.color.system_neutral1_800),
                suggestionHighlightText = c(android.R.color.system_accent1_600),
                divider = 0x1F000000,
            )
        }
    }

    /**
     * Derives a full theme from one seed background colour by shifting lightness, so presets stay
     * internally consistent instead of drifting apart as they are hand-tuned.
     */
    private fun solidTheme(id: String, name: String, seed: Int, dark: Boolean): KeyboardTheme {
        val keyBg = seed.shiftLightness(if (dark) +0.07f else +0.06f)
        val specialBg = seed.shiftLightness(if (dark) -0.03f else -0.05f)
        val accent = seed.saturate(0.55f).withLightness(if (dark) 0.72f else 0.32f)
        val text = if (dark) 0xFFECEFF1.toInt() else 0xFF1F2226.toInt()
        val hint = ColorUtils.setAlphaComponent(text, 0x99)

        return KeyboardTheme(
            id = id,
            name = name,
            isDark = dark,
            background = seed,
            keyBackground = keyBg,
            specialKeyBackground = specialBg,
            accentBackground = accent,
            keyText = text,
            specialKeyText = text,
            accentText = if (dark) 0xFF15181B.toInt() else 0xFFFFFFFF.toInt(),
            hintText = hint,
            keyBorder = if (dark) 0x1FFFFFFF else 0x1A000000,
            keyPressedOverlay = if (dark) 0x33FFFFFF else 0x1F000000,
            gestureTrail = accent,
            popupBackground = keyBg,
            popupText = text,
            popupSelectedBackground = accent,
            suggestionText = text,
            suggestionHighlightText = accent,
            divider = if (dark) 0x1FFFFFFF else 0x1F000000,
        )
    }

    private fun Int.shiftLightness(delta: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(this, hsl)
        hsl[2] = (hsl[2] + delta).coerceIn(0f, 1f)
        return ColorUtils.HSLToColor(hsl) or (Color.alpha(this) shl 24)
    }

    private fun Int.withLightness(lightness: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(this, hsl)
        hsl[2] = lightness.coerceIn(0f, 1f)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun Int.saturate(saturation: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(this, hsl)
        hsl[1] = saturation.coerceIn(0f, 1f)
        return ColorUtils.HSLToColor(hsl)
    }
}
