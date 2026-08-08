package com.slide.engine.suggest

import com.slide.engine.gesture.GestureKeyMap
import com.slide.engine.lexicon.Lexicon
import kotlin.math.hypot
import kotlin.math.ln

/** A word offered for what the user has typed so far. */
data class WordSuggestion(val word: String, val score: Float, val kind: Kind) {

    enum class Kind {
        /** Exactly what the user typed, whether or not the dictionary knows it. */
        Typed,

        /** The typed text is a prefix of this word. Offered, never applied on its own. */
        Completion,

        /** A near miss under the touch model. Only this kind can be autocorrected to. */
        Correction,
    }
}

/**
 * What to show, and what to do if the user presses space.
 *
 * [autocorrection] is deliberately separate from the head of [words]. Displaying a candidate and
 * silently substituting it are very different promises to the user, and the rules for the second
 * are much stricter than for the first.
 */
data class TypingSuggestions(val words: List<WordSuggestion>, val autocorrection: String?) {
    companion object {
        val None = TypingSuggestions(emptyList(), null)
    }
}

/**
 * Tunables for typed-word suggestion and autocorrect.
 *
 * Costs are in the same units as the language score, which runs 0 to [languageWeight]. A cost of
 * 0.5 therefore means "worth overriding only for a word roughly twice as common".
 *
 * These are reasoned defaults, not measured ones: unlike the gesture decoder there is no synthetic
 * corpus that convincingly imitates real typos, so the numbers here are expected to move once
 * there is on-device data. The tests pin behaviour, not exact scores.
 */
data class SuggesterConfig(
    /** Candidates returned. Matches the suggestion strip's three cells. */
    val maxResults: Int = 3,
    /** Weight on word frequency, and so the full range of the language score. */
    val languageWeight: Float = 1.0f,

    /** Two letters typed in the wrong order — "teh". The commonest typo, so the cheapest. */
    val transpositionCost: Float = 0.45f,
    /** Wrong key, scaled by how far that key is from the intended one. */
    val substitutionCost: Float = 0.5f,
    /** A letter the user missed — "helo". */
    val insertionCost: Float = 0.6f,
    /**
     * A missing apostrophe, priced well below a missing letter.
     *
     * It is not a slip. People leave it out knowing what they meant and expecting it back, and
     * unlike a letter it can rarely turn a word into a different one — "dont" has no reading but
     * "don't", whereas dropping a letter from "font" gives half a dozen.
     */
    val apostropheCost: Float = 0.3f,
    /** A letter the user hit twice or by accident — "helllo". */
    val deletionCost: Float = 0.6f,

    /** Charged to any completion, plus [completionCostPerChar] for each letter still to come. */
    val completionCost: Float = 0.4f,
    val completionCostPerChar: Float = 0.12f,

    /**
     * Charged to proper nouns when the user typed no capital letter.
     *
     * The wordlist rates names by how often they appear in text, which puts "Dan" and "Eth" up
     * among ordinary words and lets them shoulder aside the correction the user actually wanted
     * for "adn" or "teh". Someone who meant a name would usually have reached for shift, so
     * without one the name is the weaker reading.
     */
    val properNounCost: Float = 0.25f,

    /**
     * How far a key may be, in key widths and heights, to count as a plausible mis-hit.
     *
     * Distance is measured with x scaled by key width and y by key height, so one key in any
     * direction is 1.0 whatever the keyboard's proportions. 1.2 takes in the four orthogonal
     * neighbours and the diagonals of a staggered row, and stops short of two keys away.
     */
    val neighbourRadius: Float = 1.2f,

    /** Below this many letters a typo is indistinguishable from a short word. */
    val minCorrectionLength: Int = 3,
    /** Below this, completions are guesswork: nearly every word in the dictionary still matches. */
    val minCompletionLength: Int = 2,

    /** A correction costing more than this is too speculative to apply without being asked. */
    val maxAutocorrectCost: Float = 0.8f,
    /** Never autocorrect into a word the user is unlikely to have wanted. */
    val minAutocorrectFrequency: Int = 40,
    /**
     * How far ahead of every other candidate a correction must be before it is applied.
     *
     * A wrong autocorrect is far more annoying than a missed one — it destroys something the user
     * did type, at the moment they have stopped looking. Where the keyboard is unsure, it does
     * nothing and leaves the alternative one tap away in the strip.
     */
    val autocorrectMargin: Float = 0.15f,
    /**
     * If the typed text is the start of an ordinary word this common, it is treated as unfinished
     * rather than misspelled.
     *
     * "hel" is one letter short of "help", and the last thing it should become is "he". Proper
     * nouns do not count towards this: "teh" begins "Tehran", which is no reason to leave a
     * transposed "the" standing.
     */
    val prefixGuardFrequency: Int = 100,

    /** Longer than this and the input is not a word being typed. */
    val maxWordLength: Int = 28,
)

/**
 * Suggests words for text typed on the keys, and decides when to autocorrect.
 *
 * Corrections are found by generating the spellings one edit away from what was typed and looking
 * each up, rather than by scanning the dictionary for near matches. For an eight-letter word that
 * is a few hundred binary searches over a sorted array — microseconds — where a scan would be
 * millions of comparisons on every keystroke. The cost is that two-edit typos are not corrected;
 * they still get a strip full of completions, and are far rarer than single slips.
 *
 * The keyboard geometry comes in as a [GestureKeyMap]: it is the gesture decoder's type by history,
 * but what it actually describes is where the letters are, which is exactly what a touch model for
 * typing needs too.
 */
class TypingSuggester(
    private val lexicon: Lexicon,
    private val config: SuggesterConfig = SuggesterConfig(),
) {

    /** Reusable buffer for generated spellings, one longer than the input to fit an insertion. */
    private var buffer = CharArray(config.maxWordLength + 1)

    /** Neighbouring keys per letter with their distances, rebuilt when the layout changes. */
    private var neighbourSource: GestureKeyMap? = null
    private val neighbourLetters = arrayOfNulls<CharArray>(ALPHABET)
    private val neighbourDistance = arrayOfNulls<FloatArray>(ALPHABET)

    /** Correction candidates for the current call: lexicon index to its cheapest edit cost. */
    private val corrections = HashMap<Int, Float>()

    fun suggest(
        typed: String,
        keys: GestureKeyMap,
        blockOffensive: Boolean = true,
    ): TypingSuggestions {
        if (typed.isEmpty() || typed.length > config.maxWordLength) return TypingSuggestions.None
        val lower = typed.lowercase()
        if (!lower.all { it in 'a'..'z' || it == '\'' }) return TypingSuggestions.None

        ensureNeighbours(keys)
        corrections.clear()

        // Whether the dictionary knows the word and whether we are willing to show it are separate
        // questions. A blocked word is still a word, and correcting a deliberately typed obscenity
        // into something else would be a far stranger thing to do than simply not suggesting it.
        val exact = lexicon.indexOf(lower)
        val shown = exact.takeIf { it >= 0 && !(blockOffensive && lexicon.isOffensive(it)) }

        val completions = collectCompletions(lower, blockOffensive)
        if (lower.length >= config.minCorrectionLength) collectCorrections(lower, blockOffensive)

        val ranked = rank(shown, completions, properNounsUnmarked = typed.none(Char::isUpperCase))
        val autocorrection = chooseAutocorrection(lower, isKnownWord = exact >= 0, ranked = ranked)
        return TypingSuggestions(present(typed, ranked, autocorrection), autocorrection)
    }

    // region Candidate generation

    /** Top completions by score. The range can hold thousands, so only the best few are kept. */
    private fun collectCompletions(lower: String, blockOffensive: Boolean): TopK {
        val board = TopK(config.maxResults + 1)
        if (lower.length < config.minCompletionLength) return board

        for (index in lexicon.prefixRange(lower)) {
            val length = lexicon.lengthAt(index)
            if (length == lower.length) continue // the exact match, handled on its own
            if (blockOffensive && lexicon.isOffensive(index)) continue

            val extra = length - lower.length
            val cost = config.completionCost + config.completionCostPerChar * extra
            board.offer(index, languageScore(index) - cost)
        }
        return board
    }

    /**
     * Every spelling one edit from [lower] that the dictionary recognises.
     *
     * The four edit kinds are the four ways a finger goes wrong: the wrong key, two keys in the
     * wrong order, a key hit that should not have been, and a key missed. Each generated spelling
     * is looked up directly, and only the cheapest route to a given word is kept — "hlelo" reaches
     * "hello" by transposition and by two other longer paths, and should be charged for the
     * cheapest.
     */
    private fun collectCorrections(lower: String, blockOffensive: Boolean) {
        val length = lower.length
        if (buffer.size < length + 1) buffer = CharArray(length + 1)

        // Substitution: the intended key was one of the neighbours of the one that was hit.
        for (position in 0 until length) {
            val letter = lower[position]
            val slot = letter - 'a'
            if (slot !in 0 until ALPHABET) continue
            val letters = neighbourLetters[slot] ?: continue
            val distances = neighbourDistance[slot]!!

            lower.toCharArray(buffer)
            for (i in letters.indices) {
                buffer[position] = letters[i]
                offerCorrection(length, config.substitutionCost * distances[i], blockOffensive)
            }
        }

        // Transposition.
        for (position in 0 until length - 1) {
            if (lower[position] == lower[position + 1]) continue
            lower.toCharArray(buffer)
            buffer[position] = lower[position + 1]
            buffer[position + 1] = lower[position]
            offerCorrection(length, config.transpositionCost, blockOffensive)
        }

        // Deletion: a letter was typed that should not have been.
        if (length - 1 >= MIN_CANDIDATE_LENGTH) {
            for (skipped in 0 until length) {
                var out = 0
                for (i in 0 until length) {
                    if (i != skipped) buffer[out++] = lower[i]
                }
                offerCorrection(length - 1, config.deletionCost, blockOffensive)
            }
        }

        // Insertion: a letter was missed. Not at the end -- that is a completion, and completions
        // are shown but never applied automatically, so letting one in here would smuggle it past
        // that rule.
        for (position in 0 until length) {
            for (letter in INSERTABLE) {
                var out = 0
                for (i in 0 until position) buffer[out++] = lower[i]
                buffer[out++] = letter
                for (i in position until length) buffer[out++] = lower[i]
                val cost = if (letter == '\'') config.apostropheCost else config.insertionCost
                offerCorrection(length + 1, cost, blockOffensive)
            }
        }
    }

    private fun offerCorrection(length: Int, cost: Float, blockOffensive: Boolean) {
        val index = lexicon.indexOf(buffer, length)
        if (index < 0) return
        if (blockOffensive && lexicon.isOffensive(index)) return

        val existing = corrections[index]
        if (existing == null || cost < existing) corrections[index] = cost
    }

    // endregion

    // region Ranking

    private fun rank(
        exact: Int?,
        completions: TopK,
        properNounsUnmarked: Boolean,
    ): List<WordSuggestion> {
        fun scoreOf(index: Int, cost: Float): Float {
            val penalty = if (properNounsUnmarked && lexicon.isCapitalized(index)) config.properNounCost else 0f
            return languageScore(index) - cost - penalty
        }

        val pool = ArrayList<WordSuggestion>(corrections.size + completions.size + 1)
        val seen = HashSet<Int>()

        if (exact != null) {
            seen.add(exact)
            pool.add(WordSuggestion(lexicon.wordAt(exact), scoreOf(exact, 0f), WordSuggestion.Kind.Typed))
        }
        for ((index, cost) in corrections) {
            if (!seen.add(index)) continue
            pool.add(
                WordSuggestion(
                    lexicon.wordAt(index),
                    scoreOf(index, cost),
                    WordSuggestion.Kind.Correction,
                ),
            )
        }
        for (i in 0 until completions.size) {
            val index = completions.wordIndices[i]
            if (!seen.add(index)) continue
            pool.add(
                WordSuggestion(
                    lexicon.wordAt(index),
                    if (properNounsUnmarked && lexicon.isCapitalized(index)) {
                        completions.scores[i] - config.properNounCost
                    } else {
                        completions.scores[i]
                    },
                    WordSuggestion.Kind.Completion,
                ),
            )
        }

        pool.sortByDescending { it.score }
        return pool
    }

    /**
     * Picks the word space should substitute, or null to leave what was typed alone.
     *
     * Every rule here is a reason *not* to correct. That asymmetry is the point: the keyboard sees
     * the same evidence either way, but only one of the two outcomes can destroy text the user
     * deliberately entered.
     */
    private fun chooseAutocorrection(
        lower: String,
        isKnownWord: Boolean,
        ranked: List<WordSuggestion>,
    ): String? {
        // A word the dictionary already knows is not a typo, whatever else scores well.
        if (isKnownWord) return null
        if (lower.length < config.minCorrectionLength) return null

        val best = ranked.firstOrNull() ?: return null
        if (best.kind != WordSuggestion.Kind.Correction) return null

        val index = lexicon.indexOf(best.word)
        if (index < 0 || lexicon.frequencyAt(index) < config.minAutocorrectFrequency) return null
        if ((corrections[index] ?: Float.MAX_VALUE) > config.maxAutocorrectCost) return null

        val runnerUp = ranked.getOrNull(1)
        if (runnerUp != null && best.score - runnerUp.score < config.autocorrectMargin) return null

        // The user may simply not have finished. If what they typed starts a word they plausibly
        // meant, an unfinished word is the likelier explanation than a misspelled one.
        for (candidate in lexicon.prefixRange(lower)) {
            if (lexicon.lengthAt(candidate) == lower.length) continue
            if (lexicon.isCapitalized(candidate)) continue
            if (lexicon.frequencyAt(candidate) >= config.prefixGuardFrequency) return null
        }

        return best.word
    }

    /**
     * Orders the strip.
     *
     * What sits first is what pressing space will produce, so the user can always see the decision
     * before it is made rather than discovering it afterwards. When a correction is pending, the
     * literal they typed sits immediately beside it, one tap away.
     */
    private fun present(
        typed: String,
        ranked: List<WordSuggestion>,
        autocorrection: String?,
    ): List<WordSuggestion> {
        val literal = WordSuggestion(typed, 0f, WordSuggestion.Kind.Typed)
        val result = ArrayList<WordSuggestion>(config.maxResults)

        if (autocorrection != null) {
            result.add(ranked.first())
            result.add(literal)
        } else {
            // The literal, not the dictionary's copy of it: someone who typed "iPhone" or "hello"
            // should see it back exactly as they wrote it.
            result.add(literal)
        }

        for (suggestion in ranked) {
            if (result.size >= config.maxResults) break
            if (result.any { it.word.equals(suggestion.word, ignoreCase = true) }) continue
            result.add(suggestion)
        }
        return result
    }

    private fun languageScore(index: Int): Float =
        config.languageWeight * ln(1f + lexicon.frequencyAt(index)) / LN_MAX_FREQUENCY

    // endregion

    /**
     * Caches which keys are close enough to each letter to be a plausible mis-hit.
     *
     * Distances are normalised by key width and height separately, so "one key away" is 1.0
     * whether the keys are square or tall, and the cost of a slip does not change when the user
     * drags the keyboard height slider.
     */
    private fun ensureNeighbours(keys: GestureKeyMap) {
        if (neighbourSource === keys) return

        val letters = CharArray(ALPHABET)
        val distances = FloatArray(ALPHABET)

        for (i in 0 until ALPHABET) {
            val letter = 'a' + i
            if (!keys.has(letter)) {
                neighbourLetters[i] = null
                continue
            }

            var count = 0
            for (j in 0 until ALPHABET) {
                if (j == i) continue
                val other = 'a' + j
                if (!keys.has(other)) continue

                val dx = (keys.centerX(other) - keys.centerX(letter)) / keys.keyWidth
                val dy = (keys.centerY(other) - keys.centerY(letter)) / keys.keyHeight
                val distance = hypot(dx, dy)
                if (distance > config.neighbourRadius) continue

                letters[count] = other
                distances[count] = distance
                count++
            }

            neighbourLetters[i] = letters.copyOf(count)
            neighbourDistance[i] = distances.copyOf(count)
        }
        neighbourSource = keys
    }

    /**
     * A fixed-size best-of list, as in the gesture decoder.
     *
     * Completions for a two-letter prefix run to thousands of words; sorting them to keep four
     * would be the most expensive thing on the keypress path by a wide margin.
     */
    private class TopK(private val capacity: Int) {
        val wordIndices = IntArray(capacity)
        val scores = FloatArray(capacity)
        var size = 0
            private set

        fun offer(index: Int, score: Float) {
            if (size == capacity && score <= scores[size - 1]) return

            var slot = minOf(size, capacity - 1)
            while (slot > 0 && score > scores[slot - 1]) {
                wordIndices[slot] = wordIndices[slot - 1]
                scores[slot] = scores[slot - 1]
                slot--
            }
            wordIndices[slot] = index
            scores[slot] = score
            if (size < capacity) size++
        }
    }

    private companion object {
        const val ALPHABET = 26
        const val MIN_CANDIDATE_LENGTH = 2
        val LN_MAX_FREQUENCY = ln(256f)

        /**
         * Letters an insertion may add, plus the apostrophe.
         *
         * The apostrophe earns its place because it is the one character people routinely leave
         * out on purpose and still expect back: "dont", "wont", "cant", "im".
         */
        val INSERTABLE = CharArray(27) { if (it < 26) 'a' + it else '\'' }
    }
}
