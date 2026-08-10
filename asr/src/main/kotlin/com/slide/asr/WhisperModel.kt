package com.slide.asr

/**
 * The speech model packaged with the app.
 *
 * It is English-only and quantised to q5_1. English-only halves the model for no loss on the
 * one language Slide supports, and q5_1 is roughly a third the size of the float weights for a
 * word error rate difference that does not survive contact with a phone microphone.
 *
 * Base English was selected after the physical-device benchmark: it is quick enough for keyboard
 * dictation and avoids adding another 181 MiB model to every install and update.
 */
enum class WhisperModel(
    val assetName: String,
    val label: String,
    val description: String,
) {
    BaseEn(
        assetName = "ggml-base.en-q5_1.bin",
        label = "Base",
        description = "Fastest. Good for everyday dictation.",
    ),
    ;

    companion object {
        /**
         * Measured on a Galaxy S24 Ultra (Snapdragon 8 Gen 3), four threads, 11 seconds of audio:
         * Base decoded 11 seconds of audio in 1.7 seconds on the measured Galaxy S24 Ultra.
         *
         * Six times faster than real time is the difference between a pause and a wait. Shipping one
         * model also makes installs and security updates materially smaller.
         */
        val Default = BaseEn

        fun fromId(id: String?): WhisperModel = entries.firstOrNull { it.name == id } ?: Default
    }
}
