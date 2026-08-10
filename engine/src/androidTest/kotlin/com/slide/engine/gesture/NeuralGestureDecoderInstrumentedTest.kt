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
            it.decode(COMPUTER_TRACE, qwerty(), blockOffensive = true, previousWord = null)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        println("Neural swipe candidates=$candidates latencyMs=${"%.2f".format(elapsedMs)}")

        assertEquals("computer", candidates.firstOrNull()?.word?.lowercase())
    }

    private fun qwerty(): GestureKeyMap {
        val centers = mapOf(
            'a' to (0.10f to 0.500f), 'b' to (0.60f to 0.833f),
            'c' to (0.40f to 0.833f), 'd' to (0.30f to 0.500f),
            'e' to (0.25f to 0.167f), 'f' to (0.40f to 0.500f),
            'g' to (0.50f to 0.500f), 'h' to (0.60f to 0.500f),
            'i' to (0.75f to 0.167f), 'j' to (0.70f to 0.500f),
            'k' to (0.80f to 0.500f), 'l' to (0.90f to 0.500f),
            'm' to (0.80f to 0.833f), 'n' to (0.70f to 0.833f),
            'o' to (0.85f to 0.167f), 'p' to (0.95f to 0.167f),
            'q' to (0.05f to 0.167f), 'r' to (0.35f to 0.167f),
            's' to (0.20f to 0.500f), 't' to (0.45f to 0.167f),
            'u' to (0.65f to 0.167f), 'v' to (0.50f to 0.833f),
            'w' to (0.15f to 0.167f), 'x' to (0.30f to 0.833f),
            'y' to (0.55f to 0.167f), 'z' to (0.20f to 0.833f),
        )
        val builder = GestureKeyMap.Builder(0.10f, 1f / 3f)
        centers.forEach { (letter, point) -> builder.put(letter, point.first, point.second) }
        return requireNotNull(builder.buildOrNull())
    }

    private companion object {
        val PX = floatArrayOf(
            0.4141f, 0.4478f, 0.5f, 0.5741f, 0.6599f, 0.7256f, 0.7744f, 0.8098f,
            0.8485f, 0.867f, 0.8737f, 0.8653f, 0.8418f, 0.8182f, 0.8098f, 0.7963f,
            0.7946f, 0.8081f, 0.8418f, 0.8704f, 0.9057f, 0.9259f, 0.9545f, 0.9697f,
            0.968f, 0.9529f, 0.9141f, 0.8468f, 0.7811f, 0.7273f, 0.6869f, 0.6616f,
            0.6582f, 0.6431f, 0.6061f, 0.5572f, 0.5067f, 0.4663f, 0.4495f, 0.4461f,
            0.4411f, 0.4192f, 0.3872f, 0.362f, 0.3283f, 0.2795f, 0.2391f, 0.2323f,
            0.2407f, 0.2593f, 0.2879f, 0.3249f, 0.3468f, 0.3569f,
        )
        val PY = floatArrayOf(
            0.8991f, 0.858f, 0.7876f, 0.6702f, 0.5352f, 0.4237f, 0.3357f, 0.2653f,
            0.1655f, 0.142f, 0.142f, 0.2183f, 0.3709f, 0.588f, 0.7347f, 0.8462f,
            0.8697f, 0.811f, 0.6115f, 0.4707f, 0.3122f, 0.2066f, 0.1303f, 0.1068f,
            0.1068f, 0.1068f, 0.1185f, 0.1596f, 0.1772f, 0.1772f, 0.1772f, 0.189f,
            0.189f, 0.189f, 0.1831f, 0.189f, 0.189f, 0.189f, 0.189f, 0.189f,
            0.1831f, 0.1831f, 0.1831f, 0.1831f, 0.1831f, 0.1948f, 0.189f, 0.1948f,
            0.189f, 0.189f, 0.189f, 0.1831f, 0.1831f, 0.1831f,
        )
        val PT = longArrayOf(
            0, 100, 149, 197, 246, 297, 348, 399, 449, 498, 548, 598, 648, 698, 749,
            799, 849, 949, 999, 1047, 1100, 1152, 1197, 1248, 1314, 1364, 1414, 1465,
            1515, 1565, 1614, 1666, 1715, 1851, 1898, 1951, 1998, 2049, 2097, 2165,
            2231, 2279, 2331, 2382, 2431, 2481, 2532, 2584, 2649, 2700, 2751, 2798,
            2848, 2899,
        )
        val COMPUTER_TRACE = List(PX.size) { GesturePoint(PX[it], PY[it], PT[it]) }
    }
}
