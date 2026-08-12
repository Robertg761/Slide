package com.slide.ime.text

import android.text.InputType
import org.junit.Assert.assertEquals
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
            assertTrue(policy.allowsVoice)
            assertFalse(policy.usesRawKeyEvents)
            assertEquals(EditorKeyboardMode.TEXT, policy.keyboardMode)
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
            assertFalse(policy.allowsVoice)
        }
        assertEquals(EditorKeyboardMode.PIN, EditorInputPolicy.from(inputs.last()).keyboardMode)
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
        assertEquals(EditorKeyboardMode.EMAIL, EditorInputPolicy.from(inputs[0]).keyboardMode)
        assertEquals(EditorKeyboardMode.EMAIL, EditorInputPolicy.from(inputs[1]).keyboardMode)
        assertEquals(EditorKeyboardMode.URI, EditorInputPolicy.from(inputs[2]).keyboardMode)
        assertFalse(EditorInputPolicy.from(inputs[0]).allowsVoice)
    }

    @Test
    fun `non-text input classes never enter the language pipeline`() {
        for (inputClass in listOf(
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
            InputType.TYPE_NULL,
        )) {
            val policy = EditorInputPolicy.from(inputClass)
            assertFalse(policy.allowsSuggestions)
            assertFalse(policy.allowsVoice)
        }
    }

    @Test
    fun `TYPE_NULL uses raw hardware-style key events`() {
        val policy = EditorInputPolicy.from(InputType.TYPE_NULL)

        assertTrue(policy.usesRawKeyEvents)
        assertFalse(policy.allowsSuggestions)
        assertFalse(policy.allowsPersonalizedLearning)
        assertFalse(policy.allowsVoice)
        assertEquals(EditorKeyboardMode.TEXT, policy.keyboardMode)
    }

    @Test
    fun `number flags choose pads with only the requested affordances`() {
        val plain = EditorInputPolicy.from(InputType.TYPE_CLASS_NUMBER)
        val signed = EditorInputPolicy.from(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)
        val decimal = EditorInputPolicy.from(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val both = EditorInputPolicy.from(
            InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_SIGNED or
                InputType.TYPE_NUMBER_FLAG_DECIMAL,
        )

        assertEquals(EditorKeyboardMode.NUMBER, plain.keyboardMode)
        assertEquals(EditorKeyboardMode.SIGNED_NUMBER, signed.keyboardMode)
        assertEquals(EditorKeyboardMode.DECIMAL_NUMBER, decimal.keyboardMode)
        assertEquals(EditorKeyboardMode.SIGNED_DECIMAL_NUMBER, both.keyboardMode)
        for (policy in listOf(plain, signed, decimal, both)) {
            assertFalse(policy.allowsVoice)
            assertFalse(policy.allowsSuggestions)
        }
    }

    @Test
    fun `phone and datetime classes receive dedicated non-language modes`() {
        assertEquals(EditorKeyboardMode.PHONE, EditorInputPolicy.from(InputType.TYPE_CLASS_PHONE).keyboardMode)
        assertEquals(EditorKeyboardMode.DATETIME, EditorInputPolicy.from(InputType.TYPE_CLASS_DATETIME).keyboardMode)
        assertEquals(
            EditorKeyboardMode.DATE,
            EditorInputPolicy.from(
                InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE,
            ).keyboardMode,
        )
        assertEquals(
            EditorKeyboardMode.TIME,
            EditorInputPolicy.from(
                InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME,
            ).keyboardMode,
        )
    }
}
