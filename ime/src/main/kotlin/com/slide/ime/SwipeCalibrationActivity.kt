package com.slide.ime

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.slide.core.layout.Key
import com.slide.core.settings.KeyboardSettings
import com.slide.core.theme.Themes
import com.slide.engine.gesture.GestureCandidate
import com.slide.engine.gesture.GestureKeyMap
import com.slide.engine.gesture.GesturePoint
import com.slide.engine.gesture.NeuralGestureDecoder
import com.slide.engine.lexicon.BigramLoader
import com.slide.engine.lexicon.LexiconLoader
import com.slide.ime.view.KeyboardView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.hypot
import org.json.JSONArray
import org.json.JSONObject

/**
 * A guided capture session for tuning the swipe decoder against this person's actual finger.
 *
 * One word at a time is shown; every completed swipe is written to
 * `files/swipe_session.jsonl` together with the intended word and the exact key geometry it was
 * drawn against, which is everything an offline replay needs. The shipped decoder scores each
 * swipe as it lands so the session doubles as a live accuracy readout.
 *
 * Debug builds only, and never exported: the point is a controlled data collection the developer
 * asked for, not a feature. Nothing is recorded anywhere outside the app's private storage.
 */
class SwipeCalibrationActivity : Activity(), KeyboardView.Listener {

    private lateinit var promptView: TextView
    private lateinit var progressView: TextView
    private lateinit var resultView: TextView
    private lateinit var summaryView: TextView
    private lateinit var keyboard: KeyboardView

    private var decoder: NeuralGestureDecoder? = null
    private val decodeExecutor = Executors.newSingleThreadExecutor()

    private var output: File? = null
    private var order: List<String> = emptyList()
    private var index = 0
    private var attempts = 0
    private var topOneHits = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Shell can start even non-exported components; the flag keeps the surface debug-only.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(24), dp(16), dp(8))
        }
        progressView = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF9AA0A6.toInt())
        }
        promptView = TextView(this).apply {
            textSize = 44f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        resultView = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
        }
        summaryView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val skip = Button(this).apply {
            text = "Skip word"
            setOnClickListener { advance() }
        }
        header.addView(progressView)
        header.addView(
            promptView,
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) },
        )
        header.addView(
            resultView,
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) },
        )
        header.addView(
            summaryView,
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) },
        )
        header.addView(skip, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })

        keyboard = KeyboardView(this).apply {
            keyboardTheme =
                if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != 0) {
                    Themes.Dark
                } else {
                    Themes.Light
                }
            settings = KeyboardSettings()
            gestureTypingAvailable = true
        }
        keyboard.listener = this

        root.addView(header, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(keyboard, LinearLayout.LayoutParams(MATCH, dp(264)))
        setContentView(root)

        output = File(filesDir, "swipe_session.jsonl")
        order = SESSION_WORDS.shuffled().take(SESSION_TARGET)
        showWord()
    }

    private fun showWord() {
        if (index >= order.size) {
            finishSession()
            return
        }
        progressView.text = "${index + 1} / ${order.size}"
        promptView.text = order[index]
        resultView.text = ""
        resultView.setTextColor(0xFF9AA0A6.toInt())
    }

    private fun advance() {
        index++
        runOnUiThread { showWord() }
    }

    private fun finishSession() {
        promptView.text = "Done"
        resultView.visibility = View.GONE
        summaryView.visibility = View.VISIBLE
        summaryView.text = "Swiped $attempts words · decoder top-1 $topOneHits" +
            " (${if (attempts == 0) "n/a" else "%.0f%%".format(100.0 * topOneHits / attempts)})\n" +
            "Saved to ${output?.absolutePath}"
        progressView.text = ""
    }

    override fun onKeyDown(key: Key) = Unit

    override fun onKeyCommit(key: Key, text: String, touchX: Float, touchY: Float) {
        // Taps during a capture session are stray input; ignore them rather than typing anywhere.
    }

    override fun onGestureComplete(points: List<GesturePoint>) {
        val word = order.getOrNull(index) ?: return
        val keys = keyboard.gestureKeyMap() ?: return
        attempts++
        appendTrace(word, points, keys)
        scoreAsync(word, points)
        advance()
    }

    /** FUTO-style line: raw pixels plus the geometry that gives them meaning on replay. */
    private fun appendTrace(word: String, points: List<GesturePoint>, keys: GestureKeyMap) {
        val file = output ?: return
        val record = JSONObject().apply {
            put("word", word)
            put("width", keyboard.width)
            put("height", keyboard.height)
            put("keyWidthPx", keys.keyWidth)
            put("keyHeightPx", keys.keyHeight)
            put(
                "keys",
                JSONObject().apply {
                    for (letter in 'a'..'z') {
                        if (keys.has(letter)) {
                            put(letter.toString(), JSONArray(listOf(keys.centerX(letter), keys.centerY(letter))))
                        }
                    }
                },
            )
            put(
                "points",
                JSONArray().apply {
                    points.forEach { point ->
                        put(JSONArray(listOf(point.x.toDouble(), point.y.toDouble(), point.timeMs)))
                    }
                },
            )
        }
        decodeExecutor.execute {
            runCatching {
                file.appendText(record.toString() + "\n")
            }
        }
    }

    /**
     * The decoder is deliberately loaded here rather than in [onCreate]: this activity's process
     * start also boots the IME service, whose own model load would otherwise race this one. By
     * the first finished swipe that load has long settled.
     */
    private fun scoreAsync(word: String, points: List<GesturePoint>) {
        decodeExecutor.execute {
            if (decoder == null) {
                val appContext = applicationContext
                val lexicon = LexiconLoader.load(appContext) ?: return@execute
                val bigrams = BigramLoader.load(appContext, lexicon)
                decoder = NeuralGestureDecoder.createOrNull(appContext, lexicon, bigrams, null)
            }
            val active = decoder ?: return@execute
            val keys = keyboard.gestureKeyMap() ?: return@execute
            val candidates: List<GestureCandidate> = try {
                active.decode(points, keys, blockOffensive = true, previousWord = null)
            } catch (_: RuntimeException) {
                return@execute
            }
            val top = candidates.firstOrNull()?.word?.lowercase()
            val hit = top == word
            if (hit) synchronized(this@SwipeCalibrationActivity) { topOneHits++ }
            val label = "meant \"$word\" · got ${top ?: "nothing"}"
            runOnUiThread {
                if (index <= order.size && attempts > 0) {
                    resultView.text = label
                    resultView.setTextColor(if (hit) 0xFF81C995.toInt() else 0xFFF28B82.toInt())
                }
            }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /**
         * Swipes per session. Enough labeled personal traces to compare against the donated
         * corpus and localise systematic misses; run the session again for more.
         */
        const val SESSION_TARGET = 60

        /**
         * Common words chosen for shape coverage: confusable clusters, doubled letters,
         * long glides, high-frequency function words. Deliberately lowercase a-z so every trace
         * stays comparable with the donated corpus filters.
         */
        val SESSION_WORDS = listOf(
            "the", "and", "that", "have", "for", "not", "with", "you", "this", "but",
            "his", "from", "they", "say", "her", "she", "will", "one", "all", "would",
            "there", "their", "what", "out", "about", "who", "get", "which", "when", "make",
            "can", "like", "time", "just", "know", "take", "people", "into", "year", "your",
            "good", "some", "could", "them", "see", "other", "than", "then", "now", "look",
            "only", "come", "its", "over", "also", "back", "after", "use", "two", "how",
            "our", "work", "first", "well", "way", "even", "new", "want", "because", "any",
            "these", "give", "day", "most", "us", "pretty", "party", "sorry", "hello", "world",
            "letter", "better", "little", "middle", "bubble", "puppy", "happy", "apple", "google", "status",
            "keyboard", "swipe", "typing", "message", "phone", "screen", "morning", "tonight", "weekend", "tomorrow",
            "quick", "brown", "jumps", "lazy", "dog", "example", "pattern", "point", "value", "number",
            "before", "water", "house", "money", "school", "friend", "family", "question", "moment", "person",
        )
    }
}
