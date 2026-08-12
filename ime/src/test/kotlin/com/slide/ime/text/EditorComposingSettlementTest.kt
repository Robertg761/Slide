package com.slide.ime.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorComposingSettlementTest {
    @Test
    fun `rejected finish and commit leave a typed word unsettled`() {
        val calls = mutableListOf<String>()

        val result = EditorComposingSettlement.finish(
            typed = "word",
            correction = null,
            finish = {
                calls += "finish"
                false
            },
            commit = {
                calls += "commit:$it"
                false
            },
        )

        assertFalse(result.settled)
        assertFalse(result.corrected)
        assertFalse(result.learnTypedWord)
        assertFalse(result.learnAppliedPair)
        assertFalse(result.callbackPossible)
        assertEquals("word", result.appliedText)
        assertEquals(listOf("finish", "commit:word"), calls)
    }

    @Test
    fun `an unchallenged settled typed word remains eligible for learning`() {
        val result = EditorComposingSettlement.finish(
            typed = "newword",
            correction = null,
            finish = { true },
            commit = { false },
        )

        assertTrue(result.settled)
        assertFalse(result.corrected)
        assertTrue(result.learnTypedWord)
        assertTrue(result.learnAppliedPair)
        assertEquals("newword", result.appliedText)
    }

    @Test
    fun `a rejected atomic correction safely settles the unchanged typed word`() {
        val calls = mutableListOf<String>()

        val result = EditorComposingSettlement.finish(
            typed = "teh",
            correction = "the",
            finish = {
                calls += "finish"
                true
            },
            commit = {
                calls += "commit:$it"
                false
            },
        )

        assertTrue(result.settled)
        assertFalse(result.corrected)
        assertFalse(result.learnTypedWord)
        assertFalse(result.learnAppliedPair)
        assertEquals("teh", result.appliedText)
        assertEquals(listOf("commit:the", "finish"), calls)
    }

    @Test
    fun `a correction is reported only after its atomic commit is accepted`() {
        var finishCalls = 0

        val result = EditorComposingSettlement.finish(
            typed = "teh",
            correction = "the",
            finish = {
                finishCalls++
                false
            },
            commit = { it == "the" },
        )

        assertTrue(result.settled)
        assertTrue(result.corrected)
        assertFalse(result.learnTypedWord)
        assertTrue(result.learnAppliedPair)
        assertEquals("the", result.appliedText)
        assertEquals(0, finishCalls)
    }

    @Test
    fun `mid-word abandon remains unsettled when both editor operations reject`() {
        val result = EditorComposingSettlement.abandon(
            typed = "reopened",
            finish = { false },
            commit = { false },
        )

        assertFalse(result.settled)
        assertFalse(result.callbackPossible)
    }

    @Test
    fun `suggestion replacement remains tracked when both settlement operations reject`() {
        val calls = mutableListOf<String>()

        val result = EditorComposingSettlement.commitSuggestion(
            replacement = "hello",
            setComposing = {
                calls += "set:$it"
                true
            },
            finish = {
                calls += "finish"
                false
            },
            commit = {
                calls += "commit:$it"
                false
            },
        )

        assertFalse(result.settled)
        assertTrue(result.replacementApplied)
        assertTrue(result.callbackPossible)
        assertEquals(listOf("set:hello", "finish", "commit:hello"), calls)
    }

    @Test
    fun `suggestion can atomically commit when setComposing is rejected`() {
        var finishCalls = 0

        val result = EditorComposingSettlement.commitSuggestion(
            replacement = "hello",
            setComposing = { false },
            finish = {
                finishCalls++
                false
            },
            commit = { true },
        )

        assertTrue(result.settled)
        assertFalse(result.replacementApplied)
        assertTrue(result.callbackPossible)
        assertEquals(0, finishCalls)
    }
}
