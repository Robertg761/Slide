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
) {
    companion object {
        val NaturalText = EditorInputPolicy(
            allowsSuggestions = true,
            allowsPersonalizedLearning = true,
            isPassword = false,
        )

        fun from(inputType: Int): EditorInputPolicy {
            val inputClass = inputType and InputType.TYPE_MASK_CLASS
            if (inputClass == InputType.TYPE_CLASS_NUMBER) {
                val variation = inputType and InputType.TYPE_MASK_VARIATION
                return if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                    Password
                } else {
                    Suppressed
                }
            }
            if (inputClass != InputType.TYPE_CLASS_TEXT) return Suppressed

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

            return if (editorRejectsCandidates || nonNaturalVariation) Suppressed else NaturalText
        }

        private val Password = EditorInputPolicy(
            allowsSuggestions = false,
            allowsPersonalizedLearning = false,
            isPassword = true,
        )

        private val Suppressed = EditorInputPolicy(
            allowsSuggestions = false,
            allowsPersonalizedLearning = false,
            isPassword = false,
        )
    }
}
