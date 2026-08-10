package com.slide.engine.gesture

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slide.engine.lexicon.BigramLoader
import com.slide.engine.lexicon.LexiconLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeuralGestureDecoderInstrumentedTest {
    @Test
    fun loadsModelsAndDecodesPublishedComputerTrace() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val lexicon = requireNotNull(LexiconLoader.load(context))
        val bigrams = BigramLoader.load(context, lexicon)
        val decoder = NeuralGestureDecoder.createOrNull(context, lexicon, bigrams, null)
        assertNotNull("Neural models did not load", decoder)

        val started = System.nanoTime()
        val candidates = requireNotNull(decoder).use {
            it.decode(
                NeuralSwipeHealthCheck.points,
                NeuralSwipeHealthCheck.keys,
                blockOffensive = true,
                previousWord = null,
            )
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        println("Neural swipe candidates=$candidates latencyMs=${"%.2f".format(elapsedMs)}")

        assertEquals(
            NeuralSwipeHealthCheck.expectedWord,
            candidates.firstOrNull()?.word?.lowercase(),
        )
    }
}
