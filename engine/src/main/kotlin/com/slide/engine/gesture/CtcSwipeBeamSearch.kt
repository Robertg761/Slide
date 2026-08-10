package com.slide.engine.gesture

import com.slide.engine.lexicon.Bigrams
import com.slide.engine.lexicon.Lexicon
import com.slide.engine.lexicon.Trigrams
import com.slide.engine.lexicon.UserBigrams
import kotlin.math.pow

/** Slide-owned, model-compatible trie/Viterbi beam search. */
internal class CtcSwipeBeamSearch(
    private val lexicon: Lexicon,
    private val bigrams: Bigrams?,
    private val userBigrams: UserBigrams?,
    private val trie: SwipeLexiconTrie = SwipeLexiconTrie(lexicon),
    private val trigrams: Trigrams? = null,
    private val beamWidth: Int = 100,
    private val maxResults: Int = 5,
) {
    private val accumulator = BeamAccumulator(beamWidth * (CLASSES + 1))
    private val beamNodes = IntArray(beamWidth)
    private val beamScores = FloatArray(beamWidth)
    private val beamBlankEnded = BooleanArray(beamWidth)
    private val nextNodes = IntArray(beamWidth)
    private val nextScores = FloatArray(beamWidth)
    private val nextBlankEnded = BooleanArray(beamWidth)
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
        beamScores[0] = 0f
        beamBlankEnded[0] = false

        val steps = logProbabilities.size / CLASSES
        for (time in 0 until steps) {
            accumulator.reset()
            val offset = time * CLASSES
            for (slot in 0 until beamSize) {
                val node = beamNodes[slot]
                val score = beamScores[slot]

                // The model was trained with a Viterbi-style search state: one best path for
                // (trie node, blank-ended), not the probability sum used by conventional CTC.
                accumulator.add(node, blankEnded = true, score + logProbabilities[offset + BLANK])

                var child = trie.firstChild(node)
                while (child >= 0) {
                    val letter = trie.lastLetter(child)
                    accumulator.add(
                        child,
                        blankEnded = false,
                        score + logProbabilities[offset + letter],
                    )
                    child = trie.nextSibling(child)
                }

                // A sustained letter may remain on the current node. Advancing to a same-letter
                // child does not require an intervening blank; that is how a physical swipe can
                // produce double letters without drawing an artificial loop.
                if (!beamBlankEnded[slot] && node != ROOT) {
                    val repeated = trie.lastLetter(node)
                    accumulator.add(
                        node,
                        blankEnded = false,
                        score + logProbabilities[offset + repeated],
                    )
                }
            }

            beamSize = selectTop(accumulator)
            for (i in 0 until beamSize) {
                beamNodes[i] = nextNodes[i]
                beamScores[i] = nextScores[i]
                beamBlankEnded[i] = nextBlankEnded[i]
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
            val acoustic = beamScores[slot]
            val length = trie.depth(node).coerceAtLeast(1)
            trie.forEachTerminal(node) { wordIndex ->
                if (!blockOffensive || !lexicon.isOffensive(wordIndex)) {
                    // The decoder's calibrated language-model weight expects the vocabulary's
                    // raw 0..255 frequency byte. Log-normalizing here nearly erased the prior and
                    // allowed uncommon names to beat everyday words and contractions.
                    val frequency = lexicon.frequencyAt(wordIndex).toFloat()
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
            val length = trie.depth(node).coerceAtLeast(1)
            val pruneScore = source.scores[candidate] /
                length.toFloat().pow(PRUNE_LENGTH_NORMALIZATION) +
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
            nextScores[slot] = source.scores[sourceIndex]
            nextBlankEnded[slot] = source.blankEnded[sourceIndex]
        }
        return size
    }

    private class BeamAccumulator(maxEntries: Int) {
        val nodes = IntArray(maxEntries)
        val scores = FloatArray(maxEntries)
        val blankEnded = BooleanArray(maxEntries)
        private val table = IntArray(tableSize(maxEntries)) { EMPTY }
        var size = 0
            private set

        fun reset() {
            table.fill(EMPTY)
            size = 0
        }

        fun add(node: Int, blankEnded: Boolean, score: Float) {
            val state = node * 2 + if (blankEnded) 1 else 0
            var bucket = state * -0x61c88647 and (table.size - 1)
            while (true) {
                val existing = table[bucket]
                if (existing == EMPTY) {
                    check(size < nodes.size) { "CTC beam accumulator capacity exceeded" }
                    val added = size++
                    nodes[added] = node
                    this.blankEnded[added] = blankEnded
                    scores[added] = score
                    table[bucket] = added
                    return
                }
                if (nodes[existing] == node && this.blankEnded[existing] == blankEnded) {
                    if (score > scores[existing]) scores[existing] = score
                    return
                }
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
            // Blank-ended and character-ended states can finish on the same trie node. Keep only
            // the better score for a word so duplicate states cannot consume the five result slots.
            var existing = -1
            for (slot in 0 until size) {
                if (indices[slot] == index) {
                    existing = slot
                    break
                }
            }
            if (existing >= 0) {
                if (score <= scores[existing]) return
                for (slot in existing until size - 1) {
                    indices[slot] = indices[slot + 1]
                    scores[slot] = scores[slot + 1]
                }
                size--
            }
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
    }
}
