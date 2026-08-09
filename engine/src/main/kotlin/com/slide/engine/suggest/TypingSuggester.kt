package com.slide.engine.suggest

import com.slide.engine.gesture.GestureKeyMap
import com.slide.engine.lexicon.Bigrams
import com.slide.engine.lexicon.Lexicon
import com.slide.engine.lexicon.UserBigrams
import com.slide.engine.lexicon.UserDictionary
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
    /**
     * A letter the user missed — "helo", "oce", "sould".
     *
     * Priced just under a neighbour-key substitution, which is the opposite way round from where
     * this started, and the reason is an asymmetry in what the two edits claim. An insertion only
     * adds to what the user typed; every key they actually pressed survives it. A substitution
     * overrides one of them, asserting that a key they did press was not the key they wanted. The
     * edit that contradicts less of the input should be the cheaper explanation, and at 0.6 against
     * a substitution's 0.5 it was the dearer one — which is how "sould" reached "would" instead of
     * "should", and "fom" reached "tom" instead of "from".
     *
     * Measured on held-out sentences, moving this from 0.6 to 0.45 takes dropped-letter typos from
     * 66.4% corrected to 78.1% and *halves* wrong corrections overall, 1.5% to 0.7%. It keeps
     * improving to about 0.40, but only by taking accuracy from substitutions, which are the
     * commoner slip on glass and which `TypoCorpus` weights equally with dropped letters rather
     * than realistically.
     */
    val insertionCost: Float = 0.45f,
    /**
     * A missing apostrophe, priced well below a missing letter.
     *
     * It is not a slip. People leave it out knowing what they meant and expecting it back, and
     * unlike a letter it can rarely turn a word into a different one — "dont" has no reading but
     * "don't", whereas dropping a letter from "font" gives half a dozen.
     */
    val apostropheCost: Float = 0.3f,
    /** A letter the user hit by accident. */
    val deletionCost: Float = 0.6f,

    /**
     * A letter that repeats the one beside it — "helllo", "largee", "partt".
     *
     * The same price as a transposition, and for the same reason: both are purely mechanical slips
     * with an unambiguous reading, where an ordinary deletion is neither. At the general deletion
     * price a doubled letter loses to a neighbour-key substitution of the final letter, which is
     * how "largee" became "larger" rather than "large", and "sidde" became "sided".
     */
    val doubledLetterCost: Float = 0.45f,

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
     *
     * Read this against the scale it is measured on, which is not obvious. The language score is
     * `ln(1 + frequency) / ln(256)`, so although it spans 0 to 1 in principle, every word common
     * enough to be worth correcting *to* sits between roughly 0.75 and 1.0. A margin is therefore
     * spending from a usable range of about a quarter, and requiring 0.15 of it meant demanding the
     * intended word be some 2.3 times commoner than the runner-up. Typos of common words have
     * common words as their runners-up — "htis" offers "this" and "hits" — so the rule almost never
     * cleared, and the effect was a keyboard that visibly knew the right answer and declined to use
     * it. `CorrectionSweepTest` measures the trade: from 0.15 down to 0.08 costs one new wrong
     * correction for every six it fixes, and below 0.08 that exchange collapses to about two to one.
     */
    val autocorrectMargin: Float = 0.08f,
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

    /**
     * Weight on how well a candidate follows the preceding word.
     *
     * Added to a candidate's score, never subtracted, so a pair the model has never seen leaves
     * that candidate exactly where spelling alone put it. Only what the model positively knows can
     * move anything, which is what makes it safe to consult a model with large gaps in it.
     *
     * Sized against the language score, which spans 0 to [languageWeight]. At 0.6 a confidently
     * predicted word outweighs a substantial frequency difference, which is what lets "at ocne"
     * reach "once", while never overturning the spelling evidence, since a correction has to be
     * reachable in one edit to be a candidate at all.
     *
     * `CorrectionSweepTest` keeps improving past this — 91.9% at 1.5, against 90.4% here — and the
     * lower value is a deliberate choice rather than the measured optimum. The held-out sentences
     * share a register with the ones the model was trained on, so a high weight is partly rewarded
     * for recognising a corpus rather than a language, and the further it is trusted the more a
     * deliberate non-word that happens to fit the sentence is at risk. Revisit against text from
     * somewhere other than Tatoeba, not against a larger sweep of the same.
     */
    val contextWeight: Float = 0.6f,

    /**
     * Weight on how well a candidate follows the preceding word *for this person specifically*.
     *
     * Sized a little above [contextWeight] on purpose. It fires far more rarely — most pairs
     * anyone types have never been typed by them before — but when it does fire it is evidence
     * about the actual writer rather than about English in aggregate, and that is worth more per
     * observation. The saturating count behind it (see `UserBigrams.score`) is what stops a habit
     * repeated a hundred times from overwhelming the spelling evidence entirely.
     *
     * Measured on generic held-out text this value barely matters: anywhere from 0.2 to 1.2 gives
     * the same result to within a tenth of a point, because the pairs that recur in unrelated
     * sentences are ordinary English ones the corpus model already covers. What that measurement
     * *did* settle is the threshold in `UserBigrams`, where counting a pair seen once was actively
     * harmful. This weight governs the case the corpus cannot see — a habit the corpus has never
     * heard of — and should be revisited against real repetitive text rather than against more of
     * the same corpus.
     */
    val personalContextWeight: Float = 0.8f,
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
    /** Null when the asset is missing, which reduces this to spelling-only correction. */
    private val bigrams: Bigrams? = null,
    /** The words this person uses that the shipped dictionary does not have. */
    private val userDictionary: UserDictionary? = null,
    /** The word pairs this person writes, as opposed to the ones English writes on average. */
    private val userBigrams: UserBigrams? = null,
) {

    /**
     * This person's own successors to the current context, resolved to lexicon indices once per
     * call rather than looked up per candidate.
     *
     * There are only ever a handful, and turning each into an index costs one binary search, where
     * turning every candidate back into a string to look it up would allocate hundreds of strings
     * on the keypress path.
     */
    private var personalIndices = IntArray(0)
    private var personalScores = FloatArray(0)
    private var personalCount = 0

    /** Lexicon index of the word before the one being typed, or -1. Set per [suggest] call. */
    private var contextIndex = -1

    /** Reusable buffer for generated spellings, one longer than the input to fit an insertion. */
    private var buffer = CharArray(config.maxWordLength + 1)

    /** Neighbouring keys per letter with their distances, rebuilt when the layout changes. */
    private var neighbourSource: GestureKeyMap? = null
    private val neighbourLetters = arrayOfNulls<CharArray>(ALPHABET)
    private val neighbourDistance = arrayOfNulls<FloatArray>(ALPHABET)

    /** Correction candidates for the current call: lexicon index to its cheapest edit cost. */
    private val corrections = HashMap<Int, Float>()

    /**
     * Where each character of the current input was touched, as x,y pairs. Null when unknown.
     *
     * Held for the duration of one [suggest] call, alongside [contextIndex], rather than threaded
     * through every private method that needs it.
     */
    private var touchPoints: FloatArray? = null

    /** The keyboard the current call is measuring against, for turning pixels into key widths. */
    private var touchKeys: GestureKeyMap? = null

    /** The touch behind character [position], or null if the caller did not record one. */
    private fun touchAt(position: Int): Pair<Float, Float>? {
        val points = touchPoints ?: return null
        val x = points[position * 2]
        val y = points[position * 2 + 1]
        // Not every character arrives from a key press: an alternate chosen from a long-press
        // popup, or a letter restored by an undo, has no touch of its own.
        if (x.isNaN() || y.isNaN()) return null
        return x to y
    }

    /**
     * How far a point is from a letter's key, in key widths and heights.
     *
     * Scaled per axis, so "one key away" is 1.0 whether the keys are square or tall, exactly as in
     * the static neighbour table this stands in for.
     */
    private fun normalisedDistance(letter: Char, x: Float, y: Float): Float? {
        val keys = touchKeys ?: return null
        if (!keys.has(letter)) return null
        val dx = (keys.centerX(letter) - x) / keys.keyWidth
        val dy = (keys.centerY(letter) - y) / keys.keyHeight
        return hypot(dx, dy)
    }

    /** Whether the shipped dictionary already has this word, and so whether learning it is idle. */
    fun knows(word: String): Boolean = lexicon.indexOf(word.lowercase()) >= 0

    /**
     * @param previousWord the word immediately before the one being typed, if there is one and the
     *   cursor has not moved away from it. Supplying it is what lets the sentence break a tie that
     *   spelling and frequency cannot; omitting it costs accuracy but is never wrong.
     * @param touchPoints where each character was actually touched, as x,y pairs in the same view
     *   pixels as [keys], with NaN for characters that came from somewhere other than a key press.
     *   Two floats per character of [typed]; anything else is ignored. Without it a mis-hit is
     *   priced by how far apart two keys are, which is the same for every press of that key; with
     *   it, by how close the finger came to the key it was reaching for.
     */
    fun suggest(
        typed: String,
        keys: GestureKeyMap,
        blockOffensive: Boolean = true,
        previousWord: String? = null,
        touchPoints: FloatArray? = null,
    ): TypingSuggestions {
        if (typed.isEmpty() || typed.length > config.maxWordLength) return TypingSuggestions.None
        val lower = typed.lowercase()
        if (!lower.all { it in 'a'..'z' || it == '\'' }) return TypingSuggestions.None

        ensureNeighbours(keys)
        corrections.clear()
        contextIndex = contextIndexFor(previousWord)
        buildPersonalContext(previousWord)
        // A short array would read past its end on the last character, and a stale one would price
        // this word by where the last one was typed. Either is worse than not knowing.
        this.touchPoints = touchPoints?.takeIf { it.size >= typed.length * 2 }
        this.touchKeys = keys

        // Whether the dictionary knows the word and whether we are willing to show it are separate
        // questions. A blocked word is still a word, and correcting a deliberately typed obscenity
        // into something else would be a far stranger thing to do than simply not suggesting it.
        val exact = lexicon.indexOf(lower)
        val shown = exact.takeIf { it >= 0 && !(blockOffensive && lexicon.isOffensive(it)) }

        val completions = collectCompletions(lower, blockOffensive)
        if (lower.length >= config.minCorrectionLength) collectCorrections(lower, blockOffensive)

        val ranked = rank(shown, completions, properNounsUnmarked = typed.none(Char::isUpperCase))

        // A word this person has deliberately used before is a word, whatever the shipped
        // dictionary thinks. This is the whole point of learning: without it their own name, and
        // everyone else's, is rewritten every single time it is typed.
        val learned = userDictionary?.isTrusted(lower) == true
        val autocorrection = chooseAutocorrection(
            lower,
            isKnownWord = exact >= 0 || learned,
            ranked = ranked,
        )
        return TypingSuggestions(
            present(typed, ranked, autocorrection, learnedCompletions(lower)),
            autocorrection,
        )
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

            lower.toCharArray(buffer)

            // Where the finger actually landed, when the caller knows. A touch that caught the
            // right-hand edge of "s" is far better evidence for "d" than the fact that d is next
            // to s, which is equally true of every "s" ever typed.
            val touch = touchAt(position)
            if (touch != null) {
                val (x, y) = touch
                for (i in 0 until ALPHABET) {
                    if (i == slot) continue
                    val candidate = 'a' + i
                    val distance = normalisedDistance(candidate, x, y) ?: continue
                    if (distance > config.neighbourRadius) continue
                    buffer[position] = candidate
                    offerCorrection(length, config.substitutionCost * distance, blockOffensive)
                }
                continue
            }

            val letters = neighbourLetters[slot] ?: continue
            val distances = neighbourDistance[slot]!!
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
                val repeats = (skipped > 0 && lower[skipped] == lower[skipped - 1]) ||
                    (skipped < length - 1 && lower[skipped] == lower[skipped + 1])
                val cost = if (repeats) config.doubledLetterCost else config.deletionCost
                offerCorrection(length - 1, cost, blockOffensive)
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
        learned: List<String>,
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

        // Learned words come next, ahead of the corpus. Someone who has typed a name twice is far
        // likelier to be typing it again than to be misspelling whatever the corpus offers, and a
        // completion the user taught the keyboard themselves is the one they will trust.
        for (word in learned) {
            if (result.size >= config.maxResults) break
            if (result.any { it.word.equals(word, ignoreCase = true) }) continue
            result.add(WordSuggestion(word, 0f, WordSuggestion.Kind.Completion))
        }

        for (suggestion in ranked) {
            if (result.size >= config.maxResults) break
            if (result.any { it.word.equals(suggestion.word, ignoreCase = true) }) continue
            result.add(suggestion)
        }
        return result
    }

    /** Words the user has taught the keyboard that continue what they are typing. */
    private fun learnedCompletions(lower: String): List<String> {
        val dictionary = userDictionary ?: return emptyList()
        if (lower.length < config.minCompletionLength) return emptyList()
        return dictionary.completions(lower, config.maxResults)
    }

    /**
     * How likely this word is, before any evidence about what was typed.
     *
     * Frequency in general, plus how well it follows the preceding word when the model has an
     * opinion. The two are added rather than interpolated because the context term is one-sided:
     * see [SuggesterConfig.contextWeight].
     */
    private fun languageScore(index: Int): Float {
        var score = config.languageWeight * ln(1f + lexicon.frequencyAt(index)) / LN_MAX_FREQUENCY

        val model = bigrams
        if (model != null && contextIndex >= 0) {
            score += config.contextWeight * model.score(contextIndex, index)
        }

        // What this person writes, on top of what English writes. Added rather than blended, for
        // the same reason as the corpus term: personal data is mostly absent, and a model that is
        // mostly absent must only ever be able to promote.
        for (slot in 0 until personalCount) {
            if (personalIndices[slot] == index) {
                score += config.personalContextWeight * personalScores[slot]
                break
            }
        }
        return score
    }

    /**
     * Resolves this person's successors to the current context into lexicon indices.
     *
     * Successors that are not in the lexicon are dropped here rather than kept: they cannot be
     * ranked against corpus candidates, having no index to be scored at. They still reach the user
     * through [learnedCompletions], which works in strings.
     */
    private fun buildPersonalContext(previousWord: String?) {
        personalCount = 0
        val model = userBigrams ?: return
        if (previousWord.isNullOrEmpty()) return

        val successors = model.successorsOf(previousWord)
        if (successors.isEmpty()) return

        if (personalIndices.size < successors.size) {
            personalIndices = IntArray(successors.size)
            personalScores = FloatArray(successors.size)
        }
        for ((word, _) in successors) {
            val index = lexicon.indexOf(word)
            if (index < 0) continue
            personalIndices[personalCount] = index
            personalScores[personalCount] = model.score(previousWord, word)
            personalCount++
        }
    }

    /**
     * Resolves the preceding word to a lexicon index.
     *
     * A word the lexicon does not know has no recorded successors either, so there is nothing to
     * look up and the corrector falls back to spelling alone for that keystroke.
     */
    private fun contextIndexFor(previousWord: String?): Int {
        if (bigrams == null || previousWord.isNullOrEmpty()) return -1
        val cleaned = previousWord.lowercase().trim('\'')
        if (cleaned.isEmpty() || !cleaned.all { it in 'a'..'z' || it == '\'' }) return -1
        return lexicon.indexOf(cleaned)
    }

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
