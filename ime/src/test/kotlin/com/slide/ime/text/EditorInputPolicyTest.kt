package com.slide.ime.text

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorInputPolicyTest {

    @Test
    fun `ordinary language fields allow suggestions and learning`() {
        for (variation in listOf(
            InputType.TYPE_TEXT_VARIATION_NORMAL,
            InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
        )) {
            val policy = EditorInputPolicy.from(InputType.TYPE_CLASS_TEXT or variation)
            assertTrue(policy.allowsSuggestions)
            assertTrue(policy.allowsPersonalizedLearning)
            assertFalse(policy.isPassword)
        }
    }

    @Test
    fun `password fields suppress candidates and learning`() {
        val inputs = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        for (inputType in inputs) {
            val policy = EditorInputPolicy.from(inputType)
            assertFalse(policy.allowsSuggestions)
            assertFalse(policy.allowsPersonalizedLearning)
            assertTrue(policy.isPassword)
        }
    }

    @Test
    fun `email uri and explicit no-suggestion fields are treated as non-language`() {
        val inputs = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE,
        )

        for (inputType in inputs) {
            val policy = EditorInputPolicy.from(inputType)
            assertFalse(policy.allowsSuggestions)
            assertFalse(policy.allowsPersonalizedLearning)
            assertFalse(policy.isPassword)
        }
    }

    @Test
    fun `non-text input classes never enter the language pipeline`() {
        for (inputClass in listOf(
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
            InputType.TYPE_NULL,
        )) {
            assertFalse(EditorInputPolicy.from(inputClass).allowsSuggestions)
        }
    }
}
