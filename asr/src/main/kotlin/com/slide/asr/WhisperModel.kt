package com.slide.asr

/**
 * The speech models packaged with the app.
 *
 * Both are English-only and quantised to q5_1. English-only halves the model for no loss on the
 * one language Slide supports, and q5_1 is roughly a third the size of the float weights for a
 * word error rate difference that does not survive contact with a phone microphone.
 *
 * Which one is the better default is a question about a specific phone, so it is a setting rather
 * than a constant: [BaseEn] answers quickly enough to feel like dictation, [SmallEn] is clearly
 * more accurate on unusual words and accents but takes longer to think.
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
    SmallEn(
        assetName = "ggml-small.en-q5_1.bin",
        label = "Small",
        description = "More accurate on names and unusual words. Slower.",
    ),
    ;

    companion object {
        /**
         * Measured on a Galaxy S24 Ultra (Snapdragon 8 Gen 3), four threads, 11 seconds of audio:
         * Base decodes in 1.7s and Small in 7.4s, loading in 100ms and 136ms.
         *
         * Base it is. Six times faster than real time is the difference between a pause and a wait,
         * and a keyboard that pauses to think is a keyboard people stop using. Small stays offered
         * because at 1.4x real time it is still usable when the words are worth waiting for.
         */
        val Default = BaseEn

        fun fromId(id: String?): WhisperModel = entries.firstOrNull { it.name == id } ?: Default
    }
}
