package com.slide.engine.lexicon

/** Sparse probabilities for a word conditioned on the two words before it. */
class Trigrams(
    private val contexts: LongArray,
    private val offsets: IntArray,
    private val successors: IntArray,
    private val scores: ByteArray,
) {
    val contextCount: Int get() = contexts.size
    val tripleCount: Int get() = scores.size

    fun score(older: Int, previous: Int, next: Int): Float {
        val context = context(older, previous)
        if (context < 0 || next < 0) return 0f
        val found = binarySearch(successors, offsets[context], offsets[context + 1], next)
        return if (found < 0) 0f else (scores[found].toInt() and 0xFF) / 255f
    }

    fun topSuccessors(older: Int, previous: Int, limit: Int): IntArray {
        if (limit <= 0) return EMPTY
        val context = context(older, previous)
        if (context < 0) return EMPTY

        val best = IntArray(limit)
        val bestScores = IntArray(limit)
        var count = 0
        for (slot in offsets[context] until offsets[context + 1]) {
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

    private fun context(older: Int, previous: Int): Int {
        if (older < 0 || previous < 0) return -1
        val key = (older.toLong() shl 32) or (previous.toLong() and 0xFFFF_FFFFL)
        var low = 0
        var high = contexts.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                contexts[mid] < key -> low = mid + 1
                contexts[mid] > key -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun binarySearch(values: IntArray, from: Int, to: Int, target: Int): Int {
        var low = from
        var high = to - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                values[mid] < target -> low = mid + 1
                values[mid] > target -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private companion object {
        val EMPTY = IntArray(0)
    }
}
