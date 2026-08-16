package com.slide.asr

/**
 * The speech model packaged with the app.
 *
 * It is English-only and quantised to q5_1. English-only halves the model for no loss on the
 * one language Slide supports, and q5_1 is roughly a third the size of the float weights for a
 * word error rate difference that does not survive contact with a phone microphone.
 *
 * Small English is the smallest packaged model that held up for conversational dictation. The
 * faster Base model remains useful for clean speech, but real microphone reports showed that its
 * error rate is not good enough to be the keyboard's only recognizer.
 */
enum class WhisperModel(
    val assetName: String,
    val label: String,
    val description: String,
) {
    SmallEn(
        assetName = "ggml-small.en-q5_1.bin",
        label = "Small English",
        description = "Higher-accuracy offline dictation.",
    ),
    ;

    companion object {
        /**
         * Measured on a Galaxy S24 Ultra (Snapdragon 8 Gen 3), four threads, 11 seconds of audio:
         * Small formerly decoded 11 seconds of audio in 7.4 seconds through the portable ARM
         * backend. Runtime CPU selection now gives it access to the fastest compatible phone
         * kernel. A fresh physical-device timing is still required.
         */
        val Default = SmallEn

        fun fromId(id: String?): WhisperModel = entries.firstOrNull { it.name == id } ?: Default
    }
}
