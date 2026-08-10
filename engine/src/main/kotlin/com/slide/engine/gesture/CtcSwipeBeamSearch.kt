package com.slide.engine.gesture

import com.slide.engine.lexicon.Bigrams
import com.slide.engine.lexicon.Lexicon
import com.slide.engine.lexicon.Trigrams
import com.slide.engine.lexicon.UserBigrams
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.pow

/** Slide-owned prefix beam search. No inference or decoder source from FUTO is included. */
internal class CtcSwipeBeamSearch(
    private val lexicon: Lexicon,
    private val bigrams: Bigrams?,
    private val userBigrams: UserBigrams?,
    private val trie: SwipeLexiconTrie = SwipeLexiconTrie(lexicon),
    private val trigrams: Trigrams? = null,
    private val beamWidth: Int = 100,
    private val maxResults: Int = 5,
) {
    private val accumulator = BeamAccumulator(beamWidth * CLASSES + 1)
    private val beamNodes = IntArray(beamWidth)
    private val beamBlank = FloatArray(beamWidth)
    private val beamNonBlank = FloatArray(beamWidth)
    private val nextNodes = IntArray(beamWidth)
    private val nextBlank = FloatArray(beamWidth)
    private val nextNonBlank = FloatArray(beamWidth)
    private val topIndices = IntArray(beamWidth)
    private val topScores = FloatArray(beamWidth)

    fun decode(
        logProbabilities: FloatArray,
        blockOffensive: Boolean,
        previousWord: String?,
        previousPreviousWord: String? = null,
    ): List<GestureCandidate> {
        require(logProbabilities.size % CLASSES == 0) {
            "Expected a multiple of $CLASSES CTC classes; got ${logProbabilities.size}"
        }
        var beamSize = 1
        beamNodes[0] = ROOT
        beamBlank[0] = 0f
        beamNonBlank[0] = NEGATIVE_INFINITY

        val steps = logProbabilities.size / CLASSES
        for (time in 0 until steps) {
            accumulator.reset()
            val offset = time * CLASSES
            for (slot in 0 until beamSize) {
                val node = beamNodes[slot]
                val blank = beamBlank[slot]
                val nonBlank = beamNonBlank[slot]
                val total = logAdd(blank, nonBlank)

                accumulator.addBlank(node, total + logProbabilities[offset + BLANK])
                val last = trie.lastLetter(node)
                for (letter in 0 until LETTERS) {
                    val probability = logProbabilities[offset + letter]
                    if (letter == last) {
                        if (nonBlank != NEGATIVE_INFINITY) {
                            accumulator.addNonBlank(node, nonBlank + probability)
                        }
                        val child = trie.child(node, letter)
                        if (child >= 0 && blank != NEGATIVE_INFINITY) {
                            accumulator.addNonBlank(child, blank + probability)
                        }
                    } else {
                        val child = trie.child(node, letter)
                        if (child >= 0) accumulator.addNonBlank(child, total + probability)
                    }
                }
            }

            beamSize = selectTop(accumulator)
            for (i in 0 until beamSize) {
                beamNodes[i] = nextNodes[i]
                beamBlank[i] = nextBlank[i]
                beamNonBlank[i] = nextNonBlank[i]
            }
        }

        val contextIndex = previousWord
            ?.lowercase()
            ?.trim('\'')
            ?.takeIf { it.all { char -> char in 'a'..'z' || char == '\'' } }
            ?.let(lexicon::indexOf)
            ?: -1
        val olderContextIndex = previousPreviousWord
            ?.lowercase()
            ?.trim('\'')
            ?.takeIf { it.all { char -> char in 'a'..'z' || char == '\'' } }
            ?.let(lexicon::indexOf)
            ?: -1
        val board = ResultBoard(maxResults)
        for (slot in 0 until beamSize) {
            val node = beamNodes[slot]
            val acoustic = logAdd(beamBlank[slot], beamNonBlank[slot])
            val length = trie.depth(node).coerceAtLeast(1)
            trie.forEachTerminal(node) { wordIndex ->
                if (!blockOffensive || !lexicon.isOffensive(wordIndex)) {
                    val frequency = ln(1f + lexicon.frequencyAt(wordIndex))
                    val context = if (bigrams != null && contextIndex >= 0) {
                        CONTEXT_WEIGHT * bigrams.score(contextIndex, wordIndex)
                    } else {
                        0f
                    }
                    val longerContext = if (
                        trigrams != null && olderContextIndex >= 0 && contextIndex >= 0
                    ) {
                        TRIGRAM_CONTEXT_WEIGHT *
                            trigrams.score(olderContextIndex, contextIndex, wordIndex)
                    } else {
                        0f
                    }
                    val personalContext = if (userBigrams != null && !previousWord.isNullOrEmpty()) {
                        PERSONAL_CONTEXT_WEIGHT *
                            userBigrams.score(previousWord, lexicon.lowercaseAt(wordIndex))
                    } else {
                        0f
                    }
                    val score = acoustic / length.toFloat().pow(LENGTH_NORMALIZATION) +
                        LENGTH_BONUS * length + FREQUENCY_WEIGHT * frequency + context +
                        personalContext + longerContext
                    board.offer(wordIndex, score)
                }
            }
        }
        return board.toCandidates(lexicon)
    }

    private fun selectTop(source: BeamAccumulator): Int {
        var size = 0
        for (candidate in 0 until source.size) {
            val node = source.nodes[candidate]
            val total = logAdd(source.blank[candidate], source.nonBlank[candidate])
            val length = trie.depth(node).coerceAtLeast(1)
            val pruneScore = total / length.toFloat().pow(PRUNE_LENGTH_NORMALIZATION) +
                PRUNE_LENGTH_BONUS * trie.depth(node)
            if (size == beamWidth && pruneScore <= topScores[size - 1]) continue

            var at = minOf(size, beamWidth - 1)
            while (at > 0 && pruneScore > topScores[at - 1]) {
                topScores[at] = topScores[at - 1]
                topIndices[at] = topIndices[at - 1]
                at--
            }
            topScores[at] = pruneScore
            topIndices[at] = candidate
            if (size < beamWidth) size++
        }
        for (slot in 0 until size) {
            val sourceIndex = topIndices[slot]
            nextNodes[slot] = source.nodes[sourceIndex]
            nextBlank[slot] = source.blank[sourceIndex]
            nextNonBlank[slot] = source.nonBlank[sourceIndex]
        }
        return size
    }

    private class BeamAccumulator(maxEntries: Int) {
        val nodes = IntArray(maxEntries)
        val blank = FloatArray(maxEntries)
        val nonBlank = FloatArray(maxEntries)
        private val table = IntArray(tableSize(maxEntries)) { EMPTY }
        var size = 0
            private set

        fun reset() {
            table.fill(EMPTY)
            size = 0
        }

        fun addBlank(node: Int, score: Float) {
            val index = index(node)
            blank[index] = logAdd(blank[index], score)
        }

        fun addNonBlank(node: Int, score: Float) {
            val index = index(node)
            nonBlank[index] = logAdd(nonBlank[index], score)
        }

        private fun index(node: Int): Int {
            var bucket = node * -0x61c88647 and (table.size - 1)
            while (true) {
                val existing = table[bucket]
                if (existing == EMPTY) {
                    check(size < nodes.size) { "CTC beam accumulator capacity exceeded" }
                    val added = size++
                    nodes[added] = node
                    blank[added] = NEGATIVE_INFINITY
                    nonBlank[added] = NEGATIVE_INFINITY
                    table[bucket] = added
                    return added
                }
                if (nodes[existing] == node) return existing
                bucket = (bucket + 1) and (table.size - 1)
            }
        }

        companion object {
            private const val EMPTY = -1

            private fun tableSize(entries: Int): Int {
                var size = 1
                while (size < entries * 2) size = size shl 1
                return size
            }
        }
    }

    private class ResultBoard(private val capacity: Int) {
        private val indices = IntArray(capacity)
        private val scores = FloatArray(capacity)
        private var size = 0

        fun offer(index: Int, score: Float) {
            if (size == capacity && score <= scores[size - 1]) return
            var at = minOf(size, capacity - 1)
            while (at > 0 && score > scores[at - 1]) {
                indices[at] = indices[at - 1]
                scores[at] = scores[at - 1]
                at--
            }
            indices[at] = index
            scores[at] = score
            if (size < capacity) size++
        }

        fun toCandidates(lexicon: Lexicon): List<GestureCandidate> =
            List(size) { GestureCandidate(lexicon.wordAt(indices[it]), scores[it]) }
    }

    private companion object {
        const val ROOT = 0
        const val LETTERS = 26
        const val BLANK = 26
        const val CLASSES = 27
        const val NEGATIVE_INFINITY = Float.NEGATIVE_INFINITY

        // Tuned by the FUTO model authors for this exact encoder/decoder pair. Slide's search and
        // language integration are independent implementations; these numeric model parameters
        // are data, not copied inference code.
        const val LENGTH_NORMALIZATION = 0.5949f
        const val FREQUENCY_WEIGHT = 0.0134f
        const val LENGTH_BONUS = 0.7271f
        const val PRUNE_LENGTH_NORMALIZATION = 0.1902f
        const val PRUNE_LENGTH_BONUS = 1.2727f
        const val CONTEXT_WEIGHT = 1.5f
        const val PERSONAL_CONTEXT_WEIGHT = 0.8f
        const val TRIGRAM_CONTEXT_WEIGHT = 0.75f

        fun logAdd(a: Float, b: Float): Float {
            if (a == NEGATIVE_INFINITY) return b
            if (b == NEGATIVE_INFINITY) return a
            val high = maxOf(a, b)
            val low = minOf(a, b)
            return high + ln1p(exp((low - high).toDouble())).toFloat()
        }
    }
}
