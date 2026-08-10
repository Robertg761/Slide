package com.slide.ime.text

import android.text.InputType

/**
 * What language-aware input is safe and useful for an editor.
 *
 * InputType is only a bit field, so keeping its interpretation here makes the service's typing,
 * swipe, prediction, and learning paths share one policy. In particular, an email or URI field
 * must not be corrected merely because it is technically TYPE_CLASS_TEXT.
 */
internal data class EditorInputPolicy(
    val allowsSuggestions: Boolean,
    val allowsPersonalizedLearning: Boolean,
    val isPassword: Boolean,
    val allowsVoice: Boolean,
    val keyboardMode: EditorKeyboardMode,
) {
    companion object {
        val NaturalText = EditorInputPolicy(
            allowsSuggestions = true,
            allowsPersonalizedLearning = true,
            isPassword = false,
            allowsVoice = true,
            keyboardMode = EditorKeyboardMode.TEXT,
        )

        fun from(inputType: Int): EditorInputPolicy {
            val inputClass = inputType and InputType.TYPE_MASK_CLASS
            if (inputClass == InputType.TYPE_CLASS_NUMBER) {
                val variation = inputType and InputType.TYPE_MASK_VARIATION
                if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return NumberPassword
                val signed = (inputType and InputType.TYPE_NUMBER_FLAG_SIGNED) != 0
                val decimal = (inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0
                return suppressed(
                    when {
                        signed && decimal -> EditorKeyboardMode.SIGNED_DECIMAL_NUMBER
                        signed -> EditorKeyboardMode.SIGNED_NUMBER
                        decimal -> EditorKeyboardMode.DECIMAL_NUMBER
                        else -> EditorKeyboardMode.NUMBER
                    },
                )
            }
            if (inputClass == InputType.TYPE_CLASS_PHONE) return suppressed(EditorKeyboardMode.PHONE)
            if (inputClass == InputType.TYPE_CLASS_DATETIME) {
                val mode = when (inputType and InputType.TYPE_MASK_VARIATION) {
                    InputType.TYPE_DATETIME_VARIATION_DATE -> EditorKeyboardMode.DATE
                    InputType.TYPE_DATETIME_VARIATION_TIME -> EditorKeyboardMode.TIME
                    else -> EditorKeyboardMode.DATETIME
                }
                return suppressed(mode)
            }
            if (inputClass != InputType.TYPE_CLASS_TEXT) {
                return suppressed(EditorKeyboardMode.TEXT, allowsVoice = false)
            }

            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val password = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            if (password) return Password

            val editorRejectsCandidates =
                (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0 ||
                    (inputType and InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE) != 0
            val nonNaturalVariation = variation == InputType.TYPE_TEXT_VARIATION_URI ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_FILTER ||
                variation == InputType.TYPE_TEXT_VARIATION_PHONETIC

            val mode = when (variation) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> EditorKeyboardMode.EMAIL
                InputType.TYPE_TEXT_VARIATION_URI -> EditorKeyboardMode.URI
                else -> EditorKeyboardMode.TEXT
            }

            return if (editorRejectsCandidates || nonNaturalVariation) suppressed(mode) else NaturalText
        }

        private val Password = EditorInputPolicy(
            allowsSuggestions = false,
            allowsPersonalizedLearning = false,
            isPassword = true,
            allowsVoice = false,
            keyboardMode = EditorKeyboardMode.TEXT,
        )

        private val NumberPassword = Password.copy(keyboardMode = EditorKeyboardMode.PIN)

        private fun suppressed(
            mode: EditorKeyboardMode,
            allowsVoice: Boolean = mode == EditorKeyboardMode.TEXT,
        ) = EditorInputPolicy(
            allowsSuggestions = false,
            allowsPersonalizedLearning = false,
            isPassword = false,
            allowsVoice = allowsVoice,
            keyboardMode = mode,
        )
    }
}

internal enum class EditorKeyboardMode {
    TEXT,
    EMAIL,
    URI,
    NUMBER,
    SIGNED_NUMBER,
    DECIMAL_NUMBER,
    SIGNED_DECIMAL_NUMBER,
    PIN,
    PHONE,
    DATE,
    TIME,
    DATETIME,
}
