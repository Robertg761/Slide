package com.slide.engine.suggest

import com.slide.engine.lexicon.Lexicon

/**
 * Generated single-edit typos over the commonest words, for judging autocorrect in bulk.
 *
 * Hand-written cases pin the behaviour everyone thought of; this is for the behaviour nobody did.
 * The four edit kinds are exactly the four the corrector claims to handle, so a low rate here is a
 * failure on its own terms rather than a wish for something it never promised.
 *
 * Two things it is not. It is not a distribution — real typing does not produce these four kinds in
 * equal measure, so the headline rate is a comparable number rather than a prediction of what any
 * one person will see. And its notion of the intended word is an assumption: "ome" is generated
 * from "some", "come" and "home" alike, so a correction to any one of them is scored wrong twice
 * over. Read the wrong-correction rate as an upper bound.
 */
object TypoCorpus {

    /** Physical QWERTY adjacency, for typos that come from hitting the next key over. */
    private val neighbours = mapOf(
        'q' to "wa", 'w' to "qeas", 'e' to "wrsd", 'r' to "etdf", 't' to "ryfg",
        'y' to "tugh", 'u' to "yihj", 'i' to "uojk", 'o' to "ipkl", 'p' to "ol",
        'a' to "qwsz", 's' to "awedxz", 'd' to "serfcx", 'f' to "drtgvc", 'g' to "ftyhbv",
        'h' to "gyujnb", 'j' to "huikmn", 'k' to "jiolm", 'l' to "kop",
        'z' to "asx", 'x' to "zsdc", 'c' to "xdfv", 'v' to "cfgb", 'b' to "vghn",
        'n' to "bhjm", 'm' to "njk",
    )

    enum class Kind { TRANSPOSITION, SUBSTITUTION, DOUBLED, DROPPED }

    data class Case(val typo: String, val intended: String, val kind: Kind)

    /**
     * @param words how many of the commonest words to build typos from.
     */
    fun build(lexicon: Lexicon, words: Int = 600): List<Case> {
        val common = (0 until lexicon.size)
            .asSequence()
            .filter { lexicon.lengthAt(it) in 4..9 }
            .filter { lexicon.lowercaseAt(it).all { c -> c in 'a'..'z' } }
            .filter { !lexicon.isCapitalized(it) && !lexicon.isOffensive(it) }
            .sortedByDescending { lexicon.frequencyAt(it) }
            .take(words)
            .map { lexicon.lowercaseAt(it) }
            .toList()

        return common.flatMap(::typosFor)
            // A typo that is itself a word must never be rewritten, and the rule that protects it
            // is a different one. Keeping them here would measure that rule instead of this one.
            .filter { !lexicon.contains(it.typo) }
            .filter { it.typo.length >= 3 }
    }

    private fun typosFor(word: String): List<Case> {
        val out = ArrayList<Case>()

        for (i in 0 until word.length - 1) {
            if (word[i] == word[i + 1]) continue
            out += Case(
                word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2),
                word,
                Kind.TRANSPOSITION,
            )
        }

        for (i in word.indices) {
            val near = neighbours[word[i]] ?: continue
            // One representative neighbour per position, so no single long word floods the sample.
            out += Case(
                word.substring(0, i) + near[i % near.length] + word.substring(i + 1),
                word,
                Kind.SUBSTITUTION,
            )
        }

        for (i in word.indices) {
            out += Case(word.substring(0, i) + word[i] + word.substring(i), word, Kind.DOUBLED)
        }

        // Never the last letter: dropping that leaves a prefix, which is a completion, and
        // completions are deliberately shown without ever being applied.
        for (i in 0 until word.length - 1) {
            out += Case(word.substring(0, i) + word.substring(i + 1), word, Kind.DROPPED)
        }

        return out
    }

    /** What [suggest] did with one case. */
    enum class Outcome { RIGHT, WRONG, NONE }

    fun outcome(case: Case, applied: String?): Outcome = when {
        applied == null -> Outcome.NONE
        applied.equals(case.intended, ignoreCase = true) -> Outcome.RIGHT
        else -> Outcome.WRONG
    }
}
