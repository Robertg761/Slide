package com.slide.engine.gesture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.slide.engine.lexicon.BigramLoader
import com.slide.engine.lexicon.LexiconLoader
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures the shipped NEURAL decoder against donated, real-finger QWERTY traces, on the hardware
 * people actually swipe on — the JVM benchmark cannot load ExecuTorch and therefore exercises only
 * the deterministic fallback.
 *
 * The corpus is deliberately absent from the repository (FUTO's MIT-licensed test split is ~258 MiB).
 * Build a slice into `src/androidTest/assets/swipe_benchmark_subset.jsonl`, e.g. with Python against
 * the downloaded `test.jsonl`:
 *
 * ```
 * python3 - <<'EOF'
 * import json, os, random
 * random.seed(20260822)
 * reservoir = []
 * seen = 0
 * for line in open('/tmp/opencode/swipe-test.jsonl'):
 *     try:
 *         word = json.loads(line)['word']
 *     except Exception:
 *         continue
 *     if len(word) <= 1 or not all(c in "abcdefghijklmnopqrstuvwxyz'" for c in word):
 *         continue
 *     seen += 1
 *     if len(reservoir) < 3000:
 *         reservoir.append(line)
 *     elif random.randrange(seen) < 3000:
 *         reservoir[random.randrange(3000)] = line
 * os.makedirs('engine/src/androidTest/assets', exist_ok=True)
 * open('engine/src/androidTest/assets/swipe_benchmark_subset.jsonl', 'w').writelines(reservoir)
 * EOF
 * ```
 *
 * Without that asset this test is skipped, keeping an ordinary device run deterministic.
 */
@RunWith(AndroidJUnit4::class)
class RealSwipeNeuralBenchmarkTest {

    @Test
    fun reportsTopOneAndTopFiveAccuracyForTheNeuralDecoderOnDevice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val lines = try {
            instrumentation.context.assets.open(ASSET).bufferedReader().readLines()
        } catch (_: Exception) {
            emptyList()
        }
        assumeTrue("add $ASSET to androidTest assets to run the on-device benchmark", lines.isNotEmpty())

        val context = instrumentation.targetContext
        val lexicon = requireNotNull(LexiconLoader.load(context))
        val bigrams = BigramLoader.load(context, lexicon)
        val decoder = requireNotNull(
            NeuralGestureDecoder.createOrNull(context, lexicon, bigrams, null),
        ) { "Neural models did not load" }

        var accepted = 0
        var topOne = 0
        var topFive = 0
        var neural = 0
        var fallback = 0
        var inLexicon = 0
        var inLexiconTopOne = 0
        var inLexiconTopFive = 0
        var totalNanos = 0L
        val marginThresholds = floatArrayOf(0.25f, 0.5f, 1f, 1.5f, 2f, 3f, 4f)
        val marginTotals = IntArray(marginThresholds.size)
        val marginCorrect = IntArray(marginThresholds.size)

        decoder.use {
            for (line in lines) {
                val record = parse(line) ?: continue
                val start = System.nanoTime()
                val decoded = it.decode(
                    points = record.points,
                    keys = QWERTY,
                    blockOffensive = true,
                    previousWord = record.previousWord,
                )
                val elapsedNanos = (System.nanoTime() - start).coerceAtLeast(0L)
                when (it.lastDecoderSource) {
                    GestureDecoderSource.NEURAL -> neural++
                    GestureDecoderSource.FALLBACK -> fallback++
                    GestureDecoderSource.NONE -> Unit
                }
                totalNanos += elapsedNanos

                val candidates = decoded.map { candidate -> candidate.word.lowercase() }
                if (candidates.firstOrNull() == record.word) topOne++
                if (record.word in candidates) topFive++
                accepted++

                if (lexicon.contains(record.word)) {
                    inLexicon++
                    if (candidates.firstOrNull() == record.word) inLexiconTopOne++
                    if (record.word in candidates) inLexiconTopFive++
                }

                if (decoded.size >= 2) {
                    val margin = decoded[0].score - decoded[1].score
                    marginThresholds.forEachIndexed { index, threshold ->
                        if (margin >= threshold) {
                            marginTotals[index]++
                            if (candidates.firstOrNull() == record.word) marginCorrect[index]++
                        }
                    }
                }
            }
        }

        assumeTrue("no usable records in $ASSET", accepted >= 500)
        println("on-device neural swipe benchmark over $accepted donated QWERTY traces")
        println("  all words      top-1 ${percent(topOne, accepted)}  top-5 ${percent(topFive, accepted)}")
        println(
            "  in lexicon     top-1 ${percent(inLexiconTopOne, inLexicon)}  " +
                "top-5 ${percent(inLexiconTopFive, inLexicon)}  coverage ${percent(inLexicon, accepted)}",
        )
        println(
            "  mean decode    %.2f ms   neural $neural   fallback $fallback".format(
                totalNanos / accepted / 1_000_000.0,
            ),
        )
        marginThresholds.forEachIndexed { index, threshold ->
            println(
                "  margin >= %.2f   precision %s  coverage %s".format(
                    threshold,
                    percent(marginCorrect[index], marginTotals[index]),
                    percent(marginTotals[index], accepted),
                ),
            )
        }
        // Informational floor only: a collapse below half of traces reaching the answer at all
        // means the pipeline broke, not merely that tuning has room to go.
        val topFiveFraction = if (accepted == 0) 0.0 else topFive.toDouble() / accepted
        assertTrue("top-5 collapsed", topFiveFraction >= 0.5)
    }

    private fun percent(part: Int, whole: Int): String =
        if (whole == 0) "n/a" else "%.2f%%".format(100.0 * part / whole)

    private data class Record(
        val word: String,
        val previousWord: String?,
        val points: List<GesturePoint>,
    )

    private fun unescape(value: String): String =
        value.replace("\\\"", "\"").replace("\\\\", "\\")

    private fun sentenceWords(sentence: String): List<String> =
        Regex("[a-zA-Z]+(?:'[a-zA-Z]+)?").findAll(unescape(sentence))
            .map { it.value.lowercase() }
            .toList()

    private fun parse(line: String): Record? {
        val word = STRING_FIELDS.getValue("word").find(line)?.groupValues?.get(1)
            ?.let(::unescape)?.lowercase() ?: return null
        val sentence = STRING_FIELDS.getValue("sentence").find(line)?.groupValues?.get(1)
            ?.let(::unescape).orEmpty()
        val wordIndex = WORD_INDEX.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val previous = sentenceWords(sentence).getOrNull(wordIndex - 1)

        val dataStart = line.indexOf(DATA_PREFIX)
        if (dataStart < 0) return null
        val dataEnd = line.indexOf(DATA_SUFFIX, dataStart + DATA_PREFIX.length)
        if (dataEnd < 0) return null

        val points = ArrayList<GesturePoint>()
        var firstTime = -1L
        val data = line.substring(dataStart + DATA_PREFIX.length, dataEnd)
        POINT.findAll(data).forEach { match ->
            val time = match.groupValues[1].toLongOrNull() ?: return@forEach
            val x = match.groupValues[2].toFloatOrNull() ?: return@forEach
            val y = match.groupValues[3].toFloatOrNull() ?: return@forEach
            if (firstTime < 0) firstTime = time
            points += GesturePoint(x, y, time - firstTime)
        }
        if (points.size < 2) return null
        return Record(word, previous, points)
    }

    private companion object {
        const val ASSET = "swipe_benchmark_subset.jsonl"
        const val DATA_PREFIX = "\"data\":["
        const val DATA_SUFFIX = "],\"sentence\""

        val STRING_FIELDS = mapOf(
            "word" to Regex("\\\"word\\\":\\\"((?:\\\\.|[^\\\"])*)\\\""),
            "sentence" to Regex("\\\"sentence\\\":\\\"((?:\\\\.|[^\\\"])*)\\\""),
        )
        val WORD_INDEX = Regex("\\\"word_idx\\\":(-?\\d+)")
        val POINT = Regex(
            "\\{\\\"t\\\":(\\d+),\\\"x\\\":([-+0-9.eE]+),\\\"y\\\":([-+0-9.eE]+)\\}",
        )

        val QWERTY: GestureKeyMap = GestureKeyMap.Builder(keyWidth = 0.1f, keyHeight = 1f / 3f)
            .apply {
                "qwertyuiop".forEachIndexed { index, letter -> put(letter, 0.05f + index * 0.1f, 1f / 6f) }
                "asdfghjkl".forEachIndexed { index, letter -> put(letter, 0.10f + index * 0.1f, 0.5f) }
                "zxcvbnm".forEachIndexed { index, letter -> put(letter, 0.20f + index * 0.1f, 5f / 6f) }
            }
            .buildOrNull()!!
    }
}
