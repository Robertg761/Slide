package com.slide.engine.lexicon

/**
 * The English word list, held in a shape the gesture decoder can sweep cheaply.
 *
 * Words live in one flat [CharArray] with an offset table rather than 160k separate [String]
 * objects. That is roughly a tenth of the allocation count and keeps the whole lexicon in a
 * couple of contiguous arrays, which matters because the decoder touches thousands of entries
 * per swipe and any per-word object churn would show up as jank mid-gesture.
 *
 * Every word is stored lowercase. [FLAG_CAPITALIZED] marks entries that should be rendered with
 * a leading capital ("september"), since the gesture for a proper noun is identical to its
 * lowercase spelling.
 */
class Lexicon(
    private val chars: CharArray,
    private val offsets: IntArray,
    private val frequencies: ByteArray,
    private val flags: ByteArray,
) {

    val size: Int get() = frequencies.size

    /**
     * Word indices grouped by (first letter, last letter).
     *
     * A gesture pins down its own first and last letter far more reliably than anything in the
     * middle, because both are stationary endpoints rather than a turn taken at speed. Bucketing
     * on that pair lets the decoder look at a couple of thousand plausible words instead of all
     * 160k, which is what keeps decoding inside a single frame.
     */
    private val buckets: Array<IntArray?> = buildBuckets()

    fun wordAt(index: Int): String {
        val start = offsets[index]
        val end = offsets[index + 1]
        val word = String(chars, start, end - start)
        return if (isCapitalized(index)) word.replaceFirstChar(Char::uppercaseChar) else word
    }

    /** Raw lowercase form, for matching. Avoids the capitalisation work [wordAt] does. */
    fun lowercaseAt(index: Int): String {
        val start = offsets[index]
        return String(chars, start, offsets[index + 1] - start)
    }

    fun lengthAt(index: Int): Int = offsets[index + 1] - offsets[index]

    fun charAt(index: Int, position: Int): Char = chars[offsets[index] + position]

    /** AOSP's own 0-255 scale; higher is more common. */
    fun frequencyAt(index: Int): Int = frequencies[index].toInt() and 0xFF

    fun isOffensive(index: Int): Boolean = (flags[index].toInt() and FLAG_OFFENSIVE) != 0

    fun isCapitalized(index: Int): Boolean = (flags[index].toInt() and FLAG_CAPITALIZED) != 0

    /** Indices of every word starting with [first] and ending with [last], or empty if none. */
    fun bucket(first: Char, last: Char): IntArray {
        val f = first - 'a'
        val l = last - 'a'
        if (f !in 0 until ALPHABET || l !in 0 until ALPHABET) return EMPTY
        return buckets[f * ALPHABET + l] ?: EMPTY
    }

    /** Exact lookup. The word list is sorted, so this is a plain binary search. */
    fun indexOf(word: String): Int {
        val target = word.lowercase()
        var low = 0
        var high = size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = compareAt(mid, target)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    fun contains(word: String): Boolean = indexOf(word) >= 0

    /**
     * Exact lookup against a buffer rather than a [String].
     *
     * Autocorrect generates a few hundred candidate spellings per keystroke and looks each one up.
     * Building a [String] for every one of them would allocate that many short-lived objects on the
     * keypress path, so the corrector edits a reusable buffer and searches with this instead.
     */
    fun indexOf(word: CharArray, length: Int): Int {
        var low = 0
        var high = size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = compareAt(mid, word, length)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /**
     * Every word beginning with [prefix], as an index range, or an empty range when none do.
     *
     * The list is sorted, so words sharing a prefix are contiguous and both ends of the run are a
     * binary search. This is where typing completions come from: it costs two searches rather than
     * a scan, however short the prefix.
     */
    fun prefixRange(prefix: String): IntRange {
        if (prefix.isEmpty()) return 0 until size
        val target = prefix.lowercase()

        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (compareAt(mid, target) < 0) low = mid + 1 else high = mid
        }
        val start = low

        // Past the first match, "starts with the prefix" is true then false with no alternation,
        // so the end of the run is another binary search rather than a walk.
        high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (startsWith(mid, target)) low = mid + 1 else high = mid
        }
        return start until low
    }

    private fun startsWith(index: Int, prefix: String): Boolean {
        val start = offsets[index]
        if (offsets[index + 1] - start < prefix.length) return false
        for (i in prefix.indices) {
            if (chars[start + i] != prefix[i]) return false
        }
        return true
    }

    private fun compareAt(index: Int, target: String): Int {
        val start = offsets[index]
        val length = offsets[index + 1] - start
        val shared = minOf(length, target.length)
        for (i in 0 until shared) {
            val diff = chars[start + i] - target[i]
            if (diff != 0) return diff
        }
        return length - target.length
    }

    private fun compareAt(index: Int, target: CharArray, targetLength: Int): Int {
        val start = offsets[index]
        val length = offsets[index + 1] - start
        val shared = minOf(length, targetLength)
        for (i in 0 until shared) {
            val diff = chars[start + i] - target[i]
            if (diff != 0) return diff
        }
        return length - targetLength
    }

    private fun buildBuckets(): Array<IntArray?> {
        val counts = IntArray(ALPHABET * ALPHABET)

        // Two passes so each bucket is allocated exactly once at its final size; growing 676
        // lists dynamically would churn far more garbage than this costs.
        for (index in 0 until size) {
            val key = bucketKey(index)
            if (key >= 0) counts[key]++
        }

        val result = arrayOfNulls<IntArray>(ALPHABET * ALPHABET)
        val cursor = IntArray(ALPHABET * ALPHABET)
        for (key in counts.indices) {
            if (counts[key] > 0) result[key] = IntArray(counts[key])
        }
        for (index in 0 until size) {
            val key = bucketKey(index)
            if (key < 0) continue
            result[key]!![cursor[key]++] = index
        }
        return result
    }

    /** -1 for words the decoder can never produce, such as those bounded by an apostrophe. */
    private fun bucketKey(index: Int): Int {
        val start = offsets[index]
        val end = offsets[index + 1]
        val first = chars[start] - 'a'
        val last = chars[end - 1] - 'a'
        if (first !in 0 until ALPHABET || last !in 0 until ALPHABET) return -1
        return first * ALPHABET + last
    }

    companion object {
        const val FLAG_OFFENSIVE = 1 shl 0
        const val FLAG_ABBREVIATION = 1 shl 1
        const val FLAG_CAPITALIZED = 1 shl 2

        private const val ALPHABET = 26
        private val EMPTY = IntArray(0)
    }
}
