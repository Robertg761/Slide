package com.slide.engine.gesture

import com.slide.engine.TestLexicon
import com.slide.engine.lexicon.Lexicon
import org.junit.Ignore
import org.junit.Test

/**
 * Grid search over the decoder's weights against synthetic traces.
 *
 * Ignored by default: it is a tuning instrument, not a regression test, and it takes far too long
 * for a normal build. Run it deliberately when the scoring model changes:
 *
 *     ./gradlew :engine:testDebugUnitTest --tests '*TuningSweepTest*' -Dtest.single.ignore=false
 *
 * or simply drop the @Ignore locally. The numbers it produces are a starting point only —
 * synthetic traces have none of a real thumb's systematic bias, so the defaults should be
 * revisited against device captures.
 */
@Ignore("Tuning instrument; run by hand when the scoring model changes")
class TuningSweepTest {

    private val keys = GestureFixtures.qwerty()

    @Test
    fun `sweep`() {
        val words = tuningCorpus()
        val seeds = listOf(1L, 2L, 3L)
        val traces = words.flatMap { word ->
            seeds.map { seed ->
                word to GestureFixtures.trace(word, keys, jitter = 9f, smoothing = 3, seed = seed)
            }
        }
        println("corpus ${words.size} words, ${traces.size} traces")

        data class Result(val config: DecoderConfig, val top1: Double, val top5: Double, val label: String)

        val results = ArrayList<Result>()
        for (locationSigma in listOf(0.30f, 0.40f, 0.50f, 0.65f, 0.80f)) {
            for (shapeSigma in listOf(0.20f, 0.28f, 0.36f, 0.45f)) {
                for (language in listOf(1.5f, 2.5f, 3.0f, 3.5f, 5.0f)) {
                  for (endpointSigma in listOf(0.40f, 0.50f, 0.70f)) {
                    val config = DecoderConfig(
                        locationSigmaFactor = locationSigma,
                        shapeSigma = shapeSigma,
                        languageWeight = language,
                        endpointSigmaFactor = endpointSigma,
                    )
                    val decoder = GestureDecoder(lexicon, config)
                    var top1 = 0
                    var top5 = 0
                    for ((word, points) in traces) {
                        val out = decoder.decode(points, keys).map { it.word.lowercase() }
                        if (out.firstOrNull() == word) top1++
                        if (word in out) top5++
                    }
                    results += Result(
                        config = config,
                        top1 = top1.toDouble() / traces.size,
                        top5 = top5.toDouble() / traces.size,
                        label = "loc=%.2f shape=%.2f lang=%.1f end=%.2f"
                            .format(locationSigma, shapeSigma, language, endpointSigma),
                    )
                  }
                }
            }
        }

        println("\n== best 15 by top-1 ==")
        results.sortedByDescending { it.top1 }.take(15).forEach {
            println("  %-32s top1=%.3f top5=%.3f".format(it.label, it.top1, it.top5))
        }
        println("\n== worst 5 ==")
        results.sortedBy { it.top1 }.take(5).forEach {
            println("  %-32s top1=%.3f top5=%.3f".format(it.label, it.top1, it.top5))
        }
    }

    /**
     * Words sampled evenly across the frequency range, not just the top of it.
     *
     * Tuning against the most common few hundred words is misleading: they are exactly the
     * population an over-weighted language channel flatters, so every configuration scores ~99%
     * and the sweep shows no signal. The interesting failures are mid- and low-frequency words
     * like "swipe", where a confident geometric match has to beat a commoner neighbour.
     */
    private fun tuningCorpus(size: Int = 400, pool: Int = 20_000): List<String> {
        val ranked = (0 until lexicon.size)
            .asSequence()
            .filter { index ->
                val word = lexicon.lowercaseAt(index)
                word.length in 3..10 &&
                    word.all { it in 'a'..'z' } &&
                    !lexicon.isOffensive(index) &&
                    lexicon.frequencyAt(index) > 0
            }
            .sortedByDescending { lexicon.frequencyAt(it) }
            .take(pool)
            .toList()

        val stride = maxOf(1, ranked.size / size)
        return ranked.filterIndexed { i, _ -> i % stride == 0 }
            .take(size)
            .map { lexicon.lowercaseAt(it) }
    }

    private companion object {
        val lexicon: Lexicon get() = TestLexicon.instance
    }
}
