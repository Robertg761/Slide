package com.slide.engine.gesture

import com.slide.engine.lexicon.Bigrams
import com.slide.engine.lexicon.Lexicon
import com.slide.engine.lexicon.Trigrams
import kotlin.math.hypot
import kotlin.math.ln

/** A point sampled from the user's finger during a swipe. */
data class GesturePoint(val x: Float, val y: Float, val timeMs: Long)

/** A word the decoder thinks the swipe might have been, with its score. Higher is better. */
data class GestureCandidate(val word: String, val score: Float)

/**
 * Tunables for the decoder, all expressed as multiples of key width so they hold across screen
 * sizes and the user's key-height setting.
 *
 * The defaults come from the grid search in `TuningSweepTest` against synthetic traces, which
 * reaches 95.8% top-1 and 100% top-5 over 400 words sampled across the frequency range. Synthetic
 * traces have none of a real thumb's systematic bias, so these are a starting point and are
 * expected to move once there is on-device data. See `docs/technical-decisions.md` §1.
 */
data class DecoderConfig(
    /** Points each trace is resampled to. Higher is more faithful and more expensive. */
    val sampleCount: Int = 64,
    /** How far off a key centre the path may pass and still count as visiting it. */
    val pruneRadiusFactor: Float = 1.15f,
    /** How far from the trace ends a word's first and last letter may sit. */
    val endpointRadiusFactor: Float = 1.0f,
    /** Distinct first (and last) letters considered. Guards against a shaky start point. */
    val endpointCandidates: Int = 4,
    /**
     * Spread of the location channel; larger is more forgiving of sloppy absolute position.
     * This measures a *mean* deviation over the whole path, so it is a much tighter statistic
     * than a single point's error and wants a correspondingly small sigma.
     */
    val locationSigmaFactor: Float = 0.4f,
    /** Spread of the shape channel, in normalised units. */
    val shapeSigma: Float = 0.36f,
    /**
     * Spread of the endpoint channel. Tighter than [locationSigmaFactor] on purpose: the first
     * and last points are where the finger deliberately stopped, so they carry far more
     * information than any corner taken mid-swipe, and averaging them into the location channel
     * dilutes exactly the evidence that separates neighbours like "hello" and "help".
     */
    val endpointSigmaFactor: Float = 0.5f,
    /**
     * Weight on word frequency. Raising it favours common words over close geometric matches;
     * set too high it swamps geometry entirely, so that a perfect trace of an uncommon word like
     * "swipe" loses to a commoner neighbour like "stripe".
     */
    val languageWeight: Float = 3.0f,
    /**
     * Weight on how well a candidate follows the preceding word.
     *
     * This is the only channel that can separate words the path genuinely cannot. "typing" and
     * "topping" trace an identical straight run along the top row, because y and o both lie
     * between t and p; no amount of geometry will ever tell them apart, and frequency alone
     * always returns the same answer whichever the user meant. The sentence can.
     *
     * Added, never subtracted, so a pair the model has not seen leaves that candidate exactly
     * where the geometry put it. Sized against [languageWeight], on whose scale it sits.
     *
     * Unlike the corrector's equivalent this one has a real optimum rather than a plateau that
     * keeps climbing: `ContextualDecodeTest`'s corpus gives 93.8% top-1 at zero, 96.8% here, 96.8%
     * at 2.0, and falls away above that as the sentence starts overruling a clear trace. 1.5 sits
     * on the near side of the peak.
     */
    val contextWeight: Float = 1.5f,
    /** Extra evidence from the two-word context when the sparse trigram model has it. */
    val trigramContextWeight: Float = 1.0f,
    /** Words scored per swipe. Pruning normally yields far fewer; this is a latency backstop. */
    val maxScored: Int = 3000,
    /** Candidates returned to the caller. */
    val maxResults: Int = 5,
    /** Below this many raw points the movement is a tap or a twitch, not a word. */
    val minimumPoints: Int = 6,
    /** Below this path length, in key widths, the same applies. */
    val minimumPathLengthFactor: Float = 0.9f,
)

/**
 * Turns a swipe into ranked word candidates.
 *
 * The approach follows SHARK² (Zhai & Kristensson): reduce the trace to a fixed-length shape,
 * prune the lexicon down to words the path could plausibly have spelled, then rank the survivors
 * on independent channels — where the path went in absolute terms, what shape it traced, and how
 * common the word is. No single channel is reliable alone. Location alone cannot tell "hello"
 * from "hello" drawn slightly left; shape alone happily matches a word on the wrong side of the
 * keyboard; frequency alone would just return "the" every time.
 *
 * The pruning stage is deliberately allocation-free and reuses its buffers across decodes, since
 * it touches every word in a bucket. Scoring does allocate per surviving candidate; that is only
 * acceptable because pruning cuts the survivors down to a few hundred, and is worth revisiting if
 * profiling on a real device says otherwise.
 */
class GestureDecoder(
    private val lexicon: Lexicon,
    private val config: DecoderConfig = DecoderConfig(),
    /** Null when the asset is missing, which reduces this to geometry and frequency. */
    private val bigrams: Bigrams? = null,
    private val trigrams: Trigrams? = null,
) : GestureDecodingEngine {

    /** Lexicon index of the word before the swipe, or -1. Set per [decode] call. */
    private var contextIndex = -1
    private var olderContextIndex = -1

    /** Sample indices at which the trace passed near each letter, ascending. Reused per decode. */
    private val nearIndices = arrayOfNulls<IntArray>(ALPHABET)
    private val nearCounts = IntArray(ALPHABET)

    private val templateX = FloatArray(MAX_WORD_LENGTH)
    private val templateY = FloatArray(MAX_WORD_LENGTH)

    private val results = ScoreBoard(config.maxResults)

    /** Words that survived pruning on the most recent decode, for tests and profiling. */
    var lastScoredCount: Int = 0
        private set

    /**
     * @param blockOffensive drop words the wordlist flags as offensive, mirroring Gboard's
     *   "Block offensive words" setting. They stay typeable by hand; they are only withheld from
     *   suggestions the user did not ask for.
     * @param previousWord the word before the swipe, if there is one. Supplying it is the only way
     *   to separate candidates that trace the same path; omitting it costs accuracy but is never
     *   wrong.
     */
    @Synchronized
    override fun decode(
        points: List<GesturePoint>,
        keys: GestureKeyMap,
        blockOffensive: Boolean,
        previousWord: String?,
        previousPreviousWord: String?,
    ): List<GestureCandidate> {
        contextIndex = contextIndexFor(previousWord)
        olderContextIndex = contextIndexFor(previousPreviousWord)
        if (points.size < config.minimumPoints) return emptyList()

        val trace = SampledTrace.of(points, config.sampleCount)
        if (trace.pathLength() < keys.keyWidth * config.minimumPathLengthFactor) return emptyList()

        val normalizedTrace = trace.normalized()
        buildNearIndices(trace, keys)

        val startLetters = keys.lettersNear(
            x = trace.xs[0],
            y = trace.ys[0],
            maxDistance = keys.keyWidth * config.endpointRadiusFactor,
            limit = config.endpointCandidates,
        )
        val endLetters = keys.lettersNear(
            x = trace.xs[trace.size - 1],
            y = trace.ys[trace.size - 1],
            maxDistance = keys.keyWidth * config.endpointRadiusFactor,
            limit = config.endpointCandidates,
        )
        if (startLetters.isEmpty() || endLetters.isEmpty()) return emptyList()

        results.reset()
        var scored = 0

        for (first in startLetters) {
            for (last in endLetters) {
                for (index in lexicon.bucket(first, last)) {
                    if (scored >= config.maxScored) break

                    val length = lexicon.lengthAt(index)
                    if (length < MIN_WORD_LENGTH || length > MAX_WORD_LENGTH) continue
                    if (blockOffensive && lexicon.isOffensive(index)) continue
                    if (!pathCouldSpell(index, length)) continue

                    scored++
                    val score = score(index, length, keys, trace, normalizedTrace)
                    results.offer(index, score)
                }
            }
        }

        lastScoredCount = scored
        return results.toCandidates(lexicon)
    }

    // region Pruning

    /**
     * Records, for every letter, the sample indices where the trace came within the prune radius.
     *
     * Doing this once up front turns the per-word check into a handful of binary searches instead
     * of a full sweep over the trace, which is what makes scanning thousands of words affordable.
     */
    private fun buildNearIndices(trace: SampledTrace, keys: GestureKeyMap) {
        val radius = keys.keyWidth * config.pruneRadiusFactor
        val radiusSquared = radius * radius

        for (i in 0 until ALPHABET) {
            nearCounts[i] = 0
            if (nearIndices[i] == null) nearIndices[i] = IntArray(config.sampleCount)
        }

        for (i in 0 until ALPHABET) {
            val letter = 'a' + i
            if (!keys.has(letter)) continue

            val keyX = keys.centerX(letter)
            val keyY = keys.centerY(letter)
            val slots = nearIndices[i]!!
            var count = 0

            for (sample in 0 until trace.size) {
                val dx = trace.xs[sample] - keyX
                val dy = trace.ys[sample] - keyY
                if (dx * dx + dy * dy <= radiusSquared) {
                    slots[count++] = sample
                }
            }
            nearCounts[i] = count
        }
    }

    /**
     * True when the trace visits every letter of the word in order.
     *
     * Greedily taking the earliest usable sample for each letter is safe: if any valid ordering
     * exists, the greedy one does too, since consuming less of the trace can never hurt a later
     * letter. Repeated letters ("hello") are allowed to match the same sample, because a finger
     * does not retrace a key to double it.
     */
    private fun pathCouldSpell(wordIndex: Int, length: Int): Boolean {
        var cursor = 0
        var previous = ' '

        for (position in 0 until length) {
            val letter = lexicon.charAt(wordIndex, position)
            if (letter == previous) continue
            if (letter !in 'a'..'z') {
                // An apostrophe is inferred rather than gestured, so it costs no path.
                previous = letter
                continue
            }

            val slot = letter - 'a'
            val count = nearCounts[slot]
            if (count == 0) return false

            val found = firstAtLeast(nearIndices[slot]!!, count, cursor)
            if (found < 0) return false

            cursor = found
            previous = letter
        }
        return true
    }

    /** Binary search for the first value >= [minimum], or -1 if every value is smaller. */
    private fun firstAtLeast(values: IntArray, count: Int, minimum: Int): Int {
        var low = 0
        var high = count - 1
        var answer = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (values[mid] >= minimum) {
                answer = values[mid]
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return answer
    }

    // endregion

    // region Scoring

    private fun score(
        wordIndex: Int,
        length: Int,
        keys: GestureKeyMap,
        trace: SampledTrace,
        normalizedTrace: SampledTrace,
    ): Float {
        val corners = buildTemplate(wordIndex, length, keys)
        if (corners < 2) return Float.NEGATIVE_INFINITY

        val template = SampledTrace.resample(templateX, templateY, corners, config.sampleCount)

        val location = meanDistance(trace, template)
        val shape = meanDistance(normalizedTrace, template.normalized())

        val locationSigma = keys.keyWidth * config.locationSigmaFactor
        val locationTerm = -(location * location) / (2f * locationSigma * locationSigma)
        val shapeTerm = -(shape * shape) / (2f * config.shapeSigma * config.shapeSigma)

        // Endpoints are scored separately from the averaged location channel, against the
        // template's own first and last corners.
        val last = trace.size - 1
        val startDx = trace.xs[0] - templateX[0]
        val startDy = trace.ys[0] - templateY[0]
        val endDx = trace.xs[last] - templateX[corners - 1]
        val endDy = trace.ys[last] - templateY[corners - 1]
        val endpointSigma = keys.keyWidth * config.endpointSigmaFactor
        val endpointTerm = -(startDx * startDx + startDy * startDy + endDx * endDx + endDy * endDy) /
            (2f * endpointSigma * endpointSigma)

        // AOSP's frequency is already roughly logarithmic, so it maps onto a log-likelihood
        // without another log; the +1 only keeps zero-frequency words finite.
        val frequency = lexicon.frequencyAt(wordIndex)
        val languageTerm = config.languageWeight * ln(1f + frequency) / LN_MAX_FREQUENCY

        val contextTerm = if (bigrams != null && contextIndex >= 0) {
            config.contextWeight * bigrams.score(contextIndex, wordIndex)
        } else {
            0f
        }

        val trigramTerm = if (
            trigrams != null && olderContextIndex >= 0 && contextIndex >= 0
        ) {
            config.trigramContextWeight * trigrams.score(olderContextIndex, contextIndex, wordIndex)
        } else {
            0f
        }

        return locationTerm + shapeTerm + endpointTerm + languageTerm + contextTerm + trigramTerm
    }

    /** Resolves the preceding word to a lexicon index; -1 when there is nothing to look up. */
    private fun contextIndexFor(previousWord: String?): Int {
        if ((bigrams == null && trigrams == null) || previousWord.isNullOrEmpty()) return -1
        val cleaned = previousWord.lowercase().trim('\'')
        if (cleaned.isEmpty() || !cleaned.all { it in 'a'..'z' || it == '\'' }) return -1
        return lexicon.indexOf(cleaned)
    }

    /**
     * Lays out the ideal path for a word: a polyline through its letters' key centres.
     *
     * Consecutive repeats collapse to one corner, so "hello" and "helo" produce identical
     * templates. That is deliberate — the geometry genuinely cannot tell them apart, and it is
     * the language channel's job to prefer the far more common "hello".
     */
    private fun buildTemplate(wordIndex: Int, length: Int, keys: GestureKeyMap): Int {
        var corners = 0
        var previous = ' '

        for (position in 0 until length) {
            val letter = lexicon.charAt(wordIndex, position)
            if (letter == previous || letter !in 'a'..'z') continue
            if (!keys.has(letter)) return 0

            templateX[corners] = keys.centerX(letter)
            templateY[corners] = keys.centerY(letter)
            corners++
            previous = letter
        }
        return corners
    }

    private fun meanDistance(a: SampledTrace, b: SampledTrace): Float {
        var total = 0f
        for (i in 0 until a.size) {
            total += hypot(a.xs[i] - b.xs[i], a.ys[i] - b.ys[i])
        }
        return total / a.size
    }

    // endregion

    /**
     * A fixed-size best-of list.
     *
     * Sorting every scored word would mean allocating and ordering thousands of entries to keep
     * five, so instead each score is offered to a small sorted array and almost always rejected
     * after a single comparison.
     */
    private class ScoreBoard(private val capacity: Int) {
        private val indices = IntArray(capacity)
        private val scores = FloatArray(capacity)
        private var size = 0

        fun reset() {
            size = 0
        }

        fun offer(wordIndex: Int, score: Float) {
            if (size == capacity && score <= scores[size - 1]) return

            var slot = minOf(size, capacity - 1)
            while (slot > 0 && score > scores[slot - 1]) {
                indices[slot] = indices[slot - 1]
                scores[slot] = scores[slot - 1]
                slot--
            }
            indices[slot] = wordIndex
            scores[slot] = score
            if (size < capacity) size++
        }

        fun toCandidates(lexicon: Lexicon): List<GestureCandidate> =
            (0 until size).map { GestureCandidate(lexicon.wordAt(indices[it]), scores[it]) }
    }

    private companion object {
        const val ALPHABET = 26
        const val MIN_WORD_LENGTH = 2
        const val MAX_WORD_LENGTH = 48
        val LN_MAX_FREQUENCY = ln(256f)
    }
}
