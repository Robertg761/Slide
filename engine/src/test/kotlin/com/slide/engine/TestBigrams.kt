package com.slide.engine

import com.slide.engine.lexicon.BigramLoader
import com.slide.engine.lexicon.Bigrams
import com.slide.engine.lexicon.Trigrams
import com.slide.engine.lexicon.TrigramLoader
import java.io.File

/** The real bigram asset, loaded once for the whole test run. */
object TestBigrams {
    val instance: Bigrams by lazy {
        File("src/main/assets/${BigramLoader.ASSET_NAME}").inputStream().use {
            BigramLoader.read(it, TestLexicon.instance)
        }
    }
}

/** The real two-word-context asset, loaded once for the whole test run. */
object TestTrigrams {
    val instance: Trigrams by lazy {
        File("src/main/assets/${TrigramLoader.ASSET_NAME}").inputStream().use {
            TrigramLoader.read(it, TestLexicon.instance)
        }
    }
}

/**
 * Sentences the bigram model was never trained on.
 *
 * `tools/build_bigrams.py` holds a tenth of the corpus back by sentence id and writes a sample of
 * it here. Measuring on sentences the model has seen would mostly measure how well it memorised
 * them, which is not a question anyone types a message wanting answered.
 */
object HeldOutSentences {
    val instance: List<String> by lazy {
        File("src/test/resources/heldout_en.txt").readLines().filter { it.isNotBlank() }
    }
}
