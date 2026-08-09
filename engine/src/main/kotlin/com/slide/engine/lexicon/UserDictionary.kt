package com.slide.engine.lexicon

import java.util.concurrent.ConcurrentSkipListMap

/**
 * The words this person uses that the shipped dictionary has never heard of.
 *
 * Names, place names, usernames, in-jokes, jargon, the way they actually spell things. Without
 * this a keyboard is at war with everyone whose vocabulary is not the corpus average: it rewrites
 * a friend's name every time it is typed, and never offers it once. That is the failure people
 * mean when they say a keyboard "fights" them, and no amount of tuning the corpus model fixes it,
 * because the word is not in the corpus.
 *
 * Two rules give this its shape.
 *
 * A word is only *protected* once it has been committed deliberately more than once. A single
 * occurrence is as likely to be a typo as a word, and a dictionary that learns typos on sight
 * would defend them for ever — the one failure worse than not learning at all.
 *
 * Nothing is ever learned from a field that asked not to be learned from. That check lives at the
 * call site, where the editor's flags are, and this class has no way to see them; see the
 * incognito and password handling in the IME.
 */
class UserDictionary(
    /** Deliberate commits before a word is trusted. See the class docs for why this is not one. */
    private val trustThreshold: Int = 2,
    /** Above this the dictionary is trimmed; a phone keyboard needs nothing like this many. */
    private val capacity: Int = 4_000,
) {

    /**
     * Sorted so prefixes are contiguous, concurrent because it is written from the input thread
     * and read from whatever thread persists it.
     */
    private val counts = ConcurrentSkipListMap<String, Int>()

    val size: Int get() = counts.size

    /** Every word and its count, for persistence. */
    fun entries(): List<Pair<String, Int>> = counts.map { it.key to it.value }

    /**
     * Records one deliberate use, returning whether the word is now trusted.
     *
     * @param weight how strong the evidence is. Reverting an autocorrect is the clearest signal
     *   there is — the user was shown the keyboard's opinion and rejected it — and is worth enough
     *   on its own to trust the word immediately.
     */
    fun learn(word: String, weight: Int = 1): Boolean {
        if (!isLearnable(word)) return false
        val key = word.lowercase()
        val updated = counts.merge(key, weight) { old, new -> minOf(old + new, MAX_COUNT) } ?: weight
        if (counts.size > capacity) trim()
        return updated >= trustThreshold
    }

    /** Removes a word outright, and remembers nothing about it. */
    fun forget(word: String): Boolean = counts.remove(word.lowercase()) != null

    fun clear() = counts.clear()

    /** Whether the word has been used often enough to be defended from autocorrect. */
    fun isTrusted(word: String): Boolean =
        (counts[word.lowercase()] ?: 0) >= trustThreshold

    fun countOf(word: String): Int = counts[word.lowercase()] ?: 0

    /**
     * Trusted words beginning with [prefix], commonest first.
     *
     * Untrusted words are withheld: offering a word seen once would put the user's own typos in
     * the strip, which is a slower way of making the same mistake as correcting to them.
     */
    fun completions(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val lower = prefix.lowercase()
        return counts.tailMap(lower)
            .asSequence()
            .takeWhile { it.key.startsWith(lower) }
            .filter { it.value >= trustThreshold && it.key.length > lower.length }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
            .toList()
    }

    /** Loads persisted counts, replacing anything held. */
    fun restore(saved: List<Pair<String, Int>>) {
        counts.clear()
        for ((word, count) in saved) {
            if (isLearnable(word) && count > 0) counts[word.lowercase()] = minOf(count, MAX_COUNT)
        }
    }

    /**
     * Words worth learning: letters and apostrophes, long enough to be a word rather than a slip
     * of the thumb, short enough to be a word at all.
     */
    private fun isLearnable(word: String): Boolean =
        word.length in MIN_LENGTH..MAX_LENGTH &&
            word.all { it.isLetter() || it == '\'' } &&
            word.any(Char::isLetter)

    /** Drops the least-used quarter, which is where anything learned by accident will be. */
    private fun trim() {
        val doomed = counts.entries
            .sortedBy { it.value }
            .take(counts.size - capacity * 3 / 4)
            .map { it.key }
        doomed.forEach(counts::remove)
    }

    private companion object {
        const val MIN_LENGTH = 2
        const val MAX_LENGTH = 28

        /** Counts saturate; past this the difference between words stops meaning anything. */
        const val MAX_COUNT = 255
    }
}
