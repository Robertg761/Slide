package com.slide.app

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.slide.core.settings.KeyboardSettings
import com.slide.core.theme.Themes
import com.slide.ime.view.KeyboardSettingsPanelView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real panel actions must preserve settings changed elsewhere while their disk write waits. */
@RunWith(AndroidJUnit4::class)
class KeyboardSettingsPanelTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val newerSettings = KeyboardSettings(
        themeId = Themes.ID_DARK,
        keyHeightScale = 0.9f,
        incognitoModeEnabled = true,
        learnedDataClearEpoch = 17,
        emojiSkinTone = 4,
        updateChecksEnabled = true,
        includeAlphaUpdates = true,
        voiceModelId = "another-model",
    )

    @Test
    fun themeChipPreservesNewerPrivacyAndAppPreferences() = onMainThread {
        val changes = mutableListOf<(KeyboardSettings) -> KeyboardSettings>()
        val panel = panelRecording(changes)

        assertTrue(panel.control("Light theme").performClick())

        assertEquals(Themes.ID_LIGHT, panel.settings.themeId)
        assertEquals(1, changes.size)
        assertEquals(newerSettings.copy(themeId = Themes.ID_LIGHT), changes.single()(newerSettings))
    }

    @Test
    fun queuedSwitchesApplyTheirFieldsToTheLatestStoredSettings() = onMainThread {
        val changes = mutableListOf<(KeyboardSettings) -> KeyboardSettings>()
        val panel = panelRecording(changes)

        assertTrue(panel.control("Haptic feedback").performClick())
        assertTrue(panel.control("Auto-capitalization").performClick())

        assertEquals(2, changes.size)
        val persisted = changes.fold(newerSettings) { current, change -> change(current) }
        assertEquals(newerSettings.copy(hapticEnabled = false, autoCapitalize = false), persisted)
    }

    @Test
    fun accessibilitySliderChangePreservesUnrelatedNewerSettings() = onMainThread {
        val changes = mutableListOf<(KeyboardSettings) -> KeyboardSettings>()
        val panel = panelRecording(changes)
        val height = panel.control("Keyboard height") as SeekBar

        assertTrue(height.performAccessibilityAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, height.max.toFloat())
            },
        ))

        assertEquals(1, changes.size)
        assertEquals(newerSettings.copy(keyHeightScale = 1.4f), changes.single()(newerSettings))
    }

    private fun panelRecording(
        changes: MutableList<(KeyboardSettings) -> KeyboardSettings>,
    ) = KeyboardSettingsPanelView(instrumentation.targetContext).apply {
        settings = KeyboardSettings()
        listener = object : KeyboardSettingsPanelView.Listener {
            override fun onKeyboardSettingsDismissed() = Unit
            override fun onKeyboardSettingsChanged(change: (KeyboardSettings) -> KeyboardSettings) {
                changes += change
            }
        }
    }

    private fun View.control(description: String): View = descendants()
        .first { it.contentDescription?.toString() == description }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
        }
    }

    private fun onMainThread(test: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(test) }
        // Report an assertion on the test thread instead of crashing the app's main looper.
        requireNotNull(result).getOrThrow()
    }
}
