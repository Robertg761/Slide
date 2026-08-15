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
    const val KEY_MODEL = "model"
    const val KEY_SESSION_ID = "session_id"

    /** Zero is never allocated by the client and therefore means "no voice session". */
    const val NO_SESSION_ID = 0L

    /** Microphone level arrives as an int, since [android.os.Message.arg1] is the cheap field. */
    const val LEVEL_SCALE = 1000

    /**
     * Why a voice session ended without a transcript.
     *
     * The speech process ships a code, not a sentence: the keyboard side owns the words shown to
     * the user, which keeps them in string resources where they can be localized and tested.
     * [MSG_ERROR] carries the ordinal in [android.os.Message.arg1].
     */
    enum class Error {
        /** A start arrived while the previous session was still shutting down. */
        StillClosing,

        /** The microphone permission is missing. */
        PermissionMissing,

        /** The speech model asset could not be loaded. */
        ModelUnavailable,

        /** The microphone could not be opened. */
        MicUnavailable,

        /** Audio capture stopped unexpectedly mid-recording. */
        MicStopped,

        /** Whisper failed to produce a transcript. */
        DecodeFailed,

        /** The speech service could not be bound at all. */
        ServiceUnavailable,

        /** The speech process died while a session was active. */
        ProcessDied,
        ;

        companion object {
            fun fromOrdinal(value: Int): Error = entries.getOrElse(value) { DecodeFailed }
        }
    }

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
