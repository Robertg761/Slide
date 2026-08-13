package com.slide.engine.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureAdaptationTest {
    private fun model(
        alternativeCapacity: Int = 256,
        rejectionCapacity: Int = 128,
        decayInterval: Long = 64,
    ) = GestureAdaptation(
        initialSalt = ByteArray(16) { it.toByte() },
        alternativeCapacity = alternativeCapacity,
        rejectionCapacity = rejectionCapacity,
        decayInterval = decayInterval,
    )

    @Test
    fun `one explicit alternative fixes the same two-way confusion`() {
        val model = model()
        val original = listOf(
            GestureCandidate("home", 100f),
            GestureCandidate("hem", -50f),
            GestureCandidate("hello", -100f),
        )

        assertTrue(model.observeAlternative("home", "hem"))

        assertEquals(listOf("hem", "home", "hello"), model.rerank(original).map { it.word })
        assertEquals(listOf(3f, 2f, 1f), model.rerank(original).map { it.score })
    }

    @Test
    fun `adaptation is rank based across unrelated decoder score scales`() {
        val model = model()
        model.observeAlternative("there", "three")

        val neural = listOf(GestureCandidate("there", 82f), GestureCandidate("three", 81.9f))
        val fallback = listOf(GestureCandidate("there", -2f), GestureCandidate("three", -900f))

        assertEquals("three", model.rerank(neural).first().word)
        assertEquals("three", model.rerank(fallback).first().word)
    }

    @Test
    fun `unrelated candidate lists retain their exact objects and scores`() {
        val model = model()
        model.observeAlternative("home", "hem")
        val unrelated = listOf(GestureCandidate("world", 4.2f), GestureCandidate("word", 1.1f))

        assertTrue(unrelated === model.rerank(unrelated))
        assertEquals(listOf(4.2f, 1.1f), model.rerank(unrelated).map { it.score })
    }

    @Test
    fun `an isolated undo is conservative but repetition can demote the guess`() {
        val model = model()
        val candidates = listOf(GestureCandidate("form", 5f), GestureCandidate("from", 4f))

        model.observeImmediateUndo("form")
        assertEquals("form", model.rerank(candidates).first().word)

        model.observeImmediateUndo("form")
        assertEquals("from", model.rerank(candidates).first().word)
    }

    @Test
    fun `logical decay removes old preferences without timestamps`() {
        val model = model(decayInterval = 1)
        model.observeAlternative("home", "hem")
        assertEquals("hem", model.rerank(listOf(
            GestureCandidate("home", 2f), GestureCandidate("hem", 1f),
        )).first().word)

        model.observeImmediateUndo("word")
        model.observeImmediateUndo("word")

        assertEquals("home", model.rerank(listOf(
            GestureCandidate("home", 2f), GestureCandidate("hem", 1f),
        )).first().word)
    }

    @Test
    fun `taking and restoring snapshots does not reset partial decay age`() {
        var model = model(decayInterval = 3)
        model.observeAlternative("home", "hem")
        model.observeImmediateUndo("word")
        model = model(decayInterval = 3).also { assertTrue(it.restore(model.snapshot())) }
        model.observeImmediateUndo("words")
        model = model(decayInterval = 3).also { assertTrue(it.restore(model.snapshot())) }
        model.observeImmediateUndo("works")

        assertEquals(
            "home",
            model.rerank(
                listOf(GestureCandidate("home", 2f), GestureCandidate("hem", 1f)),
            ).first().word,
        )
    }

    @Test
    fun `snapshot round trip is deterministic bounded and contains no words`() {
        val model = model(alternativeCapacity = 2, rejectionCapacity = 2)
        model.observeAlternative("home", "hem")
        model.observeAlternative("there", "three")
        model.observeAlternative("form", "from")
        model.observeImmediateUndo("world")
        model.observeImmediateUndo("words")
        model.observeImmediateUndo("works")

        val snapshot = model.snapshot()
        val encoded = snapshot.toString().lowercase()
        for (word in listOf("home", "hem", "there", "three", "form", "from", "world")) {
            assertFalse("snapshot exposed $word", word in encoded)
        }
        assertEquals(2, snapshot.alternatives.size)
        assertEquals(2, snapshot.rejections.size)

        val restored = model()
        assertTrue(restored.restore(snapshot))
        assertEquals(snapshot, restored.snapshot())
    }

    @Test
    fun `invalid snapshot header cannot replace live learning and malformed rows are ignored`() {
        val model = model()
        model.observeAlternative("home", "hem")
        val before = model.snapshot()

        assertFalse(model.restore(before.copy(version = 999)))
        assertEquals(before, model.snapshot())

        val partlyMalformed = before.copy(
            alternatives = before.alternatives + GestureAlternativePreference(1, 1, 99, -1),
            rejections = listOf(GestureRejectionPreference(5, 0, 0)),
        )
        assertTrue(model.restore(partlyMalformed))
        assertEquals(before.alternatives, model.snapshot().alternatives)
        assertTrue(model.snapshot().rejections.isEmpty())
    }

    @Test
    fun `malformed words and non-finite source scores cannot poison the model`() {
        val model = model()
        for (word in listOf("", "a", "two words", "emoji🙂", "x".repeat(80))) {
            assertFalse(model.observeImmediateUndo(word))
            assertFalse(model.observeAlternative("valid", word))
        }

        model.observeAlternative("home", "hem")
        val candidates = listOf(
            GestureCandidate("home", Float.NaN),
            GestureCandidate("hem", Float.POSITIVE_INFINITY),
        )
        assertEquals(listOf("hem", "home"), model.rerank(candidates).map { it.word })
        assertTrue(model.rerank(candidates).all { it.score.isFinite() })
    }
}
