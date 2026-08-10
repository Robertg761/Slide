package com.slide.engine.suggest

import com.slide.engine.TestLexicon
import com.slide.engine.gesture.GestureFixtures
import kotlin.system.measureNanoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiEditCorrectionTest {
    private val keys = GestureFixtures.qwerty()
    private val suggester = TypingSuggester(TestLexicon.instance)

    @Test
    fun `recovers common words after two independent slips`() {
        val cases = mapOf(
            "keboarf" to "keyboard", // missed y, d landed on f
            "comutre" to "computer", // missed p, transposed e and r
            "acurqcy" to "accuracy", // missed c, a landed on q
            "languahe" to "language", // g landed on h, g was omitted
        )

        for ((typed, intended) in cases) {
            val result = suggester.suggest(typed, keys)
            assertEquals(
                "'$typed' should correct to '$intended'; offered ${result.words}",
                intended,
                result.autocorrection?.lowercase(),
            )
        }
    }

    @Test
    fun `second stage stays bounded on the keypress path`() {
        val inputs = listOf("keboarf", "comutre", "acurqcy", "languahe")
        // Warm class loading and the lexicon's length buckets before measuring.
        inputs.forEach { suggester.suggest(it, keys) }
        val nanos = measureNanoTime {
            repeat(10) { inputs.forEach { word -> suggester.suggest(word, keys) } }
        }
        val meanMs = nanos / 1_000_000.0 / (inputs.size * 10)
        println("two-edit correction mean %.2f ms".format(meanMs))
        assertTrue("two-edit correction took %.2f ms on average".format(meanMs), meanMs < 25.0)
    }
}
