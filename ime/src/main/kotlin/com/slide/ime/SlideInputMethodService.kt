package com.slide.ime

import android.content.Context
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.Toast
import com.slide.asr.VoiceInput
import com.slide.asr.VoiceInputClient
import com.slide.asr.WhisperModel
import com.slide.core.emoji.EmojiData
import com.slide.core.emoji.EmojiLoader
import com.slide.core.layout.Key
import com.slide.core.layout.KeyType
import com.slide.core.layout.KeyboardLayout
import com.slide.core.layout.Layouts
import com.slide.core.settings.KeyboardSettings
import com.slide.core.settings.SettingsRepository
import com.slide.core.theme.KeyboardTheme
import com.slide.core.theme.Themes
import com.slide.engine.gesture.GestureDecoder
import com.slide.engine.gesture.GestureDecodingEngine
import com.slide.engine.gesture.GestureKeyMap
import com.slide.engine.gesture.GesturePoint
import com.slide.engine.gesture.NeuralGestureDecoder
import com.slide.engine.gesture.SwipeLexiconTrie
import com.slide.engine.lexicon.Bigrams
import com.slide.engine.lexicon.BigramLoader
import com.slide.engine.lexicon.Lexicon
import com.slide.engine.lexicon.LexiconLoader
import com.slide.engine.lexicon.Trigrams
import com.slide.engine.lexicon.TrigramLoader
import com.slide.engine.lexicon.UserBigrams
import com.slide.engine.lexicon.UserDictionary
import com.slide.engine.lexicon.UserDictionaryStore
import com.slide.engine.suggest.SpatialTouchModel
import com.slide.engine.suggest.TypingSuggester
import com.slide.ime.text.AndroidGraphemeBoundaries
import com.slide.ime.text.EditorInputPolicy
import com.slide.ime.text.EditorKeyboardMode
import com.slide.ime.text.PrecedingWord
import com.slide.ime.text.SelectionUpdate
import com.slide.ime.text.matchTypedCase
import com.slide.ime.view.EmojiGlyphs
import com.slide.ime.view.EmojiPanelView
import com.slide.ime.view.EnterAction
import com.slide.ime.view.KeyboardFrame
import com.slide.ime.view.KeyboardView
import com.slide.ime.view.ShiftState
import com.slide.ime.view.SuggestionStripView
import com.slide.ime.view.VoiceOverlayView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var settingsLoaded = false

    /**
     * Connected to the speech process only while the keyboard is on screen.
     *
     * Staying bound would keep a process alive — and eventually a few hundred megabytes of model
     * with it — for as long as Slide is the selected keyboard, which is essentially always.
     */
    private val voiceClientDelegate = lazy {
        VoiceInputClient(this).also { it.listener = this }
    }
    private val voiceClient by voiceClientDelegate

    /** Which of the three key layers is on screen. */
    private enum class Layer { ALPHA, SYMBOLS, SYMBOLS_ALT }

    private var layer = Layer.ALPHA
    private var editorBaseLayout: KeyboardLayout = Layouts.QwertyEn
    private var searchPreviousLayer = Layer.ALPHA
    private var searchPreviousShift = ShiftState.OFF
    private var preservedCapsLock = false
    private var lastShiftTapMs = 0L
    private var lastSpaceCommitMs = 0L

    /** Set when the field or the user asks us not to learn from input. */
    // Fail closed until the first persisted settings snapshot arrives. The language engines are
    // gated on that snapshot too, but emoji recents can become interactive independently.
    private var incognito = true

    /** Password fields get no suggestions at all, not merely no learning. */
    private var passwordField = false

    /** One policy shared by typing, swiping, prediction, and personalized learning. */
    private var editorInputPolicy = EditorInputPolicy.NaturalText

    /** Whether the active editor set IME_FLAG_NO_PERSONALIZED_LEARNING. */
    private var editorRequestsNoLearning = false

    /**
     * Null until the lexicon finishes loading, and permanently null if the asset is unreadable.
     *
     * Swipes that land before it is ready commit nothing rather than queueing, since a word
     * appearing seconds after the gesture would be worse than none at all.
     */
    private var gestureDecoder: GestureDecodingEngine? = null

    /** Shares the lexicon with the decoder; null until it loads, for the same reason. */
    private var typingSuggester: TypingSuggester? = null

    /**
     * The key geometry currently used by both typing and gesture scoring.
     *
     * GestureKeyMap is immutable. Reusing one until the view's bounds or layout changes avoids
     * rebuilding both it and TypingSuggester's neighbour cache on every character.
     */
    private var gestureKeyMapCache: GestureKeyMap? = null

    /**
     * The words this person uses that the shipped dictionary does not have.
     *
     * Created up front rather than with the lexicon, because it is the one dictionary that is
     * useful before anything has loaded and must never be missed a word because a load was slow.
     */
    private val userDictionary = UserDictionary()

    /** The word pairs this person writes, learned alongside the words themselves. */
    private val userBigrams = UserBigrams()

    /** Per-key touch offsets learned only from words this person confirmed. */
    private val spatialTouchModel = SpatialTouchModel()

    private val userDictionaryStore by lazy { UserDictionaryStore(applicationContext) }

    private val learnedDataReady = CompletableDeferred<Unit>()
    private var learnedLoadStarted = false
    private val learnedPersistence = LearnedDataPersistenceState()
    private var observedLearnedDataClearEpoch: Long? = null

    private var emojiData: EmojiData? = null
    private var emojiRenderable: Array<IntArray>? = null
    private var recentEmoji: List<String> = emptyList()

    /** Monotonically identifies the editor whose connection may receive asynchronous input. */
    private var editorGeneration = 0L

    /** Editor generation that explicitly started the active voice session, or null when inactive. */
    private var voiceEditorGeneration: Long? = null

    /** Prevents an old, untagged speech callback being mistaken for a newly started session. */
    private var voiceCancellationPending = false

    /** Absolute selection cached from framework callbacks and updated optimistically by cursor swipes. */
    private var cachedSelectionStart = -1
    private var cachedSelectionEnd = -1

    /**
     * The word being typed, held as composing text in the editor rather than committed.
     *
     * Composing text is what makes autocorrect safe: the word is a region the editor knows about,
     * so replacing it is one atomic call rather than a character count we compute ourselves and
     * hope still matches. It is also what puts the underline under the word, which is the only
     * warning the user gets that the keyboard has opinions about it.
     */
    private val composing = StringBuilder()

    /**
     * Where each character of [composing] was touched, as x,y pairs, or NaN where unknown.
     *
     * Kept in step with [composing] rather than derived from it, because it cannot be derived: the
     * same letter typed twice has two different touches, and that difference is the whole value.
     * Preallocated, so tracking it costs no allocation on the keypress path.
     */
    private val composingTouches = FloatArray(MAX_TRACKED_TOUCHES * 2) { Float.NaN }

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
     * True when this word began as literal committed text rather than composing text.
     *
     * The model loads asynchronously and settings can change while a key is down. Once even one
     * character of a word has bypassed composition, the rest must do the same; starting later
     * would let autocorrect replace only the suffix. A separator, cursor move, or full-word reopen
     * is the safe point at which the latch is reset.
     */
    private var literalWordInProgress = false

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

    /** Context and candidate whose observation must be repaired if an alternative is selected. */
    private var lastGestureLearnedPair: Pair<String, String>? = null

    /** Coalesces partial traces so model inference never queues behind the user's finger. */
    private var pendingGesturePreview: List<GesturePoint>? = null
    private var gesturePreviewJob: Job? = null
    private var gesturePreviewGeneration = 0L

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
                val previous = settings
                settings = updated
                settingsLoaded = true
                incognito = editorRequestsNoLearning ||
                    !editorInputPolicy.allowsPersonalizedLearning ||
                    updated.incognitoModeEnabled

                val previousClearEpoch = observedLearnedDataClearEpoch
                observedLearnedDataClearEpoch = updated.learnedDataClearEpoch
                if (previousClearEpoch == null && !learnedLoadStarted) {
                    // Establish the persisted clear epoch before reading either file. Otherwise a
                    // settings clear that wins startup could become our baseline after stale data
                    // had already been restored into memory.
                    learnedLoadStarted = true
                    loadLearnedData()
                } else if (
                    previousClearEpoch != null &&
                    previousClearEpoch != updated.learnedDataClearEpoch
                ) {
                    clearLearnedDataFromMemoryAndDisk()
                }

                if (
                    previous.showNumberRow != updated.showNumberRow ||
                    previous.keyHeightScale != updated.keyHeightScale ||
                    previous.bottomPaddingDp != updated.bottomPaddingDp
                ) {
                    gestureKeyMapCache = null
                }
                keyboardView?.settings = updated
                emojiPanel?.skinTone = updated.emojiSkinTone
                updateGestureAvailability()

                val suggestionPolicyChanged =
                    previous.suggestionsEnabled != updated.suggestionsEnabled ||
                        previous.autocorrectEnabled != updated.autocorrectEnabled ||
                        previous.blockOffensiveWords != updated.blockOffensiveWords
                if (!fieldSuggestionsEnabled()) {
                    if (composing.isNotEmpty()) {
                        // The already-entered prefix is now committed literally. Keep the rest of
                        // this same word literal even if the setting is immediately turned back on.
                        literalWordInProgress = true
                        abandonComposing()
                    } else {
                        clearSuggestions()
                    }
                } else if (suggestionPolicyChanged) {
                    when {
                        composing.isNotEmpty() -> updateTypingSuggestions()
                        stripMode == StripMode.Prediction -> {
                            clearSuggestions()
                            updatePredictions()
                        }
                        stripMode in setOf(StripMode.Gesture, StripMode.GesturePreview) &&
                            previous.blockOffensiveWords != updated.blockOffensiveWords ->
                            clearSuggestions()
                    }
                }

                refreshSuggestionEmptyMessage()
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
            val (lexicon, bigrams, trigrams, decoder, trie) = withContext(Dispatchers.IO) {
                val words = LexiconLoader.load(applicationContext)
                // The model is keyed by lexicon index, so it is worthless without the lexicon and
                // is not worth reading if that failed. A null model is survivable on its own: the
                // corrector falls back to spelling alone.
                val pairs = words?.let { BigramLoader.load(applicationContext, it) }
                val triples = words?.let { TrigramLoader.load(applicationContext, it) }
                val trie = words?.let(::SwipeLexiconTrie)
                val loadedDecoder = words?.let {
                    val fallback = GestureDecoder(it, bigrams = pairs, trigrams = triples)
                    NeuralGestureDecoder.createOrNull(
                        context = applicationContext,
                        lexicon = it,
                        bigrams = pairs,
                        userBigrams = userBigrams,
                        trie = requireNotNull(trie),
                        trigrams = triples,
                        fallback = fallback,
                    ) ?: fallback
                }
                LoadedLanguageResources(words, pairs, triples, loadedDecoder, trie)
            }
            // Typing and swiping are the only paths that learn. Do not publish either engine until
            // the persisted dictionaries have been restored, or a word learned in the gap could
            // be wiped by restore completing a moment later.
            learnedDataReady.await()
            if (lexicon != null) {
                gestureDecoder = decoder
                typingSuggester = TypingSuggester(
                    lexicon,
                    bigrams = bigrams,
                    trigrams = trigrams,
                    userDictionary = userDictionary,
                    userBigrams = userBigrams,
                    spatialModel = spatialTouchModel,
                    trie = requireNotNull(trie),
                )
                Log.i(
                    TAG,
                    "${if (decoder is NeuralGestureDecoder) "Neural" else "Fallback"} decoder " +
                        "and suggester ready with ${lexicon.size} words" +
                        (bigrams?.let { ", ${it.pairCount} bigrams" } ?: ", no bigrams") +
                        (trigrams?.let { ", ${it.tripleCount} trigrams" } ?: ", no trigrams"),
                )
                updateGestureAvailability()
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
            emojiRenderable = renderable
            emojiPanel?.apply {
                data = catalogue
                this.renderable = renderable
            }
            Log.i(TAG, "Emoji ready: ${renderable.sumOf { it.size }} of ${catalogue.size} drawable")
        }
    }

    private data class LoadedLanguageResources(
        val lexicon: Lexicon?,
        val bigrams: Bigrams?,
        val trigrams: Trigrams?,
        val decoder: GestureDecodingEngine?,
        val trie: SwipeLexiconTrie?,
    )

    override fun onCreateInputView(): View {
        val theme = resolveTheme()

        val strip = SuggestionStripView(this).apply {
            listener = this@SlideInputMethodService
            keyboardTheme = theme
            voiceEnabled = editorInputPolicy.allowsVoice
        }
        val view = KeyboardView(this).apply {
            listener = this@SlideInputMethodService
            settings = this@SlideInputMethodService.settings
            keyboardTheme = theme
            keyboardLayout = Layouts.QwertyEn
            enterAction = EnterAction.RETURN
            gestureTypingAvailable = false
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (
                    left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom
                ) {
                    gestureKeyMapCache = null
                }
            }
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
                emojiRenderable?.let { cached -> renderable = cached }
            }
            visibility = View.GONE
        }

        suggestionStrip = strip
        keyboardView = view
        gestureKeyMapCache = null
        voiceOverlay = overlay
        emojiPanel = emoji
        updateGestureAvailability()

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

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // This callback precedes the visual input-view transition, so it closes the small window in
        // which an old speech result could otherwise see the framework's new InputConnection.
        editorGeneration++
        cancelVoiceForEditorTransition()
    }

    override fun onFinishInput() {
        editorGeneration++
        cancelVoiceForEditorTransition()
        super.onFinishInput()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorGeneration++
        cancelVoiceForEditorTransition()
        exitEmojiSearch(showPicker = false)
        layer = Layer.ALPHA
        hideEmojiPanel()
        editorInputPolicy = EditorInputPolicy.from(info.inputType)
        editorBaseLayout = layoutFor(editorInputPolicy.keyboardMode)
        passwordField = editorInputPolicy.isPassword
        editorRequestsNoLearning =
            (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        incognito = !settingsLoaded || editorRequestsNoLearning ||
            !editorInputPolicy.allowsPersonalizedLearning ||
            settings.incognitoModeEnabled
        literalWordInProgress = false
        abandonComposing()
        gestureKeyMapCache = null
        lastShiftTapMs = 0L
        preservedCapsLock = false
        // EditorInfo already carries absolute initial offsets. Using it avoids a potentially slow
        // getExtractedText Binder round trip on the IME main thread; later changes come through
        // onUpdateSelection, also as absolute document offsets.
        cachedSelectionStart = info.initialSelStart
        cachedSelectionEnd = info.initialSelEnd

        keyboardView?.apply {
            shiftState = ShiftState.OFF
            keyboardLayout = editorBaseLayout
            settings = this@SlideInputMethodService.settings
            enterAction = enterActionFor(info.imeOptions)
        }
        suggestionStrip?.voiceEnabled = editorInputPolicy.allowsVoice
        updateGestureAvailability()
        applyTheme(resolveTheme())
        // Candidates from the previous field would be nonsense here, and tapping one would try to
        // rewrite text that belongs to a different editor.
        clearSuggestions()
        refreshSuggestionEmptyMessage()
        updateShiftFromCursor()
        updatePredictions()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        gestureKeyMapCache = null
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
        editorGeneration++
        cancelVoiceForEditorTransition()
        exitEmojiSearch(showPicker = false)
        hideEmojiPanel()
        if (voiceClientDelegate.isInitialized()) voiceClient.unbind()
        voiceCancellationPending = false
        if (learnedPersistence.deletionPending) scheduleLearnedDataDelete()
        saveLearnedWords()
    }

    /**
     * Lets go of the word in progress when the field does.
     *
     * Composing text outlives the input view otherwise, and the next editor would open with an
     * underlined fragment of the last one's sentence sitting in it.
     */
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        editorGeneration++
        cancelVoiceForEditorTransition()
        literalWordInProgress = false
        abandonComposing()
        cachedSelectionStart = -1
        cachedSelectionEnd = -1
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
        cancelVoiceForEditorTransition()
        if (voiceClientDelegate.isInitialized()) voiceClient.unbind()
        val finalLearnedData = captureFinalLearnedData()
        scope.cancel()
        // Neural close shares its monitor with decode, so a native preview already in flight
        // finishes before its modules are destroyed. Cancelling first prevents another preview
        // from entering after that destruction.
        (gestureDecoder as? AutoCloseable)?.close()
        finalLearnedData?.let(::flushFinalLearnedData)
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

    override fun onKeyCommit(key: Key, text: String, touchX: Float, touchY: Float) {
        if (key.type != KeyType.SHIFT) lastShiftTapMs = 0L
        if (keyboardView?.searchMode == true) {
            handleSearchKey(key, text)
            return
        }

        val connection = currentInputConnection ?: return

        if (key.type in EDITING_KEYS) selfEdit = true

        // Any keypress ends the swiped word: the candidates no longer describe what is in front of
        // the cursor, so leaving them up would offer a replacement for text that has moved on. A
        // typing strip is the opposite -- it is about to be rebuilt from the new keystroke.
        if (
            stripMode == StripMode.Gesture ||
            stripMode == StripMode.GesturePreview ||
            stripMode == StripMode.Prediction
        ) {
            clearSuggestions()
        }

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
            KeyType.CHARACTER -> handleCharacter(connection, text, touchX, touchY)
        }
    }

    override fun onGestureComplete(points: List<GesturePoint>): Boolean {
        if (!settings.gestureTypingEnabled || !editorInputPolicy.allowsSuggestions) {
            clearSuggestions()
            return false
        }
        if (searchModeShown || emojiPanelShown || voiceOverlayShown || layer != Layer.ALPHA) return false

        val decoder = gestureDecoder ?: return false
        val connection = currentInputConnection ?: return false
        val keys = currentGestureKeyMap() ?: return false

        val context = precedingContextForSwipe()
        val candidates = decoder.decode(
            points = points,
            keys = keys,
            blockOffensive = settings.blockOffensiveWords,
            previousWord = context.previous,
            previousPreviousWord = context.older,
        )
        val best = candidates.firstOrNull() ?: return false

        // Only settle a typed prefix once decoding has definitely produced replacement input. A
        // rejected path falls back to its starting key in KeyboardView and must not mutate state.
        if (composing.isNotEmpty()) selfEdit = true
        finishComposing(connection)

        selfEdit = true
        commitGestureWord(connection, best.word)
        literalWordInProgress = false
        if (settings.suggestionsEnabled) {
            stripMode = StripMode.Gesture
            suggestionStrip?.setSuggestions(candidates.map { it.word })
        } else {
            clearSuggestions()
        }
        return true
    }

    override fun onGesturePreview(points: List<GesturePoint>) {
        if (!settings.gestureTypingEnabled || !editorInputPolicy.allowsSuggestions) return
        if (searchModeShown || emojiPanelShown || voiceOverlayShown || layer != Layer.ALPHA) return
        if (gestureDecoder == null || currentGestureKeyMap() == null) return

        pendingGesturePreview = points
        if (gesturePreviewJob?.isActive == true) return
        val generation = gesturePreviewGeneration
        gesturePreviewJob = scope.launch {
            while (generation == gesturePreviewGeneration) {
                val trace = pendingGesturePreview ?: break
                pendingGesturePreview = null
                val decoder = gestureDecoder ?: break
                val keys = currentGestureKeyMap() ?: break
                val blockOffensive = settings.blockOffensiveWords
                val context = precedingContextForSwipe()
                val candidates = withContext(Dispatchers.Default) {
                    decoder.decode(
                        trace,
                        keys,
                        blockOffensive,
                        context.previous,
                        context.older,
                    )
                }
                if (generation != gesturePreviewGeneration) break
                // A newer trace will be decoded immediately; avoid flashing a result that is
                // already stale while the finger is still moving.
                if (pendingGesturePreview == null && candidates.isNotEmpty()) {
                    stripMode = StripMode.GesturePreview
                    lastGestureCommit = null
                    suggestionStrip?.setSuggestions(candidates.map { it.word })
                }
            }
        }
    }

    override fun onGesturePreviewCancelled() {
        gesturePreviewGeneration++
        pendingGesturePreview = null
        gesturePreviewJob?.cancel()
        gesturePreviewJob = null
        if (stripMode == StripMode.GesturePreview) clearSuggestions()
    }

    override fun onCursorMove(steps: Int) {
        if (steps == 0) return
        val connection = currentInputConnection ?: return
        selfEdit = true
        val start = cachedSelectionStart
        val end = cachedSelectionEnd
        if (start < 0 || end < 0) {
            sendCursorKeyEvents(connection, steps)
            return
        }

        val target = if (start != end) {
            if (steps < 0) minOf(start, end) else maxOf(start, end)
        } else if (steps < 0) {
            val before = connection.getTextBeforeCursor(MAX_GRAPHEME_CONTEXT_CHARS, 0)?.toString()
            if (before.isNullOrEmpty()) {
                sendCursorKeyEvents(connection, steps)
                return
            }
            (start - (before.length - AndroidGraphemeBoundaries.move(before, before.length, steps)))
                .coerceAtLeast(0)
        } else {
            val after = connection.getTextAfterCursor(MAX_GRAPHEME_CONTEXT_CHARS, 0)?.toString()
            if (after.isNullOrEmpty()) {
                sendCursorKeyEvents(connection, steps)
                return
            }
            start + AndroidGraphemeBoundaries.move(after, 0, steps)
        }

        if (connection.setSelection(target, target)) {
            cachedSelectionStart = target
            cachedSelectionEnd = target
        } else {
            cachedSelectionStart = -1
            cachedSelectionEnd = -1
        }
        literalWordInProgress = false
        abandonComposing()
        updateShiftFromCursor()
        keyboardView?.announceForAccessibility("Cursor moved")
    }

    private fun sendCursorKeyEvents(connection: InputConnection, steps: Int) {
        val direction = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, direction))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, direction))
        }
        cachedSelectionStart = -1
        cachedSelectionEnd = -1
        literalWordInProgress = false
        abandonComposing()
        updateShiftFromCursor()
    }

    override fun onDeleteWordGesture() {
        val connection = currentInputConnection ?: return
        selfEdit = true
        finishComposing(connection)
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            connection.commitText("", 1)
            literalWordInProgress = false
            return
        }

        val before = connection.getTextBeforeCursor(MAX_WORD_DELETE_CHARS, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        var start = before.length
        while (start > 0 && before[start - 1].isWhitespace()) start--
        while (start > 0 && !before[start - 1].isWhitespace()) start--
        connection.deleteSurroundingText(before.length - start, 0)
        literalWordInProgress = false
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

        val previousWord = precedingWordForSwipe()
        learnPair(previousWord, word)
        lastGestureLearnedPair = previousWord?.let { it to word }

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
        when (stripMode) {
            StripMode.Gesture -> pickGestureAlternative(index, word)
            StripMode.Typing -> pickTypedSuggestion(word)
            StripMode.Prediction -> commitPrediction(word)
            StripMode.GesturePreview, StripMode.Empty -> Unit
        }
    }

    /**
     * Holding a candidate teaches the keyboard a word, or takes one back.
     *
     * The escape hatch that makes learning safe to do automatically. A word learned by mistake —
     * the same typo made twice — is otherwise defended for ever, and the moment the user notices
     * is the moment it is sitting in front of them in the strip.
     *
     * Which way it goes is not a choice the user has to make: a word already learned can only
     * sensibly be forgotten, and one that is not can only be learned. Anything the shipped
     * dictionary already knows is neither.
     */
    override fun onSuggestionHeld(index: Int, word: String) {
        performHaptic()
        val suggester = typingSuggester ?: return
        if (word.isEmpty() || suggester.knows(word)) {
            announce("$word is already in the dictionary")
            return
        }

        if (userDictionary.isTrusted(word)) {
            userDictionary.forget(word)
            userBigrams.forget(word)
            announce("Forgot $word")
        } else {
            if (incognito) return
            userDictionary.learn(word, weight = TRUSTED_AT_ONCE)
            announce("Learned $word")
        }
        learnedPersistence.markDirty()
        saveLearnedWords()
        // The strip was built against the old dictionary, so what it offers may have just changed.
        if (composing.isNotEmpty()) updateTypingSuggestions()
    }

    /**
     * Says something the user needs to know, without a window of our own to say it in.
     *
     * An IME has no UI outside the input view, and the input view is entirely spoken for. A toast
     * is the one channel that does not steal space from the keys.
     */
    private fun announce(message: String) {
        keyboardView?.announceForAccessibility(message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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

        selfEdit = true
        connection.beginBatchEdit()
        connection.deleteSurroundingText(previous.length, 0)
        connection.commitText(replacement, 1)
        connection.endBatchEdit()

        // The first-ranked word was only a machine guess. Selecting another candidate is direct
        // evidence: remove the wrong observation and teach the chosen pair instead.
        lastGestureLearnedPair?.let { (context, guessed) ->
            if (!incognito) {
                userBigrams.unlearn(context, guessed)
                userBigrams.learn(context, word)
                learnedPersistence.markDirty()
                saveLearnedWords()
            }
            lastGestureLearnedPair = context to word
        }

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
        lastGestureLearnedPair = null
        stripMode = StripMode.Empty
    }

    private fun refreshSuggestionEmptyMessage() {
        suggestionStrip?.setEmptyMessage(
            when {
                passwordField -> "Suggestions are off in password fields"
                !editorInputPolicy.allowsSuggestions -> "Suggestions are off in this field"
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
        searchPreviousShift = shiftState()
        keyboardView?.apply {
            shiftState = ShiftState.OFF
            keyboardLayout = Layouts.QwertyEn
            searchQuery = ""
            searchMode = true
            searchResults = recentEmoji.take(MAX_SEARCH_RESULTS)
        }
        suggestionStrip?.voiceEnabled = false
        suggestionStrip?.setEmptyMessage("Emoji search is open")
        clearSuggestions()
        updateGestureAvailability()
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
        if (layer == Layer.ALPHA && searchPreviousShift == ShiftState.LOCKED) {
            setShift(ShiftState.LOCKED)
        } else if (layer == Layer.ALPHA) {
            updateShiftFromCursor()
        }
        suggestionStrip?.voiceEnabled = editorInputPolicy.allowsVoice
        refreshSuggestionEmptyMessage()
        if (showPicker && emojiData != null) {
            emojiPanel?.reset()
            emojiPanel?.visibility = View.VISIBLE
        }
        updateGestureAvailability()
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
        if (voiceCancellationPending) {
            announce("Voice typing is still closing")
            return
        }
        if (!editorInputPolicy.allowsVoice || currentInputConnection == null) {
            announce("Voice typing is unavailable in this field")
            return
        }
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
        voiceEditorGeneration = editorGeneration
        updateGestureAvailability()
        voiceClient.start(WhisperModel.fromId(settings.voiceModelId))
    }

    override fun onVoiceDismissed(committed: Boolean) {
        if (voiceEditorGeneration != editorGeneration) {
            cancelVoiceForEditorTransition()
            return
        }
        if (committed) {
            voiceClient.stop() // the transcript arrives in onVoiceResult
        } else {
            voiceEditorGeneration = null
            voiceCancellationPending = true
            voiceClient.cancel()
            hideVoiceOverlay()
        }
    }

    override fun onVoiceState(state: VoiceInput.State) {
        if (voiceCancellationPending) {
            if (state == VoiceInput.State.Idle) voiceCancellationPending = false
            return
        }
        if (voiceEditorGeneration != editorGeneration) return
        voiceOverlay?.state = state
        // Idle after a result or a cancellation means the session is over. The overlay is already
        // hidden in those paths; this catches the speech process dying underneath us.
        if (state == VoiceInput.State.Idle && voiceOverlay?.errorText == null) {
            voiceEditorGeneration = null
            hideVoiceOverlay()
        }
    }

    override fun onVoiceLevel(level: Float) {
        if (voiceEditorGeneration != editorGeneration) return
        voiceOverlay?.setLevel(level)
    }

    override fun onVoiceResult(text: String) {
        if (voiceCancellationPending) return
        val resultGeneration = voiceEditorGeneration
        voiceEditorGeneration = null
        hideVoiceOverlay()
        if (
            resultGeneration == null ||
            resultGeneration != editorGeneration ||
            !editorInputPolicy.allowsVoice ||
            text.isBlank()
        ) return
        val connection = currentInputConnection ?: return
        selfEdit = true
        commitDictation(connection, text)
    }

    override fun onVoiceError(reason: String) {
        if (voiceCancellationPending) {
            voiceCancellationPending = false
            return
        }
        if (voiceEditorGeneration != editorGeneration) return
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
        literalWordInProgress = false
        updateShiftFromCursor()
    }

    private fun hideVoiceOverlay() {
        voiceOverlay?.apply {
            visibility = View.GONE
            errorText = null
            state = VoiceInput.State.Idle
        }
        updateGestureAvailability()
        setBackCallbackRegistered(emojiPanelShown)
    }

    /** Cancels asynchronous speech before an editor generation can be replaced. */
    private fun cancelVoiceForEditorTransition() {
        val hadSession = voiceEditorGeneration != null || voiceOverlayShown
        voiceEditorGeneration = null
        if (hadSession && voiceClientDelegate.isInitialized()) {
            voiceCancellationPending = true
            voiceClient.cancel()
        }
        hideVoiceOverlay()
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
        updateGestureAvailability()
        setBackCallbackRegistered(true)
    }

    private fun hideEmojiPanel() {
        emojiPanel?.apply {
            if (visibility == View.GONE) return@apply
            reset()
            visibility = View.GONE
        }
        updateGestureAvailability()
        setBackCallbackRegistered(voiceOverlayShown)
    }

    private val searchModeShown: Boolean
        get() = keyboardView?.searchMode == true

    private val emojiPanelShown: Boolean
        get() = emojiPanel?.visibility == View.VISIBLE

    override fun onEmojiPicked(emoji: String) {
        performHaptic()
        val connection = currentInputConnection ?: return
        selfEdit = true
        connection.commitText(emoji, 1)
        // Whatever the undo record pointed at is no longer what sits before the cursor.
        lastAutocorrect = null
        literalWordInProgress = false
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
        // Emoji are often multi-code-point ZWJ, tone, flag or keycap clusters, so this borrows the
        // key row's ICU grapheme-aware delete rather than leaving a partial glyph behind.
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

    private fun handleCharacter(
        connection: InputConnection,
        text: String,
        touchX: Float = Float.NaN,
        touchY: Float = Float.NaN,
    ) {
        // Typing with the cursor parked in the middle of a reopened word: the region can only grow
        // at its end, so settling it first is the only way the letter lands where the user is
        // looking.
        if (composing.isNotEmpty() && !composingAtEnd) abandonComposing()

        if (isWordCharacter(text)) {
            // A cursor can arrive at the edge of existing text without a useful selection callback
            // (notably on initial focus). Starting a one-character composing suffix there is just
            // as unsafe as starting one after an asynchronous model load.
            if (
                composing.isEmpty() &&
                !literalWordInProgress &&
                cursorTouchesWord(connection)
            ) {
                literalWordInProgress = true
            }

            val suggester = typingSuggester.takeIf { fieldSuggestionsEnabled() }
            val keys = suggester?.let { currentGestureKeyMap() }
            if (literalWordInProgress || suggester == null || keys == null) {
                if (composing.isNotEmpty()) {
                    literalWordInProgress = true
                    abandonComposing()
                }
                literalWordInProgress = true
                connection.commitText(text, 1)
                clearSuggestions()
            } else {
                recordTouch(composing.length, touchX, touchY)
                composing.append(text)
                connection.setComposingText(composing, 1)
                updateTypingSuggestions(suggester, keys)
            }
        } else {
            // Punctuation ends a word, so it settles whatever was pending first -- typing "teh,"
            // should correct exactly as "teh " does.
            finishComposing(connection)
            connection.commitText(text, 1)
            literalWordInProgress = false
        }

        if (shiftState() == ShiftState.SHIFTED) setShift(ShiftState.OFF)
        updateShiftFromCursor()
    }

    /** Whether a new composing region here would cover only a suffix of an existing word. */
    private fun cursorTouchesWord(connection: InputConnection): Boolean {
        val before = connection.getTextBeforeCursor(1, 0)?.lastOrNull()
        val after = connection.getTextAfterCursor(1, 0)?.firstOrNull()
        return before?.let(::isWordCharacter) == true || after?.let(::isWordCharacter) == true
    }

    /**
     * Notes where a character was touched, so the corrector can price a mis-hit by how close the
     * finger came rather than by how far apart two keys are.
     *
     * Anything past the tracked length is simply not recorded: the corrector refuses words that
     * long anyway, and a bounds check here is cheaper than growing an array on the keypress path.
     */
    private fun recordTouch(position: Int, x: Float, y: Float) {
        if (position !in 0 until MAX_TRACKED_TOUCHES) return
        composingTouches[position * 2] = x
        composingTouches[position * 2 + 1] = y
    }

    /**
     * Forgets the recorded touches.
     *
     * Called wherever [composing] is emptied or replaced by something that was not typed just now.
     * A stale touch is worse than no touch: it would price this word by where the last one was
     * typed, which is a confident answer drawn from the wrong evidence.
     */
    private fun clearTouches() {
        composingTouches.fill(Float.NaN)
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
        literalWordInProgress = false
        updateShiftFromCursor()
        updatePredictions()
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
            composing.setLength(AndroidGraphemeBoundaries.previousBoundary(composing, composing.length))
            recordTouch(composing.length, Float.NaN, Float.NaN)
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
            literalWordInProgress = false
            updateShiftFromCursor()
            return
        }

        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            connection.commitText("", 1)
            val collapsed = minOf(cachedSelectionStart, cachedSelectionEnd).takeIf { it >= 0 }
            if (collapsed != null) {
                cachedSelectionStart = collapsed
                cachedSelectionEnd = collapsed
            }
        } else {
            val before = connection.getTextBeforeCursor(MAX_GRAPHEME_CONTEXT_CHARS, 0)?.toString().orEmpty()
            if (before.isNotEmpty()) {
                val boundary = AndroidGraphemeBoundaries.previousBoundary(before, before.length)
                val toDelete = before.length - boundary
                connection.deleteSurroundingText(toDelete, 0)
                if (cachedSelectionStart == cachedSelectionEnd && cachedSelectionStart >= 0) {
                    cachedSelectionStart = (cachedSelectionStart - toDelete).coerceAtLeast(0)
                    cachedSelectionEnd = cachedSelectionStart
                }
            }
        }
        literalWordInProgress = cursorTouchesWord(connection)
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
        literalWordInProgress = false
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
        if (layer == Layer.ALPHA && target != Layer.ALPHA) {
            preservedCapsLock = shiftState() == ShiftState.LOCKED
        }
        layer = target
        gestureKeyMapCache = null
        if (target != Layer.ALPHA) setShift(ShiftState.OFF)
        keyboardView?.keyboardLayout = layoutFor(target)
        if (target == Layer.ALPHA) {
            if (preservedCapsLock) setShift(ShiftState.LOCKED) else updateShiftFromCursor()
        }
        updateGestureAvailability()
    }

    private fun layoutFor(layer: Layer) = when (layer) {
        Layer.ALPHA -> editorBaseLayout
        Layer.SYMBOLS -> Layouts.SymbolsEn
        Layer.SYMBOLS_ALT -> Layouts.SymbolsAltEn
    }

    private fun layoutFor(mode: EditorKeyboardMode): KeyboardLayout = when (mode) {
        EditorKeyboardMode.TEXT -> Layouts.QwertyEn
        EditorKeyboardMode.EMAIL -> Layouts.EmailEn
        EditorKeyboardMode.URI -> Layouts.UriEn
        EditorKeyboardMode.NUMBER, EditorKeyboardMode.PIN -> Layouts.NumberPad
        EditorKeyboardMode.SIGNED_NUMBER -> Layouts.SignedNumberPad
        EditorKeyboardMode.DECIMAL_NUMBER -> Layouts.DecimalPad
        EditorKeyboardMode.SIGNED_DECIMAL_NUMBER -> Layouts.SignedDecimalPad
        EditorKeyboardMode.PHONE -> Layouts.PhonePad
        EditorKeyboardMode.DATE -> Layouts.DatePad
        EditorKeyboardMode.TIME -> Layouts.TimePad
        EditorKeyboardMode.DATETIME -> Layouts.DateTimePad
    }

    private fun updateGestureAvailability() {
        keyboardView?.gestureTypingAvailable =
            settings.gestureTypingEnabled &&
            editorInputPolicy.allowsSuggestions &&
            gestureDecoder != null &&
            layer == Layer.ALPHA &&
            !searchModeShown &&
            !emojiPanelShown &&
            !voiceOverlayShown
    }

    // endregion

    // region The word being typed

    /** Whether language candidates are appropriate for this field and enabled by the user. */
    private fun fieldSuggestionsEnabled(): Boolean =
        settings.suggestionsEnabled && editorInputPolicy.allowsSuggestions

    /** Reuses immutable geometry until a layout-affecting event invalidates it. */
    private fun currentGestureKeyMap(): GestureKeyMap? {
        if (layer != Layer.ALPHA || searchModeShown) return null
        gestureKeyMapCache?.let { return it }
        return keyboardView?.gestureKeyMap()?.also { gestureKeyMapCache = it }
    }

    private fun updateTypingSuggestions() {
        val suggester = typingSuggester.takeIf { fieldSuggestionsEnabled() }
        val keys = suggester?.let { currentGestureKeyMap() }
        if (suggester == null || keys == null || composing.isEmpty()) {
            clearSuggestions()
            return
        }

        updateTypingSuggestions(suggester, keys)
    }

    private fun updateTypingSuggestions(suggester: TypingSuggester, keys: GestureKeyMap) {
        val context = precedingContext()
        val result = suggester.suggest(
            typed = composing.toString(),
            keys = keys,
            blockOffensive = settings.blockOffensiveWords,
            previousWord = context.previous,
            previousPreviousWord = context.older,
            touchPoints = composingTouches,
        )
        pendingAutocorrection = result.autocorrection
            .takeIf { settings.autocorrectEnabled && !recomposed }

        stripMode = StripMode.Typing
        suggestionStrip?.setSuggestions(result.words.map { it.word })
    }

    /**
     * Remembers a word the user committed on purpose.
     *
     * Three things never reach this. A field that asked not to be learned from — [incognito] covers
     * password fields too. A word the shipped dictionary already has, which would only crowd out
     * the ones it does not. And anything the user did not clearly choose: a word merely typed once
     * arrives here with a weight of one and is not defended until it happens again, because a
     * dictionary that learns typos on sight would defend them for ever.
     *
     * @param weight see [UserDictionary.learn]. Undoing an autocorrect is worth enough on its own,
     *   being the one moment the user is told what the keyboard thinks and says no.
     */
    private fun learnWord(word: String, weight: Int = 1) {
        if (incognito) return
        val suggester = typingSuggester ?: return
        if (word.isEmpty() || suggester.knows(word)) return
        if (!word.all { it.isLetter() || it == '\'' }) return

        userDictionary.learn(word, weight)
        learnedPersistence.markDirty()
    }

    /**
     * Remembers that this person wrote [word] after [previous].
     *
     * Unlike a word, a pair needs no threshold before it counts for anything: it only ever adds a
     * small, saturating bonus, so a one-off juxtaposition cannot outweigh what the corpus knows,
     * and there is nothing here for a typo to be defended by. What it does do is notice, quickly,
     * that this person keeps writing "kubectl apply" or "Sam Whitmore".
     */
    private fun learnPair(previous: String?, word: String) {
        if (incognito) return
        if (previous.isNullOrEmpty() || word.isEmpty()) return
        userBigrams.learn(previous, word)
        learnedPersistence.markDirty()
    }

    /** Learns geometry only from a word the person explicitly allowed or selected. */
    private fun learnTouches(typed: String, intended: String) {
        if (incognito) return
        val keys = currentGestureKeyMap() ?: return
        if (spatialTouchModel.observe(typed, intended, composingTouches, keys) > 0) {
            learnedPersistence.markDirty()
        }
    }

    /** Restores into temporary collections, then publishes them on the input thread. */
    private fun loadLearnedData() {
        val generation = learnedPersistence.beginLoad()
        scope.launch {
            var loadedResult: LearnedDataSnapshot? = null
            try {
                val loaded = withContext(Dispatchers.IO) {
                    // A prior service may still be committing its last snapshot. Waiting before
                    // taking the shared IO lock prevents this instance from restoring stale data
                    // and later overwriting that final commit.
                    LEARNED_DATA_FINALIZER_JOB?.join()
                    LEARNED_DATA_IO.withLock {
                        if (!learnedPersistence.isCurrent(generation)) return@withLock null
                        val words = UserDictionary()
                        val pairs = UserBigrams()
                        val touches = SpatialTouchModel()
                        val deletionCompleted = userDictionaryStore.completePendingDeletion()
                        if (deletionCompleted) {
                            userDictionaryStore.load(words)
                            userDictionaryStore.load(pairs)
                            userDictionaryStore.load(touches)
                        }
                        LearnedDataSnapshot(
                            words = words,
                            pairs = pairs,
                            touches = touches,
                            deletionPending = !deletionCompleted,
                        )
                    }
                }
                loadedResult = loaded

                if (loaded != null && learnedPersistence.isCurrent(generation)) {
                    // No engine capable of learning is published until learnedDataReady completes.
                    // Merging the current values as well makes this robust to any future early
                    // learning path without letting restore erase it.
                    userDictionary.restore(loaded.words.entries() + userDictionary.entries())
                    userBigrams.restore(loaded.pairs.entries() + userBigrams.entries())
                    // Loading is complete before any typing engine is published, so unlike words
                    // and pairs there cannot yet be a concurrent touch observation to merge.
                    spatialTouchModel.restore(loaded.touches.entries())
                }
            } finally {
                val accepted = learnedPersistence.finishLoad(
                    loadGeneration = generation,
                    pendingDeletion = loadedResult?.deletionPending,
                )
                if (accepted && learnedPersistence.deletionPending) scheduleLearnedDataDelete()
                if (!learnedDataReady.isCompleted) learnedDataReady.complete(Unit)
                saveLearnedWords()
            }
        }
    }

    /**
     * Applies a settings-screen clear to the live model and orders the disk delete after old IO.
     *
     * The first settings emission establishes the epoch baseline; only later changes call this.
     * New learning while deletion is queued remains in memory and is saved after the last clear.
     */
    private fun clearLearnedDataFromMemoryAndDisk() {
        learnedPersistence.requestClear()
        userDictionary.clear()
        userBigrams.clear()
        spatialTouchModel.clear()

        if (composing.isNotEmpty()) updateTypingSuggestions()
        if (stripMode == StripMode.Prediction) updatePredictions()

        scheduleLearnedDataDelete()
    }

    /** Starts one bounded-backoff delete sequence; a newer clear gets a follow-up sequence. */
    private fun scheduleLearnedDataDelete() {
        val ticket = learnedPersistence.beginDeletion() ?: return
        scope.launch {
            val deleted = deleteLearnedDataWithRetry()
            val current = learnedPersistence.finishDeletion(ticket, deleted)
            if (!current) {
                // Another settings request persisted a newer marker while this IO result was on
                // its way back to the main thread. That marker needs its own proved completion.
                scheduleLearnedDataDelete()
                return@launch
            }
            if (!deleted) {
                Log.w(TAG, "Learned-data deletion remains pending after retry attempts")
            }
            // If new observations arrived during deletion, persist their clean post-clear snapshot.
            // A failed delete with no new data waits for the next lifecycle save opportunity rather
            // than looping forever on storage that may remain unavailable.
            if (deleted || learnedPersistence.dirty) {
                saveLearnedWords()
            }
        }
    }

    private suspend fun deleteLearnedDataWithRetry(): Boolean {
        repeat(LEARNED_DATA_DELETE_ATTEMPTS) { attempt ->
            val deleted = withContext(Dispatchers.IO) {
                LEARNED_DATA_IO.withLock { userDictionaryStore.completePendingDeletion() }
            }
            if (deleted) return true
            if (attempt + 1 < LEARNED_DATA_DELETE_ATTEMPTS) {
                delay(LEARNED_DATA_DELETE_RETRY_MS * (attempt + 1))
            }
        }
        return false
    }

    /**
     * Writes the learned words out, at a moment when nothing is being typed.
     *
     * Saving on every word would put a file write on the keypress path for a file that only has to
     * survive the process, and the keyboard leaving the screen is both frequent enough to lose
     * very little and quiet enough to cost nothing.
     */
    private fun saveLearnedWords() {
        val ticket = learnedPersistence.beginSave() ?: return

        val words = UserDictionary().also { it.restore(userDictionary.entries()) }
        val pairs = UserBigrams().also { it.restore(userBigrams.entries()) }
        val touches = SpatialTouchModel().also { it.restore(spatialTouchModel.entries()) }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                LEARNED_DATA_IO.withLock {
                    if (!learnedPersistence.isCurrent(ticket.generation)) return@withLock null
                    val deleted =
                        !ticket.completePendingDeletionFirst ||
                            userDictionaryStore.completePendingDeletion()
                    if (!deleted) {
                        LearnedDataWriteResult(saved = false, pendingDeleteResolved = false)
                    } else {
                        val wordsSaved = userDictionaryStore.save(words)
                        val pairsSaved = userDictionaryStore.save(pairs)
                        val touchesSaved = userDictionaryStore.save(touches)
                        LearnedDataWriteResult(
                            saved = wordsSaved && pairsSaved && touchesSaved,
                            pendingDeleteResolved = true,
                        )
                    }
                }
            }

            learnedPersistence.finishSave(
                ticket = ticket,
                saved = result?.saved,
                pendingDeletionResolved = result?.pendingDeleteResolved == true,
            )
            if (result?.saved != false) {
                // A successful snapshot may have raced with newer observations. Flush that newer
                // dirty state now; failures wait for the next normal save opportunity rather than
                // spinning on storage that may remain unavailable.
                saveLearnedWords()
            } else {
                Log.w(TAG, "Learned-data snapshot remains dirty after a failed save")
            }
        }
    }

    private data class LearnedDataSnapshot(
        val words: UserDictionary,
        val pairs: UserBigrams,
        val touches: SpatialTouchModel,
        val deletionPending: Boolean,
    )

    private data class LearnedDataWriteResult(
        val saved: Boolean,
        val pendingDeleteResolved: Boolean,
    )

    /** Captures only when destruction could otherwise strand a write or a clear. */
    private fun captureFinalLearnedData(): FinalLearnedData? {
        if (!learnedPersistence.needsFinalization) return null

        val words = UserDictionary().also { it.restore(userDictionary.entries()) }
        val pairs = UserBigrams().also { it.restore(userBigrams.entries()) }
        val touches = SpatialTouchModel().also { it.restore(spatialTouchModel.entries()) }
        return FinalLearnedData(
            words = words,
            pairs = pairs,
            touches = touches,
            completePendingDeletionFirst = learnedPersistence.clearOutstanding,
        )
    }

    /** Lets the final snapshot outlive cancellation of the service's ordinary coroutine scope. */
    private fun flushFinalLearnedData(data: FinalLearnedData) {
        val previousFinalizer = LEARNED_DATA_FINALIZER_JOB
        val finalizer = LEARNED_DATA_FINALIZER_SCOPE.launch {
            previousFinalizer?.join()
            val completed = LEARNED_DATA_IO.withLock {
                val deleted =
                    !data.completePendingDeletionFirst ||
                        userDictionaryStore.completePendingDeletion()
                if (!deleted) {
                    false
                } else {
                    // Persist even an empty snapshot. `deletionPending` is deliberately
                    // conservative after any failed save, so an empty model can mean the user
                    // forgot their final word rather than requested a full clear.
                    val wordsSaved = userDictionaryStore.save(data.words)
                    val pairsSaved = userDictionaryStore.save(data.pairs)
                    val touchesSaved = userDictionaryStore.save(data.touches)
                    wordsSaved && pairsSaved && touchesSaved
                }
            }

            if (!completed) {
                Log.w(TAG, "Final learned-data flush did not complete successfully")
            }
        }
        // Assigned before onDestroy returns, so a replacement service can join this exact write.
        LEARNED_DATA_FINALIZER_JOB = finalizer
    }

    private data class FinalLearnedData(
        val words: UserDictionary,
        val pairs: UserBigrams,
        val touches: SpatialTouchModel,
        val completePendingDeletionFirst: Boolean,
    )

    /** The word before the one being typed, for the corrector to weigh candidates against. */
    private fun precedingWord(): String? =
        textBehindCursor()?.let(PrecedingWord::of)

    private fun precedingContext(): PrecedingWord.Context =
        textBehindCursor()?.let(PrecedingWord::contextOf) ?: PrecedingWord.Context(null, null)

    /** The word before a swipe, which has no fragment in front of the cursor to step over. */
    private fun precedingWordForSwipe(): String? =
        textBehindCursor()?.let(PrecedingWord::beforeNewWord)

    private fun precedingContextForSwipe(): PrecedingWord.Context =
        textBehindCursor()?.let(PrecedingWord::contextBeforeNewWord)
            ?: PrecedingWord.Context(null, null)

    private fun textBehindCursor(): String? =
        currentInputConnection?.getTextBeforeCursor(MAX_CONTEXT_CHARS, 0)?.toString()

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
        val previous = precedingWord()
        var corrected = false

        if (correction != null && !correction.equals(typed, ignoreCase = true)) {
            val cased = matchTypedCase(typed, correction)
            connection.setComposingText(cased, 1)
            lastAutocorrect = Autocorrect(
                original = typed,
                applied = cased,
                previous = previous,
                touches = composingTouches.copyOf(typed.length * 2),
                keys = currentGestureKeyMap(),
            )
            corrected = true
        } else if (!recomposed) {
            // Settled exactly as typed, with the keyboard offering no objection. That is the
            // ordinary way a word the dictionary has never heard of gets into the language.
            // Reopened words are excluded: the user went back to look at one, not to write it.
            learnWord(typed)
            learnTouches(typed, typed)
        }

        // Whatever actually landed in the text is what followed the previous word, whether that is
        // what was typed or what it was corrected to. Read before the region is settled, so the
        // word in progress is still there to be stepped over.
        learnPair(previous, if (corrected) lastAutocorrect?.applied.orEmpty() else typed)

        connection.finishComposingText()
        composing.setLength(0)
        clearTouches()
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
        clearTouches()
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

        // The clearest signal a keyboard ever gets. The user was shown what it thought they meant
        // and said no, in the one gesture that means exactly that and nothing else. A word rescued
        // this way is trusted from now on rather than waiting to be typed again.
        learnWord(undo.original, weight = TRUSTED_AT_ONCE)
        if (!incognito && !undo.previous.isNullOrEmpty()) {
            userBigrams.unlearn(undo.previous, undo.applied)
            userBigrams.learn(undo.previous, undo.original)
            learnedPersistence.markDirty()
        }
        val keys = undo.keys
        if (!incognito && keys != null && undo.touches != null) {
            if (spatialTouchModel.observe(undo.original, undo.original, undo.touches, keys) > 0) {
                learnedPersistence.markDirty()
            }
        }

        updateShiftFromCursor()
        return true
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
        // The dictionary is lowercase. Whether this word is new or reopened, swapping it for a
        // correction must not quietly undo the capitalization the person typed.
        val replacement = matchTypedCase(composing.toString(), word)
        val previous = precedingWord()

        // A strip tap is explicit confirmation, including when it chooses a correction. Learn
        // before clearing the composing trace; insertions in the intended word are aligned and
        // skipped by the spatial model because no physical touch exists for them.
        learnTouches(composing.toString(), replacement)
        learnPair(previous, replacement)

        // Reaching past the keyboard's own first choice to pick out what they wrote is a deliberate
        // choice, and means the same thing as undoing a correction.
        if (word.equals(composing.toString(), ignoreCase = true)) {
            learnWord(word, weight = TRUSTED_AT_ONCE)
        }

        selfEdit = true
        connection.beginBatchEdit()
        connection.setComposingText(replacement, 1)
        connection.finishComposingText()
        if (appendSpace) connection.commitText(" ", 1)
        connection.endBatchEdit()

        composing.setLength(0)
        clearTouches()
        pendingAutocorrection = null
        lastAutocorrect = null
        recomposed = false
        composingAtEnd = true
        literalWordInProgress = false
        lastSpaceCommitMs = 0L
        clearSuggestions()
        updateShiftFromCursor()
        // Picking a suggestion appends a space, so the word is finished and the next one is open.
        if (appendSpace) updatePredictions()
    }

    /**
     * Offers what usually comes next, when nothing has been typed to correct.
     *
     * The strip is otherwise empty between words, which is most of the time someone spends looking
     * at it. Predictions are only shown where they are actually useful — with a word behind the
     * cursor and nothing in front of it — and they are silently absent when the models have nothing
     * confident to say, because three guessed words cost a glance every time they appear.
     */
    private fun updatePredictions() {
        if (composing.isNotEmpty()) return
        if (!fieldSuggestionsEnabled()) return
        if (searchModeShown || emojiPanelShown || voiceOverlayShown) return

        val suggester = typingSuggester ?: return
        val context = precedingContextForSwipe()
        val predictions = suggester.predict(
            previousWord = context.previous,
            previousPreviousWord = context.older,
            blockOffensive = settings.blockOffensiveWords,
        )
        if (predictions.isEmpty()) {
            clearSuggestions()
            return
        }

        stripMode = StripMode.Prediction
        suggestionStrip?.setSuggestions(predictions)
    }

    /**
     * Commits a predicted word, with the spacing a typed one would have had.
     *
     * The whole value of a prediction is the keystrokes it saves, so it has to arrive finished —
     * separated from what came before and ready for the next word — rather than needing a space
     * pressed after it.
     */
    private fun commitPrediction(word: String) {
        val connection = currentInputConnection ?: return
        // Read before the commit; afterwards the preceding word is the one just put down.
        val previous = precedingWordForSwipe()
        val before = connection.getTextBeforeCursor(1, 0)
        val needsSpace = !before.isNullOrEmpty() && isWordCharacter(before[0])

        selfEdit = true
        connection.beginBatchEdit()
        connection.commitText(if (needsSpace) " $word " else "$word ", 1)
        connection.endBatchEdit()

        // Taking a prediction is a deliberate choice of what comes next, and worth learning from
        // exactly as typing it would have been.
        learnPair(previous, word)
        lastAutocorrect = null
        literalWordInProgress = false
        lastSpaceCommitMs = 0L
        clearSuggestions()
        updateShiftFromCursor()
        updatePredictions()
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

        cachedSelectionStart = newSelStart
        cachedSelectionEnd = newSelEnd

        val ourEdit = selfEdit
        selfEdit = false

        val update = SelectionUpdate.evaluate(
            oldSelStart = oldSelStart,
            oldSelEnd = oldSelEnd,
            newSelStart = newSelStart,
            newSelEnd = newSelEnd,
            candidatesStart = candidatesStart,
            candidatesEnd = candidatesEnd,
            selfEdit = ourEdit,
            hasComposingText = composing.isNotEmpty(),
        )

        if (composing.isNotEmpty() && !update.cursorLeftComposing) {
            update.composingAtEnd?.let { composingAtEnd = it }
            if (update.externalSelectionChanged) updateShiftFromCursor()
            return
        }
        // Dropping one word and reopening another are the same gesture — a tap somewhere else in
        // the sentence — so the tap that ends the first must be allowed to start the second.
        if (composing.isNotEmpty()) abandonComposing()

        if (update.externalSelectionChanged) {
            // Gesture and prediction candidates describe the old cursor context. Clear first so
            // the same tap can reopen the newly selected word instead of being blocked by the stale
            // strip mode.
            literalWordInProgress = false
            clearSuggestions()
            reopenWordAtCursor(newSelStart, newSelEnd)
            updateShiftFromCursor()
        }
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
        if (!fieldSuggestionsEnabled()) return
        if (searchModeShown || emojiPanelShown || voiceOverlayShown) return

        val suggester = typingSuggester ?: return
        val keys = currentGestureKeyMap() ?: return

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
        clearTouches()
        composing.append(word)
        recomposed = true
        composingAtEnd = end == 0
        literalWordInProgress = false
        updateTypingSuggestions(suggester, keys)
    }

    /** A word autocorrect changed, and what it changed from. */
    private data class Autocorrect(
        val original: String,
        val applied: String,
        val previous: String?,
        val touches: FloatArray?,
        val keys: GestureKeyMap?,
    )

    /** What the suggestion strip is showing, and so what a tap on it should do. */
    private enum class StripMode { Empty, GesturePreview, Gesture, Typing, Prediction }

    // endregion

    // region Shift

    private fun shiftState(): ShiftState = keyboardView?.shiftState ?: ShiftState.OFF

    private fun setShift(state: ShiftState) {
        keyboardView?.shiftState = state
    }

    /** Applies sentence auto-capitalisation, unless caps lock is on or the user disabled it. */
    private fun updateShiftFromCursor() {
        if (layer != Layer.ALPHA || searchModeShown) return
        if (editorBaseLayout.rows.none { row -> row.keys.any { it.type == KeyType.SHIFT } }) {
            setShift(ShiftState.OFF)
            return
        }
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

    private fun enterActionFor(imeOptions: Int): EnterAction {
        if ((imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return EnterAction.RETURN
        return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> EnterAction.GO
            EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
            EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
            EditorInfo.IME_ACTION_PREVIOUS -> EnterAction.PREVIOUS
            EditorInfo.IME_ACTION_NEXT -> EnterAction.NEXT
            EditorInfo.IME_ACTION_DONE -> EnterAction.DONE
            else -> EnterAction.RETURN
        }
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

    // endregion

    private companion object {
        const val TAG = "SlideIME"
        const val DOUBLE_TAP_WINDOW_MS = 300L
        const val DOUBLE_SPACE_WINDOW_MS = 800L
        const val HAPTIC_DURATION_MS = 12L
        const val LEARNED_DATA_DELETE_ATTEMPTS = 3
        const val LEARNED_DATA_DELETE_RETRY_MS = 75L
        const val MAX_WORD_DELETE_CHARS = 2048
        const val MAX_SEARCH_QUERY_LENGTH = 64
        const val MAX_SEARCH_RESULTS = 6
        const val MAX_GRAPHEME_CONTEXT_CHARS = 256

        /**
         * Characters whose touch positions are tracked.
         *
         * Comfortably past the longest word the corrector will look at, so the bound is never the
         * thing that stops a word being priced by where the finger landed.
         */
        const val MAX_TRACKED_TOUCHES = 48

        /** Text read either side of the cursor when reopening a word. */
        const val MAX_REOPEN_CHARS = 48

        /** Enough to reach back over the word in progress and the one before it. */
        const val MAX_CONTEXT_CHARS = 64

        /**
         * Weight enough to trust a word on the spot.
         *
         * Reserved for the two gestures that can only mean "this is a word": undoing a correction,
         * and reaching past the keyboard's first choice to pick out what was actually typed.
         */
        const val TRUSTED_AT_ONCE = 2

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

        /** Process-lifetime IO for a final snapshot after an IME service instance is destroyed. */
        val LEARNED_DATA_FINALIZER_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Most recently scheduled finalizer; each new finalizer and service startup joins it. */
        @Volatile
        var LEARNED_DATA_FINALIZER_JOB: Job? = null

        /** Serializes every learned-data file mutation once lifecycle ordering is established. */
        val LEARNED_DATA_IO = Mutex()
    }
}
