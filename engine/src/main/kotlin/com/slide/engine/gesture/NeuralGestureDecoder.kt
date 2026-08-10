package com.slide.engine.gesture

import android.content.Context
import android.util.Log
import com.slide.engine.lexicon.Bigrams
import com.slide.engine.lexicon.Lexicon
import com.slide.engine.lexicon.Trigrams
import com.slide.engine.lexicon.UserBigrams
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

/** On-device neural spatial decoding followed by Slide's trie-constrained CTC beam search. */
class NeuralGestureDecoder private constructor(
    private val encoder: Module,
    private val decoder: Module,
    lexicon: Lexicon,
    bigrams: Bigrams?,
    userBigrams: UserBigrams?,
    trie: SwipeLexiconTrie,
    trigrams: Trigrams?,
    private val fallback: GestureDecodingEngine?,
) : GestureDecodingEngine, AutoCloseable {
    private val beamSearch = CtcSwipeBeamSearch(lexicon, bigrams, userBigrams, trie, trigrams)
    private val failover = GestureDecodeFailover(fallback)
    private var closed = false

    @Synchronized
    override fun decode(
        points: List<GesturePoint>,
        keys: GestureKeyMap,
        blockOffensive: Boolean,
        previousWord: String?,
        previousPreviousWord: String?,
    ): List<GestureCandidate> {
        return failover.decode(
            points = points,
            keys = keys,
            blockOffensive = blockOffensive,
            previousWord = previousWord,
            previousPreviousWord = previousPreviousWord,
        ) {
            decodeNeural(
                points,
                keys,
                blockOffensive,
                previousWord,
                previousPreviousWord,
            )
        }
    }

    private fun decodeNeural(
        points: List<GesturePoint>,
        keys: GestureKeyMap,
        blockOffensive: Boolean,
        previousWord: String?,
        previousPreviousWord: String?,
    ): List<GestureCandidate> {
        val input = NeuralSwipePreprocessor.prepare(points, keys) ?: return emptyList()
        val encoderOutput = encoder.forward(
            EValue.from(Tensor.fromBlob(input.features, longArrayOf(1, 2, INPUT_POINTS.toLong()))),
            EValue.from(Tensor.fromBlob(input.layoutKeys, longArrayOf(1, MAX_KEYS.toLong(), 2))),
            EValue.from(Tensor.fromBlob(input.layoutMask, longArrayOf(1, MAX_KEYS.toLong()))),
        )
        check(encoderOutput.size == 3) { "Swipe encoder returned ${encoderOutput.size} outputs" }

        val emissions = encoderOutput[0].toTensor().getDataAsFloatArray()
        val coefficients = encoderOutput[1].toTensor().getDataAsFloatArray()
        val intention = encoderOutput[2].toTensor().getDataAsFloatArray()
        check(emissions.size == TIME_STEPS * (MAX_KEYS + 1)) { "Unexpected encoder emissions" }
        check(coefficients.size == TIME_STEPS * COEFFICIENTS) { "Unexpected encoder coefficients" }
        check(intention.size == TIME_STEPS) { "Unexpected encoder intention gate" }

        val decoderInput = FloatArray(TIME_STEPS * DECODER_INPUT)
        for (time in 0 until TIME_STEPS) {
            val destination = time * DECODER_INPUT
            val emissionSource = time * (MAX_KEYS + 1)
            emissions.copyInto(
                decoderInput,
                destination,
                emissionSource,
                emissionSource + LETTERS,
            )
            decoderInput[destination + LETTERS] = emissions[emissionSource + MAX_KEYS]
            coefficients.copyInto(
                decoderInput,
                destination + LETTERS + 1,
                time * COEFFICIENTS,
                (time + 1) * COEFFICIENTS,
            )
            decoderInput[destination + DECODER_INPUT - 1] = intention[time]
        }

        val refined = decoder.forward(
            EValue.from(
                Tensor.fromBlob(
                    decoderInput,
                    longArrayOf(1, TIME_STEPS.toLong(), DECODER_INPUT.toLong()),
                ),
            ),
        )
        check(refined.size == 1) { "Swipe decoder returned ${refined.size} outputs" }
        val logProbabilities = refined[0].toTensor().getDataAsFloatArray()
        check(logProbabilities.size == TIME_STEPS * CTC_CLASSES) { "Unexpected decoder output" }
        return beamSearch.decode(
            logProbabilities,
            blockOffensive,
            previousWord,
            previousPreviousWord,
        )
    }

    private fun passesHealthCheck(): Boolean =
        decodeNeural(
            points = NeuralSwipeHealthCheck.points,
            keys = NeuralSwipeHealthCheck.keys,
            blockOffensive = true,
            previousWord = null,
            previousPreviousWord = null,
        ).firstOrNull()?.word?.equals(NeuralSwipeHealthCheck.expectedWord, ignoreCase = true) == true

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        failover.disablePrimary()
        decoder.destroy()
        encoder.destroy()
    }

    companion object {
        private const val INPUT_POINTS = 64
        private const val MAX_KEYS = 64
        private const val TIME_STEPS = 32
        private const val LETTERS = 26
        private const val CTC_CLASSES = 27
        private const val COEFFICIENTS = 64
        private const val DECODER_INPUT = CTC_CLASSES + COEFFICIENTS + 1

        private const val ENCODER_SHA =
            "725242bab5d14345e96ff214e8de2bfbc1f962c232d320df9c24cb82ffd1fbaf"
        private const val DECODER_SHA =
            "01eaf16ac4bc0f1ed0698c240807f0e95e6d427bcf6de04983ffc50736744d85"

        /** Returns null when the native runtime, verified assets, or known-trace health check fails. */
        fun createOrNull(
            context: Context,
            lexicon: Lexicon,
            bigrams: Bigrams?,
            userBigrams: UserBigrams?,
            trie: SwipeLexiconTrie = SwipeLexiconTrie(lexicon),
            trigrams: Trigrams? = null,
            fallback: GestureDecodingEngine? = null,
        ): NeuralGestureDecoder? =
            runCatching {
                val encoderFile = SwipeModelStore.materialize(context, "encoder.pte", ENCODER_SHA)
                val decoderFile = SwipeModelStore.materialize(context, "decoder.pte", DECODER_SHA)
                val encoder = Module.load(encoderFile.absolutePath, Module.LOAD_MODE_MMAP)
                try {
                    val decoder = Module.load(decoderFile.absolutePath, Module.LOAD_MODE_MMAP)
                    try {
                        val loaded = NeuralGestureDecoder(
                            encoder = encoder,
                            decoder = decoder,
                            lexicon = lexicon,
                            bigrams = bigrams,
                            userBigrams = userBigrams,
                            trie = trie,
                            trigrams = trigrams,
                            fallback = fallback,
                        )
                        check(loaded.passesHealthCheck()) {
                            "Packaged swipe model failed its known-trace health check"
                        }
                        loaded
                    } catch (failure: Throwable) {
                        decoder.destroy()
                        throw failure
                    }
                } catch (failure: Throwable) {
                    encoder.destroy()
                    throw failure
                }
            }.onFailure { failure ->
                Log.w(TAG, "Neural swipe unavailable; deterministic decoder will be used", failure)
            }.getOrNull()

        private const val TAG = "SlideSwipe"
    }
}

/**
 * Makes the packaged model an accuracy upgrade, never a single point of failure.
 *
 * An empty or structurally implausible result is a normal search miss rather than proof that the
 * runtime is broken, so it falls back for only that trace. A runtime exception disables native
 * inference for the rest of the process; repeatedly paying for the same broken model call would
 * make every swipe stall.
 */
internal class GestureDecodeFailover(
    private val fallback: GestureDecodingEngine?,
) {
    private var primaryAvailable = true

    fun decode(
        points: List<GesturePoint>,
        keys: GestureKeyMap,
        blockOffensive: Boolean,
        previousWord: String?,
        previousPreviousWord: String?,
        primary: () -> List<GestureCandidate>,
    ): List<GestureCandidate> {
        if (primaryAvailable) {
            try {
                usable(primary()).takeIf(List<GestureCandidate>::isNotEmpty)?.let { return it }
            } catch (_: RuntimeException) {
                primaryAvailable = false
            }
        }
        return usable(
            fallback
                ?.decode(points, keys, blockOffensive, previousWord, previousPreviousWord)
                .orEmpty(),
        )
    }

    fun disablePrimary() {
        primaryAvailable = false
    }

    private fun usable(candidates: List<GestureCandidate>): List<GestureCandidate> =
        candidates.filter { candidate ->
            // One-letter words are taps, not glides. The neural trie can otherwise accept a long
            // h-e-l-l-o trace as "h" and suppress the deterministic decoder's valid recovery.
            candidate.word.count(Char::isLetter) >= MIN_GESTURE_WORD_LETTERS &&
                candidate.score.isFinite()
        }

    private companion object {
        const val MIN_GESTURE_WORD_LETTERS = 2
    }
}
