package com.slide.engine.gesture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.slide.engine.lexicon.BigramLoader
import com.slide.engine.lexicon.LexiconLoader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Replays a person's own captured swipes (`SwipeCalibrationActivity` output) through the shipped
 * neural decoder and reports exactly which words fail and how the failures look.
 *
 * The corpus file is personal data and stays out of the repository: place it at
 * `engine/src/androidTest/assets/swipe_session.jsonl` (gitignored) before running. Without it this
 * test is skipped.
 */
@RunWith(AndroidJUnit4::class)
class PersonalSwipeBenchmarkTest {

    @Test
    fun replaysPersonalTracesAndReportsFailures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val lines = try {
            instrumentation.context.assets.open(ASSET).bufferedReader().readLines()
        } catch (_: Exception) {
            emptyList()
        }
        assumeTrue("add $ASSET to androidTest assets to run the personal replay", lines.isNotEmpty())

        val records = lines.mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }
        val context = instrumentation.targetContext
        val lexicon = requireNotNull(LexiconLoader.load(context))
        val bigrams = BigramLoader.load(context, lexicon)
        val decoder = requireNotNull(
            NeuralGestureDecoder.createOrNull(context, lexicon, bigrams, null),
        ) { "Neural models did not load" }

        var topOne = 0
        var topFive = 0
        val failures = ArrayList<Triple<String, String, Float>>()
        val marginsOfHits = ArrayList<Float>()

        decoder.use {
            for (record in records) {
                val word = record.getString("word").lowercase()
                val keys = parseKeys(record)
                val points = parsePoints(record)
                if (points.size < 6) continue
                val candidates = it.decode(points, keys, blockOffensive = true, previousWord = null)
                val ranked = candidates.map { c -> c.word.lowercase() }
                if (ranked.firstOrNull() == word) {
                    topOne++
                    if (candidates.size >= 2) marginsOfHits.add(candidates[0].score - candidates[1].score)
                }
                if (word in ranked.take(5)) topFive++
                else if (ranked.firstOrNull() != word) {
                    // Not even in the top five: log with whatever led instead.
                    failures.add(Triple(word, ranked.take(3).joinToString(","), marginOrZero(candidates)))
                } else {
                    failures.add(Triple(word, "rank${ranked.indexOf(word) + 1}:${ranked[ranked.indexOf(word)]}", marginOrZero(candidates)))
                }
            }
        }

        println("personal swipe replay over ${records.size} traces")
        println("  top-1 $topOne  top-5 $topFive")
        val sortedMisses = failures.filter { !it.second.startsWith("rank") || !it.second.substringAfter("rank").startsWith("2") }
        println("  misses (${failures.size}):")
        failures.forEach { (word, got, margin) ->
            println("    meant \"$word\" got [$got] margin=${"%.2f".format(margin)}")
        }
        if (marginsOfHits.isNotEmpty()) {
            val sorted = marginsOfHits.sorted()
            println(
                "  hit margins: p25=%.2f median=%.2f p75=%.2f".format(
                    sorted[sorted.size / 4],
                    sorted[sorted.size / 2],
                    sorted[sorted.size * 3 / 4],
                ),
            )
        }
        assertTrue(records.isNotEmpty())
    }

    private fun marginOrZero(candidates: List<GestureCandidate>): Float =
        if (candidates.size >= 2) candidates[0].score - candidates[1].score else 0f

    private fun parseKeys(record: JSONObject): GestureKeyMap {
        val builder = GestureKeyMap.Builder(
            record.getDouble("keyWidthPx").toFloat(),
            record.getDouble("keyHeightPx").toFloat(),
        )
        val keys = record.getJSONObject("keys")
        for (letter in 'a'..'z') {
            if (keys.has(letter.toString())) {
                val xy = keys.getJSONArray(letter.toString())
                builder.put(letter, xy.getDouble(0).toFloat(), xy.getDouble(1).toFloat())
            }
        }
        return builder.buildOrNull()!!
    }

    private fun parsePoints(record: JSONObject): List<GesturePoint> {
        val pointsArray = record.getJSONArray("points")
        return (0 until pointsArray.length()).map { index ->
            val xyz = pointsArray.getJSONArray(index)
            GesturePoint(xyz.getDouble(0).toFloat(), xyz.getDouble(1).toFloat(), xyz.getLong(2))
        }
    }

    private companion object {
        const val ASSET = "swipe_session.jsonl"
    }
}
