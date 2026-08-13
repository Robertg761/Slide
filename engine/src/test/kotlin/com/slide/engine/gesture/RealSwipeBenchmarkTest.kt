package com.slide.engine.gesture

import com.slide.engine.TestBigrams
import com.slide.engine.TestLexicon
import java.io.File
import kotlin.math.exp
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Measures the shipped decoder against donated, real-finger QWERTY traces.
 *
 * The corpus is deliberately not checked into the repository: FUTO's public test split is about
 * 258 MiB. Download it from the MIT-licensed `futo-org/swipe.futo.org` dataset and pass its path:
 *
 *     ./gradlew :engine:testDebugUnitTest \
 *       --tests '*RealSwipeBenchmarkTest*' \
 *       -Dslide.realSwipeDataset=/path/to/test.jsonl
 *
 * Add `-Dslide.typingQualityOutput=/path/to/results.jsonl` for a privacy-safe machine report input.
 *
 * Without that property this test is skipped, keeping an ordinary offline build deterministic.
 * The default limit matches the public 20,000 non-single-letter subset used in FUTO Keyboard's
 * published comparison with Gboard. Set `slide.realSwipeLimit` to select a different size.
 */
class RealSwipeBenchmarkTest {

    @Test
    fun `reports top one and top five accuracy on real swipes`() {
        val path = System.getProperty(DATASET_PROPERTY)
        assumeTrue("set -D$DATASET_PROPERTY to run the real-swipe benchmark", !path.isNullOrBlank())

        val file = File(path!!)
        assertTrue("dataset does not exist: ${file.absolutePath}", file.isFile)

        val decoder = GestureDecoder(TestLexicon.instance, bigrams = TestBigrams.instance)
        val limit = System.getProperty(LIMIT_PROPERTY)?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_LIMIT
        var accepted = 0
        var topOne = 0
        var topFive = 0
        var inLexicon = 0
        var inLexiconTopOne = 0
        var inLexiconTopFive = 0
        var totalScored = 0L
        var totalNanos = 0L
        val qualityWriter = System.getProperty(QUALITY_OUTPUT_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?.let { outputPath ->
                val output = File(outputPath)
                output.absoluteFile.parentFile?.mkdirs()
                TypingQualityJsonlWriter(output.bufferedWriter())
            }
        val marginThresholds = floatArrayOf(0.25f, 0.5f, 1f, 1.5f, 2f, 3f, 4f)
        val marginTotals = IntArray(marginThresholds.size)
        val marginCorrect = IntArray(marginThresholds.size)

        try {
            file.useLines { lines ->
                for (line in lines) {
                    if (accepted >= limit) break
                    val record = parse(line) ?: continue
                    if (record.word.length <= 1 || !record.word.all { it in 'a'..'z' || it == '\'' }) continue

                    val start = System.nanoTime()
                    val decoded = decoder.decode(
                        points = record.points,
                        keys = QWERTY,
                        previousWord = record.previousWord,
                    )
                    val elapsedNanos = (System.nanoTime() - start).coerceAtLeast(0L)
                    val candidates = decoded.map { it.word.lowercase() }
                    totalNanos += elapsedNanos
                    totalScored += decoder.lastScoredCount

                    val expectedIndex = candidates.indexOf(record.word)
                    qualityWriter?.append(
                        TypingQualityCase(
                            inputKind = TypingQualityInputKind.SWIPE,
                            expectedRank = expectedIndex.takeIf { it >= 0 }?.plus(1),
                            committed = decoded.isNotEmpty(),
                            usedFallback = false,
                            latencyMillis = elapsedNanos / 1_000_000.0,
                            confidence = confidence(decoded),
                        ),
                    )

                    val margin = if (decoded.size >= 2) decoded[0].score - decoded[1].score else null
                    if (margin != null) {
                        marginThresholds.forEachIndexed { index, threshold ->
                            if (margin >= threshold) {
                                marginTotals[index]++
                                if (candidates.firstOrNull() == record.word) marginCorrect[index]++
                            }
                        }
                    }

                    accepted++
                    val known = TestLexicon.instance.contains(record.word)
                    if (candidates.firstOrNull() == record.word) topOne++
                    if (record.word in candidates) topFive++
                    if (known) {
                        inLexicon++
                        if (candidates.firstOrNull() == record.word) inLexiconTopOne++
                        if (record.word in candidates) inLexiconTopFive++
                    }
                }
            }
        } finally {
            qualityWriter?.close()
        }

        assertTrue("benchmark found only $accepted usable records", accepted >= minOf(limit, 1_000))
        println("real swipe benchmark over $accepted donated QWERTY traces")
        println("  all words     top-1 ${percent(topOne, accepted)}  top-5 ${percent(topFive, accepted)}")
        println(
            "  in lexicon    top-1 ${percent(inLexiconTopOne, inLexicon)}  " +
                "top-5 ${percent(inLexiconTopFive, inLexicon)}  coverage ${percent(inLexicon, accepted)}",
        )
        println("  mean decode   %.2f ms, %.0f candidates scored".format(
            totalNanos / accepted / 1_000_000.0,
            totalScored.toDouble() / accepted,
        ))
        marginThresholds.forEachIndexed { index, threshold ->
            println(
                "  margin >= %.2f  precision %s  coverage %s".format(
                    threshold,
                    percent(marginCorrect[index], marginTotals[index]),
                    percent(marginTotals[index], accepted),
                ),
            )
        }
    }

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

    private fun sentenceWords(sentence: String): List<String> =
        WORD.findAll(sentence.lowercase()).map { it.value }.toList()

    private fun unescape(value: String): String = buildString(value.length) {
        var escaped = false
        for (char in value) {
            if (escaped) {
                append(
                    when (char) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> char
                    },
                )
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else {
                append(char)
            }
        }
        if (escaped) append('\\')
    }

    private fun percent(numerator: Int, denominator: Int): String =
        if (denominator == 0) "n/a" else "%.2f%%".format(numerator * 100.0 / denominator)

    /** Softmax share of the top result; bounded, deterministic, and content-free. */
    private fun confidence(candidates: List<GestureCandidate>): Double {
        val top = candidates.firstOrNull()?.score?.takeIf(Float::isFinite) ?: return 0.0
        val denominator = candidates.sumOf { candidate ->
            exp((candidate.score - top).toDouble().coerceIn(-50.0, 0.0))
        }
        return (1.0 / denominator).coerceIn(0.0, 1.0)
    }

    private data class Record(
        val word: String,
        val previousWord: String?,
        val points: List<GesturePoint>,
    )

    private companion object {
        const val DATASET_PROPERTY = "slide.realSwipeDataset"
        const val LIMIT_PROPERTY = "slide.realSwipeLimit"
        const val QUALITY_OUTPUT_PROPERTY = "slide.typingQualityOutput"
        const val DEFAULT_LIMIT = 20_000
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
        val WORD = Regex("[a-z]+(?:'[a-z]+)?")

        val QWERTY: GestureKeyMap = GestureKeyMap.Builder(keyWidth = 0.1f, keyHeight = 1f / 3f)
            .apply {
                "qwertyuiop".forEachIndexed { index, letter -> put(letter, 0.05f + index * 0.1f, 1f / 6f) }
                "asdfghjkl".forEachIndexed { index, letter -> put(letter, 0.10f + index * 0.1f, 0.5f) }
                "zxcvbnm".forEachIndexed { index, letter -> put(letter, 0.20f + index * 0.1f, 5f / 6f) }
            }
            .buildOrNull()!!
    }
}
