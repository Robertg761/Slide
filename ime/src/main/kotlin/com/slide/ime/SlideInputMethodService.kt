package com.slide.ime

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
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
import com.slide.engine.gesture.GestureAdaptation
import com.slide.engine.gesture.GestureCandidate
import com.slide.engine.gesture.GestureDecoderProvenance
import com.slide.engine.gesture.GestureDecoderSource
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
import com.slide.ime.text.AutoSpacing
import com.slide.ime.text.EditorComposingSettlement
import com.slide.ime.text.EditorInputPolicy
import com.slide.ime.text.EditorKeyboardMode
import com.slide.ime.text.EditorSelection
import com.slide.ime.text.ExpectedSelectionTracker
import com.slide.ime.text.GestureDeleteTransaction
import com.slide.ime.text.GestureEditorSnapshot
import com.slide.ime.text.GestureEditTransaction
import com.slide.ime.text.GestureUndoState
import com.slide.ime.text.OrderedInputGuard
import com.slide.ime.text.OrderedInputQueue
import com.slide.ime.text.OrderedInputRequest
import com.slide.ime.text.PrecedingWord
import com.slide.ime.text.SelectionUpdate
import com.slide.ime.text.SelfEditFallback
import com.slide.ime.text.cursorAfterReplacement
import com.slide.ime.text.isCaseableCharacter
import com.slide.ime.text.matchTypedCase
import com.slide.ime.text.resolveCharacterCase
import com.slide.ime.quality.ConfidenceBucket
import com.slide.ime.quality.DecisionOutcome
import com.slide.ime.quality.DecoderSource
import com.slide.ime.quality.ModelReadiness
import com.slide.ime.quality.QualityInputMode
import com.slide.ime.quality.QualityModel
import com.slide.ime.quality.TypingQualityCollector
import com.slide.ime.view.ClipboardPanelView
import com.slide.ime.view.EmojiGlyphs
import com.slide.ime.view.EmojiPanelView
import com.slide.ime.view.EnterAction
import com.slide.ime.view.KeyboardFrame
import com.slide.ime.view.KeyboardSettingsPanelView
import com.slide.ime.view.KeyboardView
import com.slide.ime.view.ShiftState
import com.slide.ime.view.SuggestionStripView
import com.slide.ime.view.TextEditPanelView
import com.slide.ime.view.VoiceOverlayView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
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
import java.io.FileDescriptor
import java.io.PrintWriter
import kotlin.math.exp

class SlideInputMethodService :
    InputMethodService(),
    KeyboardView.Listener,
    SuggestionStripView.Listener,
    KeyboardSettingsPanelView.Listener,
    EmojiPanelView.Listener,
    VoiceOverlayView.Listener,
    VoiceInputClient.Listener,
    TextEditPanelView.Listener,
    ClipboardPanelView.Listener {

    private lateinit var settingsRepository: SettingsRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var keyboardView: KeyboardView? = null
    private var suggestionStrip: SuggestionStripView? = null
    private var voiceOverlay: VoiceOverlayView? = null
    private var emojiPanel: EmojiPanelView? = null
    private var keyboardSettingsPanel: KeyboardSettingsPanelView? = null
    private var textEditPanel: TextEditPanelView? = null
    private var clipboardPanel: ClipboardPanelView? = null

    /**
     * Created with the service and listening for as long as it lives: history only exists if
     * copies were observed when they happened. Android already scopes clipboard access to the
     * active default IME, and [ClipboardHistory] refuses sensitive clips and keeps recents
     * in memory only.
     */
    private val clipboardHistory by lazy { ClipboardHistory(this) }
    private var keyboardFrame: KeyboardFrame? = null
    private var inputRoot: View? = null
    private var settings = KeyboardSettings()
    private var settingsLoaded = false

    /**
     * Connected to the speech process only while the keyboard is on screen, plus a short grace
     * period after it hides.
     *
     * Staying bound indefinitely would keep a process alive — and eventually a few hundred
     * megabytes of model with it — for as long as Slide is the selected keyboard, which is
     * essentially always. But tearing down at the instant the keyboard hides makes the common
     * switch-field-and-dictate-again flow pay process start plus model load every time. The
     * [VOICE_UNBIND_GRACE_MS] window keeps the model warm across brief hides and still lets the
     * memory go when dictation is actually done.
     */
    private val voiceClientDelegate = lazy {
        VoiceInputClient(this).also { it.listener = this }
    }
    private val voiceClient by voiceClientDelegate

    /** Pending delayed release of the speech process; cancelled when the keyboard returns. */
    private var voiceUnbindJob: Job? = null

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

    /** Re-evaluated for each input view because enabled IMEs can change while Slide is alive. */
    private var imeSwitcherOffered = false

    /** A permission cannot conjure microphone hardware on devices that do not have any. */
    private val deviceHasMicrophone by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }

    private fun voiceAvailableForEditor(): Boolean =
        deviceHasMicrophone && editorInputPolicy.allowsVoice

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

    /** Bounded swipe preferences learned only from explicit alternative picks and immediate undo. */
    private val gestureAdaptation = GestureAdaptation()

    /** Fixed-size, text-free process-local quality aggregates. */
    private val typingQuality = TypingQualityCollector()

    private val userDictionaryStore by lazy { UserDictionaryStore(applicationContext) }

    private val learnedDataReady = CompletableDeferred<Unit>()
    private var learnedLoadStarted = false
    private val learnedPersistence = LearnedDataPersistenceState()
    private val learnedDataClearEpoch = LearnedDataClearEpochState(
        UserDictionaryStore.latestDeletionRequestGeneration(),
    )
    private var removeLearnedDeletionListener: (() -> Unit)? = null

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

    /** Last text-free suggestion decision for the active composing word. */
    private var pendingTypedQuality: PendingTypedQuality? = null

    private data class PendingTypedQuality(
        val latencyMillis: Double,
        val candidateCount: Int,
        val confidence: ConfidenceBucket,
    )

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

    /** Exact cursor positions expected from ordered edits whose callbacks may arrive much later. */
    private val expectedSelections = ExpectedSelectionTracker()

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

    /** One-shot whole-word Backspace, intentionally independent of suggestion-strip visibility. */
    private val gestureUndoState = GestureUndoState()

    /** Coalesces partial traces so model inference never queues behind the user's finger. */
    private var pendingGesturePreview: List<GesturePoint>? = null
    private var gesturePreviewJob: Job? = null
    private var gesturePreviewGeneration = 0L

    /**
     * The words before the current swipe, resolved once per stroke for the live preview.
     *
     * Valid only while [swipePreviewContextGeneration] equals the stroke's preview generation,
     * which every cancel, completion, and editor transition bumps.
     */
    private var swipePreviewContext: PrecedingWord.Context? = null
    private var swipePreviewContextGeneration = -1L

    /** Completed swipes and following edits are chained so off-thread inference cannot reorder input. */
    private val gestureInputQueue = OrderedInputQueue(scope) { editorGeneration }

    /** What the field looked like when the edit at the head of that chain was released. */
    private var orderedInputGuard: OrderedInputGuard? = null

    /** Canceled native inference is not interruptible; one gate prevents canceled work piling up. */
    private val gestureDecodeMutex = Mutex()

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
        clipboardHistory.startListening()
        typingQuality.recordModelReadiness(QualityModel.TYPING_SUGGESTER, ModelReadiness.NOT_READY)
        typingQuality.recordModelReadiness(QualityModel.SWIPE_DECODER, ModelReadiness.NOT_READY)
        removeLearnedDeletionListener = userDictionaryStore.addDeletionRequestListener { generation ->
            scope.launch {
                if (learnedDataClearEpoch.observeDeletionRequest(generation)) {
                    clearLearnedDataFromMemoryAndDisk()
                }
            }
        }
        settingsRepository = SettingsRepository(applicationContext)
        settingsRepository.settings
            .onEach { updated ->
                val previous = settings
                settings = updated
                settingsLoaded = true
                incognito = editorRequestsNoLearning ||
                    !editorInputPolicy.allowsPersonalizedLearning ||
                    updated.incognitoModeEnabled

                val clearEpoch = updated.learnedDataClearEpoch
                // Wiping everything this person has taught the keyboard is irreversible, so what an
                // observed clear epoch is allowed to mean is spelled out in full. Four cases:
                //
                //   epoch < 0         Not authoritative. The settings file has never been read
                //                     successfully and this snapshot is the whole-defaults fallback
                //                     carrying KeyboardSettings.LEARNED_DATA_EPOCH_UNKNOWN. It says
                //                     nothing about the stored epoch, so it becomes neither a
                //                     baseline nor a wipe; the real value arrives when a retry
                //                     succeeds. Treating its 0 as authoritative is what would make
                //                     the true epoch N look like a clear a moment later.
                //   no baseline yet   The first authoritative reading. Adopt it unless the durable
                //                     deletion marker proves a clear won the startup race.
                //   epoch < baseline  The stored file was reset — corruption replaced it with an
                //                     empty one. Adopt the lower value rather than keeping ours: a
                //                     stale high baseline would put the user's next Clear (0 -> 1)
                //                     underneath it and silently ignore it for the whole session.
                //   epoch > baseline  The only case that is a clear the user asked for. Wipe.
                if (clearEpoch >= 0) {
                    val userRequestedClear = learnedDataClearEpoch.observeEpoch(
                        clearEpoch,
                        UserDictionaryStore.latestDeletionRequestGeneration(),
                    )
                    if (userRequestedClear) clearLearnedDataFromMemoryAndDisk()
                }
                if (!learnedLoadStarted) {
                    // Started by the first snapshot of any kind, authoritative or not. Waiting for a
                    // readable epoch would leave this person without their own words for as long as
                    // the settings file keeps failing, and the clear epoch above is already settled
                    // for this emission.
                    learnedLoadStarted = true
                    loadLearnedData()
                }

                if (
                    previous.showNumberRow != updated.showNumberRow ||
                    previous.keyHeightScale != updated.keyHeightScale ||
                    previous.bottomPaddingDp != updated.bottomPaddingDp
                ) {
                    gestureKeyMapCache = null
                }
                keyboardView?.settings = updated
                keyboardSettingsPanel?.settings = updated
                emojiPanel?.skinTone = updated.emojiSkinTone
                updateGestureAvailability()

                val suggestionPolicyChanged =
                    previous.suggestionsEnabled != updated.suggestionsEnabled ||
                        previous.autocorrectEnabled != updated.autocorrectEnabled ||
                        previous.blockOffensiveWords != updated.blockOffensiveWords
                if (
                    previous.gestureTypingEnabled != updated.gestureTypingEnabled ||
                    previous.blockOffensiveWords != updated.blockOffensiveWords
                ) {
                    cancelGestureInputSequence()
                }
                if (!fieldSuggestionsEnabled()) {
                    if (composing.isNotEmpty()) {
                        // The already-entered prefix is now committed literally. Keep the rest of
                        // this same word literal even if the setting is immediately turned back on.
                        if (abandonComposing().settled) literalWordInProgress = true
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
                    GestureDecoder(it, bigrams = pairs, trigrams = triples)
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
                typingQuality.recordModelReadiness(
                    QualityModel.TYPING_SUGGESTER,
                    ModelReadiness.PRIMARY_READY,
                )
                typingQuality.recordModelReadiness(
                    QualityModel.SWIPE_DECODER,
                    ModelReadiness.FALLBACK_READY,
                )
                Log.i(
                    TAG,
                    "Deterministic decoder and suggester ready with ${lexicon.size} words" +
                        (bigrams?.let { ", ${it.pairCount} bigrams" } ?: ", no bigrams") +
                        (trigrams?.let { ", ${it.tripleCount} trigrams" } ?: ", no trigrams"),
                )
                updateGestureAvailability()

                // Basic glide typing is ready before native model loading begins. The model is an
                // optional promotion after it proves the complete tensor/search contract; a slow
                // first copy or an incompatible runtime must never turn swipes into key slide-off.
                var neural: NeuralGestureDecoder? = null
                try {
                    withContext(Dispatchers.IO) {
                        neural = NeuralGestureDecoder.createOrNull(
                            context = applicationContext,
                            lexicon = lexicon,
                            bigrams = bigrams,
                            userBigrams = userBigrams,
                            trie = requireNotNull(trie),
                            trigrams = trigrams,
                            fallback = requireNotNull(decoder),
                        )
                    }
                    val ready = neural ?: return@launch
                    gestureDecoder = ready
                    neural = null // Ownership transfers to the service and is released in onDestroy.
                    typingQuality.recordModelReadiness(
                        QualityModel.SWIPE_DECODER,
                        ModelReadiness.PRIMARY_READY,
                    )
                    Log.i(TAG, "Neural swipe passed its known-trace health check and is ready")
                } finally {
                    // Cancellation can arrive while native loading is not interruptible. Keeping
                    // the candidate in this outer variable ensures that late result is still closed.
                    neural?.close()
                }
            } else {
                typingQuality.recordModelReadiness(
                    QualityModel.TYPING_SUGGESTER,
                    ModelReadiness.UNAVAILABLE,
                )
                typingQuality.recordModelReadiness(
                    QualityModel.SWIPE_DECODER,
                    ModelReadiness.UNAVAILABLE,
                )
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
            voiceEnabled = voiceAvailableForEditor()
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
        val keyboardSettings = KeyboardSettingsPanelView(this).apply {
            listener = this@SlideInputMethodService
            keyboardTheme = theme
            settings = this@SlideInputMethodService.settings
            visibility = View.GONE
        }
        val textEdit = TextEditPanelView(this).apply {
            listener = this@SlideInputMethodService
            keyboardTheme = theme
            visibility = View.GONE
        }
        val clipboard = ClipboardPanelView(this).apply {
            listener = this@SlideInputMethodService
            keyboardTheme = theme
            visibility = View.GONE
        }

        suggestionStrip = strip
        keyboardView = view
        gestureKeyMapCache = null
        voiceOverlay = overlay
        emojiPanel = emoji
        keyboardSettingsPanel = keyboardSettings
        textEditPanel = textEdit
        clipboardPanel = clipboard
        updateGestureAvailability()

        // Emoji, voice, and settings sit on top of the keys rather than replacing them, so the
        // input view keeps exactly the same height whichever panel is open. Swapping in a child of
        // a different height would resize the window and shove the app's text around mid-sentence.
        // KeyboardFrame is what holds them to the keys' height; the keys must be added first.
        val keys = KeyboardFrame(this).apply {
            addView(view, MATCH_PARENT, WRAP_CONTENT)
            addView(emoji, MATCH_PARENT, MATCH_PARENT)
            addView(overlay, MATCH_PARENT, MATCH_PARENT)
            addView(keyboardSettings, MATCH_PARENT, MATCH_PARENT)
            addView(textEdit, MATCH_PARENT, MATCH_PARENT)
            addView(clipboard, MATCH_PARENT, MATCH_PARENT)
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
        cancelGestureInputSequence()
        expectedSelections.invalidate()
        gestureUndoState.invalidate()
        selfEdit = false
        cancelVoiceForEditorTransition()
        hideKeyboardSettingsPanel(restoreEditorUi = false)
        hideTextEditPanel(restoreEditorUi = false)
        hideClipboardPanel(restoreEditorUi = false)
    }

    override fun onFinishInput() {
        editorGeneration++
        cancelGestureInputSequence()
        expectedSelections.invalidate()
        gestureUndoState.invalidate()
        selfEdit = false
        cancelVoiceForEditorTransition()
        hideKeyboardSettingsPanel(restoreEditorUi = false)
        hideTextEditPanel(restoreEditorUi = false)
        hideClipboardPanel(restoreEditorUi = false)
        super.onFinishInput()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorGeneration++
        cancelGestureInputSequence()
        expectedSelections.invalidate()
        gestureUndoState.invalidate()
        selfEdit = false
        cancelVoiceForEditorTransition()
        hideKeyboardSettingsPanel(restoreEditorUi = false)
        hideTextEditPanel(restoreEditorUi = false)
        hideClipboardPanel(restoreEditorUi = false)
        exitEmojiSearch(showPicker = false)
        layer = Layer.ALPHA
        hideEmojiPanel()
        editorInputPolicy = EditorInputPolicy.from(info.inputType)
        imeSwitcherOffered = shouldOfferImeSwitcher()
        editorBaseLayout = layoutFor(editorInputPolicy.keyboardMode)
        passwordField = editorInputPolicy.isPassword
        editorRequestsNoLearning =
            (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        incognito = !settingsLoaded || editorRequestsNoLearning ||
            !editorInputPolicy.allowsPersonalizedLearning ||
            settings.incognitoModeEnabled
        literalWordInProgress = false
        // onStartInputView already refers to the new connection. Any retained region belongs to
        // the editor that just ended and must never be committed into this field.
        discardComposingForEditorTransition()
        // The composing region belonged to the previous editor; no callback from it may classify a
        // selection report in this newly started field.
        selfEdit = false
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
            keyboardLayout = layoutFor(Layer.ALPHA)
            settings = this@SlideInputMethodService.settings
            enterAction = enterActionFor(info.imeOptions)
        }
        suggestionStrip?.voiceEnabled = voiceAvailableForEditor()
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
        keyboardSettingsPanel?.keyboardTheme = theme
        textEditPanel?.keyboardTheme = theme
        clipboardPanel?.keyboardTheme = theme
        // The frame's navigation-bar strip and any rounding the window puts around the input view
        // are the two places the keyboard's own colour does not otherwise reach, and both sit right
        // along the bottom edge where a mismatch reads as the keyboard not fitting the screen.
        keyboardFrame?.themeBackground = theme.background
        inputRoot?.setBackgroundColor(theme.background)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // The keyboard is back before the grace period ran out; keep the speech process and its
        // loaded model for the next dictation instead of paying the reload.
        voiceUnbindJob?.cancel()
        voiceUnbindJob = null
    }

    /**
     * Schedules release of the speech process when the keyboard leaves the screen.
     *
     * Unbinding immediately would make every hide-and-return dictation pay process start plus
     * model load again; never unbinding would keep hundreds of megabytes resident for as long as
     * Slide is the selected keyboard, which is essentially always. The delay keeps quick returns
     * instant and still lets the memory go once the user has moved on.
     */
    override fun onWindowHidden() {
        super.onWindowHidden()
        editorGeneration++
        cancelGestureInputSequence()
        expectedSelections.invalidate()
        gestureUndoState.invalidate()
        selfEdit = false
        cancelVoiceForEditorTransition()
        exitEmojiSearch(showPicker = false)
        hideEmojiPanel()
        hideKeyboardSettingsPanel(restoreEditorUi = false)
        hideTextEditPanel(restoreEditorUi = false)
        hideClipboardPanel(restoreEditorUi = false)
        if (voiceClientDelegate.isInitialized()) {
            voiceUnbindJob?.cancel()
            voiceUnbindJob = scope.launch {
                delay(VOICE_UNBIND_GRACE_MS)
                voiceUnbindJob = null
                voiceClient.unbind()
            }
        }
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
        cancelGestureInputSequence()
        expectedSelections.invalidate()
        gestureUndoState.invalidate()
        cancelVoiceForEditorTransition()
        hideKeyboardSettingsPanel(restoreEditorUi = false)
        hideTextEditPanel(restoreEditorUi = false)
        hideClipboardPanel(restoreEditorUi = false)
        literalWordInProgress = false
        abandonComposing()
        selfEdit = false
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

    /** The overlays that can cover or replace the keys, topmost first. */
    private enum class Panel { VOICE, SETTINGS, TEXT_EDIT, CLIPBOARD, SEARCH, EMOJI }

    /**
     * The topmost panel over the keys, or null when the plain keyboard is showing.
     *
     * Derived from the same view state as the individual `*Shown` predicates, so it can never
     * disagree with them — and every "is anything covering the keys?" decision goes through it.
     * A panel added here is automatically part of back handling, gesture gating, and prediction
     * gating, instead of needing each of those call sites edited in step.
     */
    private val activePanel: Panel?
        get() = when {
            voiceOverlayShown -> Panel.VOICE
            keyboardSettingsPanelShown -> Panel.SETTINGS
            textEditPanelShown -> Panel.TEXT_EDIT
            clipboardPanelShown -> Panel.CLIPBOARD
            searchModeShown -> Panel.SEARCH
            emojiPanelShown -> Panel.EMOJI
            else -> null
        }

    private val anyPanelShown: Boolean get() = activePanel != null

    /**
     * Closes the topmost panel, reporting whether there was one.
     *
     * Dictation sits above the picker, so it goes first. Backing out of it counts as cancelling
     * rather than finishing: a transcript the user backed away from is not one they asked for.
     */
    private fun handleBack(): Boolean = when (activePanel) {
        Panel.VOICE -> {
            onVoiceDismissed(committed = false)
            true
        }

        Panel.SETTINGS -> {
            hideKeyboardSettingsPanel(restoreEditorUi = true)
            true
        }

        Panel.TEXT_EDIT -> {
            hideTextEditPanel(restoreEditorUi = true)
            true
        }

        Panel.CLIPBOARD -> {
            hideClipboardPanel(restoreEditorUi = true)
            true
        }

        Panel.SEARCH -> {
            exitEmojiSearch(showPicker = true)
            true
        }

        Panel.EMOJI -> {
            hideEmojiPanel()
            true
        }

        null -> false
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

    private fun refreshBackCallback() {
        setBackCallbackRegistered(anyPanelShown)
    }

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
        cancelGestureInputSequence()
        expectedSelections.invalidate()
        cancelVoiceForEditorTransition()
        if (voiceClientDelegate.isInitialized()) voiceClient.unbind()
        removeLearnedDeletionListener?.invoke()
        removeLearnedDeletionListener = null
        val finalLearnedData = captureFinalLearnedData()
        scope.cancel()
        // Neural close shares its monitor with decode, so a native preview already in flight
        // finishes before its modules are destroyed. Cancelling first prevents another preview
        // from entering after that destruction.
        (gestureDecoder as? AutoCloseable)?.close()
        finalLearnedData?.let(::flushFinalLearnedData)
        clipboardHistory.stopListening()
        keyboardView = null
        suggestionStrip = null
        voiceOverlay = null
        emojiPanel = null
        keyboardSettingsPanel = null
        textEditPanel = null
        clipboardPanel = null
        super.onDestroy()
    }

    /** On-demand support output: bounded aggregate enums/counters only, never typed content. */
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        super.dump(fd, writer, args)
        writer.println("Slide typing quality (process-local, text-free aggregate)")
        writer.println(typingQuality.snapshot())
    }

    // region KeyboardView.Listener

    override fun onKeyDown(key: Key) {
        performHaptic()
        performSound(key)
    }

    override fun onKeyCommit(key: Key, text: String, touchX: Float, touchY: Float) {
        // Taken here rather than where the key is applied: a tap released while a swipe is still
        // decoding runs minutes-of-thought later in queue order, and a double-tap window measured
        // from that moment turns two unhurried shift taps into caps lock.
        val pressedAtMs = System.currentTimeMillis()
        if (queueBehindGestureInput { processKeyCommit(key, text, touchX, touchY, pressedAtMs) }) {
            return
        }
        processKeyCommit(key, text, touchX, touchY, pressedAtMs)
    }

    private fun processKeyCommit(
        key: Key,
        text: String,
        touchX: Float,
        touchY: Float,
        pressedAtMs: Long,
    ) {
        // A whole word the last swipe committed, still sitting untouched behind the cursor. A
        // letter tapped now starts the next word rather than extending the swiped one, so it
        // needs the separating space a fresh swipe would get. Captured before this keypress
        // retires the record.
        val swipedWordBehindCursor = gestureUndoState.snapshot()
            ?.takeIf {
                gestureUndoState.matchesEditorAndCursor(editorGeneration, collapsedCursorPosition())
            }
            ?.committedText
        // Shift edits nothing, so it costs neither the swipe its Backspace undo nor the
        // capitalised word about to follow it its separating space.
        if (key.type != KeyType.DELETE && key.type != KeyType.SHIFT) gestureUndoState.invalidate()
        if (key.type != KeyType.SHIFT) lastShiftTapMs = 0L
        if (keyboardView?.searchMode == true) {
            handleSearchKey(key, text)
            return
        }

        val connection = currentInputConnection ?: return

        val selfEditWasPending = selfEdit
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

        // A key may have been rendered while a preceding swipe was still decoding. Resolve its
        // case now, after any queued Shift event ahead of it has actually changed the state.
        val appliedText = if (key.type == KeyType.CHARACTER && isCaseableCharacter(text)) {
            resolveCharacterCase(text, shifted = shiftState() != ShiftState.OFF)
        } else {
            text
        }

        val callbackPossible = when (key.type) {
            KeyType.SHIFT -> {
                handleShiftTap(pressedAtMs)
                false
            }
            KeyType.DELETE -> if (editorInputPolicy.usesRawKeyEvents) {
                handleRawKey(connection, KeyEvent.KEYCODE_DEL)
            } else {
                handleDelete(connection)
            }
            KeyType.ENTER -> if (editorInputPolicy.usesRawKeyEvents) {
                handleRawKey(connection, KeyEvent.KEYCODE_ENTER)
            } else {
                handleEnter(connection)
            }
            KeyType.SYMBOLS -> {
                switchLayer(Layer.SYMBOLS)
                false
            }
            KeyType.SYMBOLS_ALT -> {
                switchLayer(Layer.SYMBOLS_ALT)
                false
            }
            KeyType.ALPHA -> {
                switchLayer(Layer.ALPHA)
                false
            }
            KeyType.SPACE -> if (editorInputPolicy.usesRawKeyEvents) {
                handleRawText(connection, text)
            } else {
                handleSpace(connection, text, pressedAtMs)
            }
            KeyType.MIC -> {
                processVoiceRequested()
                false
            }
            KeyType.EMOJI -> {
                showEmojiPanel()
                false
            }
            KeyType.GLOBE -> {
                switchToNextIme()
                false
            }
            KeyType.SETTINGS -> false
            KeyType.CHARACTER -> if (editorInputPolicy.usesRawKeyEvents) {
                handleRawText(connection, appliedText)
            } else {
                handleCharacter(connection, appliedText, touchX, touchY, swipedWordBehindCursor)
            }
        }
        if (key.type in EDITING_KEYS) {
            // A rejected/no-op connection cannot produce the callback that normally clears this
            // one-shot. Preserve an earlier edit still awaiting acknowledgement, but never arm a
            // fresh fallback for an operation the editor declined.
            selfEdit = SelfEditFallback.afterAttempt(
                previouslyPending = selfEditWasPending,
                callbackPossible = callbackPossible,
                fallbackStillArmed = selfEdit,
            )
        }
    }

    override fun onGestureComplete(points: List<GesturePoint>) {
        if (!settings.gestureTypingEnabled || !editorInputPolicy.allowsSuggestions) {
            clearSuggestions()
            return
        }
        if (anyPanelShown || layer != Layer.ALPHA) return

        // Keep a live preview visible while final inference runs. If this trace never produced a
        // preview, clear older candidates now so they cannot rewrite the wrong swipe.
        if (stripMode != StripMode.GesturePreview) clearSuggestions()
        enqueueGestureInput { request -> decodeAndCommitGesture(points, request) }
    }

    private suspend fun decodeAndCommitGesture(
        points: List<GesturePoint>,
        request: OrderedInputRequest,
    ) {
        if (!gestureInputIsCurrent(request) || !gestureModeAvailable()) {
            if (gestureInputIsCurrent(request)) clearGesturePreview()
            return
        }

        // This executes after all earlier queued input, so both language context and undo state
        // describe the text that is actually in the editor, including a preceding rapid swipe.
        gestureUndoState.invalidate()
        if (stripMode != StripMode.GesturePreview) clearSuggestions()
        val decoder = gestureDecoder ?: return clearGesturePreview()
        val connection = currentInputConnection ?: return clearGesturePreview()
        val keys = currentGestureKeyMap() ?: return clearGesturePreview()
        val editorSnapshot = gestureEditorSnapshot(connection)
        val context = precedingContextForSwipe()
        val blockOffensive = settings.blockOffensiveWords
        val decodeStarted = SystemClock.elapsedRealtimeNanos()
        val decodeResult = try {
            withContext(Dispatchers.Default) {
                gestureDecodeMutex.withLock {
                    decodeGesture(
                        decoder = decoder,
                        points = points,
                        keys = keys,
                        blockOffensive = blockOffensive,
                        previousWord = context.previous,
                        previousPreviousWord = context.older,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            typingQuality.recordSwipeDecision(
                elapsedMillis(decodeStarted),
                DecoderSource.UNKNOWN,
                0,
                ConfidenceBucket.UNKNOWN,
                DecisionOutcome.CANCELLED,
            )
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Final swipe decode failed", failure)
            typingQuality.recordSwipeDecision(
                elapsedMillis(decodeStarted),
                DecoderSource.UNKNOWN,
                0,
                ConfidenceBucket.UNKNOWN,
                DecisionOutcome.FAILED,
            )
            if (gestureInputIsCurrent(request)) clearGesturePreview()
            return
        }
        // Include dispatcher and mutex contention: this is the delay the ordered final input paid,
        // not only the time spent inside the decoder after it eventually got the gate.
        val decoded = decodeResult.copy(latencyMillis = elapsedMillis(decodeStarted))
        val candidates = decoded.candidates

        if (
            !gestureInputIsCurrent(request) ||
            currentInputConnection !== connection ||
            !gestureModeAvailable()
        ) {
            recordSwipeDecision(decoded, DecisionOutcome.STALE)
            return
        }
        val liveSnapshot = gestureEditorSnapshot(connection)
        if (!editorSnapshot.matches(liveSnapshot)) {
            recordSwipeDecision(decoded, DecisionOutcome.STALE)
            clearGesturePreview()
            return
        }

        val best = candidates.firstOrNull() ?: run {
            recordSwipeDecision(decoded, DecisionOutcome.NO_CANDIDATE)
            return clearGesturePreview()
        }

        // Only settle a typed prefix once decoding has definitely produced replacement input. A
        // length-changing autocorrection moves the cursor before the swipe is inserted, so record
        // that intermediate position as well as the final commit. Editors are allowed to coalesce
        // both callbacks; ExpectedSelectionTracker deliberately handles that case.
        val composingBefore = composing.toString()
        // The snapshot just compared was read from this connection with nothing edited since, so
        // its cursor is the position before the word is settled. Extracting text again only to ask
        // the same question is a blocking round trip that can carry the whole field with it.
        val selectionBeforeFinish = liveSnapshot.cursor
        val finish = finishComposing(connection)
        if (!finish.settled) {
            // A swipe is a dependent edit: inserting it while the editor still owns the typed
            // prefix can replace that prefix or put the decoded word inside its active region.
            updateTypingSuggestions()
            recordSwipeDecision(decoded, DecisionOutcome.EDITOR_REJECTED)
            return
        }
        val corrected = finish.corrected
        // Reused as the position the commit starts from: settling the word is the only edit
        // between the two, and where nothing was settled the position has not moved at all.
        var selectionBeforeCommit = selectionBeforeFinish
        if (composingBefore.isNotEmpty()) {
            val selectionAfterFinish = readEditorSelection(connection) ?: run {
                val applied = lastAutocorrect?.applied.takeIf { corrected }
                val cursor = selectionBeforeFinish?.takeIf { it.start == it.end }?.start
                if (applied != null && cursor != null) {
                    val adjusted = cursorAfterReplacement(
                        cursor,
                        originalLength = composingBefore.length,
                        replacementLength = applied.length,
                    )
                    EditorSelection(adjusted, adjusted)
                } else {
                    selectionBeforeFinish
                }
            }
            if (selectionAfterFinish != null) {
                cachedSelectionStart = selectionAfterFinish.start
                cachedSelectionEnd = selectionAfterFinish.end
                if (selectionAfterFinish != selectionBeforeFinish) {
                    expectedSelections.expect(selectionBeforeFinish, selectionAfterFinish)
                }
                selectionBeforeCommit = selectionAfterFinish
            }
        }

        if (!commitGestureWord(connection, best.word, selectionBeforeCommit)) {
            recordSwipeDecision(decoded, DecisionOutcome.EDITOR_REJECTED)
            clearGesturePreview()
            return
        }
        recordSwipeDecision(decoded, DecisionOutcome.TOP_CANDIDATE_COMMITTED)
        literalWordInProgress = false
        if (settings.suggestionsEnabled) {
            stripMode = StripMode.Gesture
            suggestionStrip?.setSuggestions(candidates.map { it.word })
        } else {
            clearSuggestions()
        }
    }

    private data class DecodedGesture(
        val candidates: List<GestureCandidate>,
        val source: DecoderSource,
        val latencyMillis: Double,
        val confidence: ConfidenceBucket,
    )

    /** Called under [gestureDecodeMutex], keeping failover provenance tied to this exact result. */
    private fun decodeGesture(
        decoder: GestureDecodingEngine,
        points: List<GesturePoint>,
        keys: GestureKeyMap,
        blockOffensive: Boolean,
        previousWord: String?,
        previousPreviousWord: String?,
    ): DecodedGesture {
        val started = SystemClock.elapsedRealtimeNanos()
        val raw = decoder.decode(
            points,
            keys,
            blockOffensive,
            previousWord,
            previousPreviousWord,
        )
        val source = when ((decoder as? GestureDecoderProvenance)?.lastDecoderSource) {
            GestureDecoderSource.NEURAL -> DecoderSource.NEURAL
            GestureDecoderSource.FALLBACK -> DecoderSource.FALLBACK
            GestureDecoderSource.NONE -> DecoderSource.UNKNOWN
            null -> if (decoder is GestureDecoder) DecoderSource.FALLBACK else DecoderSource.UNKNOWN
        }
        val adapted = gestureAdaptation.rerank(raw)
        return DecodedGesture(
            candidates = adapted,
            source = source,
            latencyMillis = elapsedMillis(started),
            confidence = gestureConfidence(adapted),
        )
    }

    private fun recordSwipeDecision(decoded: DecodedGesture, outcome: DecisionOutcome) {
        typingQuality.recordSwipeDecision(
            latencyMillis = decoded.latencyMillis,
            decoderSource = decoded.source,
            candidateCount = decoded.candidates.size,
            confidence = decoded.confidence,
            outcome = outcome,
        )
    }

    private fun gestureConfidence(candidates: List<GestureCandidate>): ConfidenceBucket {
        return scoreConfidence(candidates.map { it.score })
    }

    private fun scoreConfidence(scores: List<Float>): ConfidenceBucket {
        val top = scores.firstOrNull()?.takeIf(Float::isFinite)
            ?: return ConfidenceBucket.UNKNOWN
        var denominator = 0.0
        for (score in scores) {
            if (!score.isFinite()) return ConfidenceBucket.UNKNOWN
            denominator += exp((score - top).toDouble().coerceIn(-50.0, 0.0))
        }
        val share = 1.0 / denominator
        return when {
            share < 0.55 -> ConfidenceBucket.LOW
            share < 0.80 -> ConfidenceBucket.MEDIUM
            else -> ConfidenceBucket.HIGH
        }
    }

    private fun elapsedMillis(startedNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startedNanos).coerceAtLeast(0L) / 1_000_000.0

    private fun gestureModeAvailable(): Boolean =
        settings.gestureTypingEnabled &&
            editorInputPolicy.allowsSuggestions &&
            !anyPanelShown &&
            layer == Layer.ALPHA

    private fun clearGesturePreview() {
        if (stripMode == StripMode.GesturePreview) clearSuggestions()
    }

    private fun enqueueGestureInput(action: suspend (OrderedInputRequest) -> Unit) =
        gestureInputQueue.enqueue { request ->
            try {
                action(request)
            } finally {
                // Whatever the decode did or decided not to do, the field it leaves behind is what
                // the edits queued after it were released against. Not for a sequence that has been
                // cancelled: its own guard was cleared with it, and nothing queued behind it will
                // run to read this one anyway.
                if (gestureInputIsCurrent(request)) orderedInputGuard = captureOrderedInputGuard()
            }
        }

    private fun queueBehindGestureInput(action: () -> Unit): Boolean =
        gestureInputQueue.enqueueIfPending { processOrderedFollowup(action) }

    /**
     * What the editor looks like right now, to the extent it can be asked cheaply.
     *
     * [GESTURE_QUEUE_GUARD_CHARS] characters, not the whole field: this runs on the ordered path
     * for every key released during a decode, and the point of the guard is to be cheaper than the
     * mistake it prevents.
     */
    private fun captureOrderedInputGuard(): OrderedInputGuard {
        val connection = currentInputConnection
        return OrderedInputGuard(
            selection = cachedEditorSelection(),
            textBeforeCursor = connection
                ?.getTextBeforeCursor(GESTURE_QUEUE_GUARD_CHARS, 0)
                ?.toString(),
        )
    }

    /**
     * Reads the editor back after a deferred edit before allowing the next queued action to run.
     *
     * The synchronous read is rare (only input released while final swipe inference is pending)
     * and closes two races at once: later cursor gestures see the real position, and delayed
     * updateSelection callbacks are matched to exact positions rather than one shared Boolean.
     *
     * The action runs inside one batch edit because exactly one net expectation is registered for
     * it. Several of these actions mutate the field more than once — the automatic space before a
     * word, the delete-then-full-stop of a double space — and a per-step callback matches nothing
     * the tracker holds. Since [selfEdit] has already been cleared by then, it would be read as the
     * user moving the cursor, which drops queued keystrokes and destroys the composing region.
     *
     * That relies on the editor honouring batch edits, which the framework editors do and a hand
     * written [InputConnection] need not: one that reports every step regardless still produces the
     * intermediate callback and still misclassifies it, now without the queue being cancelled
     * underneath it. Registering an expectation per step instead would cover those editors too, but
     * it would mean every one of these actions reporting its own mutations back — a contract across
     * a dozen call sites, each of which has a fallback path for editors that cannot say where the
     * cursor went. One batch here holds the whole invariant in one place; the residue is a
     * misclassification on unusual editors rather than the cascade it used to be.
     */
    private fun processOrderedFollowup(action: () -> Unit) {
        val connection = currentInputConnection
        if (connection == null) {
            try {
                action()
            } finally {
                cachedSelectionStart = -1
                cachedSelectionEnd = -1
                selfEdit = false
            }
            return
        }

        // This key was released against a field that has since been replaced from elsewhere — the
        // app's own send button emptying the box is the ordinary way it happens. Dropping it is
        // what the queue's contract already does for editor transitions; applying it would put the
        // letter into whatever took the field's place. The guard is deliberately left stale, so the
        // rest of the keys released against the same vanished text go the same way.
        val expected = orderedInputGuard
        if (expected != null && !expected.stillApplies(captureOrderedInputGuard())) return

        val before = readEditorSelection(connection) ?: cachedEditorSelection()
        // Actions that describe their own mutation precisely — a whole-word gesture delete knows
        // what it removed even where the editor will not say — must not then be described a second
        // time from out here. Today the two agree and the tracker would fold them together, but a
        // read succeeding for one and failing for the other would leave two expectations the
        // coalesced callback matches neither of.
        val expectationsBefore = expectedSelections.size
        connection.beginBatchEdit()
        try {
            action()
        } finally {
            if (currentInputConnection !== connection) {
                cachedSelectionStart = -1
                cachedSelectionEnd = -1
                selfEdit = false
            } else {
                val after = readEditorSelection(connection)
                if (after != null) {
                    cachedSelectionStart = after.start
                    cachedSelectionEnd = after.end
                    val actionRegisteredItsOwn = expectedSelections.size > expectationsBefore
                    if (!actionRegisteredItsOwn && after != before) {
                        expectedSelections.expect(before, after)
                    }
                    selfEdit = false
                }
                // If this editor does not support extracted text, retain the existing one-shot
                // fallback so its eventual callback is still recognized as ours.
            }
            // Last, so the coalesced callback is only released once the position it will report is
            // the one the tracker has been told to expect.
            connection.endBatchEdit()
            // What the next queued edit was released against.
            orderedInputGuard = captureOrderedInputGuard()
        }
    }

    private fun gestureInputPending(): Boolean = gestureInputQueue.hasPending

    private fun gestureInputIsCurrent(request: OrderedInputRequest): Boolean =
        gestureInputQueue.isCurrent(request)

    /**
     * Drops speculative gesture work without cancelling input the user has already committed.
     *
     * [OrderedInputQueue] reserves cancellation for editor and session transitions: a queued action
     * is a key the user has already released, and nothing replays it. A cursor moving under the
     * keyboard is not a transition, and a final decode still re-checks the editor it was started
     * against before committing anything, so only the preview needs abandoning.
     */
    private fun cancelGesturePreviewWork() {
        gesturePreviewGeneration++
        pendingGesturePreview = null
        gesturePreviewJob?.cancel()
        gesturePreviewJob = null
    }

    private fun cancelGestureInputSequence() {
        gestureInputQueue.cancel()
        orderedInputGuard = null
        cancelGesturePreviewWork()
    }

    override fun onGesturePreview(points: List<GesturePoint>) {
        if (!settings.gestureTypingEnabled || !editorInputPolicy.allowsSuggestions) return
        if (anyPanelShown || layer != Layer.ALPHA) return
        if (gestureDecoder == null || currentGestureKeyMap() == null) return

        gestureUndoState.invalidate()
        if (!settings.suggestionsEnabled) return
        // A completed gesture has priority. Native inference cannot be interrupted once entered,
        // so starting previews here would only queue work ahead of ordered final input.
        if (gestureInputPending()) return
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
                // One blocking Binder round trip into the editor per stroke, not per tick: the
                // words before the swipe cannot change while the finger is still down, and the
                // preview generation is bumped by every path that could make them change (cancel,
                // completion, editor transitions).
                val context = swipePreviewContext
                    .takeIf { swipePreviewContextGeneration == generation }
                    ?: precedingContextForSwipe().also {
                        swipePreviewContext = it
                        swipePreviewContextGeneration = generation
                    }
                val candidates = try {
                    withContext(Dispatchers.Default) {
                        gestureDecodeMutex.withLock {
                            decodeGesture(
                                decoder = decoder,
                                points = trace,
                                keys = keys,
                                blockOffensive = blockOffensive,
                                previousWord = context.previous,
                                previousPreviousWord = context.older,
                            ).candidates
                        }
                    }
                } catch (_: CancellationException) {
                    break
                } catch (failure: Throwable) {
                    Log.w(TAG, "Swipe preview decode failed", failure)
                    break
                }
                if (generation != gesturePreviewGeneration) break
                // A newer trace will be decoded immediately; avoid flashing a result that is
                // already stale while the finger is still moving.
                if (
                    pendingGesturePreview == null &&
                    settings.suggestionsEnabled &&
                    candidates.isNotEmpty()
                ) {
                    stripMode = StripMode.GesturePreview
                    lastGestureCommit = null
                    suggestionStrip?.setSuggestions(candidates.map { it.word })
                }
            }
        }
    }

    override fun onGesturePreviewCancelled(clearCandidates: Boolean) {
        gesturePreviewGeneration++
        pendingGesturePreview = null
        gesturePreviewJob?.cancel()
        gesturePreviewJob = null
        if (clearCandidates && stripMode == StripMode.GesturePreview) clearSuggestions()
    }

    override fun onCursorMove(steps: Int) {
        if (steps == 0) return
        if (queueBehindGestureInput { processCursorMove(steps) }) return
        processCursorMove(steps)
    }

    private fun processCursorMove(steps: Int) {
        gestureUndoState.invalidate()
        val connection = currentInputConnection ?: return
        val selfEditWasPending = selfEdit
        selfEdit = true
        val start = cachedSelectionStart
        val end = cachedSelectionEnd
        if (start < 0 || end < 0) {
            val callbackPossible = sendCursorKeyEvents(connection, steps)
            selfEdit = SelfEditFallback.afterAttempt(
                selfEditWasPending,
                callbackPossible,
                selfEdit,
            )
            return
        }

        val target = if (start != end) {
            if (steps < 0) minOf(start, end) else maxOf(start, end)
        } else if (steps < 0) {
            val before = connection.getTextBeforeCursor(MAX_GRAPHEME_CONTEXT_CHARS, 0)?.toString()
            if (before.isNullOrEmpty()) {
                val callbackPossible = sendCursorKeyEvents(connection, steps)
                selfEdit = SelfEditFallback.afterAttempt(
                    selfEditWasPending,
                    callbackPossible,
                    selfEdit,
                )
                return
            }
            (start - (before.length - AndroidGraphemeBoundaries.move(before, before.length, steps)))
                .coerceAtLeast(0)
        } else {
            val after = connection.getTextAfterCursor(MAX_GRAPHEME_CONTEXT_CHARS, 0)?.toString()
            if (after.isNullOrEmpty()) {
                val callbackPossible = sendCursorKeyEvents(connection, steps)
                selfEdit = SelfEditFallback.afterAttempt(
                    selfEditWasPending,
                    callbackPossible,
                    selfEdit,
                )
                return
            }
            start + AndroidGraphemeBoundaries.move(after, 0, steps)
        }

        val abandonment = abandonComposing(connection)
        if (!abandonment.settled) {
            selfEdit = SelfEditFallback.afterAttempt(
                selfEditWasPending,
                abandonment.callbackPossible,
                selfEdit,
            )
            return
        }
        val selectionWillChange = start != end || target != start
        val moved = selectionWillChange && connection.setSelection(target, target)
        if (moved) {
            cachedSelectionStart = target
            cachedSelectionEnd = target
        } else if (selectionWillChange) {
            cachedSelectionStart = -1
            cachedSelectionEnd = -1
        }
        literalWordInProgress = false
        val callbackPossible = abandonment.callbackPossible || moved
        updateShiftFromCursor()
        selfEdit = SelfEditFallback.afterAttempt(
            selfEditWasPending,
            callbackPossible,
            selfEdit,
        )
        if (moved) keyboardView?.announceForAccessibility("Cursor moved")
    }

    private fun sendCursorKeyEvents(connection: InputConnection, steps: Int): Boolean {
        val abandonment = abandonComposing(connection)
        if (!abandonment.settled) return abandonment.callbackPossible

        val direction = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        var keyAccepted = false
        for (ignored in 0 until kotlin.math.abs(steps)) {
            val accepted = sendSoftKeyPair(connection, direction)
            keyAccepted = keyAccepted || accepted
            if (!accepted) break
        }
        cachedSelectionStart = -1
        cachedSelectionEnd = -1
        literalWordInProgress = false
        val callbackPossible = abandonment.callbackPossible || keyAccepted
        updateShiftFromCursor()
        return callbackPossible
    }

    override fun onDeleteWordGesture() {
        if (queueBehindGestureInput(::processDeleteWordGesture)) return
        processDeleteWordGesture()
    }

    private fun processDeleteWordGesture() {
        gestureUndoState.invalidate()
        val connection = currentInputConnection ?: return
        val selfEditWasPending = selfEdit
        selfEdit = true
        val finish = finishComposing(connection)
        var callbackPossible = finish.callbackPossible
        if (!finish.settled) {
            selfEdit = SelfEditFallback.afterAttempt(
                selfEditWasPending,
                callbackPossible,
                selfEdit,
            )
            return
        }
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            val deleted = connection.commitText("", 1)
            callbackPossible = callbackPossible || deleted
            if (deleted) literalWordInProgress = false
            selfEdit = SelfEditFallback.afterAttempt(
                selfEditWasPending,
                callbackPossible,
                selfEdit,
            )
            return
        }

        val before = connection.getTextBeforeCursor(MAX_WORD_DELETE_CHARS, 0)?.toString().orEmpty()
        if (before.isEmpty()) {
            selfEdit = SelfEditFallback.afterAttempt(
                selfEditWasPending,
                callbackPossible,
                selfEdit,
            )
            return
        }
        var start = before.length
        while (start > 0 && before[start - 1].isWhitespace()) start--
        while (start > 0 && !before[start - 1].isWhitespace()) start--
        val deleted = connection.deleteSurroundingText(before.length - start, 0)
        callbackPossible = callbackPossible || deleted
        if (deleted) {
            literalWordInProgress = false
            updateShiftFromCursor()
        }
        selfEdit = SelfEditFallback.afterAttempt(
            selfEditWasPending,
            callbackPossible,
            selfEdit,
        )
    }

    /**
     * Inserts a decoded word, spacing it from whatever precedes it the way a typed word would be.
     *
     * Gboard's behaviour, which people are used to: swiping mid-sentence inserts a leading space
     * so words do not run together, but not at the very start of a field or straight after
     * existing whitespace or an opening bracket.
     */
    private fun commitGestureWord(
        connection: InputConnection,
        word: String,
        /** Where the cursor already was, read once by the caller rather than extracted again. */
        knownSelectionBeforeCommit: EditorSelection?,
    ): Boolean {
        val selfEditWasPending = selfEdit
        val selectionBeforeCommit = knownSelectionBeforeCommit ?: cachedEditorSelection()
        val before = connection.getTextBeforeCursor(AUTO_SPACING_CONTEXT_CHARS, 0)
        val needsSpace = AutoSpacing.beforeWord(before)

        val previousWord = precedingWordForSwipe()
        val learnablePair = if (!incognito && !previousWord.isNullOrEmpty() && word.isNotEmpty()) {
            previousWord to word
        } else {
            null
        }

        val shifted = shiftState()
        val text = (if (needsSpace) " " else "") + applyShift(word, shifted)

        val committed = connection.commitText(text, 1)
        var exactSelectionTracked = false
        lastGestureCommit = text.takeIf { committed }
        lastGestureShift = shifted
        if (committed) {
            val liveSelection = readEditorSelection(connection)
            if (liveSelection != null) {
                cachedSelectionStart = liveSelection.start
                cachedSelectionEnd = liveSelection.end
                if (liveSelection != selectionBeforeCommit) {
                    expectedSelections.expect(selectionBeforeCommit, liveSelection)
                    exactSelectionTracked = true
                }
            } else {
                val insertionStart = minOf(cachedSelectionStart, cachedSelectionEnd)
                    .takeIf { cachedSelectionStart >= 0 && cachedSelectionEnd >= 0 }
                if (insertionStart != null) {
                    cachedSelectionStart = insertionStart + text.length
                    cachedSelectionEnd = cachedSelectionStart
                    expectedSelections.expect(
                        selectionBeforeCommit,
                        EditorSelection(cachedSelectionStart, cachedSelectionEnd),
                    )
                    exactSelectionTracked = true
                }
            }
            learnPair(previousWord, word)
            lastGestureLearnedPair = learnablePair
            gestureUndoState.arm(
                text,
                editorGeneration,
                collapsedCursorPosition(),
                learnablePair,
                word,
            )
        } else {
            lastGestureLearnedPair = null
            gestureUndoState.invalidate()
        }
        selfEdit = SelfEditFallback.afterAttempt(
            selfEditWasPending,
            callbackPossible = committed,
            fallbackStillArmed = committed && !exactSelectionTracked,
        )
        lastAutocorrect = null

        if (committed) {
            if (shifted == ShiftState.SHIFTED) setShift(ShiftState.OFF)
            updateShiftFromCursor()
        }
        return committed
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
        if (stripMode != StripMode.Gesture) gestureUndoState.invalidate()
        when (stripMode) {
            StripMode.Gesture -> pickGestureAlternative(index, word)
            StripMode.Typing -> pickTypedSuggestion(word)
            StripMode.Prediction -> commitPrediction(word)
            StripMode.GesturePreview, StripMode.Empty -> Unit
        }
    }

    override fun onSettingsRequested() {
        if (queueBehindGestureInput(::processSettingsRequested)) return
        processSettingsRequested()
    }

    private fun processSettingsRequested() {
        gestureUndoState.invalidate()
        performHaptic()
        if (keyboardSettingsPanelShown) {
            hideKeyboardSettingsPanel(restoreEditorUi = true)
        } else {
            showKeyboardSettingsPanel()
        }
    }

    override fun onKeyboardSettingsDismissed() {
        performHaptic()
        hideKeyboardSettingsPanel(restoreEditorUi = true)
    }

    override fun onKeyboardSettingsChanged(settings: KeyboardSettings) {
        performHaptic()
        // The panel carries its complete latest snapshot, so two quick toggles cannot overwrite
        // each other while DataStore serialises the writes.
        scope.launch { settingsRepository.update { settings } }
    }

    private fun showKeyboardSettingsPanel() {
        val panel = keyboardSettingsPanel ?: return
        if (panel.visibility == View.VISIBLE) return

        // Settings are an interaction mode, not a new Android task. Settle the current word and
        // close mutually exclusive panels before covering the keys in-place.
        if (composing.isNotEmpty()) {
            val connection = currentInputConnection ?: return
            if (!finishComposing(connection).settled) return
        }
        if (voiceOverlayShown) onVoiceDismissed(committed = false)
        exitEmojiSearch(showPicker = false)
        hideEmojiPanel()
        hideTextEditPanel(restoreEditorUi = false)
        hideClipboardPanel(restoreEditorUi = false)
        clearSuggestions()

        panel.settings = settings
        panel.visibility = View.VISIBLE
        suggestionStrip?.voiceEnabled = false
        suggestionStrip?.setEmptyMessage("Keyboard settings")
        updateGestureAvailability()
        refreshBackCallback()
        panel.announceForAccessibility("Keyboard settings opened")
    }

    private fun hideKeyboardSettingsPanel(restoreEditorUi: Boolean) {
        val panel = keyboardSettingsPanel ?: return
        if (panel.visibility != View.VISIBLE) return
        panel.visibility = View.GONE

        suggestionStrip?.voiceEnabled = voiceAvailableForEditor()
        clearSuggestions()
        refreshSuggestionEmptyMessage()
        updateGestureAvailability()
        refreshBackCallback()
        if (restoreEditorUi) {
            updateShiftFromCursor()
            updatePredictions()
            keyboardView?.announceForAccessibility("Keyboard settings closed")
        }
    }

    private val keyboardSettingsPanelShown: Boolean
        get() = keyboardSettingsPanel?.visibility == View.VISIBLE

    // region Text editing and clipboard panels

    private val textEditPanelShown: Boolean
        get() = textEditPanel?.visibility == View.VISIBLE

    private val clipboardPanelShown: Boolean
        get() = clipboardPanel?.visibility == View.VISIBLE

    // Queued like the settings and voice shortcuts: a swipe that just ended may still be
    // decoding, and opening a panel now would gate gesture mode off and silently drop its word.
    override fun onTextEditRequested() {
        if (queueBehindGestureInput(::showTextEditPanel)) return
        showTextEditPanel()
    }

    override fun onClipboardRequested() {
        if (queueBehindGestureInput(::showClipboardPanel)) return
        showClipboardPanel()
    }

    private fun showTextEditPanel() {
        val panel = textEditPanel ?: return
        if (panel.visibility == View.VISIBLE) return

        // Like settings, an interaction mode over the keys: the word in progress settles first so
        // cursor movement operates on finished text, and rival panels close underneath it.
        if (composing.isNotEmpty()) {
            val connection = currentInputConnection ?: return
            if (!finishComposing(connection).settled) return
        }
        if (voiceOverlayShown) onVoiceDismissed(committed = false)
        exitEmojiSearch(showPicker = false)
        hideEmojiPanel()
        hideClipboardPanel(restoreEditorUi = false)
        hideKeyboardSettingsPanel(restoreEditorUi = false)
        clearSuggestions()

        panel.reset()
        panel.visibility = View.VISIBLE
        suggestionStrip?.voiceEnabled = false
        suggestionStrip?.setEmptyMessage("Edit text")
        updateGestureAvailability()
        refreshBackCallback()
        panel.announceForAccessibility("Text editing opened")
    }

    private fun hideTextEditPanel(restoreEditorUi: Boolean) {
        val panel = textEditPanel ?: return
        if (panel.visibility != View.VISIBLE) return
        panel.reset()
        panel.visibility = View.GONE

        suggestionStrip?.voiceEnabled = voiceAvailableForEditor()
        clearSuggestions()
        refreshSuggestionEmptyMessage()
        updateGestureAvailability()
        refreshBackCallback()
        if (restoreEditorUi) {
            updateShiftFromCursor()
            updatePredictions()
            keyboardView?.announceForAccessibility("Text editing closed")
        }
    }

    override fun onTextEditDismissed() {
        hideTextEditPanel(restoreEditorUi = true)
    }

    override fun onTextEditSelectingChanged(selecting: Boolean) {
        performHaptic()
        announce(if (selecting) "Arrows now extend the selection" else "Selection mode off")
    }

    override fun onTextEditAction(action: TextEditPanelView.Action) {
        val connection = currentInputConnection ?: return
        performHaptic()
        when (action) {
            TextEditPanelView.Action.Left -> sendCursorKey(connection, KeyEvent.KEYCODE_DPAD_LEFT)
            TextEditPanelView.Action.Right -> sendCursorKey(connection, KeyEvent.KEYCODE_DPAD_RIGHT)
            TextEditPanelView.Action.Up -> sendCursorKey(connection, KeyEvent.KEYCODE_DPAD_UP)
            TextEditPanelView.Action.Down -> sendCursorKey(connection, KeyEvent.KEYCODE_DPAD_DOWN)
            TextEditPanelView.Action.SelectAll -> {
                connection.performContextMenuAction(android.R.id.selectAll)
                announce("Selected all")
            }
            TextEditPanelView.Action.Copy -> {
                connection.performContextMenuAction(android.R.id.copy)
                announce("Copied")
            }
            TextEditPanelView.Action.Cut -> {
                connection.performContextMenuAction(android.R.id.cut)
                announce("Cut")
            }
            TextEditPanelView.Action.Paste -> {
                connection.performContextMenuAction(android.R.id.paste)
                announce("Pasted")
            }
            TextEditPanelView.Action.Delete -> {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
        }
    }

    /**
     * Arrow presses travel as real key events so each editor's own cursor logic applies —
     * multi-line movement, RTL runs, list widgets. While Select mode is on, Shift is held around
     * the arrow, which is exactly how a hardware keyboard extends a selection.
     */
    private fun sendCursorKey(connection: InputConnection, keyCode: Int) {
        val selecting = textEditPanel?.selecting == true
        val now = SystemClock.uptimeMillis()
        val meta = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        if (selecting) {
            connection.sendKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, meta),
            )
        }
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
        if (selecting) {
            connection.sendKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0),
            )
        }
    }

    private fun showClipboardPanel() {
        val panel = clipboardPanel ?: return
        if (panel.visibility == View.VISIBLE) return

        if (composing.isNotEmpty()) {
            val connection = currentInputConnection ?: return
            if (!finishComposing(connection).settled) return
        }
        if (voiceOverlayShown) onVoiceDismissed(committed = false)
        exitEmojiSearch(showPicker = false)
        hideEmojiPanel()
        hideTextEditPanel(restoreEditorUi = false)
        hideKeyboardSettingsPanel(restoreEditorUi = false)
        clearSuggestions()

        refreshClipboardPanelItems()
        panel.visibility = View.VISIBLE
        suggestionStrip?.voiceEnabled = false
        suggestionStrip?.setEmptyMessage("Clipboard")
        updateGestureAvailability()
        refreshBackCallback()
        panel.announceForAccessibility("Clipboard opened")
    }

    private fun hideClipboardPanel(restoreEditorUi: Boolean) {
        val panel = clipboardPanel ?: return
        if (panel.visibility != View.VISIBLE) return
        panel.visibility = View.GONE

        suggestionStrip?.voiceEnabled = voiceAvailableForEditor()
        clearSuggestions()
        refreshSuggestionEmptyMessage()
        updateGestureAvailability()
        refreshBackCallback()
        if (restoreEditorUi) {
            updateShiftFromCursor()
            updatePredictions()
            keyboardView?.announceForAccessibility("Clipboard closed")
        }
    }

    private fun refreshClipboardPanelItems() {
        clipboardPanel?.setItems(
            clipboardHistory.entries().map { ClipboardPanelView.Item(it.text, it.pinned) },
        )
    }

    override fun onClipboardDismissed() {
        hideClipboardPanel(restoreEditorUi = true)
    }

    override fun onClipboardItemPicked(text: String) {
        if (queueBehindGestureInput { processClipboardPaste(text) }) return
        processClipboardPaste(text)
    }

    /** Mirrors the emoji commit path, which is the other panel that inserts literal text. */
    private fun processClipboardPaste(text: String) {
        gestureUndoState.invalidate()
        performHaptic()
        val connection = currentInputConnection ?: return
        val selfEditWasPending = selfEdit
        selfEdit = true
        val finish = finishComposing(connection)
        if (!finish.settled) {
            selfEdit = SelfEditFallback.afterAttempt(
                selfEditWasPending,
                finish.callbackPossible,
                selfEdit,
            )
            return
        }
        val committed = if (editorInputPolicy.usesRawKeyEvents) {
            handleRawText(connection, text)
        } else {
            connection.commitText(text, 1)
        }
        if (!committed) {
            selfEdit = SelfEditFallback.afterAttempt(
                selfEditWasPending,
                callbackPossible = finish.callbackPossible,
                fallbackStillArmed = selfEdit,
            )
            return
        }
        selfEdit = SelfEditFallback.afterAttempt(
            selfEditWasPending,
            callbackPossible = true,
            fallbackStillArmed = selfEdit,
        )
        lastAutocorrect = null
        literalWordInProgress = false
        hideClipboardPanel(restoreEditorUi = false)
        updateShiftFromCursor()
        announce("Pasted")
    }

    override fun onClipboardItemPinToggled(text: String, pinned: Boolean) {
        performHaptic()
        if (pinned) clipboardHistory.pin(text) else clipboardHistory.unpin(text)
        refreshClipboardPanelItems()
        announce(if (pinned) "Pinned" else "Unpinned")
    }

    override fun onClipboardItemDeleted(text: String) {
        performHaptic()
        clipboardHistory.remove(text)
        refreshClipboardPanelItems()
        announce("Deleted from clipboard history")
    }

    // endregion

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
        val originalUndo = gestureUndoState.snapshot() ?: return
        val liveSelection = connection.getSelectedText(0)
        val hasCachedSelection = cachedSelectionStart >= 0 &&
            cachedSelectionEnd >= 0 &&
            cachedSelectionStart != cachedSelectionEnd

        if (
            !liveSelection.isNullOrEmpty() ||
            hasCachedSelection ||
            !gestureUndoState.matchesEditorAndCursor(
                editorGeneration,
                collapsedCursorPosition(),
            ) ||
            connection.getTextBeforeCursor(previous.length, 0)?.toString() != previous
        ) {
            gestureUndoState.invalidate()
            clearSuggestions()
            return
        }

        val prefix = if (previous.startsWith(" ")) " " else ""
        val replacement = prefix + applyShift(word, lastGestureShift)
        if (replacement == previous) return

        val selfEditWasPending = selfEdit
        selfEdit = true
        val selectionBefore = readEditorSelection(connection) ?: cachedEditorSelection()
        connection.beginBatchEdit()
        val transaction = try {
            GestureEditTransaction.replace(
                original = previous,
                replacement = replacement,
                deleteBeforeCursor = { connection.deleteSurroundingText(it, 0) },
                commit = { connection.commitText(it, 1) },
            )
        } finally {
            connection.endBatchEdit()
        }
        var exactSelectionTracked = false
        if (!transaction.replaced) {
            val selectionAfter = readEditorSelection(connection)
            if (selectionAfter != null) {
                cachedSelectionStart = selectionAfter.start
                cachedSelectionEnd = selectionAfter.end
                if (selectionAfter != selectionBefore) {
                    expectedSelections.expect(selectionBefore, selectionAfter)
                    exactSelectionTracked = true
                }
            } else if (transaction.deleted) {
                if (transaction.restoredOriginal) {
                    cachedSelectionStart = originalUndo.cursorPosition ?: -1
                    cachedSelectionEnd = originalUndo.cursorPosition ?: -1
                } else {
                    cachedSelectionStart = -1
                    cachedSelectionEnd = -1
                }
            }
            when {
                !transaction.deleted -> Unit // The original text and undo record are unchanged.
                transaction.restoredOriginal -> gestureUndoState.arm(
                    originalUndo.committedText,
                    originalUndo.editorGeneration,
                    originalUndo.cursorPosition,
                    originalUndo.learnedPair,
                    originalUndo.adaptiveWord,
                )
                else -> {
                    rollbackGestureLearning(originalUndo.learnedPair)
                    gestureUndoState.invalidate()
                }
            }
            selfEdit = SelfEditFallback.afterAttempt(
                previouslyPending = selfEditWasPending,
                callbackPossible = transaction.deleted && !transaction.restoredOriginal,
                fallbackStillArmed = selfEdit && !exactSelectionTracked,
            )
            clearSuggestions()
            updateShiftFromCursor()
            return
        }
        val selectionAfter = readEditorSelection(connection)
        if (selectionAfter != null) {
            cachedSelectionStart = selectionAfter.start
            cachedSelectionEnd = selectionAfter.end
            if (selectionAfter != selectionBefore) {
                expectedSelections.expect(selectionBefore, selectionAfter)
                exactSelectionTracked = true
            }
        } else if (cachedSelectionStart == cachedSelectionEnd && cachedSelectionStart >= 0) {
            cachedSelectionStart += replacement.length - previous.length
            cachedSelectionEnd = cachedSelectionStart
            expectedSelections.expect(
                selectionBefore,
                EditorSelection(cachedSelectionStart, cachedSelectionEnd),
            )
            exactSelectionTracked = true
        }
        selfEdit = SelfEditFallback.afterAttempt(
            previouslyPending = selfEditWasPending,
            callbackPossible = true,
            fallbackStillArmed = selfEdit && !exactSelectionTracked,
        )

        // The first-ranked word was only a machine guess. A verified replacement is the explicit
        // outcome the bounded swipe model is allowed to learn from; no trace or editor context is
        // retained by that model.
        var learnedGestureChanged = false
        val rejectedAdaptiveWord = originalUndo.adaptiveWord
        if (
            !incognito &&
            rejectedAdaptiveWord != null &&
            gestureAdaptation.observeAlternative(rejectedAdaptiveWord, word)
        ) {
            learnedPersistence.markDirty()
            learnedGestureChanged = true
        }

        // Repair the language observation as well, using context only in the existing bigram
        // model. Gesture adaptation itself remains context-free and fingerprinted.
        lastGestureLearnedPair?.let { (context, guessed) ->
            if (!incognito) {
                userBigrams.unlearn(context, guessed)
                userBigrams.learn(context, word)
                learnedPersistence.markDirty()
                learnedGestureChanged = true
                lastGestureLearnedPair = context to word
            } else {
                lastGestureLearnedPair = null
            }
        }
        if (learnedGestureChanged) saveLearnedWords()
        typingQuality.recordExplicitAlternateSelection(QualityInputMode.SWIPE)

        // The strip stays up, and keeps its order, so a second wrong guess is also one tap away
        // and the candidates do not move under the user's thumb.
        lastGestureCommit = replacement
        gestureUndoState.arm(
            replacement,
            editorGeneration,
            collapsedCursorPosition(),
            lastGestureLearnedPair,
            word,
        )
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
                keyboardSettingsPanelShown -> "Keyboard settings"
                passwordField -> "Suggestions are off in password fields"
                !editorInputPolicy.allowsSuggestions -> "Suggestions are off in this field"
                !settings.suggestionsEnabled -> "Suggestions are disabled"
                else -> ""
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
            KeyType.GLOBE -> {
                switchToNextIme()
                return
            }
            KeyType.SHIFT, KeyType.SYMBOLS, KeyType.SYMBOLS_ALT, KeyType.ALPHA, KeyType.EMOJI,
            KeyType.MIC, KeyType.SETTINGS -> return
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
            keyboardLayout = Layouts.withImeSwitcher(Layouts.QwertyEn, imeSwitcherOffered)
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
        suggestionStrip?.voiceEnabled = voiceAvailableForEditor()
        refreshSuggestionEmptyMessage()
        if (showPicker && emojiData != null) {
            emojiPanel?.reset()
            emojiPanel?.visibility = View.VISIBLE
        }
        updateGestureAvailability()
        refreshBackCallback()
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
        if (queueBehindGestureInput(::processVoiceRequested)) return
        processVoiceRequested()
    }

    private fun processVoiceRequested() {
        gestureUndoState.invalidate()
        if (voiceCancellationPending) {
            announce("Voice typing is still closing")
            return
        }
        val connection = currentInputConnection
        if (!voiceAvailableForEditor() || connection == null) {
            if (!deviceHasMicrophone) {
                announce("Voice typing is unavailable because this device has no microphone")
                return
            }
            announce("Voice typing is unavailable in this field")
            return
        }
        if (!MicPermissionActivity.hasPermission(this)) {
            // The user has just signalled intent to dictate, so start the speech process while
            // the permission dialog is up: its fork and bind overlap the dialog instead of being
            // paid serially on the second tap. No model is loaded until dictation starts.
            voiceClient.bind()
            startActivity(MicPermissionActivity.intent(this))
            return
        }

        // Dictation replaces the whole input view, so the picker has no business staying open
        // underneath it.
        hideEmojiPanel()
        if (!finishComposing(connection).settled) {
            announce("Finish the current word before starting voice typing")
            return
        }
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
            !voiceAvailableForEditor() ||
            text.isBlank()
        ) return
        val connection = currentInputConnection ?: return
        val selfEditWasPending = selfEdit
        selfEdit = true
        val callbackPossible = commitDictation(connection, text)
        selfEdit = SelfEditFallback.afterAttempt(
            selfEditWasPending,
            callbackPossible,
            selfEdit,
        )
    }

    override fun onVoiceError(error: VoiceInput.Error) {
        if (voiceCancellationPending) {
            voiceCancellationPending = false
            return
        }
        if (voiceEditorGeneration != editorGeneration) return
        voiceOverlay?.apply {
            errorText = getString(voiceErrorText(error))
            state = VoiceInput.State.Idle
        }
    }

    /** The user-facing words for a speech-process error code, owned by the keyboard side. */
    private fun voiceErrorText(error: VoiceInput.Error): Int = when (error) {
        VoiceInput.Error.StillClosing -> R.string.voice_error_still_closing
        VoiceInput.Error.PermissionMissing -> R.string.voice_error_permission
        VoiceInput.Error.ModelUnavailable -> R.string.voice_error_model
        VoiceInput.Error.MicUnavailable -> R.string.voice_error_mic_unavailable
        VoiceInput.Error.MicStopped -> R.string.voice_error_mic_stopped
        VoiceInput.Error.DecodeFailed -> R.string.voice_error_decode
        VoiceInput.Error.ServiceUnavailable -> R.string.voice_error_service
        VoiceInput.Error.ProcessDied -> R.string.voice_error_process_died
    }

    /**
     * Inserts a transcript, spaced from the surrounding text the way a swiped word is.
     *
     * Whisper punctuates and capitalises its own output, so nothing here second-guesses it beyond
     * joining it to what is already in the field.
     */
    private fun commitDictation(connection: InputConnection, text: String): Boolean {
        val finish = finishComposing(connection)
        if (!finish.settled) return finish.callbackPossible

        val before = connection.getTextBeforeCursor(AUTO_SPACING_CONTEXT_CHARS, 0)
        val needsSpace = AutoSpacing.beforeWord(before)

        if (!connection.commitText(if (needsSpace) " $text" else text, 1)) {
            return finish.callbackPossible
        }
        lastAutocorrect = null
        literalWordInProgress = false
        updateShiftFromCursor()
        return true
    }

    private fun hideVoiceOverlay() {
        voiceOverlay?.apply {
            visibility = View.GONE
            errorText = null
            state = VoiceInput.State.Idle
        }
        updateGestureAvailability()
        refreshBackCallback()
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
        if (composing.isNotEmpty()) {
            val connection = currentInputConnection ?: return
            if (!finishComposing(connection).settled) return
        }
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
        refreshBackCallback()
    }

    private val searchModeShown: Boolean
        get() = keyboardView?.searchMode == true

    private val emojiPanelShown: Boolean
        get() = emojiPanel?.visibility == View.VISIBLE

    override fun onEmojiPicked(emoji: String) {
        if (queueBehindGestureInput { processEmojiPicked(emoji) }) return
        processEmojiPicked(emoji)
    }

    private fun processEmojiPicked(emoji: String) {
        gestureUndoState.invalidate()
        performHaptic()
        val connection = currentInputConnection ?: return
        val selfEditWasPending = selfEdit
        selfEdit = true
        val finish = finishComposing(connection)
        if (!finish.settled) {
            selfEdit = SelfEditFallback.afterAttempt(
                selfEditWasPending,
                finish.callbackPossible,
                selfEdit,
            )
            return
        }
        val committed = if (editorInputPolicy.usesRawKeyEvents) {
            handleRawText(connection, emoji)
        } else {
            connection.commitText(emoji, 1)
        }
        if (!committed) {
            selfEdit = SelfEditFallback.afterAttempt(
                selfEditWasPending,
                callbackPossible = finish.callbackPossible,
                fallbackStillArmed = selfEdit,
            )
            return
        }
        selfEdit = SelfEditFallback.afterAttempt(
            selfEditWasPending,
            callbackPossible = true,
            fallbackStillArmed = selfEdit,
        )
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
        if (queueBehindGestureInput(::processEmojiBackspace)) return
        processEmojiBackspace()
    }

    private fun processEmojiBackspace() {
        performHaptic()
        val connection = currentInputConnection ?: return
        // Routed like every other edit the keyboard makes: ordered behind a swipe still decoding,
        // and announced, so the selection change it causes is recognized as ours rather than read
        // as the user having moved the cursor.
        val selfEditWasPending = selfEdit
        selfEdit = true
        // Emoji are often multi-code-point ZWJ, tone, flag or keycap clusters, so this borrows the
        // key row's ICU grapheme-aware delete rather than leaving a partial glyph behind.
        val callbackPossible = handleDelete(connection)
        selfEdit = SelfEditFallback.afterAttempt(
            selfEditWasPending,
            callbackPossible,
            selfEdit,
        )
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

    /** Sends printable text the way TYPE_NULL editors request it: as virtual hardware events. */
    private fun handleRawText(connection: InputConnection, text: String): Boolean {
        if (text.isEmpty()) return false
        val generated = runCatching {
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
        }.getOrNull()
        val sent = if (!generated.isNullOrEmpty()) {
            var anyTextKeyAccepted = false
            generated.forEach { event ->
                val accepted = connection.sendKeyEvent(
                    KeyEvent.changeFlags(event, event.flags or KeyEvent.FLAG_SOFT_KEYBOARD),
                )
                // ACTION_UP and modifier presses complete a virtual hardware sequence but do not
                // insert text themselves. A connection accepting only those cannot produce the
                // edit callback for which selfEdit is armed.
                if (
                    accepted &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    !KeyEvent.isModifierKey(event.keyCode)
                ) {
                    anyTextKeyAccepted = true
                }
            }
            anyTextKeyAccepted
        } else {
            // Some Unicode strings have no key-code representation. ACTION_MULTIPLE preserves the
            // characters while still honouring TYPE_NULL's request not to use commitText().
            connection.sendKeyEvent(
                KeyEvent(
                    SystemClock.uptimeMillis(),
                    text,
                    KeyCharacterMap.VIRTUAL_KEYBOARD,
                    KeyEvent.FLAG_SOFT_KEYBOARD,
                ),
            )
        }
        if (sent) rawEditSucceeded()
        return sent
    }

    /** Sends Delete/Enter with both halves of a virtual soft-keyboard press. */
    private fun handleRawKey(connection: InputConnection, keyCode: Int): Boolean {
        val sent = sendSoftKeyPair(connection, keyCode)
        if (sent) rawEditSucceeded()
        return sent
    }

    private fun sendSoftKeyPair(connection: InputConnection, keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now,
            now,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_SOFT_KEYBOARD,
            InputDevice.SOURCE_KEYBOARD,
        )
        if (!connection.sendKeyEvent(down)) return false
        // Delete and Enter take effect on ACTION_DOWN. A connection rejecting only the matching
        // key-up can still produce a selection callback for the accepted edit.
        connection.sendKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
        return true
    }

    private fun rawEditSucceeded() {
        lastAutocorrect = null
        literalWordInProgress = false
        cachedSelectionStart = -1
        cachedSelectionEnd = -1
        clearSuggestions()
        if (shiftState() == ShiftState.SHIFTED) setShift(ShiftState.OFF)
    }

    private fun handleCharacter(
        connection: InputConnection,
        text: String,
        touchX: Float = Float.NaN,
        touchY: Float = Float.NaN,
        /** The last swipe's committed word when the cursor still rests at its end, else null. */
        swipedWordBehindCursor: String? = null,
    ): Boolean {
        var callbackPossible = false
        // Typing with the cursor parked in the middle of a reopened word: the region can only grow
        // at its end, so settling it first is the only way the letter lands where the user is
        // looking.
        if (composing.isNotEmpty() && !composingAtEnd) {
            val abandonment = abandonComposing(connection)
            callbackPossible = abandonment.callbackPossible || callbackPossible
            if (!abandonment.settled) return callbackPossible
        }

        if (isWordCharacter(text)) {
            // Delay automatic spacing until a word actually starts. This turns `hello,w` into
            // `hello, w`, while leaving `hello?!` and a manually entered space exactly as typed.
            // Non-language editors opt out through the same policy as correction and prediction,
            // so an address or URL is never rewritten behind the user's back.
            val wantsAutoSpace = composing.isEmpty() &&
                editorInputPolicy.allowsSuggestions &&
                AutoSpacing.beforeTypedWord(
                    connection.getTextBeforeCursor(AUTO_SPACING_CONTEXT_CHARS, 0),
                    swipedWordBehindCursor,
                )

            // The space and the letter it makes room for are one edit. Reported separately they
            // produce two selection callbacks against a single one-shot [selfEdit], so the second
            // is read as the user moving the cursor: candidates cleared, the word in progress
            // abandoned, and the cursor mid-word taken as an invitation to reopen it.
            if (wantsAutoSpace) connection.beginBatchEdit()
            var autoSpaceCommitted = false
            var characterCommitted = false
            var autoSpaceRolledBack = false
            try {
                autoSpaceCommitted = wantsAutoSpace && connection.commitText(" ", 1)

                // A cursor can arrive at the edge of existing text without a useful selection
                // callback (notably on initial focus). Starting a one-character composing suffix
                // there is just as unsafe as starting one after an asynchronous model load.
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
                        val abandonment = abandonComposing(connection)
                        callbackPossible = abandonment.callbackPossible || callbackPossible
                        if (!abandonment.settled) return callbackPossible
                        literalWordInProgress = true
                    }
                    characterCommitted = connection.commitText(text, 1)
                    if (characterCommitted) {
                        literalWordInProgress = true
                        clearSuggestions()
                    }
                } else {
                    val next = composing.toString() + text
                    if (connection.setComposingText(next, 1)) {
                        recordTouch(composing.length, touchX, touchY)
                        composing.append(text)
                        characterCommitted = true
                        updateTypingSuggestions(suggester, keys)
                    } else if (composing.isEmpty() && connection.commitText(text, 1)) {
                        // The editor declined composing text from the outset. Continue literally
                        // rather than tracking a region that does not exist and duplicating it on
                        // the next character.
                        characterCommitted = true
                        literalWordInProgress = true
                        clearSuggestions()
                    }
                }

                if (!characterCommitted && autoSpaceCommitted) {
                    // Do not leave an automatic separator behind when the character it was making
                    // room for was rejected.
                    autoSpaceRolledBack = connection.deleteSurroundingText(1, 0)
                }
            } finally {
                if (wantsAutoSpace) connection.endBatchEdit()
            }
            if (!characterCommitted) {
                return callbackPossible || (autoSpaceCommitted && !autoSpaceRolledBack)
            }
            callbackPossible = true
        } else {
            // Punctuation ends a word, so it settles whatever was pending first -- typing "teh,"
            // should correct exactly as "teh " does.
            val finish = finishComposing(connection)
            callbackPossible = finish.callbackPossible || callbackPossible
            if (!finish.settled) return callbackPossible
            val committed = connection.commitText(text, 1)
            callbackPossible = callbackPossible || committed
            if (committed) literalWordInProgress = false
        }

        if (shiftState() == ShiftState.SHIFTED) setShift(ShiftState.OFF)
        updateShiftFromCursor()
        return callbackPossible
    }

    /** Whether a new composing region here would cover only a suffix of an existing word. */
    private fun cursorTouchesWord(connection: InputConnection): Boolean {
        val before = connection.getTextBeforeCursor(2, 0)
        val after = connection.getTextAfterCursor(2, 0)
        val beforeCodePoint = before?.takeIf { it.isNotEmpty() }
            ?.let { Character.codePointBefore(it, it.length) }
        val afterCodePoint = after?.takeIf { it.isNotEmpty() }
            ?.let { Character.codePointAt(it, 0) }
        return beforeCodePoint?.let(::isWordCharacter) == true ||
            afterCodePoint?.let(::isWordCharacter) == true
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

    /** Letters, combining marks, and common apostrophes build a word; everything else ends one. */
    private fun isWordCharacter(text: String): Boolean =
        text.codePointCount(0, text.length) == 1 && isWordCharacter(text.codePointAt(0))

    private fun isWordCharacter(codePoint: Int): Boolean =
        Character.isLetter(codePoint) ||
            codePoint in WORD_APOSTROPHES ||
            Character.getType(codePoint) in COMBINING_MARK_TYPES

    private fun handleSpace(connection: InputConnection, text: String, pressedAtMs: Long): Boolean {
        // Space is where a typed word is settled, and so where autocorrect actually happens.
        val finish = finishComposing(connection)
        if (!finish.settled) return finish.callbackPossible

        // The interval between the two presses, not between the two moments the keyboard got round
        // to applying them.
        val isDoubleSpace = settings.doubleSpacePeriod &&
            pressedAtMs - lastSpaceCommitMs < DOUBLE_SPACE_WINDOW_MS &&
            endsWithLetterThenSpace(connection)

        var separatorCommitted = false
        var editorChanged = false
        if (isDoubleSpace) {
            // One edit, so the editor reports one selection change: the intermediate position
            // between the delete and the full stop matches no expectation and would be read as the
            // user moving the cursor.
            connection.beginBatchEdit()
            var punctuated = false
            try {
                val replacement = GestureEditTransaction.replace(
                    original = " ",
                    replacement = ". ",
                    deleteBeforeCursor = { connection.deleteSurroundingText(it, 0) },
                    commit = { connection.commitText(it, 1) },
                )
                punctuated = replacement.replaced
                separatorCommitted = punctuated
                editorChanged = replacement.replaced ||
                    (replacement.deleted && !replacement.restoredOriginal)
                if (!separatorCommitted && (!replacement.deleted || replacement.restoredOriginal)) {
                    // Falling back to the literal second Space is safe only if the first one is
                    // still present. If restoration failed, another mutation would make the
                    // damage larger.
                    separatorCommitted = connection.commitText(text, 1)
                    editorChanged = editorChanged || separatorCommitted
                    if (separatorCommitted) lastSpaceCommitMs = pressedAtMs
                }
            } finally {
                connection.endBatchEdit()
            }
            if (punctuated) {
                lastSpaceCommitMs = 0L
                // The word before the full stop is no longer where the undo record says it is.
                lastAutocorrect = null
            }
        } else {
            separatorCommitted = connection.commitText(text, 1)
            editorChanged = separatorCommitted
            if (separatorCommitted) lastSpaceCommitMs = pressedAtMs
        }
        if (separatorCommitted) literalWordInProgress = false
        updateShiftFromCursor()
        if (separatorCommitted) updatePredictions()
        return finish.callbackPossible || editorChanged
    }

    /** True when the text is "<letter><space>", the only case where double-space should punctuate. */
    private fun endsWithLetterThenSpace(connection: InputConnection): Boolean {
        val before = connection.getTextBeforeCursor(2, 0) ?: return false
        return before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()
    }

    private fun handleDelete(connection: InputConnection): Boolean {
        if (deleteLastGestureCommit(connection)) return true
        val revert = revertAutocorrect(connection)
        if (revert.consumed) return revert.callbackPossible

        var callbackPossible = false

        // Same reasoning as typing: with the cursor inside a reopened word, shortening the region
        // would delete its last letter rather than the one before the cursor.
        if (composing.isNotEmpty() && !composingAtEnd) {
            val abandonment = abandonComposing(connection)
            callbackPossible = abandonment.callbackPossible || callbackPossible
            if (!abandonment.settled) return callbackPossible
        }

        // Mid-word, backspace shortens the composing region rather than deleting from the editor,
        // so the suggestions keep up with what is actually in front of the cursor.
        if (composing.isNotEmpty()) {
            val nextLength = AndroidGraphemeBoundaries.previousBoundary(composing, composing.length)
            val next = composing.substring(0, nextLength)
            if (!connection.setComposingText(next, 1)) return callbackPossible
            composing.setLength(nextLength)
            recordTouch(nextLength, Float.NaN, Float.NaN)
            if (next.isEmpty()) {
                // The empty string has to be committed before the region is finished. On its own,
                // finishComposingText() settles what is there rather than removing it, which would
                // leave the letter this backspace just deleted sitting in the editor.
                connection.finishComposingText()
                clearSuggestions()
            } else {
                updateTypingSuggestions()
            }
            literalWordInProgress = false
            updateShiftFromCursor()
            return true
        }

        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            val deleted = connection.commitText("", 1)
            callbackPossible = callbackPossible || deleted
            if (deleted) {
                val collapsed = minOf(cachedSelectionStart, cachedSelectionEnd).takeIf { it >= 0 }
                if (collapsed != null) {
                    cachedSelectionStart = collapsed
                    cachedSelectionEnd = collapsed
                }
            }
        } else {
            val before = connection.getTextBeforeCursor(MAX_GRAPHEME_CONTEXT_CHARS, 0)?.toString().orEmpty()
            if (before.isNotEmpty()) {
                val boundary = AndroidGraphemeBoundaries.previousBoundary(before, before.length)
                val toDelete = before.length - boundary
                if (connection.deleteSurroundingText(toDelete, 0)) {
                    callbackPossible = true
                    if (cachedSelectionStart == cachedSelectionEnd && cachedSelectionStart >= 0) {
                        cachedSelectionStart = (cachedSelectionStart - toDelete).coerceAtLeast(0)
                        cachedSelectionEnd = cachedSelectionStart
                    }
                }
            }
        }
        literalWordInProgress = cursorTouchesWord(connection)
        updateShiftFromCursor()
        return callbackPossible
    }

    /** Deletes the exact text from the immediately preceding swipe as one atomic Backspace. */
    private fun deleteLastGestureCommit(connection: InputConnection): Boolean {
        val expectedLength = gestureUndoState.expectedTextLength ?: return false
        val selected = connection.getSelectedText(0)
        val hasSelection = !selected.isNullOrEmpty() ||
            (cachedSelectionStart >= 0 &&
                cachedSelectionEnd >= 0 &&
                cachedSelectionStart != cachedSelectionEnd)
        val before = if (hasSelection) {
            null
        } else {
            connection.getTextBeforeCursor(expectedLength, 0)?.toString()
        }
        val undo = gestureUndoState.consume(
            editorGeneration,
            hasSelection,
            before,
            collapsedCursorPosition(),
        ) ?: return false

        typingQuality.recordImmediateUndo(QualityInputMode.SWIPE)
        if (
            !incognito &&
            undo.adaptiveWord != null &&
            gestureAdaptation.observeImmediateUndo(undo.adaptiveWord)
        ) {
            learnedPersistence.markDirty()
            saveLearnedWords()
        }

        val selectionBefore = readEditorSelection(connection) ?: cachedEditorSelection()
        connection.beginBatchEdit()
        val deletion = try {
            GestureDeleteTransaction.delete(
                units = undo.committedText.length,
                deleteRange = { connection.deleteSurroundingText(it, 0) },
                deleteUnit = { connection.deleteSurroundingText(1, 0) },
            )
        } finally {
            connection.endBatchEdit()
        }
        // The verified immediate Backspace rejects the guessed phrase even when an unusual editor
        // refuses both the range delete and its bounded one-unit fallback.
        rollbackGestureLearning(undo.learnedPair)
        if (!deletion.changedEditor) return false

        var selectionTracked = false
        val liveSelection = readEditorSelection(connection)
        if (liveSelection != null) {
            cachedSelectionStart = liveSelection.start
            cachedSelectionEnd = liveSelection.end
            if (liveSelection != selectionBefore) {
                expectedSelections.expect(selectionBefore, liveSelection)
                selectionTracked = true
            }
        } else {
            val cursorAfterDelete = undo.cursorPosition?.minus(deletion.deletedUnits)
            if (cursorAfterDelete != null) {
                cachedSelectionStart = cursorAfterDelete.coerceAtLeast(0)
                cachedSelectionEnd = cachedSelectionStart
                expectedSelections.expect(
                    selectionBefore,
                    EditorSelection(cachedSelectionStart, cachedSelectionEnd),
                )
                selectionTracked = true
            } else {
                cachedSelectionStart = -1
                cachedSelectionEnd = -1
            }
        }
        if (selectionTracked) selfEdit = false
        lastAutocorrect = null
        literalWordInProgress = !deletion.fullyDeleted && cursorTouchesWord(connection)
        clearSuggestions()
        updateShiftFromCursor()
        updatePredictions()
        return true
    }

    private fun rollbackGestureLearning(pair: Pair<String, String>?) {
        pair ?: return
        userBigrams.unlearn(pair.first, pair.second)
        learnedPersistence.markDirty()
        saveLearnedWords()
    }

    private fun collapsedCursorPosition(): Int? = cachedSelectionStart.takeIf {
        it >= 0 && it == cachedSelectionEnd
    }

    private fun cachedEditorSelection(): EditorSelection? =
        if (cachedSelectionStart >= 0 && cachedSelectionEnd >= 0) {
            EditorSelection(cachedSelectionStart, cachedSelectionEnd)
        } else {
            null
        }

    /**
     * One request object, reused, asking for as little as an extraction can be asked for.
     *
     * Only the two offsets are ever wanted. The hints say so, but do not make this cheap: AOSP's
     * `TextView.extractTextInternal` ignores them on this path and copies the entire field across
     * the Binder either way, so a request is as expensive as the document is long. What the hints
     * buy is the editors that do read them; what actually bounds the cost is asking fewer times,
     * which is why the swipe path threads one reading through several call sites instead of asking
     * again at each. Reusing the object also keeps the per-call allocation off the input path.
     */
    private val selectionRequest = ExtractedTextRequest().apply {
        token = 0
        flags = 0
        hintMaxChars = 0
        hintMaxLines = 0
    }

    private fun readEditorSelection(connection: InputConnection): EditorSelection? {
        val extracted = connection.getExtractedText(selectionRequest, 0) ?: return null
        if (extracted.selectionStart < 0 || extracted.selectionEnd < 0) return null
        val offset = extracted.startOffset.coerceAtLeast(0)
        return EditorSelection(
            start = offset + extracted.selectionStart,
            end = offset + extracted.selectionEnd,
        )
    }

    private fun gestureEditorSnapshot(connection: InputConnection): GestureEditorSnapshot =
        GestureEditorSnapshot(
            cursor = readEditorSelection(connection) ?: collapsedCursorPosition()?.let {
                EditorSelection(it, it)
            },
            textBeforeCursor = connection
                .getTextBeforeCursor(GESTURE_CONTEXT_GUARD_CHARS, 0)
                ?.toString(),
            textAfterCursor = connection
                .getTextAfterCursor(GESTURE_CONTEXT_GUARD_CHARS, 0)
                ?.toString(),
        )

    private fun handleEnter(connection: InputConnection): Boolean {
        val finish = finishComposing(connection)
        if (!finish.settled) return finish.callbackPossible
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val suppressed = (info?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) ?: 0) != 0

        val actionRequested = !suppressed &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        val handled = actionRequested && connection.performEditorAction(action)
        val editorMutated = !handled && (
            connection.commitText("\n", 1) ||
                handleRawKey(connection, KeyEvent.KEYCODE_ENTER)
            )
        if (handled || editorMutated) {
            literalWordInProgress = false
            updateShiftFromCursor()
        }
        // An editor action can submit or advance a form without moving this field's selection. It
        // consumes Enter, but only a successful text/key fallback can produce our edit callback.
        return finish.callbackPossible || editorMutated
    }

    private fun handleShiftTap(pressedAtMs: Long) {
        // Measured between presses. Two taps queued behind a swipe decode are applied back to back,
        // so an execution-time window would lock caps for a deliberate, unhurried pair.
        val doubleTapped = pressedAtMs - lastShiftTapMs < DOUBLE_TAP_WINDOW_MS
        lastShiftTapMs = pressedAtMs

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

    private fun layoutFor(layer: Layer): KeyboardLayout {
        val base = when (layer) {
            Layer.ALPHA -> editorBaseLayout
            Layer.SYMBOLS -> Layouts.SymbolsEn
            Layer.SYMBOLS_ALT -> Layouts.SymbolsAltEn
        }
        return Layouts.withImeSwitcher(base, imeSwitcherOffered)
    }

    private fun shouldOfferImeSwitcher(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return shouldOfferSwitchingToNextInputMethod()
        }
        val token = window?.window?.attributes?.token ?: return false
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return false
        @Suppress("DEPRECATION")
        return manager.shouldOfferSwitchingToNextInputMethod(token)
    }

    private fun switchToNextIme() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!switchToNextInputMethod(false)) announce("No other input method is available")
            return
        }
        val token = window?.window?.attributes?.token ?: run {
            announce("Input method switcher is unavailable")
            return
        }
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        @Suppress("DEPRECATION")
        if (manager?.switchToNextInputMethod(token, false) != true) {
            announce("No other input method is available")
        }
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
            !anyPanelShown
    }

    // endregion

    // region The word being typed

    /** Whether language candidates are appropriate for this field and enabled by the user. */
    private fun fieldSuggestionsEnabled(): Boolean =
        settings.suggestionsEnabled && editorInputPolicy.allowsSuggestions

    /** Reuses immutable geometry until a layout-affecting event invalidates it. */
    private fun currentGestureKeyMap(): GestureKeyMap? {
        if (layer != Layer.ALPHA || searchModeShown || keyboardSettingsPanelShown) return null
        gestureKeyMapCache?.let { return it }
        return keyboardView?.gestureKeyMap()?.also { gestureKeyMapCache = it }
    }

    private fun updateTypingSuggestions() {
        val suggester = typingSuggester.takeIf { fieldSuggestionsEnabled() }
        val keys = suggester?.let { currentGestureKeyMap() }
        if (suggester == null || keys == null || composing.isEmpty()) {
            pendingTypedQuality = null
            clearSuggestions()
            return
        }

        updateTypingSuggestions(suggester, keys)
    }

    private fun updateTypingSuggestions(suggester: TypingSuggester, keys: GestureKeyMap) {
        val context = precedingContext()
        val started = SystemClock.elapsedRealtimeNanos()
        val result = suggester.suggest(
            typed = composing.toString(),
            keys = keys,
            blockOffensive = settings.blockOffensiveWords,
            previousWord = context.previous,
            previousPreviousWord = context.older,
            touchPoints = composingTouches,
        )
        val appliedAutocorrection = result.autocorrection
            .takeIf { settings.autocorrectEnabled && !recomposed }
        pendingTypedQuality = PendingTypedQuality(
            latencyMillis = elapsedMillis(started),
            candidateCount = result.words.size,
            // A literal decision is produced by several safety gates, not a calibrated score.
            // Unknown is more honest than treating its presentation-first position as confidence.
            confidence = if (appliedAutocorrection != null) {
                scoreConfidence(result.words.map { it.score })
            } else {
                ConfidenceBucket.UNKNOWN
            },
        )
        pendingAutocorrection = appliedAutocorrection

        stripMode = StripMode.Typing
        suggestionStrip?.setSuggestions(result.words.map { it.word })
    }

    private fun recordTypedDecision(outcome: DecisionOutcome) {
        val decision = pendingTypedQuality ?: return
        pendingTypedQuality = null
        typingQuality.recordTypedDecision(
            latencyMillis = decision.latencyMillis,
            candidateCount = decision.candidateCount,
            confidence = decision.confidence,
            outcome = outcome,
        )
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
                        val gestures = GestureAdaptation()
                        val deletionCompleted = userDictionaryStore.completePendingDeletion()
                        if (deletionCompleted) {
                            userDictionaryStore.load(words)
                            userDictionaryStore.load(pairs)
                            userDictionaryStore.load(touches)
                            userDictionaryStore.load(gestures)
                        }
                        LearnedDataSnapshot(
                            words = words,
                            pairs = pairs,
                            touches = touches,
                            gestures = gestures,
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
                    gestureAdaptation.restore(loaded.gestures.snapshot())
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
        gestureAdaptation.clear()

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

        val (words, pairs, touches, gestures) = copyLearnedModels()
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
                        val gesturesSaved = userDictionaryStore.save(gestures)
                        LearnedDataWriteResult(
                            saved = wordsSaved && pairsSaved && touchesSaved && gesturesSaved,
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
        val gestures: GestureAdaptation,
        val deletionPending: Boolean,
    )

    private data class LearnedDataWriteResult(
        val saved: Boolean,
        val pendingDeleteResolved: Boolean,
    )

    /** Main-thread deep copies of the four learned models, taken for a background write. */
    private data class LearnedModelCopies(
        val words: UserDictionary,
        val pairs: UserBigrams,
        val touches: SpatialTouchModel,
        val gestures: GestureAdaptation,
    )

    /**
     * The one place the copy ritual lives: a fifth learned model added here is snapshotted by
     * every save path — ordinary, final, or clear — instead of needing each site edited in step.
     */
    private fun copyLearnedModels() = LearnedModelCopies(
        words = UserDictionary().also { it.restore(userDictionary.entries()) },
        pairs = UserBigrams().also { it.restore(userBigrams.entries()) },
        touches = SpatialTouchModel().also { it.restore(spatialTouchModel.entries()) },
        gestures = GestureAdaptation().also { it.restore(gestureAdaptation.snapshot()) },
    )

    /** Captures only when destruction could otherwise strand a write or a clear. */
    private fun captureFinalLearnedData(): FinalLearnedData? {
        if (!learnedPersistence.needsFinalization) return null

        val (words, pairs, touches, gestures) = copyLearnedModels()
        return FinalLearnedData(
            words = words,
            pairs = pairs,
            touches = touches,
            gestures = gestures,
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
                    val gesturesSaved = userDictionaryStore.save(data.gestures)
                    wordsSaved && pairsSaved && touchesSaved && gesturesSaved
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
        val gestures: GestureAdaptation,
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
     * The result distinguishes an accepted settlement from a fully rejected attempt so callers
     * never apply an edit that assumes the composing region is gone.
     */
    private data class ComposingFinishResult(
        /** True when no region existed or the editor accepted an operation that settled it. */
        val settled: Boolean,
        val corrected: Boolean,
        /** At least one accepted editor operation can result in a selection/candidate callback. */
        val callbackPossible: Boolean,
    )

    private fun finishComposing(connection: InputConnection): ComposingFinishResult {
        if (composing.isEmpty()) return ComposingFinishResult(true, false, false)

        val typed = composing.toString()
        val correction = pendingAutocorrection
            ?.takeUnless { it.equals(typed, ignoreCase = true) }
            ?.let { matchTypedCase(typed, it) }
        val previous = precedingWord()
        val settlement = EditorComposingSettlement.finish(
            typed = typed,
            correction = correction,
            finish = connection::finishComposingText,
            commit = { connection.commitText(it, 1) },
        )
        if (!settlement.settled) {
            // The editor still owns this exact region. Keep every piece of state needed to retry;
            // clearing it would let a following separator or gesture land inside live composition.
            return ComposingFinishResult(
                settled = false,
                corrected = false,
                callbackPossible = settlement.callbackPossible,
            )
        }

        val autocorrect = correction?.takeIf { settlement.corrected }?.let { applied ->
            Autocorrect(
                original = typed,
                applied = applied,
                previous = previous,
                touches = composingTouches.copyOf(typed.length * 2),
                keys = currentGestureKeyMap(),
            )
        }
        lastAutocorrect = autocorrect
        recordTypedDecision(
            if (settlement.corrected) {
                DecisionOutcome.TOP_CANDIDATE_COMMITTED
            } else {
                DecisionOutcome.LITERAL_COMMITTED
            },
        )
        if (settlement.learnTypedWord && !recomposed) {
            // Settled exactly as typed, with the keyboard offering no objection. That is the
            // ordinary way a word the dictionary has never heard of gets into the language.
            // Reopened words are excluded: the user went back to look at one, not to write it.
            learnWord(typed)
            learnTouches(typed, typed)
        }

        // Whatever actually landed in the text is what followed the previous word. Read before
        // clearing our copy, so a composing word is still correctly stepped over.
        if (settlement.learnAppliedPair && !recomposed) {
            learnPair(previous, settlement.appliedText)
        }
        composing.setLength(0)
        clearTouches()
        pendingAutocorrection = null
        recomposed = false
        composingAtEnd = true
        clearSuggestions()
        if (settlement.callbackPossible) selfEdit = true
        return ComposingFinishResult(
            settled = true,
            corrected = settlement.corrected,
            callbackPossible = settlement.callbackPossible,
        )
    }

    /**
     * Drops the word in progress without changing it, for when the cursor has moved out from under
     * us or the field has been swapped. Correcting text we may no longer own is not worth the risk.
     */
    private fun abandonComposing(
        connection: InputConnection? = currentInputConnection,
    ): EditorComposingSettlement.AbandonResult {
        if (composing.isEmpty()) {
            lastAutocorrect = null
            pendingTypedQuality = null
            clearSuggestions()
            return EditorComposingSettlement.AbandonResult(
                settled = true,
                callbackPossible = false,
            )
        }
        val editor = connection ?: return EditorComposingSettlement.AbandonResult(
            settled = false,
            callbackPossible = false,
        )
        val settlement = EditorComposingSettlement.abandon(
            typed = composing.toString(),
            finish = editor::finishComposingText,
            commit = { editor.commitText(it, 1) },
        )
        if (!settlement.settled) return settlement

        recordTypedDecision(DecisionOutcome.STALE)

        composing.setLength(0)
        clearTouches()
        pendingAutocorrection = null
        lastAutocorrect = null
        recomposed = false
        composingAtEnd = true
        if (settlement.callbackPossible) selfEdit = true
        clearSuggestions()
        return settlement
    }

    /** Drops state that can no longer refer to the framework's current InputConnection. */
    private fun discardComposingForEditorTransition() {
        recordTypedDecision(DecisionOutcome.STALE)
        composing.setLength(0)
        clearTouches()
        pendingAutocorrection = null
        lastAutocorrect = null
        recomposed = false
        composingAtEnd = true
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
    private data class AutocorrectRevertResult(
        val consumed: Boolean,
        val callbackPossible: Boolean,
    )

    private fun revertAutocorrect(connection: InputConnection): AutocorrectRevertResult {
        val notConsumed = AutocorrectRevertResult(false, false)
        val undo = lastAutocorrect ?: return notConsumed
        lastAutocorrect = null
        if (composing.isNotEmpty()) return notConsumed

        // The separator committed after the word, if the user has already pressed one.
        val before = connection.getTextBeforeCursor(undo.applied.length + 1, 0)?.toString()
            ?: return notConsumed
        val separator = when {
            before == undo.applied -> ""
            before.length == undo.applied.length + 1 &&
                before.startsWith(undo.applied) &&
                !before.last().isLetterOrDigit() -> before.substring(undo.applied.length)
            else -> return notConsumed
        }

        // Closed from a finally: batch nesting is counted by the editor, so a throw between the two
        // calls would leave it permanently open and the keyboard would never hear about a selection
        // again for as long as the field is on screen.
        connection.beginBatchEdit()
        val replacement = try {
            GestureEditTransaction.replace(
                original = undo.applied + separator,
                replacement = undo.original + separator,
                deleteBeforeCursor = { connection.deleteSurroundingText(it, 0) },
                commit = { connection.commitText(it, 1) },
            )
        } finally {
            connection.endBatchEdit()
        }
        if (!replacement.deleted) return notConsumed
        if (!replacement.replaced) {
            // The helper restored the correction where possible. Either way the editor has already
            // accepted a mutation, so consuming this Backspace is safer than applying a second,
            // unrelated deletion to uncertain text.
            cachedSelectionStart = -1
            cachedSelectionEnd = -1
            return AutocorrectRevertResult(
                consumed = true,
                // A successful restoration leaves the batch observably unchanged, so no final
                // selection callback is guaranteed. A failed restoration leaves text deleted.
                callbackPossible = !replacement.restoredOriginal,
            )
        }

        typingQuality.recordImmediateCorrection(QualityInputMode.TYPED)

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
        return AutocorrectRevertResult(true, true)
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
        val typed = composing.toString()
        val previous = precedingWord()

        val selfEditWasPending = selfEdit
        selfEdit = true
        // Closed from a finally; an unbalanced begin leaves the editor's nesting count open for
        // good, and with it every further selection callback.
        connection.beginBatchEdit()
        lateinit var suggestion: EditorComposingSettlement.SuggestionResult
        var spaceCommitted = !appendSpace
        try {
            suggestion = EditorComposingSettlement.commitSuggestion(
                replacement = replacement,
                setComposing = { connection.setComposingText(it, 1) },
                finish = connection::finishComposingText,
                commit = { connection.commitText(it, 1) },
            )
            if (suggestion.settled && appendSpace) {
                spaceCommitted = connection.commitText(" ", 1)
            }
        } finally {
            connection.endBatchEdit()
        }
        selfEdit = SelfEditFallback.afterAttempt(
            selfEditWasPending,
            callbackPossible = suggestion.callbackPossible,
            fallbackStillArmed = selfEdit,
        )
        if (!suggestion.settled) {
            recordTypedDecision(DecisionOutcome.EDITOR_REJECTED)
            if (suggestion.replacementApplied) {
                // The replacement landed as composing text but both settlement operations were
                // rejected. Keep tracking the editor's new live value so a retry or following key
                // cannot overwrite it with the stale word that preceded the explicit strip tap.
                composing.setLength(0)
                composing.append(replacement)
                clearTouches()
                pendingAutocorrection = null
                lastAutocorrect = null
                composingAtEnd = true
                literalWordInProgress = false
                cachedSelectionStart = -1
                cachedSelectionEnd = -1
            }
            return
        }

        // A strip tap is explicit confirmation. Learn only after the editor confirms the chosen
        // word landed; failed custom connections must not train a model from text they rejected.
        learnTouches(typed, replacement)
        learnPair(previous, replacement)
        if (word.equals(typed, ignoreCase = true)) {
            learnWord(word, weight = TRUSTED_AT_ONCE)
        }
        recordTypedDecision(DecisionOutcome.LITERAL_COMMITTED)
        typingQuality.recordExplicitAlternateSelection(QualityInputMode.TYPED)

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
        if (appendSpace && spaceCommitted) updatePredictions()
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
        if (anyPanelShown) return

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
        val before = connection.getTextBeforeCursor(AUTO_SPACING_CONTEXT_CHARS, 0)
        val needsSpace = AutoSpacing.beforeWord(before)

        val selfEditWasPending = selfEdit
        selfEdit = true
        // Closed from a finally; an unbalanced begin leaves the editor's nesting count open for
        // good, and with it every further selection callback.
        connection.beginBatchEdit()
        val committed = try {
            connection.commitText(if (needsSpace) " $word " else "$word ", 1)
        } finally {
            connection.endBatchEdit()
        }
        selfEdit = SelfEditFallback.afterAttempt(
            selfEditWasPending,
            callbackPossible = committed,
            fallbackStillArmed = selfEdit,
        )
        if (!committed) {
            return
        }

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

        val reported = EditorSelection(newSelStart, newSelEnd)
        val expectedEdit = expectedSelections.consume(
            from = EditorSelection(oldSelStart, oldSelEnd),
            to = reported,
        )
        // An expected callback with more of our own chain still outstanding is an intermediate
        // report: the editor has already been sent the rest and is no longer where this callback
        // says. Caching the end of the chain instead keeps an edit arriving in the gap — a
        // Backspace hunting for the word a swipe just committed — from measuring itself against a
        // position that has been superseded.
        val landed = if (expectedEdit) expectedSelections.pendingTarget() ?: reported else reported
        cachedSelectionStart = landed.start
        cachedSelectionEnd = landed.end

        // Whether the offsets this callback carries can be taken at face value. With nothing of
        // ours outstanding, every edit the keyboard has made has already been acknowledged, so
        // there is nothing that could have moved the text out from under these numbers. Captured
        // before the invalidation below, which would otherwise make the answer trivially yes.
        val reportedOffsetsAreCurrent = !expectedSelections.hasPending() && !selfEdit

        val ourEdit = expectedEdit || selfEdit
        // A matching earlier callback can arrive while a later queued mutation is using the
        // one-shot fallback (for an editor without extracted-text support). Preserve that later
        // fallback until its own callback; exact tracked edits clear selfEdit when registered.
        if (!expectedEdit) selfEdit = false

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

        if (update.externalSelectionChanged) {
            // Only the speculative half. Keys the user has already released are queued behind a
            // decode with nothing to replay them, and the queue's own contract reserves
            // cancellation for editor and session transitions; a cursor moving is neither, and the
            // decode still re-checks the editor it was started against before committing.
            cancelGesturePreviewWork()
            expectedSelections.invalidate()
            gestureUndoState.invalidate()
        }

        if (composing.isNotEmpty() && !update.cursorLeftComposing) {
            update.composingAtEnd?.let { composingAtEnd = it }
            if (update.externalSelectionChanged) updateShiftFromCursor()
            return
        }
        // Dropping one word and reopening another are the same gesture — a tap somewhere else in
        // the sentence — so the tap that ends the first must be allowed to start the second.
        if (composing.isNotEmpty()) {
            val abandonment = abandonComposing(currentInputConnection)
            if (!abandonment.settled) return
        }

        if (update.externalSelectionChanged) {
            // Gesture and prediction candidates describe the old cursor context. Clear first so
            // the same tap can reopen the newly selected word instead of being blocked by the stale
            // strip mode.
            literalWordInProgress = false
            clearSuggestions()
            reopenWordAtCursor(newSelStart, newSelEnd, reportedOffsetsAreCurrent)
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
     *
     * The offset the region is measured from must belong to the same reading of the field as the
     * characters it is measured over. A callback delivered after a mutation the keyboard has since
     * made carries offsets from before it, and measuring from one of those while reading the
     * characters live puts the composing region on the wrong text — which the next
     * [setComposingText] then silently overwrites.
     *
     * [reportedOffsetsAreCurrent] says the callback was checked against exactly that: with nothing
     * of ours outstanding it cannot be describing a superseded field, so it is used as it stands.
     * That is the ordinary case, and it keeps a tap costing no extraction at all — and keeps this
     * working on editors that do not implement `getExtractedText`, which is where the live read
     * would come back empty. Only when something of ours is in flight is the editor asked directly,
     * and an editor that will then not say where its cursor is gets no region rather than a guess.
     */
    private fun reopenWordAtCursor(
        selectionStart: Int,
        selectionEnd: Int,
        reportedOffsetsAreCurrent: Boolean,
    ) {
        if (selectionStart != selectionEnd || selectionStart < 0) return
        if (stripMode != StripMode.Empty) return
        if (!fieldSuggestionsEnabled()) return
        if (anyPanelShown) return

        val suggester = typingSuggester ?: return
        val keys = currentGestureKeyMap() ?: return

        val connection = currentInputConnection ?: return
        val cursorPosition = if (reportedOffsetsAreCurrent) {
            selectionStart
        } else {
            val live = readEditorSelection(connection) ?: return
            if (live.start != live.end || live.start < 0) return
            live.start
        }
        val before = connection.getTextBeforeCursor(MAX_REOPEN_CHARS, 0)?.toString() ?: return
        val after = connection.getTextAfterCursor(MAX_REOPEN_CHARS, 0)?.toString() ?: return

        var start = before.length
        while (start > 0) {
            val codePoint = Character.codePointBefore(before, start)
            if (!isWordCharacter(codePoint)) break
            start -= Character.charCount(codePoint)
        }
        var end = 0
        while (end < after.length) {
            val codePoint = Character.codePointAt(after, end)
            if (!isWordCharacter(codePoint)) break
            end += Character.charCount(codePoint)
        }

        // A run that reaches either end of the window may well continue past it, and reopening half
        // of a word would offer replacements for something the user never typed.
        if (start == 0 && before.length == MAX_REOPEN_CHARS) return
        if (end == after.length && after.length == MAX_REOPEN_CHARS) return

        val word = before.substring(start) + after.substring(0, end)
        if (word.codePointCount(0, word.length) !in MIN_REOPEN_LENGTH..MAX_REOPEN_LENGTH) return
        if (word.codePoints().noneMatch(Character::isLetter)) return

        val regionStart = cursorPosition - (before.length - start)
        if (regionStart < 0) return

        val selfEditWasPending = selfEdit
        selfEdit = true
        if (!connection.setComposingRegion(regionStart, regionStart + word.length)) {
            selfEdit = SelfEditFallback.afterAttempt(
                selfEditWasPending,
                callbackPossible = false,
                fallbackStillArmed = selfEdit,
            )
            return
        }
        selfEdit = SelfEditFallback.afterAttempt(
            selfEditWasPending,
            callbackPossible = true,
            fallbackStillArmed = selfEdit,
        )
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

        /**
         * How long a hidden keyboard keeps the speech process (and its loaded model) alive.
         * Long enough to cover switching fields or apps mid-thought; short enough that the
         * memory is returned once dictation is actually over.
         */
        const val VOICE_UNBIND_GRACE_MS = 30_000L
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

        /** Enough to classify a supplementary symbol and an opening-versus-closing quote. */
        const val AUTO_SPACING_CONTEXT_CHARS = 64

        val WORD_APOSTROPHES = setOf('\''.code, '\u2019'.code, '\u02bc'.code, '\uff07'.code)
        val COMBINING_MARK_TYPES = setOf(
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
        )

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

        /** Enough surrounding editor text to detect a cursor/context move during native decode. */
        const val GESTURE_CONTEXT_GUARD_CHARS = 48

        /**
         * Text behind the cursor proving a queued edit still belongs to the field it was made in.
         *
         * Shorter than the decode's guard on purpose. This is checked once per key released during
         * a decode, and it only has to notice the field being emptied or rewritten from elsewhere,
         * which never leaves the last few characters intact.
         */
        const val GESTURE_QUEUE_GUARD_CHARS = 16

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
