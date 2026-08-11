package com.slide.core.settings

import java.io.IOException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardSettingsTest {

    /** What the repository falls back to when no setting has ever been read. */
    private val unreadableSettings = KeyboardSettings(
        incognitoModeEnabled = true,
        learnedDataClearEpoch = KeyboardSettings.LEARNED_DATA_EPOCH_UNKNOWN,
    )

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

    // region Surviving preference read failures

    /**
     * The defaults this used to fall back to carry `learnedDataClearEpoch = 0`, and the IME reads
     * a change in that number as a request to throw away everything it has learned. One unreadable
     * moment must not look like the user asking for that.
     */
    @Test
    fun `a read failure keeps the settings already read and never rewinds the clear epoch`() =
        runBlocking {
            val stored = KeyboardSettings(themeId = "midnight", learnedDataClearEpoch = 7L)
            val changed = stored.copy(hapticEnabled = false)
            var subscriptions = 0
            val failures = mutableListOf<Throwable>()

            val settings = flow {
                if (subscriptions++ == 0) {
                    emit(stored)
                    throw IOException("the preferences file went away mid-read")
                }
                emit(changed)
            }.survivingReadFailures(
                fallback = { unreadableSettings },
                onFailure = { failures += it },
                retryDelayMillis = { 0L },
            )

            val seen = settings.take(2).toList()

            // The failure neither ended the flow nor replaced what had been read: the upstream was
            // resubscribed and the next real change still arrived.
            assertEquals(listOf(stored, changed), seen)
            assertEquals(2, subscriptions)
            assertEquals(1, failures.size)
            assertTrue(seen.none { it.learnedDataClearEpoch != 7L })
        }

    /** With nothing read yet there is no user state to keep, and no baseline to disturb. */
    @Test
    fun `only a first read failure falls back to defaults`() = runBlocking {
        val stored = KeyboardSettings(themeId = "midnight", learnedDataClearEpoch = 7L)
        var subscriptions = 0

        val settings = flow {
            if (subscriptions++ == 0) throw IOException("the preferences file is missing")
            emit(stored)
        }.survivingReadFailures(
            fallback = { unreadableSettings },
            onFailure = {},
            retryDelayMillis = { 0L },
        )

        assertEquals(listOf(unreadableSettings, stored), settings.take(2).toList())
    }

    /**
     * The fallback is followed by a retry that succeeds, and the real epoch it then reads is
     * usually larger than nothing — so a fallback carrying a plain `0` would make the first
     * successful read look like the user asking to clear everything they have taught the
     * keyboard. The sentinel is what stops the retry from being read as a request.
     */
    @Test
    fun `the fallback never carries an authoritative clear epoch`() = runBlocking {
        val stored = KeyboardSettings(themeId = "midnight", learnedDataClearEpoch = 7L)
        var subscriptions = 0

        val settings = flow {
            if (subscriptions++ == 0) throw IOException("the preferences file is missing")
            emit(stored)
        }.survivingReadFailures(
            fallback = { unreadableSettings },
            onFailure = {},
            retryDelayMillis = { 0L },
        )

        val seen = settings.take(2).toList()

        assertEquals(KeyboardSettings.LEARNED_DATA_EPOCH_UNKNOWN, seen.first().learnedDataClearEpoch)
        assertTrue(
            "an unread epoch has to be negative to be unmistakable",
            seen.first().learnedDataClearEpoch < 0L,
        )
        assertEquals(7L, seen.last().learnedDataClearEpoch)
    }

    /**
     * A ceiling that is never climbed back down turns one bad afternoon into a session that takes
     * half a minute to notice every later change.
     */
    @Test
    fun `the backoff starts again after a successful read`() = runBlocking {
        val delays = mutableListOf<Long>()
        var subscriptions = 0

        val settings = flow {
            when (subscriptions++) {
                0, 1 -> throw IOException("the preferences file is busy")
                // Reads once, then fails again: the read is what has to reset the count.
                2 -> {
                    emit(KeyboardSettings(themeId = "midnight"))
                    throw IOException("the preferences file is busy again")
                }
                else -> emit(KeyboardSettings(themeId = "dusk"))
            }
        }.survivingReadFailures(
            fallback = { unreadableSettings },
            onFailure = {},
            retryDelayMillis = { attempt ->
                delays += attempt
                0L
            },
        )

        // The fallback, the read that succeeds, and the read after the failure that follows it.
        settings.take(3).toList()

        assertEquals(listOf(0L, 1L, 0L), delays)
    }

    /** A bug in the mapping is not a bad file, and hiding it behind a retry loop would be worse. */
    @Test
    fun `a failure that is not an IO failure still propagates`() {
        val settings = flow<KeyboardSettings> { throw IllegalStateException("not an IO problem") }
            .survivingReadFailures(
                fallback = { KeyboardSettings() },
                onFailure = {},
                retryDelayMillis = { 0L },
            )

        assertThrows(IllegalStateException::class.java) { runBlocking { settings.toList() } }
    }

    @Test
    fun `retries back off and then stop growing`() {
        assertEquals(250L, preferenceRetryDelayMillis(0))
        assertEquals(500L, preferenceRetryDelayMillis(1))
        assertEquals(32_000L, preferenceRetryDelayMillis(7))
        assertEquals(32_000L, preferenceRetryDelayMillis(1_000))
    }

    // endregion
}
