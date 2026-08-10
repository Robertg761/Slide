package com.slide.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStore
import com.slide.core.emoji.EmojiData
import com.slide.core.theme.Themes
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** User-facing keyboard preferences. */
data class KeyboardSettings(
    val themeId: String = Themes.ID_DYNAMIC,
    val followSystemDarkMode: Boolean = true,
    val showKeyBorders: Boolean = false,
    val showKeyPreview: Boolean = true,
    val showNumberRow: Boolean = false,
    /** Multiplier on the default key height, 0.7–1.4. */
    val keyHeightScale: Float = 1f,
    /** Extra space below the keyboard in dp, for gesture-nav comfort. */
    val bottomPaddingDp: Float = 0f,
    val hapticEnabled: Boolean = true,
    /** 0–1, scaled to a vibration amplitude. */
    val hapticStrength: Float = 0.5f,
    val soundEnabled: Boolean = false,
    val soundVolume: Float = 0.5f,
    val gestureTypingEnabled: Boolean = true,
    /** Shows word candidates above the keys as the user types. */
    val suggestionsEnabled: Boolean = true,
    /**
     * Lets a separator replace a misspelled word with the strip's first candidate.
     *
     * Separate from [suggestionsEnabled] because some people want to see the candidates and pick
     * them by hand without the keyboard ever changing a word on its own.
     */
    val autocorrectEnabled: Boolean = true,
    /**
     * Stops Slide from learning new words and word pairs until the user turns it off again.
     *
     * This is separate from editor-requested incognito mode so the user can ask for the same
     * privacy in any app. Existing learned data remains available until it is explicitly cleared.
     */
    val incognitoModeEnabled: Boolean = false,
    /**
     * Changes whenever the settings app durably requests clearing learned words and word pairs.
     *
     * The IME observes this value so it can also discard its live in-memory copies. It is an epoch
     * rather than a Boolean because every clear action must be observable, including consecutive
     * requests.
     */
    val learnedDataClearEpoch: Long = 0L,
    /**
     * Withholds slurs and profanity from swipe and suggestion results.
     *
     * On by default, matching Gboard: an accidental obscenity committed into a message is a much
     * worse surprise than having to type a deliberate one by hand.
     */
    val blockOffensiveWords: Boolean = true,
    /**
     * Which packaged speech model voice typing uses, as a `WhisperModel` name.
     *
     * Stored as a plain string rather than the enum so `:core` need not depend on `:asr`; an
     * unrecognised value falls back to the default.
     */
    val voiceModelId: String = "",
    val autoCapitalize: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    /**
     * Skin tone applied to every emoji that has one, or [EmojiData.TONE_DEFAULT] for the yellow
     * form. One setting for the whole picker, as in Gboard, rather than a choice per emoji.
     */
    val emojiSkinTone: Int = EmojiData.TONE_DEFAULT,
    /** Explicit opt-in: update checks are the only feature that contacts GitHub. */
    val updateChecksEnabled: Boolean = false,
    /** Alpha builds are intentionally opt-in even while Slide itself is pre-1.0. */
    val includeAlphaUpdates: Boolean = false,
) {
    /** Autocorrection can only operate while the suggestion pipeline and strip are enabled. */
    val isAutocorrectionActive: Boolean
        get() = suggestionsEnabled && autocorrectEnabled
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "slide_settings")

/** One DataStore per file per process, rooted where Android backup cannot see it. */
private object NoBackupRecentEmojiStore {
    private val stores = mutableMapOf<String, DataStore<Preferences>>()

    @Synchronized
    fun get(context: Context): DataStore<Preferences> {
        val file = File(context.noBackupFilesDir, "datastore/recent_emoji.preferences_pb")
        return stores.getOrPut(file.absolutePath) {
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = {
                    val directory = checkNotNull(file.parentFile)
                    check(directory.isDirectory || directory.mkdirs()) {
                        "Could not create the no-backup emoji-history directory"
                    }
                    file
                },
            )
        }
    }
}

/** Reads and writes [KeyboardSettings]. Held by the IME and the settings UI alike. */
class SettingsRepository(private val context: Context) {

    private val recentEmojiStore = NoBackupRecentEmojiStore.get(context.applicationContext)
    private val recentEmojiMigration = Mutex()

    /**
     * Deduplicated because recently-used emoji share this store, and they change on every tap.
     * Without it the keyboard would rebuild its theme and re-lay out its keys each time someone
     * picked an emoji, for a change that has nothing to do with either.
     */
    val settings: Flow<KeyboardSettings> =
        context.dataStore.data
            .map { it.toSettings() }
            .catch { error ->
                if (error !is IOException) throw error
                // Keep the keyboard usable after a transient preferences read failure, but never
                // learn while a persisted privacy choice is unavailable.
                emit(KeyboardSettings(incognitoModeEnabled = true))
            }
            .distinctUntilChanged()

    /**
     * Emoji the user picked recently, most recent first.
     *
     * Usage rather than preference, which is why it sits beside [settings] instead of inside it:
     * nothing in the settings UI shows it, and it changes far more often than anything that does.
     */
    val recentEmoji: Flow<List<String>> = flow {
        migrateLegacyRecentEmoji()
        emitAll(
            recentEmojiStore.data
                .map {
                    it[Keys.RECENT_EMOJI].orEmpty()
                        .split(RECENT_SEPARATOR)
                        .filter(String::isNotEmpty)
                }
                .catch { error ->
                    if (error !is IOException) throw error
                    emit(emptyList())
                }
                .distinctUntilChanged(),
        )
    }

    /**
     * Moves [emoji] to the front of the recent list, trimmed to [MAX_RECENT].
     *
     * Re-picking something already in the list promotes it rather than duplicating it, so the row
     * stays a most-recently-used list and not a history log.
     */
    suspend fun recordEmojiUse(emoji: String) {
        migrateLegacyRecentEmoji()
        recentEmojiStore.edit { prefs ->
            val current = prefs[Keys.RECENT_EMOJI].orEmpty()
                .split(RECENT_SEPARATOR)
                .filter { it.isNotEmpty() && it != emoji }
            prefs[Keys.RECENT_EMOJI] =
                (listOf(emoji) + current).take(MAX_RECENT).joinToString(RECENT_SEPARATOR)
        }
    }

    suspend fun clearRecentEmoji() {
        recentEmojiStore.edit { it.remove(Keys.RECENT_EMOJI) }
        // Remove any pre-0.2.1 value even when migration had not run yet.
        context.dataStore.edit { it.remove(Keys.RECENT_EMOJI) }
    }

    /** Moves pre-0.2.1 emoji history out of the backed-up settings file exactly once. */
    private suspend fun migrateLegacyRecentEmoji() = recentEmojiMigration.withLock {
        val legacy = context.dataStore.data.first()[Keys.RECENT_EMOJI] ?: return@withLock
        recentEmojiStore.edit { privatePrefs ->
            if (privatePrefs[Keys.RECENT_EMOJI].isNullOrEmpty()) {
                privatePrefs[Keys.RECENT_EMOJI] = legacy
            }
        }
        context.dataStore.edit { it.remove(Keys.RECENT_EMOJI) }
    }

    suspend fun update(transform: (KeyboardSettings) -> KeyboardSettings) {
        context.dataStore.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs[Keys.THEME_ID] = updated.themeId
            prefs[Keys.FOLLOW_SYSTEM_DARK] = updated.followSystemDarkMode
            prefs[Keys.KEY_BORDERS] = updated.showKeyBorders
            prefs[Keys.KEY_PREVIEW] = updated.showKeyPreview
            prefs[Keys.NUMBER_ROW] = updated.showNumberRow
            prefs[Keys.KEY_HEIGHT] = updated.keyHeightScale
            prefs[Keys.BOTTOM_PADDING] = updated.bottomPaddingDp
            prefs[Keys.HAPTIC] = updated.hapticEnabled
            prefs[Keys.HAPTIC_STRENGTH] = updated.hapticStrength
            prefs[Keys.SOUND] = updated.soundEnabled
            prefs[Keys.SOUND_VOLUME] = updated.soundVolume
            prefs[Keys.GESTURE_TYPING] = updated.gestureTypingEnabled
            prefs[Keys.SUGGESTIONS] = updated.suggestionsEnabled
            prefs[Keys.AUTOCORRECT] = updated.autocorrectEnabled
            prefs[Keys.INCOGNITO_MODE] = updated.incognitoModeEnabled
            prefs[Keys.LEARNED_DATA_CLEAR_EPOCH] = updated.learnedDataClearEpoch
            prefs[Keys.BLOCK_OFFENSIVE] = updated.blockOffensiveWords
            prefs[Keys.VOICE_MODEL] = updated.voiceModelId
            prefs[Keys.AUTO_CAPITALIZE] = updated.autoCapitalize
            prefs[Keys.DOUBLE_SPACE_PERIOD] = updated.doubleSpacePeriod
            prefs[Keys.EMOJI_SKIN_TONE] = updated.emojiSkinTone
            prefs[Keys.UPDATE_CHECKS] = updated.updateChecksEnabled
            prefs[Keys.INCLUDE_ALPHA_UPDATES] = updated.includeAlphaUpdates
        }
    }

    /** Notifies a running IME that a durable learned-data clear request is waiting. */
    suspend fun notifyLearnedDataCleared() {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.LEARNED_DATA_CLEAR_EPOCH] ?: 0L
            prefs[Keys.LEARNED_DATA_CLEAR_EPOCH] =
                if (current == Long.MAX_VALUE) 1L else current + 1L
        }
    }

    private fun Preferences.toSettings(): KeyboardSettings {
        val defaults = KeyboardSettings()
        return KeyboardSettings(
            themeId = this[Keys.THEME_ID] ?: defaults.themeId,
            followSystemDarkMode = this[Keys.FOLLOW_SYSTEM_DARK] ?: defaults.followSystemDarkMode,
            showKeyBorders = this[Keys.KEY_BORDERS] ?: defaults.showKeyBorders,
            showKeyPreview = this[Keys.KEY_PREVIEW] ?: defaults.showKeyPreview,
            showNumberRow = this[Keys.NUMBER_ROW] ?: defaults.showNumberRow,
            keyHeightScale = this[Keys.KEY_HEIGHT] ?: defaults.keyHeightScale,
            bottomPaddingDp = this[Keys.BOTTOM_PADDING] ?: defaults.bottomPaddingDp,
            hapticEnabled = this[Keys.HAPTIC] ?: defaults.hapticEnabled,
            hapticStrength = this[Keys.HAPTIC_STRENGTH] ?: defaults.hapticStrength,
            soundEnabled = this[Keys.SOUND] ?: defaults.soundEnabled,
            soundVolume = this[Keys.SOUND_VOLUME] ?: defaults.soundVolume,
            gestureTypingEnabled = this[Keys.GESTURE_TYPING] ?: defaults.gestureTypingEnabled,
            suggestionsEnabled = this[Keys.SUGGESTIONS] ?: defaults.suggestionsEnabled,
            autocorrectEnabled = this[Keys.AUTOCORRECT] ?: defaults.autocorrectEnabled,
            incognitoModeEnabled = this[Keys.INCOGNITO_MODE] ?: defaults.incognitoModeEnabled,
            learnedDataClearEpoch =
                this[Keys.LEARNED_DATA_CLEAR_EPOCH] ?: defaults.learnedDataClearEpoch,
            blockOffensiveWords = this[Keys.BLOCK_OFFENSIVE] ?: defaults.blockOffensiveWords,
            voiceModelId = this[Keys.VOICE_MODEL] ?: defaults.voiceModelId,
            autoCapitalize = this[Keys.AUTO_CAPITALIZE] ?: defaults.autoCapitalize,
            doubleSpacePeriod = this[Keys.DOUBLE_SPACE_PERIOD] ?: defaults.doubleSpacePeriod,
            emojiSkinTone = this[Keys.EMOJI_SKIN_TONE] ?: defaults.emojiSkinTone,
            updateChecksEnabled = this[Keys.UPDATE_CHECKS] ?: defaults.updateChecksEnabled,
            includeAlphaUpdates = this[Keys.INCLUDE_ALPHA_UPDATES] ?: defaults.includeAlphaUpdates,
        )
    }

    private object Keys {
        val THEME_ID = stringPreferencesKey("theme_id")
        val FOLLOW_SYSTEM_DARK = booleanPreferencesKey("follow_system_dark")
        val KEY_BORDERS = booleanPreferencesKey("key_borders")
        val KEY_PREVIEW = booleanPreferencesKey("key_preview")
        val NUMBER_ROW = booleanPreferencesKey("number_row")
        val KEY_HEIGHT = floatPreferencesKey("key_height_scale")
        val BOTTOM_PADDING = floatPreferencesKey("bottom_padding_dp")
        val HAPTIC = booleanPreferencesKey("haptic_enabled")
        val HAPTIC_STRENGTH = floatPreferencesKey("haptic_strength")
        val SOUND = booleanPreferencesKey("sound_enabled")
        val SOUND_VOLUME = floatPreferencesKey("sound_volume")
        val GESTURE_TYPING = booleanPreferencesKey("gesture_typing")
        val SUGGESTIONS = booleanPreferencesKey("suggestions_enabled")
        val AUTOCORRECT = booleanPreferencesKey("autocorrect_enabled")
        val INCOGNITO_MODE = booleanPreferencesKey("incognito_mode_enabled")
        val LEARNED_DATA_CLEAR_EPOCH = longPreferencesKey("learned_data_clear_epoch")
        val BLOCK_OFFENSIVE = booleanPreferencesKey("block_offensive_words")
        val VOICE_MODEL = stringPreferencesKey("voice_model")
        val AUTO_CAPITALIZE = booleanPreferencesKey("auto_capitalize")
        val DOUBLE_SPACE_PERIOD = booleanPreferencesKey("double_space_period")
        val EMOJI_SKIN_TONE = intPreferencesKey("emoji_skin_tone")
        val UPDATE_CHECKS = booleanPreferencesKey("update_checks_enabled")
        val INCLUDE_ALPHA_UPDATES = booleanPreferencesKey("include_alpha_updates")
        val RECENT_EMOJI = stringPreferencesKey("recent_emoji")
    }

    private companion object {
        /** Two rows on a typical phone, which is as far back as anyone scrolls for a recent. */
        const val MAX_RECENT = 32

        /** A control character, so it can never appear inside an emoji sequence. */
        const val RECENT_SEPARATOR = "\u001F"
    }
}
