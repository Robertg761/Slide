package com.slide.asr

/** The wire protocol between the keyboard and the speech process. */
object VoiceInput {

    // Keyboard to service.
    const val MSG_START = 1
    const val MSG_STOP = 2
    const val MSG_CANCEL = 3

    // Service to keyboard.
    const val MSG_STATE = 10
    const val MSG_LEVEL = 11
    const val MSG_RESULT = 12
    const val MSG_ERROR = 13

    const val KEY_TEXT = "text"
    const val KEY_REASON = "reason"
    const val KEY_MODEL = "model"

    /** Microphone level arrives as an int, since [android.os.Message.arg1] is the cheap field. */
    const val LEVEL_SCALE = 1000

    /** What the speech process is doing, so the keyboard can say so. */
    enum class State {
        /** Nothing loaded, nothing recording. */
        Idle,

        /** Reading the model into memory. Only happens on the first use after a cold start. */
        Preparing,

        /** Recording. */
        Listening,

        /** Recording stopped, whisper is decoding. */
        Transcribing,
        ;

        companion object {
            fun fromOrdinal(value: Int): State = entries.getOrElse(value) { Idle }
        }
    }
}
