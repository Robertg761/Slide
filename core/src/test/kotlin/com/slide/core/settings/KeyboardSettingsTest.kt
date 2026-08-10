package com.slide.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardSettingsTest {

    @Test
    fun `autocorrection is active only when both controls are enabled`() {
        assertTrue(KeyboardSettings().isAutocorrectionActive)
        assertFalse(
            KeyboardSettings(suggestionsEnabled = false, autocorrectEnabled = true)
                .isAutocorrectionActive,
        )
        assertFalse(
            KeyboardSettings(suggestionsEnabled = true, autocorrectEnabled = false)
                .isAutocorrectionActive,
        )
        assertFalse(
            KeyboardSettings(suggestionsEnabled = false, autocorrectEnabled = false)
                .isAutocorrectionActive,
        )
    }

    @Test
    fun `privacy controls start without changing or clearing user data`() {
        val settings = KeyboardSettings()

        assertFalse(settings.incognitoModeEnabled)
        assertEquals(0L, settings.learnedDataClearEpoch)
    }
}
