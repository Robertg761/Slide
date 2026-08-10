package com.slide.engine.lexicon

import java.util.concurrent.ConcurrentHashMap

/**
 * The word pairs this person actually writes, as opposed to the ones English writes on average.
 *
 * [Bigrams] knows that "at once" is commoner than "at ounce". It has no idea that this particular
 * person writes "kubectl apply" forty times a week, or that their friend Sam is always "Sam
 * Whitmore", because neither pair appears in any corpus and one of the words is not in any
 * dictionary. That gap is exactly where a keyboard feels least like it knows you.
 *
 * Keyed by strings rather than by lexicon index, which is the whole reason this cannot simply
 * reuse [Bigrams]. Half the value is in pairs whose words are not in the lexicon at all, and an
 * index-keyed model has no way to represent those.
 *
 * Counts are evidence, not truth. A pair seen once contributes a small amount and a pair seen
 * twenty times contributes a bounded amount more, so a single odd juxtaposition cannot outweigh
 * what the corpus knows, and no amount of repetition lets a personal habit dominate outright.
 */
class UserBigrams(
    /** Sightings before a pair counts for anything. See [score] for why this is not one. */
    private val trustThreshold: Int = 4,
    /** Total pairs kept. Trimmed by count when exceeded; a phone needs nothing like this many. */
    private val capacity: Int = 3_000,
) {

    private data class Successor(val surface: SurfaceForm, val count: Int)

    /** Normalised context to its best-known surface form. */
    private val contextSurfaces = ConcurrentHashMap<String, SurfaceForm>()
    private val pairs = ConcurrentHashMap<String, ConcurrentHashMap<String, Successor>>()

    val size: Int get() = pairs.values.sumOf { it.size }

    /** Every pair and its count, for persistence. */
    fun entries(): List<Triple<String, String, Int>> =
        pairs.flatMap { (previous, successors) ->
            val previousSurface = contextSurfaces[previous]?.value ?: previous
            successors.values.map { next -> Triple(previousSurface, next.surface.value, next.count) }
        }

    fun learn(previous: String, next: String) {
        if (!isUsable(previous) || !isUsable(next)) return
        val previousKey = previous.lowercase()
        val nextKey = next.lowercase()
        contextSurfaces.merge(previousKey, SurfaceForm.first(previous)) { old, _ ->
            old.observe(previous)
        }
        val successors = pairs.getOrPut(previousKey) { ConcurrentHashMap() }
        successors.merge(nextKey, Successor(SurfaceForm.first(next), 1)) { old, new ->
            Successor(
                surface = old.surface.observe(next),
                count = minOf(old.count + new.count, MAX_COUNT),
            )
        }
        if (size > capacity) trim()
    }

    /** What this person has written after [previous], with counts. Empty when nothing is known. */
    fun successorsOf(previous: String): Map<String, Int> =
        pairs[previous.lowercase()]?.values?.associate { it.surface.value to it.count } ?: emptyMap()

    /**
     * How strongly this person's own habits predict [next] after [previous], from 0 to 1.
     *
     * Deliberately not a probability. Personal data is far too sparse for one — a pair seen twice
     * out of three observations is not two-thirds likely, it is barely evidence at all. This is a
     * saturating count instead: something, quickly, and then not much more.
     *
     * Nothing at all below [trustThreshold], which is the rule that makes this safe rather than
     * merely plausible. A pair seen once is not a habit, and its danger is not that it fails to
     * help — it is that it *does* something. Asked about a context it has seen once, the model
     * promotes whatever happened to follow that time, which is as likely to be the wrong candidate
     * as the right one. Measured on held-out text, counting singletons cost more in wrong
     * corrections than it bought in right ones; ignoring them keeps the gain and drops the harm.
     */
    fun score(previous: String, next: String): Float {
        val count = pairs[previous.lowercase()]?.get(next.lowercase())?.count ?: return 0f
        if (count < trustThreshold) return 0f
        return minOf(1f, count.toFloat() / CONFIDENT_COUNT)
    }

    /** Removes every personal phrase that contains [word], on either side. */
    fun forget(word: String) {
        val key = word.lowercase()
        pairs.remove(key)
        contextSurfaces.remove(key)

        for ((previous, successors) in pairs) {
            successors.remove(key)
            if (successors.isEmpty() && pairs.remove(previous, successors)) {
                contextSurfaces.remove(previous)
            }
        }
    }

    fun clear() {
        pairs.clear()
        contextSurfaces.clear()
    }

    fun restore(saved: List<Triple<String, String, Int>>) {
        clear()
        for ((previous, next, count) in saved) {
            if (!isUsable(previous) || !isUsable(next) || count <= 0) continue
            val previousKey = previous.lowercase()
            val nextKey = next.lowercase()
            contextSurfaces.merge(
                previousKey,
                SurfaceForm.restored(previous, count),
            ) { old, new -> old.merge(new) }
            pairs.getOrPut(previousKey) { ConcurrentHashMap() }
                .merge(
                    nextKey,
                    Successor(SurfaceForm.restored(next, count), minOf(count, MAX_COUNT)),
                ) { old, new ->
                    Successor(
                        surface = old.surface.merge(new.surface),
                        count = minOf(old.count + new.count, MAX_COUNT),
                    )
                }
        }
    }

    private fun isUsable(word: String): Boolean =
        word.length in 1..MAX_LENGTH && word.all { it.isLetter() || it == '\'' } &&
            word.any(Char::isLetter)

    /** Drops the least-seen quarter, which is where anything typed once and never again will be. */
    private fun trim() {
        val doomed = pairs.flatMap { (previous, successors) ->
            successors.map { (next, value) -> Triple(previous, next, value.count) }
        }
            .sortedBy { it.third }
            .take(size - capacity * 3 / 4)
        for ((previous, next, _) in doomed) {
            val successors = pairs[previous] ?: continue
            successors.remove(next)
            if (successors.isEmpty()) {
                pairs.remove(previous)
                contextSurfaces.remove(previous)
            }
        }
    }

    private companion object {
        const val MAX_LENGTH = 28
        const val MAX_COUNT = 255

        /**
         * Sightings at which a personal pair is as sure as it is going to get.
         *
         * Twice the trust threshold, so a pair that has only just qualified contributes half of
         * what a well-established habit does rather than arriving at full strength.
         */
        const val CONFIDENT_COUNT = 8f
    }
}
