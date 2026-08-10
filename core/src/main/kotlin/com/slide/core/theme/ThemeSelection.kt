package com.slide.core.theme

/** Pure selection policy kept separate from Android colour construction for JVM coverage. */
internal object ThemeSelection {
    fun presetId(
        requestedId: String,
        knownIds: Set<String>,
        systemInDarkMode: Boolean,
        followSystem: Boolean,
    ): String = when {
        requestedId in knownIds -> requestedId
        followSystem && systemInDarkMode -> Themes.ID_DARK
        else -> Themes.ID_LIGHT
    }
}
