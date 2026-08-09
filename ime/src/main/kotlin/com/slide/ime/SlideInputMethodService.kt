package com.slide.ime

import android.content.Context
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import com.slide.asr.VoiceInput
import com.slide.asr.VoiceInputClient
import com.slide.asr.WhisperModel
import com.slide.core.emoji.EmojiData
import com.slide.core.emoji.EmojiLoader
import com.slide.core.layout.Key
import com.slide.core.layout.KeyType
import com.slide.core.layout.Layouts
import com.slide.core.settings.KeyboardSettings
import com.slide.core.settings.SettingsRepository
import com.slide.core.theme.KeyboardTheme
import com.slide.core.theme.Themes
import com.slide.engine.gesture.GestureDecoder
import com.slide.engine.gesture.GesturePoint
import com.slide.engine.lexicon.LexiconLoader
import com.slide.engine.suggest.TypingSuggester
import com.slide.ime.view.EmojiGlyphs
import com.slide.ime.view.EmojiPanelView
import com.slide.ime.view.EnterAction
import com.slide.ime.view.KeyboardFrame
import com.slide.ime.view.KeyboardView
import com.slide.ime.view.ShiftState
import com.slide.ime.view.SuggestionStripView
import com.slide.ime.view.VoiceOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SlideInputMethodService :
    InputMethodService(),
    KeyboardView.Listener,
    SuggestionStripView.Listener,
    EmojiPanelView.Listener,
    VoiceOverlayView.Listener,
    VoiceInputClient.Listener {

    private lateinit var settingsRepository: SettingsRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var keyboardView: KeyboardView? = null
    private var suggestionStrip: SuggestionStripView? = null
    private var voiceOverlay: VoiceOverlayView? = null
    private var emojiPanel: EmojiPanelView? = null
    private var keyboardFrame: KeyboardFrame? = null
    private var inputRoot: View? = null
    private var settings = KeyboardSettings()

    /**
     * Connected to the speech process only while the keyboard is on screen.
     *
     * Staying bound would keep a process alive — and eventually a few hundred megabytes of model
     * with it — for as long as Slide is the selected keyboard, which is essentially always.
     */
    private val voiceClient by lazy {
        VoiceInputClient(this).also { it.listener = this }
    }

    /** Which of the three key layers is on screen. */
    private enum class Layer { ALPHA, SYMBOLS, SYMBOLS_ALT }

    private var layer = Layer.ALPHA
    private var searchPreviousLayer = Layer.ALPHA
    private var lastShiftTapMs = 0L
    private var lastSpaceCommitMs = 0L

    /** Set when the field asks us not to learn from input (password fields, incognito browsers). */
    private var incognito = false

    /** Password fields get no suggestions at all, not merely no learning. */
    private var passwordField = false

    /**
     * Null until the lexicon finishes loading, and permanently null if the asset is unreadable.
     *
     * Swipes that land before it is ready commit nothing rather than queueing, since a word
     * appearing seconds after the gesture would be worse than none at all.
     */
    private var gestureDecoder: GestureDecoder? = null

    /** Shares the lexicon with the decoder; null until it loads, for the same reason. */
    private var typingSuggester: TypingSuggester? = null

    private var emojiData: EmojiData? = null
    private var recentEmoji: List<String> = emptyList()

    /**
     * The word being typed, held as composing text in the editor rather than committed.
     *
     * Composing text is what makes autocorrect safe: the word is a region the editor knows about,
     * so replacing it is one atomic call rather than a character count we compute ourselves and
     * hope still matches. It is also what puts the underline under the word, which is the only
     * warning the user gets that the keyboard has opinions about it.
     */
    private val composing = StringBuilder()

    /** What a separator would turn [composing] into, or null to leave it as typed. */
    private var pendingAutocorrection: String? = null

    /**
     * True when [composing] is a finished word the user tapped back into rather than one being
     * typed.
     *
     * It changes two things. Nothing is autocorrected — the user went back to a word deliberately,
     * and having the keyboard change it out from under them at that moment is the opposite of what
     * they asked for. And picking from the strip replaces the word in place instead of appending a
     * space, because there is already a sentence on the other side of it.
     */
    private var recomposed = false

    /**
     * False when the cursor sits inside [composing] rather than at its end.
     *
     * The composing region can only be extended at its end, so a keystroke with the cursor
     * elsewhere in the word has to settle the region first and be typed literally. The region is
     * still worth keeping in that state: it is what puts alternatives for the word in the strip.
     */
    private var composingAtEnd = true

    /**
     * Set whenever the keyboard itself edits the field, and cleared by the selection change that
     * results.
     *
     * [onUpdateSelection] cannot otherwise tell the cursor landing where we just put it from the
     * user tapping somewhere, and only the second is an invitation to reopen a word.
     */
    private var selfEdit = false

    /** The last word autocorrect changed, so the next backspace can put it back. */
    private var lastAutocorrect: Autocorrect? = null

    /** What the strip is currently showing, which decides what tapping a cell means. */
    private var stripMode = StripMode.Empty

    /**
     * Exactly what the last swipe put into the field, leading space and capitalisation included.
     *
     * Picking an alternative from the strip means removing this and putting the other word in its
     * place, so it has to be the literal committed text rather than the decoded word: if it no
     * longer sits immediately before the cursor, the user has moved on and the pick is abandoned.
     */
    private var lastGestureCommit: String? = null

    /** The shift state the last swipe was committed under, so alternatives are cased to match. */
    private var lastGestureShift = ShiftState.OFF

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private val audioManager: AudioManager? by lazy {
        getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
        settingsRepository.settings
            .onEach { updated ->
                settings = updated
                keyboardView?.settings = updated
                emojiPanel?.skinTone = updated.emojiSkinTone
                applyTheme(resolveTheme())
            }
            .launchIn(scope)

        settingsRepository.recentEmoji
            .onEach {
                recentEmoji = it
                emojiPanel?.recents = it
                if (keyboardView?.searchMode == true) refreshEmojiSearch()
            }
            .launchIn(scope)

        // Roughly a megabyte to parse; doing it on the main thread would stall the first frame
        // of the keyboard, which is the one moment the user is definitely watching.
        scope.launch {
            val lexicon = withContext(Dispatchers.IO) { LexiconLoader.load(applicationContext) }
            if (lexicon != null) {
                gestureDecoder = GestureDecoder(lexicon)
                typingSuggester = TypingSuggester(lexicon)
                Log.i(TAG, "Decoder and suggester ready with ${lexicon.size} words")
            }
        }

        // Loaded separately from the lexicon so a slow font scan cannot hold up gesture typing,
        // which the user is far more likely to reach for in the first seconds after opening.
        scope.launch {
            val (catalogue, renderable) = withContext(Dispatchers.IO) {
                val loaded = EmojiLoader.load(applicationContext)
                loaded to loaded?.let(EmojiGlyphs::renderable)
            }
            if (catalogue == null || renderable == null) return@launch
            emojiData = catalogue
            emojiPanel?.apply {
                data = catalogue
                this.renderable = renderable
            }
            Log.i(TAG, "Emoji ready: ${renderable.sumOf { it.size }} of ${catalogue.size} drawable")
        }
    }

    override fun onCreateInputView(): View {
        val theme = resolveTheme()

        val strip = SuggestionStripView(this).apply {
            listener = this@SlideInputMethodService
            keyboardTheme = theme
        }
        val view = KeyboardView(this).apply {
            listener = this@SlideInputMethodService
            settings = this@SlideInputMethodService.settings
            keyboardTheme = theme
            keyboardLayout = Layouts.QwertyEn
            enterAction = EnterAction.RETURN
        }
        val overlay = VoiceOverlayView(this).apply {
            listener = this@SlideInputMethodService
            keyboardTheme = theme
            visibility = View.GONE
        }
        val emoji = EmojiPanelView(this).apply {
            listener = this@SlideInputMethodService
            keyboardTheme = theme
            skinTone = settings.emojiSkinTone
            emojiData?.let {
                data = it
                renderable = EmojiGlyphs.renderable(it)
            }
            visibility = View.GONE
        }

        suggestionStrip = strip
        keyboardView = view
        voiceOverlay = overlay
        emojiPanel = emoji

        // The picker and the voice overlay sit on top of the keys rather than replacing them, so the
        // input view keeps exactly the same height whichever is open. Swapping in a panel of a
        // different height would resize the window and shove the app's text around mid-sentence.
        // KeyboardFrame is what holds them to the keys' height; the keys must be added first.
        val keys = KeyboardFrame(this).apply {
            addView(view, MATCH_PARENT, WRAP_CONTENT)
            addView(emoji, MATCH_PARENT, MATCH_PARENT)
            addView(overlay, MATCH_PARENT, MATCH_PARENT)
        }
        keyboardFrame = keys

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(strip, MATCH_PARENT, WRAP_CONTENT)
            addView(keys, MATCH_PARENT, WRAP_CONTENT)
            inputRoot = this
            applyTheme(theme)
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        exitEmojiSearch(showPicker = false)
        layer = Layer.ALPHA
        hideEmojiPanel()
        passwordField = isPasswordField(info)
        incognito = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0 ||
            passwordField
        abandonComposing()

        keyboardView?.apply {
            keyboardLayout = Layouts.QwertyEn
            settings = this@SlideInputMethodService.settings
            enterAction = enterActionFor(info.imeOptions)
        }
        applyTheme(resolveTheme())
        // Candidates from the previous field would be nonsense here, and tapping one would try to
        // rewrite text that belongs to a different editor.
        clearSuggestions()
        refreshSuggestionEmptyMessage()
        updateShiftFromCursor()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme(resolveTheme())
    }

    private fun applyTheme(theme: KeyboardTheme) {
        keyboardView?.keyboardTheme = theme
        suggestionStrip?.keyboardTheme = theme
        voiceOverlay?.keyboardTheme = theme
        emojiPanel?.keyboardTheme = theme
        // The frame's navigation-bar strip and any rounding the window puts around the input view
        // are the two places the keyboard's own colour does not otherwise reach, and both sit right
        // along the bottom edge where a mismatch reads as the keyboard not fitting the screen.
        keyboardFrame?.themeBackground = theme.background
        inputRoot?.setBackgroundColor(theme.background)
    }

    /**
     * Releases the speech process whenever the keyboard leaves the screen.
     *
     * The alternative — staying bound so the next dictation starts instantly — keeps a process
     * holding the whole model alive for as long as Slide is the selected keyboard, which is
     * essentially always. Paying a few hundred milliseconds of reload at the start of a dictation,
     * where the overlay already says "Getting ready", is the better trade.
     */
    override fun onWindowHidden() {
        super.onWindowHidden()
        exitEmojiSearch(showPicker = false)
        hideEmojiPanel()
        hideVoiceOverlay()
        voiceClient.unbind()
    }

    /**
     * Lets go of the word in progress when the field does.
     *
     * Composing text outlives the input view otherwise, and the next editor would open with an
     * underlined fragment of the last one's sentence sitting in it.
     */
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        abandonComposing()
    }

    /**
     * Lets back close whatever covers the keys before it closes the keyboard.
     *
     * Without this, backing out of the picker or out of dictation dismisses the keyboard entirely
     * and the user has to tap the text field again to carry on typing.
     *
     * This is the path for API 26 to 32. From API 33 the system routes back through
     * [OnBackInvokedDispatcher] instead and never calls this, which is what
     * [setBackCallbackRegistered] is for.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && handleBack()) return true
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Closes the topmost panel, reporting whether there was one.
     *
     * Dictation sits above the picker, so it goes first. Backing out of it counts as cancelling
     * rather than finishing: a transcript the user backed away from is not one they asked for.
     */
    private fun handleBack(): Boolean = when {
        voiceOverlayShown -> {
            onVoiceDismissed(committed = false)
            true
        }

        searchModeShown -> {
            exitEmojiSearch(showPicker = true)
            true
        }

        emojiPanelShown -> {
            hideEmojiPanel()
            true
        }

        else -> false
    }

    /**
     * Back handling for API 33 and up, where a key event is no longer what arrives.
     *
     * An IME that registers nothing here gets the framework's default callback, which hides the
     * whole window — so the panels have to claim back for as long as they are open, and give it
     * back the moment they close. Held as a field because unregistering needs the same instance.
     */
    private val backCallback: OnBackInvokedCallback by lazy {
        OnBackInvokedCallback { handleBack() }
    }

    private var backCallbackRegistered = false

    private fun setBackCallbackRegistered(registered: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (registered == backCallbackRegistered) return
        val dispatcher = window?.onBackInvokedDispatcher ?: return
        if (registered) {
            dispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backCallback,
            )
        } else {
            dispatcher.unregisterOnBackInvokedCallback(backCallback)
        }
        backCallbackRegistered = registered
    }

    /** Landscape fullscreen editing is off by default; it hides too much context. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        voiceClient.unbind()
        scope.cancel()
        keyboardView = null
        suggestionStrip = null
        voiceOverlay = null
        emojiPanel = null
        super.onDestroy()
    }

    // region KeyboardView.Listener

    override fun onKeyDown(key: Key) {
        performHaptic()
        performSound(key)
    }

    override fun onKeyCommit(key: Key, text: String) {
        if (keyboardView?.searchMode == true) {
            handleSearchKey(key, text)
            return
        }

        val connection = currentInputConnection ?: return

        if (key.type in EDITING_KEYS) selfEdit = true

        // Any keypress ends the swiped word: the candidates no longer describe what is in front of
        // the cursor, so leaving them up would offer a replacement for text that has moved on. A
        // typing strip is the opposite -- it is about to be rebuilt from the new keystroke.
        if (stripMode == StripMode.Gesture) clearSuggestions()

        // An autocorrection can only be taken back by the very next key press, and only by
        // backspace. Anything else the user does means they have accepted it.
        if (key.type != KeyType.DELETE) lastAutocorrect = null

        when (key.type) {
            KeyType.SHIFT -> handleShiftTap()
            KeyType.DELETE -> handleDelete(connection)
            KeyType.ENTER -> handleEnter(connection)
            KeyType.SYMBOLS -> switchLayer(Layer.SYMBOLS)
            KeyType.SYMBOLS_ALT -> switchLayer(Layer.SYMBOLS_ALT)
            KeyType.ALPHA -> switchLayer(Layer.ALPHA)
            KeyType.SPACE -> handleSpace(connection, text)
            KeyType.MIC -> onVoiceRequested()
            KeyType.EMOJI -> showEmojiPanel()
            KeyType.GLOBE, KeyType.SETTINGS -> Unit
            KeyType.CHARACTER -> handleCharacter(connection, text)
        }
    }

    override fun onGestureComplete(points: List<GesturePoint>) {
        selfEdit = true
        val decoder = gestureDecoder ?: return
        val connection = currentInputConnection ?: return
        val keys = keyboardView?.gestureKeyMap() ?: return

        // A swipe ends the typed word as surely as a space does.
        finishComposing(connection)

        val candidates = decoder.decode(points, keys, blockOffensive = settings.blockOffensiveWords)
        val best = candidates.firstOrNull() ?: return

        commitGestureWord(connection, best.word)
        stripMode = StripMode.Gesture
        suggestionStrip?.setSuggestions(candidates.map { it.word })
    }

    override fun onCursorMove(steps: Int) {
        selfEdit = true
        val connection = currentInputConnection ?: return
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
        if (extracted != null) {
            val start = extracted.selectionStart
            val end = extracted.selectionEnd
            val target = if (start != end) {
                if (steps < 0) start else end
            } else {
                (start + steps).coerceIn(0, extracted.text?.length ?: start)
            }
            connection.setSelection(target, target)
            abandonComposing()
            updateShiftFromCursor()
            keyboardView?.announceForAccessibility("Cursor moved")
        } else {
            val direction = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(kotlin.math.abs(steps)) {
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, direction))
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, direction))
            }
            abandonComposing()
        }
    }

    override fun onDeleteWordGesture() {
        selfEdit = true
        val connection = currentInputConnection ?: return
        finishComposing(connection)
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            connection.commitText("", 1)
            return
        }

        val before = connection.getTextBeforeCursor(MAX_WORD_DELETE_CHARS, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        var start = before.length
        while (start > 0 && before[start - 1].isWhitespace()) start--
        while (start > 0 && !before[start - 1].isWhitespace()) start--
        connection.deleteSurroundingText(before.length - start, 0)
        updateShiftFromCursor()
    }

    /**
     * Inserts a decoded word, spacing it from whatever precedes it the way a typed word would be.
     *
     * Gboard's behaviour, which people are used to: swiping mid-sentence inserts a leading space
     * so words do not run together, but not at the very start of a field or straight after
     * existing whitespace or an opening bracket.
     */
    private fun commitGestureWord(connection: InputConnection, word: String) {
        val before = connection.getTextBeforeCursor(1, 0)
        val needsSpace = !before.isNullOrEmpty() && before[0].let { it.isLetterOrDigit() || it in ".,!?;:'\")" }

        val shifted = shiftState()
        val text = (if (needsSpace) " " else "") + applyShift(word, shifted)

        connection.commitText(text, 1)
        lastGestureCommit = text
        lastGestureShift = shifted
        lastAutocorrect = null

        if (shifted == ShiftState.SHIFTED) setShift(ShiftState.OFF)
        updateShiftFromCursor()
    }

    /**
     * Swaps the swiped word for the alternative the user tapped.
     *
     * The replacement is verified against the text actually in the field first. An
     * [InputConnection] is a view onto another process's editor: it can change underneath us
     * between the swipe and the tap, and deleting a character count that no longer corresponds to
     * our word would eat the user's text.
     */
    override fun onSuggestionPicked(index: Int, word: String) {
        performHaptic()
        selfEdit = true
        when (stripMode) {
            StripMode.Gesture -> pickGestureAlternative(index, word)
            StripMode.Typing -> pickTypedSuggestion(word)
            StripMode.Empty -> Unit
        }
    }

    private fun pickGestureAlternative(index: Int, word: String) {
        if (index == 0) return // Already committed; nothing to do.

        val connection = currentInputConnection ?: return
        val previous = lastGestureCommit ?: return

        if (connection.getTextBeforeCursor(previous.length, 0)?.toString() != previous) {
            clearSuggestions()
            return
        }

        val prefix = if (previous.startsWith(" ")) " " else ""
        val replacement = prefix + applyShift(word, lastGestureShift)

        connection.beginBatchEdit()
        connection.deleteSurroundingText(previous.length, 0)
        connection.commitText(replacement, 1)
        connection.endBatchEdit()

        // The strip stays up, and keeps its order, so a second wrong guess is also one tap away
        // and the candidates do not move under the user's thumb.
        lastGestureCommit = replacement
        updateShiftFromCursor()
    }

    private fun applyShift(word: String, state: ShiftState): String = when (state) {
        ShiftState.LOCKED -> word.uppercase()
        ShiftState.SHIFTED -> word.replaceFirstChar(Char::uppercaseChar)
        ShiftState.OFF -> word
    }

    private fun clearSuggestions() {
        suggestionStrip?.clear()
        lastGestureCommit = null
        stripMode = StripMode.Empty
    }

    private fun refreshSuggestionEmptyMessage() {
        suggestionStrip?.setEmptyMessage(
            when {
                passwordField -> "Suggestions are off in password fields"
                !settings.suggestionsEnabled -> "Suggestions are disabled"
                else -> "Type or swipe for suggestions"
            },
        )
    }

    // region Emoji search keyboard

    private fun handleSearchKey(key: Key, text: String) {
        val view = keyboardView ?: return
        var query = view.searchQuery
        when (key.type) {
            KeyType.CHARACTER -> query += text.lowercase()
            KeyType.SPACE -> query += " "
            KeyType.DELETE -> query = query.dropLastCodePoint()
            KeyType.ENTER -> {
                searchResults().firstOrNull()?.let { onSearchEmojiPicked(it) }
                return
            }
            KeyType.SHIFT, KeyType.SYMBOLS, KeyType.SYMBOLS_ALT, KeyType.ALPHA, KeyType.EMOJI,
            KeyType.MIC, KeyType.GLOBE, KeyType.SETTINGS -> return
        }
        view.searchQuery = query.take(MAX_SEARCH_QUERY_LENGTH)
        refreshEmojiSearch()
    }

    private fun String.dropLastCodePoint(): String {
        if (isEmpty()) return this
        val end = offsetByCodePoints(length, -1)
        return substring(0, end)
    }

    private fun searchResults(): List<String> = keyboardView?.searchResults.orEmpty()

    private fun refreshEmojiSearch() {
        val view = keyboardView ?: return
        val catalogue = emojiData ?: run {
            view.searchResults = emptyList()
            return
        }
        val query = view.searchQuery.trim()
        view.searchResults = if (query.isEmpty()) {
            recentEmoji.take(MAX_SEARCH_RESULTS)
        } else {
            catalogue.search(query, limit = MAX_SEARCH_RESULTS).map { catalogue.toned(it, settings.emojiSkinTone) }
        }
    }

    private fun startEmojiSearch() {
        hideEmojiPanel()
        searchPreviousLayer = layer
        keyboardView?.apply {
            searchQuery = ""
            searchMode = true
            searchResults = recentEmoji.take(MAX_SEARCH_RESULTS)
        }
        suggestionStrip?.setEmptyMessage("Emoji search is open")
        clearSuggestions()
        setBackCallbackRegistered(true)
    }

    private fun exitEmojiSearch(showPicker: Boolean) {
        val view = keyboardView ?: return
        if (!view.searchMode && !showPicker) return
        view.searchMode = false
        view.searchQuery = ""
        view.searchResults = emptyList()
        layer = searchPreviousLayer
        view.keyboardLayout = layoutFor(layer)
        if (layer == Layer.ALPHA) updateShiftFromCursor()
        refreshSuggestionEmptyMessage()
        if (showPicker && emojiData != null) {
            emojiPanel?.reset()
            emojiPanel?.visibility = View.VISIBLE
        }
        setBackCallbackRegistered(if (showPicker) true else emojiPanelShown || voiceOverlayShown)
    }

    override fun onSearchQueryChanged(query: String) {
        keyboardView?.searchQuery = query.take(MAX_SEARCH_QUERY_LENGTH)
        refreshEmojiSearch()
    }

    override fun onSearchEmojiPicked(emoji: String) {
        onEmojiPicked(emoji)
        keyboardView?.announceForAccessibility("Emoji $emoji inserted")
        keyboardView?.searchQuery = ""
        refreshEmojiSearch()
    }

    override fun onSearchClosed() {
        exitEmojiSearch(showPicker = true)
    }

    // endregion

    // endregion

    // region Voice input

    /**
     * Opens the dictation overlay, first getting the microphone permission if it is missing.
     *
     * The permission dialog is a separate activity and its answer cannot be delivered back here,
     * so a first tap on a fresh install asks and a second one dictates. Worth it to avoid asking
     * for the microphone during setup, before the user has any reason to say yes.
     */
    override fun onVoiceRequested() {
        if (!MicPermissionActivity.hasPermission(this)) {
            startActivity(MicPermissionActivity.intent(this))
            return
        }

        // Dictation replaces the whole input view, so the picker has no business staying open
        // underneath it.
        hideEmojiPanel()
        currentInputConnection?.let(::finishComposing)
        voiceOverlay?.apply {
            errorText = null
            state = VoiceInput.State.Preparing
            visibility = View.VISIBLE
        }
        setBackCallbackRegistered(true)
        clearSuggestions()
        voiceClient.start(WhisperModel.fromId(settings.voiceModelId))
    }

    override fun onVoiceDismissed(committed: Boolean) {
        if (committed) {
            voiceClient.stop() // the transcript arrives in onVoiceResult
        } else {
            voiceClient.cancel()
            hideVoiceOverlay()
        }
    }

    override fun onVoiceState(state: VoiceInput.State) {
        voiceOverlay?.state = state
        // Idle after a result or a cancellation means the session is over. The overlay is already
        // hidden in those paths; this catches the speech process dying underneath us.
        if (state == VoiceInput.State.Idle && voiceOverlay?.errorText == null) hideVoiceOverlay()
    }

    override fun onVoiceLevel(level: Float) {
        voiceOverlay?.setLevel(level)
    }

    override fun onVoiceResult(text: String) {
        hideVoiceOverlay()
        if (text.isBlank()) return
        selfEdit = true

        val connection = currentInputConnection ?: return
        commitDictation(connection, text)
    }

    override fun onVoiceError(reason: String) {
        voiceOverlay?.apply {
            errorText = reason
            state = VoiceInput.State.Idle
        }
    }

    /**
     * Inserts a transcript, spaced from the surrounding text the way a swiped word is.
     *
     * Whisper punctuates and capitalises its own output, so nothing here second-guesses it beyond
     * joining it to what is already in the field.
     */
    private fun commitDictation(connection: InputConnection, text: String) {
        val before = connection.getTextBeforeCursor(1, 0)
        val needsSpace = !before.isNullOrEmpty() &&
            before[0].let { it.isLetterOrDigit() || it in ".,!?;:'\")" }

        connection.commitText(if (needsSpace) " $text" else text, 1)
        lastAutocorrect = null
        updateShiftFromCursor()
    }

    private fun hideVoiceOverlay() {
        voiceOverlay?.apply {
            visibility = View.GONE
            errorText = null
            state = VoiceInput.State.Idle
        }
        setBackCallbackRegistered(emojiPanelShown)
    }

    private val voiceOverlayShown: Boolean
        get() = voiceOverlay?.visibility == View.VISIBLE

    // endregion

    // region The emoji picker

    private fun showEmojiPanel() {
        if (keyboardView?.searchMode == true) return
        val panel = emojiPanel ?: return
        if (panel.data == null) return // Still loading, or the asset is missing.

        // An emoji ends the word in progress the same way punctuation does, and it must settle
        // before the panel covers the keys -- otherwise the correction lands after the emoji.
        currentInputConnection?.let(::finishComposing)
        // Word candidates have nothing to say about emoji, and the panel covers the keys they
        // would apply to.
        clearSuggestions()
        panel.reset()
        panel.visibility = View.VISIBLE
        setBackCallbackRegistered(true)
    }

    private fun hideEmojiPanel() {
        emojiPanel?.apply {
            if (visibility == View.GONE) return@apply
            reset()
            visibility = View.GONE
        }
        setBackCallbackRegistered(voiceOverlayShown)
    }

    private val searchModeShown: Boolean
        get() = keyboardView?.searchMode == true

    private val emojiPanelShown: Boolean
        get() = emojiPanel?.visibility == View.VISIBLE

    override fun onEmojiPicked(emoji: String) {
        performHaptic()
        selfEdit = true
        currentInputConnection?.commitText(emoji, 1)
        // Whatever the undo record pointed at is no longer what sits before the cursor.
        lastAutocorrect = null
        // Recents are personal by definition, so an incognito or password field contributes none.
        if (!incognito) scope.launch { settingsRepository.recordEmojiUse(emoji) }
        updateShiftFromCursor()
    }

    override fun onSkinTonePicked(tone: Int) {
        scope.launch { settingsRepository.update { it.copy(emojiSkinTone = tone) } }
    }

    override fun onEmojiBackspace() {
        performHaptic()
        val connection = currentInputConnection ?: return
        // Emoji are surrogate pairs and often ZWJ sequences, so this borrows the key row's
        // code-point-aware delete rather than removing one char and leaving half a glyph.
        handleDelete(connection)
    }

    override fun onEmojiPanelClosed() {
        performHaptic()
        hideEmojiPanel()
    }

    override fun onEmojiSearchRequested() {
        performHaptic()
        startEmojiSearch()
    }

    // endregion

    // region Input handling

    private fun handleCharacter(connection: InputConnection, text: String) {
        // Typing with the cursor parked in the middle of a reopened word: the region can only grow
        // at its end, so settling it first is the only way the letter lands where the user is
        // looking.
        if (composing.isNotEmpty() && !composingAtEnd) abandonComposing()

        if (isWordCharacter(text) && suggestionsAvailable()) {
            composing.append(text)
            connection.setComposingText(composing, 1)
            updateTypingSuggestions()
        } else {
            // Punctuation ends a word, so it settles whatever was pending first -- typing "teh,"
            // should correct exactly as "teh " does.
            finishComposing(connection)
            connection.commitText(text, 1)
        }

        if (shiftState() == ShiftState.SHIFTED) setShift(ShiftState.OFF)
        updateShiftFromCursor()
    }

    /** Letters and the apostrophe build a word; everything else ends one. */
    private fun isWordCharacter(text: String): Boolean =
        text.length == 1 && isWordCharacter(text[0])

    private fun isWordCharacter(character: Char): Boolean =
        character.isLetter() || character == '\''

    private fun handleSpace(connection: InputConnection, text: String) {
        // Space is where a typed word is settled, and so where autocorrect actually happens.
        finishComposing(connection)

        val now = System.currentTimeMillis()
        val isDoubleSpace = settings.doubleSpacePeriod &&
            now - lastSpaceCommitMs < DOUBLE_SPACE_WINDOW_MS &&
            endsWithLetterThenSpace(connection)

        if (isDoubleSpace) {
            connection.deleteSurroundingText(1, 0)
            connection.commitText(". ", 1)
            lastSpaceCommitMs = 0L
            // The word before the full stop is no longer where the undo record says it is.
            lastAutocorrect = null
        } else {
            connection.commitText(text, 1)
            lastSpaceCommitMs = now
        }
        updateShiftFromCursor()
    }

    /** True when the text is "<letter><space>", the only case where double-space should punctuate. */
    private fun endsWithLetterThenSpace(connection: InputConnection): Boolean {
        val before = connection.getTextBeforeCursor(2, 0) ?: return false
        return before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()
    }

    private fun handleDelete(connection: InputConnection) {
        if (revertAutocorrect(connection)) return

        // Same reasoning as typing: with the cursor inside a reopened word, shortening the region
        // would delete its last letter rather than the one before the cursor.
        if (composing.isNotEmpty() && !composingAtEnd) abandonComposing()

        // Mid-word, backspace shortens the composing region rather than deleting from the editor,
        // so the suggestions keep up with what is actually in front of the cursor.
        if (composing.isNotEmpty()) {
            composing.setLength(composing.length - 1)
            if (composing.isEmpty()) {
                // The empty string has to be committed before the region is finished. On its own,
                // finishComposingText() settles what is there rather than removing it, which would
                // leave the letter this backspace just deleted sitting in the editor.
                connection.setComposingText("", 1)
                connection.finishComposingText()
                clearSuggestions()
            } else {
                connection.setComposingText(composing, 1)
                updateTypingSuggestions()
            }
            updateShiftFromCursor()
            return
        }

        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            connection.commitText("", 1)
        } else {
            // Delete a whole code point so emoji and surrogate pairs vanish in one press.
            val before = connection.getTextBeforeCursor(2, 0) ?: ""
            val toDelete = if (before.length == 2 && Character.isSurrogatePair(before[0], before[1])) 2 else 1
            connection.deleteSurroundingText(toDelete, 0)
        }
        updateShiftFromCursor()
    }

    private fun handleEnter(connection: InputConnection) {
        finishComposing(connection)
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val suppressed = (info?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) ?: 0) != 0

        if (!suppressed && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            connection.performEditorAction(action)
        } else {
            connection.commitText("\n", 1)
        }
        updateShiftFromCursor()
    }

    private fun handleShiftTap() {
        val now = System.currentTimeMillis()
        val doubleTapped = now - lastShiftTapMs < DOUBLE_TAP_WINDOW_MS
        lastShiftTapMs = now

        setShift(
            when {
                doubleTapped -> ShiftState.LOCKED
                shiftState() == ShiftState.OFF -> ShiftState.SHIFTED
                else -> ShiftState.OFF
            },
        )
    }

    private fun switchLayer(target: Layer) {
        layer = target
        if (target != Layer.ALPHA) setShift(ShiftState.OFF)
        keyboardView?.keyboardLayout = layoutFor(target)
        if (target == Layer.ALPHA) updateShiftFromCursor()
    }

    private fun layoutFor(layer: Layer) = when (layer) {
        Layer.ALPHA -> Layouts.QwertyEn
        Layer.SYMBOLS -> Layouts.SymbolsEn
        Layer.SYMBOLS_ALT -> Layouts.SymbolsAltEn
    }

    // endregion

    // region The word being typed

    /**
     * Whether the keyboard should be composing and suggesting at all.
     *
     * Password fields are excluded outright rather than merely excluded from learning: a strip that
     * completes someone's password in three cells beside their thumb is a shoulder-surfing hazard,
     * and composing text there also trips up managers that watch the field.
     */
    private fun suggestionsAvailable(): Boolean =
        settings.suggestionsEnabled &&
            !passwordField &&
            typingSuggester != null &&
            keyboardView?.gestureKeyMap() != null

    private fun updateTypingSuggestions() {
        val suggester = typingSuggester
        val keys = keyboardView?.gestureKeyMap()
        if (suggester == null || keys == null || composing.isEmpty()) {
            clearSuggestions()
            return
        }

        val result = suggester.suggest(
            typed = composing.toString(),
            keys = keys,
            blockOffensive = settings.blockOffensiveWords,
        )
        pendingAutocorrection = result.autocorrection
            .takeIf { settings.autocorrectEnabled && !recomposed }

        stripMode = StripMode.Typing
        suggestionStrip?.setSuggestions(result.words.map { it.word })
    }

    /**
     * Settles the word in progress, applying a pending autocorrection on the way out.
     *
     * Returns whether anything was corrected, which is what decides if the next backspace should
     * undo rather than delete.
     */
    private fun finishComposing(connection: InputConnection): Boolean {
        if (composing.isEmpty()) return false

        val typed = composing.toString()
        val correction = pendingAutocorrection
        var corrected = false

        if (correction != null && !correction.equals(typed, ignoreCase = true)) {
            val cased = matchCase(typed, correction)
            connection.setComposingText(cased, 1)
            lastAutocorrect = Autocorrect(original = typed, applied = cased)
            corrected = true
        }

        connection.finishComposingText()
        composing.setLength(0)
        pendingAutocorrection = null
        recomposed = false
        composingAtEnd = true
        clearSuggestions()
        return corrected
    }

    /**
     * Drops the word in progress without changing it, for when the cursor has moved out from under
     * us or the field has been swapped. Correcting text we may no longer own is not worth the risk.
     */
    private fun abandonComposing() {
        val wasComposing = composing.isNotEmpty()
        composing.setLength(0)
        pendingAutocorrection = null
        lastAutocorrect = null
        recomposed = false
        composingAtEnd = true
        if (wasComposing) selfEdit = true
        currentInputConnection?.finishComposingText()
        clearSuggestions()
    }

    /**
     * Puts back the word autocorrect replaced, if the very next thing the user did was backspace.
     *
     * This is the escape hatch that makes autocorrect tolerable: whatever it got wrong is one key
     * away from being undone, using the key people already reach for when they see a wrong word.
     * The applied text is checked against the field first, so a stale record can never eat
     * something else the user has since typed.
     */
    private fun revertAutocorrect(connection: InputConnection): Boolean {
        val undo = lastAutocorrect ?: return false
        lastAutocorrect = null
        if (composing.isNotEmpty()) return false

        // The separator committed after the word, if the user has already pressed one.
        val before = connection.getTextBeforeCursor(undo.applied.length + 1, 0)?.toString() ?: return false
        val separator = when {
            before == undo.applied -> ""
            before.length == undo.applied.length + 1 &&
                before.startsWith(undo.applied) &&
                !before.last().isLetterOrDigit() -> before.substring(undo.applied.length)
            else -> return false
        }

        connection.beginBatchEdit()
        connection.deleteSurroundingText(undo.applied.length + separator.length, 0)
        connection.commitText(undo.original + separator, 1)
        connection.endBatchEdit()

        updateShiftFromCursor()
        return true
    }

    /**
     * Dresses a correction in the capitalisation of the word it replaces.
     *
     * The dictionary is lowercase, but "Teh" at the start of a sentence must come back as "The"
     * rather than quietly undoing the user's shift key.
     */
    private fun matchCase(typed: String, corrected: String): String {
        val letters = typed.filter(Char::isLetter)
        return when {
            letters.length > 1 && letters.all(Char::isUpperCase) -> corrected.uppercase()
            typed.firstOrNull()?.isUpperCase() == true -> corrected.replaceFirstChar(Char::uppercaseChar)
            else -> corrected
        }
    }

    /**
     * Commits the candidate the user tapped, followed by a space.
     *
     * The space is what makes picking a suggestion worth doing: it saves the separator keypress as
     * well as the letters, which is the whole reason to reach up to the strip rather than carry on
     * typing. No undo record is kept — a word the user chose by hand is not something to offer to
     * take back.
     */
    private fun pickTypedSuggestion(word: String) {
        val connection = currentInputConnection ?: return
        if (composing.isEmpty()) return

        // A word reopened from the middle of a sentence already has a separator after it. Adding
        // another would turn every correction into a stray double space.
        val appendSpace = !recomposed
        // The dictionary is lowercase. A word reopened mid-sentence was written with whatever
        // capitalisation the user chose, and swapping it for a correction must not quietly undo it.
        val replacement = if (recomposed) matchCase(composing.toString(), word) else word

        connection.beginBatchEdit()
        connection.setComposingText(replacement, 1)
        connection.finishComposingText()
        if (appendSpace) connection.commitText(" ", 1)
        connection.endBatchEdit()

        composing.setLength(0)
        pendingAutocorrection = null
        lastAutocorrect = null
        recomposed = false
        composingAtEnd = true
        lastSpaceCommitMs = 0L
        clearSuggestions()
        updateShiftFromCursor()
    }

    /**
     * Notices the user moving the cursor, tapping elsewhere, or selecting text.
     *
     * Two jobs. A word in progress is dropped once the cursor leaves it, because everything the
     * keyboard believes about it stops being true at that point and correcting text the user has
     * navigated away from is the worst thing it could do. And a cursor that lands *in* a finished
     * word reopens it, which is what makes a wrong word fixable after the fact instead of only in
     * the second or so before the next space.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )

        val ourEdit = selfEdit
        selfEdit = false

        // Editors that do not report a composing region give -1 here. That is not evidence the
        // cursor moved, so it must not be treated as such, or suggestions would never survive a
        // keystroke in those apps.
        val insideComposing = candidatesStart >= 0 && candidatesEnd >= 0 &&
            newSelStart >= candidatesStart && newSelEnd <= candidatesEnd
        val cursorLeftTheWord = newSelStart != newSelEnd ||
            (candidatesEnd >= 0 && !insideComposing)

        if (composing.isNotEmpty() && !cursorLeftTheWord) {
            composingAtEnd = candidatesEnd < 0 || newSelEnd == candidatesEnd
            return
        }
        // Dropping one word and reopening another are the same gesture — a tap somewhere else in
        // the sentence — so the tap that ends the first must be allowed to start the second.
        if (composing.isNotEmpty()) abandonComposing()

        if (!ourEdit) reopenWordAtCursor(newSelStart, newSelEnd)
    }

    /**
     * Turns the finished word the cursor has landed in back into a composing region, so the strip
     * offers replacements for it.
     *
     * This is the only route to fixing a word the keyboard got wrong once the moment has passed —
     * a swipe that decoded to the wrong thing two words ago, or a correction noticed on re-reading.
     * Without it the only remedy is deleting back to the mistake and retyping everything after it.
     *
     * Nothing is changed here, only marked: the word is put back exactly as it stands, autocorrect
     * is held off for it, and if the user carries on typing or moves away again it settles
     * untouched.
     */
    private fun reopenWordAtCursor(selectionStart: Int, selectionEnd: Int) {
        if (selectionStart != selectionEnd || selectionStart < 0) return
        if (stripMode != StripMode.Empty) return
        if (!suggestionsAvailable()) return
        if (searchModeShown || emojiPanelShown || voiceOverlayShown) return

        val connection = currentInputConnection ?: return
        val before = connection.getTextBeforeCursor(MAX_REOPEN_CHARS, 0)?.toString() ?: return
        val after = connection.getTextAfterCursor(MAX_REOPEN_CHARS, 0)?.toString() ?: return

        var start = before.length
        while (start > 0 && isWordCharacter(before[start - 1])) start--
        var end = 0
        while (end < after.length && isWordCharacter(after[end])) end++

        // A run that reaches either end of the window may well continue past it, and reopening half
        // of a word would offer replacements for something the user never typed.
        if (start == 0 && before.length == MAX_REOPEN_CHARS) return
        if (end == after.length && after.length == MAX_REOPEN_CHARS) return

        val word = before.substring(start) + after.substring(0, end)
        if (word.length !in MIN_REOPEN_LENGTH..MAX_REOPEN_LENGTH) return
        if (word.none(Char::isLetter)) return

        val regionStart = selectionStart - (before.length - start)
        if (regionStart < 0) return

        selfEdit = true
        connection.setComposingRegion(regionStart, regionStart + word.length)
        composing.setLength(0)
        composing.append(word)
        recomposed = true
        composingAtEnd = end == 0
        updateTypingSuggestions()
    }

    /** A word autocorrect changed, and what it changed from. */
    private data class Autocorrect(val original: String, val applied: String)

    /** What the suggestion strip is showing, and so what a tap on it should do. */
    private enum class StripMode { Empty, Gesture, Typing }

    // endregion

    // region Shift

    private fun shiftState(): ShiftState = keyboardView?.shiftState ?: ShiftState.OFF

    private fun setShift(state: ShiftState) {
        keyboardView?.shiftState = state
    }

    /** Applies sentence auto-capitalisation, unless caps lock is on or the user disabled it. */
    private fun updateShiftFromCursor() {
        if (layer != Layer.ALPHA) return
        if (shiftState() == ShiftState.LOCKED) return
        if (!settings.autoCapitalize) {
            setShift(ShiftState.OFF)
            return
        }

        val info = currentInputEditorInfo ?: return
        val connection = currentInputConnection ?: return
        val capsMode = connection.getCursorCapsMode(info.inputType)
        setShift(if (capsMode != 0) ShiftState.SHIFTED else ShiftState.OFF)
    }

    // endregion

    // region Feedback and theming

    /**
     * Keypress feedback must never be able to kill the keyboard.
     *
     * A missing permission or a vendor vibrator quirk throwing here would crash the IME process
     * mid-sentence and drop the user back to their previous keyboard, which is a far worse failure
     * than silently going without a buzz.
     */
    private fun performHaptic() {
        if (!settings.hapticEnabled) return

        val amplitude = (settings.hapticStrength * 255f).toInt().coerceIn(1, 255)
        val vibrator = vibrator
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(HAPTIC_DURATION_MS, amplitude))
            } else {
                keyboardView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Haptic feedback unavailable; continuing without it", e)
        }
    }

    private fun performSound(key: Key) {
        if (!settings.soundEnabled) return
        val effect = when (key.type) {
            KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            KeyType.DELETE -> AudioManager.FX_KEYPRESS_DELETE
            KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        try {
            audioManager?.playSoundEffect(effect, settings.soundVolume)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Keypress sound unavailable; continuing without it", e)
        }
    }

    private fun enterActionFor(imeOptions: Int): EnterAction = when (
        imeOptions and EditorInfo.IME_MASK_ACTION
    ) {
        EditorInfo.IME_ACTION_GO -> EnterAction.GO
        EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
        EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
        EditorInfo.IME_ACTION_NEXT -> EnterAction.NEXT
        EditorInfo.IME_ACTION_DONE -> EnterAction.DONE
        else -> EnterAction.RETURN
    }

    private fun resolveTheme(): KeyboardTheme {
        val nightMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val inDarkMode = nightMask == Configuration.UI_MODE_NIGHT_YES
        return Themes.resolve(
            context = this,
            themeId = settings.themeId,
            systemInDarkMode = inDarkMode,
            followSystem = settings.followSystemDarkMode,
        )
    }

    private fun isPasswordField(info: EditorInfo): Boolean {
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val klass = info.inputType and InputType.TYPE_MASK_CLASS
        return when (klass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    // endregion

    private companion object {
        const val TAG = "SlideIME"
        const val DOUBLE_TAP_WINDOW_MS = 300L
        const val DOUBLE_SPACE_WINDOW_MS = 800L
        const val HAPTIC_DURATION_MS = 12L
        const val MAX_WORD_DELETE_CHARS = 2048
        const val MAX_SEARCH_QUERY_LENGTH = 64
        const val MAX_SEARCH_RESULTS = 6

        /** Text read either side of the cursor when reopening a word. */
        const val MAX_REOPEN_CHARS = 48

        /** Below this, a word has too many neighbours for the strip to be worth anything. */
        const val MIN_REOPEN_LENGTH = 2

        /** Above this it is not a word, and the suggester would refuse it anyway. */
        const val MAX_REOPEN_LENGTH = 28

        /** The key types that change the field, and so expect a selection update of their own. */
        val EDITING_KEYS = setOf(
            KeyType.CHARACTER,
            KeyType.SPACE,
            KeyType.DELETE,
            KeyType.ENTER,
        )
    }
}
