package com.slide.engine.lexicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserBigramsTest {

    @Test
    fun `replacing a suggestion removes the wrong observation`() {
        val pairs = UserBigrams(trustThreshold = 1)
        pairs.learn("over", "their")

        pairs.unlearn("over", "their")
        pairs.learn("over", "there")

        assertEquals(0f, pairs.score("over", "their"), 0f)
        assertTrue(pairs.score("over", "there") > 0f)
    }

    @Test
    fun `forgetting a word removes phrases on both sides but preserves unrelated phrases`() {
        val pairs = UserBigrams()
        repeat(5) {
            pairs.learn("Sam", "Whitmore")
            pairs.learn("Whitmore", "called")
            pairs.learn("kubectl", "apply")
        }

        pairs.forget("WHITMORE")

        assertTrue(pairs.successorsOf("Sam").isEmpty())
        assertTrue(pairs.successorsOf("Whitmore").isEmpty())
        assertEquals(0f, pairs.score("Sam", "Whitmore"), 0f)
        assertEquals(5, pairs.successorsOf("kubectl")["apply"])
    }
}
