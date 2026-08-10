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

    @Test
    fun decodesDonatedThatsTraceAsCommonContraction() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val lexicon = requireNotNull(LexiconLoader.load(context))
        val bigrams = BigramLoader.load(context, lexicon)
        val decoder = requireNotNull(
            NeuralGestureDecoder.createOrNull(context, lexicon, bigrams, null),
        ) { "Neural models did not load" }

        val candidates = decoder.use {
            it.decode(
                DonatedThatsTrace.points,
                NeuralSwipeHealthCheck.keys,
                blockOffensive = true,
                previousWord = "considering",
            )
        }

        assertEquals(
            "Candidates were $candidates",
            "that's",
            candidates.firstOrNull()?.word?.lowercase(),
        )
    }
}

/**
 * Real finger trace 63997 from the MIT-licensed FUTO swipe test set.
 *
 * The source word omits punctuation, as swipe keyboards do geometrically; the language model must
 * restore the common contraction instead of promoting an unrelated proper name.
 * https://huggingface.co/datasets/futo-org/swipe.futo.org
 */
private object DonatedThatsTrace {
    val points: List<GesturePoint> by lazy {
        List(X.size) { index -> GesturePoint(X[index], Y[index], TIME[index]) }
    }

    private val X = floatArrayOf(
        0.435484f, 0.448925f, 0.454301f, 0.465054f, 0.475806f, 0.483871f, 0.5f, 0.516129f,
        0.521505f, 0.534946f, 0.545699f, 0.553763f, 0.55914f, 0.564516f, 0.569892f, 0.572581f,
        0.572581f, 0.575269f, 0.575269f, 0.575269f, 0.575269f, 0.575269f, 0.572581f, 0.564516f,
        0.551075f, 0.534946f, 0.516129f, 0.491935f, 0.465054f, 0.438172f, 0.405914f, 0.376344f,
        0.349462f, 0.322581f, 0.295699f, 0.27957f, 0.263441f, 0.252688f, 0.244624f, 0.236559f,
        0.228495f, 0.223118f, 0.217742f, 0.212366f, 0.206989f, 0.198925f, 0.193548f, 0.188172f,
        0.182796f, 0.177419f, 0.174731f, 0.174731f, 0.169355f, 0.166667f, 0.166667f, 0.166667f,
        0.166667f, 0.19086f, 0.231183f, 0.241935f, 0.266129f, 0.287634f, 0.317204f, 0.341398f,
        0.370968f, 0.397849f, 0.422043f, 0.443548f, 0.465054f, 0.478495f, 0.491935f, 0.5f,
        0.508065f, 0.513441f, 0.516129f, 0.516129f, 0.448925f, 0.435484f, 0.405914f, 0.38172f,
        0.362903f, 0.344086f, 0.327957f, 0.314516f, 0.303763f, 0.293011f, 0.284946f, 0.27957f,
        0.276882f, 0.271505f, 0.268817f, 0.263441f, 0.260753f, 0.255376f, 0.252688f, 0.247312f,
        0.244624f, 0.241935f, 0.241935f, 0.241935f,
    )

    private val Y = floatArrayOf(
        0.145833f, 0.192782f, 0.210387f, 0.245599f, 0.286678f, 0.304284f, 0.339495f, 0.380575f,
        0.398181f, 0.427523f, 0.450998f, 0.474472f, 0.503815f, 0.52142f, 0.539026f, 0.5625f,
        0.574237f, 0.585974f, 0.597711f, 0.60358f, 0.609448f, 0.615317f, 0.615317f, 0.627054f,
        0.632923f, 0.632923f, 0.632923f, 0.627054f, 0.621185f, 0.609448f, 0.597711f, 0.580106f,
        0.568369f, 0.556631f, 0.539026f, 0.533157f, 0.527289f, 0.527289f, 0.527289f, 0.527289f,
        0.527289f, 0.533157f, 0.533157f, 0.533157f, 0.533157f, 0.533157f, 0.527289f, 0.527289f,
        0.52142f, 0.52142f, 0.52142f, 0.515552f, 0.515552f, 0.515552f, 0.515552f, 0.515552f,
        0.515552f, 0.468603f, 0.392312f, 0.380575f, 0.351232f, 0.32189f, 0.298416f, 0.274941f,
        0.251467f, 0.23973f, 0.227993f, 0.216256f, 0.210387f, 0.204519f, 0.19865f, 0.19865f,
        0.192782f, 0.192782f, 0.192782f, 0.19865f, 0.245599f, 0.257336f, 0.28081f, 0.310153f,
        0.333627f, 0.362969f, 0.392312f, 0.409918f, 0.439261f, 0.468603f, 0.486209f, 0.492077f,
        0.509683f, 0.52142f, 0.527289f, 0.533157f, 0.539026f, 0.550763f, 0.556631f, 0.5625f,
        0.5625f, 0.568369f, 0.568369f, 0.568369f,
    )

    private val TIME = longArrayOf(
        0L, 58L, 66L, 75L, 84L, 89L, 98L, 108L, 114L, 124L, 130L, 141L,
        148L, 156L, 164L, 172L, 181L, 190L, 199L, 207L, 217L, 223L, 233L, 257L,
        268L, 273L, 284L, 293L, 300L, 309L, 316L, 324L, 334L, 342L, 350L, 357L,
        367L, 376L, 382L, 392L, 401L, 408L, 416L, 422L, 434L, 441L, 449L, 458L,
        464L, 475L, 482L, 491L, 501L, 508L, 515L, 524L, 591L, 599L, 609L, 616L,
        624L, 632L, 642L, 651L, 658L, 666L, 676L, 683L, 693L, 700L, 708L, 718L,
        724L, 732L, 742L, 750L, 809L, 817L, 826L, 834L, 844L, 851L, 859L, 868L,
        876L, 885L, 893L, 902L, 910L, 916L, 924L, 933L, 943L, 950L, 957L, 968L,
        975L, 984L, 992L, 1001L,
    )
}
