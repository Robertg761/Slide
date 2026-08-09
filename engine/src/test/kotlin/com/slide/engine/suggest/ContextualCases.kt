package com.slide.engine.suggest

import com.slide.engine.HeldOutSentences
import com.slide.engine.lexicon.Lexicon

/**
 * Single-edit typos placed where they were actually written, with the real preceding word.
 *
 * The counterpart to [TypoCorpus], which judges typos in isolation. Isolation is the only fair way
 * to judge spelling evidence, but it is not how anyone types, and it cannot show what the sentence
 * is worth. Sentences come from the held-out tenth of the corpus that the bigram model was never
 * trained on.
 */
object ContextualCases {

    data class Case(
        val typo: String,
        val intended: String,
        val previous: String,
        val kind: TypoCorpus.Kind,
    )

    private val TOKEN = Regex("[a-z']+")

    /**
     * @param sentences which held-out sentences to build from. Defaults to all of them; the
     *   adaptive tests pass a slice, so that what a personal model was trained on and what it is
     *   measured against cannot overlap.
     */
    fun build(
        lexicon: Lexicon,
        sentences: List<String> = HeldOutSentences.instance,
    ): List<Case> {
        val out = ArrayList<Case>()
        for ((n, sentence) in sentences.withIndex()) {
            val tokens = TOKEN.findAll(sentence.lowercase()).map { it.value }.toList()
            if (tokens.size < 3) continue

            // Rotate the position, so the sample is not all second words.
            val start = 1 + n % (tokens.size - 1)
            for (offset in tokens.indices) {
                val at = start + offset
                // The first word of a sentence has nothing before it, so it could only ever
                // measure the no-context path and would dilute the comparison.
                if (at !in 1 until tokens.size) continue

                val word = tokens[at]
                val previous = tokens[at - 1]
                if (word.length !in 4..9 || !word.all { it in 'a'..'z' }) continue
                if (lexicon.indexOf(word) < 0 || lexicon.indexOf(previous) < 0) continue

                val typo = typoFor(word, n) ?: continue
                // A typo that is itself a word is protected by a different rule entirely.
                if (lexicon.contains(typo)) continue

                out += Case(typo, word, previous, kindFor(n))
                break
            }
        }
        return out
    }

    private fun kindFor(salt: Int): TypoCorpus.Kind = when (salt % 4) {
        0 -> TypoCorpus.Kind.TRANSPOSITION
        1 -> TypoCorpus.Kind.SUBSTITUTION
        2 -> TypoCorpus.Kind.DOUBLED
        else -> TypoCorpus.Kind.DROPPED
    }

    /** One single-edit typo, its kind rotating with the sentence so all four are represented. */
    private fun typoFor(word: String, salt: Int): String? {
        val at = salt % (word.length - 1)
        return when (salt % 4) {
            0 -> if (word[at] == word[at + 1]) {
                null
            } else {
                word.substring(0, at) + word[at + 1] + word[at] + word.substring(at + 2)
            }

            1 -> TypoCorpus.neighbourOf(word[at])?.let {
                word.substring(0, at) + it + word.substring(at + 1)
            }

            2 -> word.substring(0, at) + word[at] + word.substring(at)
            else -> word.substring(0, at) + word.substring(at + 1)
        }
    }
}
