package com.slide.engine

import com.slide.engine.lexicon.Lexicon
import com.slide.engine.lexicon.LexiconLoader
import java.io.File

/**
 * The real 160k-word asset, loaded once for the whole test run.
 *
 * Parsing it costs a noticeable fraction of a second, and every suite that needs words needs all of
 * them — a hand-written stub would not exercise the frequency ranking or the bucket structure that
 * the decoder and the corrector both lean on.
 */
object TestLexicon {
    val instance: Lexicon by lazy {
        File("src/main/assets/${LexiconLoader.ASSET_NAME}").inputStream().use(LexiconLoader::read)
    }
}
