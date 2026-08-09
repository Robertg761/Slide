package com.slide.engine.lexicon

/**
 * How likely each word is to follow each other word, for the pairs common enough to be worth
 * storing.
 *
 * This is the piece that lets the keyboard tell "over there" from "over their". Word frequency
 * alone cannot: both are common, and nothing about the letters distinguishes them once a letter
 * has been dropped. It is also the difference between a corrector that guesses from spelling and
 * one that reads the sentence.
 *
 * Held as three parallel arrays rather than a map, for the same reason [Lexicon] is: this is
 * consulted for every candidate on every keystroke, and a `HashMap<Long, Float>` of four hundred
 * thousand entries would be both far larger and far slower to walk than two binary searches.
 *
 * Indices are [Lexicon] indices, so a bigram file only means anything alongside the lexicon it was
 * built against; [BigramLoader] checks that they match.
 */
class Bigrams(
    /** Ascending lexicon indices of words that have any recorded successor. */
    private val contexts: IntArray,
    /** Where each context's run begins in [successors], plus a final end marker. */
    private val offsets: IntArray,
    /** Successor lexicon indices, ascending within each context's run. */
    private val successors: IntArray,
    /** Quantised log P(successor | context), 1 to 255. */
    private val scores: ByteArray,
) {

    val contextCount: Int get() = contexts.size
    val pairCount: Int get() = scores.size

    /**
     * How strongly [previous] predicts [next], from 0 for "nothing recorded" to 1 for "almost
     * always".
     *
     * Zero for an unknown pair is deliberate and is what makes this safe to add to any candidate's
     * score: a word the model has never seen after this one is left exactly where it was rather
     * than being pushed down. The model can promote, never demote, so its gaps cost nothing beyond
     * the help they fail to give.
     */
    fun score(previous: Int, next: Int): Float {
        if (previous < 0 || next < 0) return 0f

        val context = binarySearch(contexts, 0, contexts.size, previous)
        if (context < 0) return 0f

        val from = offsets[context]
        val to = offsets[context + 1]
        val found = binarySearch(successors, from, to, next)
        if (found < 0) return 0f

        return (scores[found].toInt() and 0xFF) / 255f
    }

    /** Whether anything at all is known about what follows [previous]. */
    fun hasContext(previous: Int): Boolean =
        previous >= 0 && binarySearch(contexts, 0, contexts.size, previous) >= 0

    /**
     * The likeliest words to follow [previous], best first, at most [limit] of them.
     *
     * Unlike [score], which answers a question about a candidate the corrector already has, this
     * one is asked when there is no candidate at all — nothing has been typed yet — so it has to
     * produce the words rather than rank them.
     *
     * A context's successors are stored ascending by index rather than by score, so this is a scan
     * of the run. That is the right trade: the run is rarely more than a few dozen long, and
     * ordering the file by score would cost the delta encoding that makes it a megabyte instead of
     * three.
     */
    fun topSuccessors(previous: Int, limit: Int): IntArray {
        if (previous < 0 || limit <= 0) return EMPTY
        val context = binarySearch(contexts, 0, contexts.size, previous)
        if (context < 0) return EMPTY

        val from = offsets[context]
        val to = offsets[context + 1]

        val best = IntArray(limit)
        val bestScores = IntArray(limit)
        var count = 0

        for (slot in from until to) {
            val score = scores[slot].toInt() and 0xFF
            var at = minOf(count, limit - 1)
            if (count == limit && score <= bestScores[at]) continue
            while (at > 0 && score > bestScores[at - 1]) {
                best[at] = best[at - 1]
                bestScores[at] = bestScores[at - 1]
                at--
            }
            best[at] = successors[slot]
            bestScores[at] = score
            if (count < limit) count++
        }
        return best.copyOf(count)
    }

    private fun binarySearch(values: IntArray, from: Int, to: Int, target: Int): Int {
        var low = from
        var high = to - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = values[mid]
            when {
                value < target -> low = mid + 1
                value > target -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private companion object {
        val EMPTY = IntArray(0)
    }
}
