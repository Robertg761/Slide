package com.slide.ime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM guard for service wiring that otherwise needs a real InputMethodService host.
 *
 * Operation semantics are covered by EditorComposingSettlementTest; these checks ensure each
 * dependent edit still consults that result before mutating the InputConnection.
 */
class SlideInputMethodServiceSettlementWiringTest {
    private val source = run {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
            it.parentFile
        }.first { File(it, "settings.gradle.kts").isFile }
        File(
            root,
            "ime/src/main/kotlin/com/slide/ime/SlideInputMethodService.kt",
        ).readText()
    }

    @Test
    fun `space and punctuation stop before their dependent commit when settlement is rejected`() {
        assertOrdered(
            method("handleSpace", "endsWithLetterThenSpace"),
            "if (!finish.settled) return finish.callbackPossible",
            "connection.commitText(text, 1)",
        )
        assertOrdered(
            method("handleCharacter", "cursorTouchesWord"),
            "if (!finish.settled) return callbackPossible",
            "val committed = connection.commitText(text, 1)",
        )
    }

    @Test
    fun `swipe and whole-word delete stop before their dependent edit`() {
        assertOrdered(
            method("decodeAndCommitGesture", "clearGesturePreview"),
            "if (!finish.settled)",
            "commitGestureWord(connection, best.word, selectionBeforeCommit)",
        )
        assertOrdered(
            method("processDeleteWordGesture", "commitGestureWord"),
            "if (!finish.settled)",
            "val selected = connection.getSelectedText(0)",
        )
    }

    @Test
    fun `mid-word edits stop when abandon cannot settle the active region`() {
        val characters = method("handleCharacter", "cursorTouchesWord")
        assertOrdered(
            characters,
            "val abandonment = abandonComposing(connection)",
            "if (!abandonment.settled) return callbackPossible",
        )
        val delete = method("handleDelete", "deleteLastGestureCommit")
        assertOrdered(
            delete,
            "val abandonment = abandonComposing(connection)",
            "if (!abandonment.settled) return callbackPossible",
        )
    }

    @Test
    fun `settlement failures retain composing state`() {
        val finish = method("finishComposing", "abandonComposing")
        assertOrdered(
            finish,
            "if (!settlement.settled)",
            "composing.setLength(0)",
        )
        val abandon = method("abandonComposing", "discardComposingForEditorTransition")
        assertOrdered(
            abandon,
            "if (!settlement.settled) return settlement",
            "composing.setLength(0)",
        )
    }

    @Test
    fun `rejected suggestion retains the accepted replacement and never learns it`() {
        val suggestion = method("pickTypedSuggestion", "updatePredictions")
        assertOrdered(
            suggestion,
            "if (!suggestion.settled)",
            "composing.append(replacement)",
        )
        assertOrdered(
            suggestion,
            "return\n        }",
            "learnTouches(typed, replacement)",
        )
    }

    @Test
    fun `finish learning follows the settlements explicit approval flags`() {
        val finish = method("finishComposing", "abandonComposing")
        assertOrdered(
            finish,
            "if (settlement.learnTypedWord && !recomposed)",
            "learnWord(typed)",
        )
        assertOrdered(
            finish,
            "if (settlement.learnAppliedPair && !recomposed)",
            "learnPair(previous, settlement.appliedText)",
        )
    }

    @Test
    fun `gesture adaptation sees only verified replacements and consumed immediate undo`() {
        val alternative = method("pickGestureAlternative", "applyShift")
        assertOrdered(
            alternative,
            "if (!transaction.replaced)",
            "gestureAdaptation.observeAlternative(rejectedAdaptiveWord, word)",
        )
        assertTrue(alternative.contains("!incognito"))

        val undo = method("deleteLastGestureCommit", "rollbackGestureLearning")
        assertOrdered(
            undo,
            ") ?: return false",
            "gestureAdaptation.observeImmediateUndo(undo.adaptiveWord)",
        )
        assertTrue(undo.contains("!incognito"))
    }

    @Test
    fun `both gesture decoders pass through one adaptive and measured seam`() {
        val decode = method("decodeGesture", "recordSwipeDecision")
        assertOrdered(decode, "decoder.decode(", "gestureAdaptation.rerank(raw)")
        assertOrdered(decode, "lastDecoderSource", "gestureAdaptation.rerank(raw)")
    }

    private fun method(name: String, nextName: String): String {
        val start = source.indexOf("fun $name(")
        val end = source.indexOf("fun $nextName(", start + 1)
        assertTrue("Missing method $name", start >= 0)
        assertTrue("Missing method after $name: $nextName", end > start)
        return source.substring(start, end)
    }

    private fun assertOrdered(body: String, first: String, second: String) {
        val firstIndex = body.indexOf(first)
        val secondIndex = body.indexOf(second, firstIndex + first.length)
        assertTrue("Missing first marker: $first", firstIndex >= 0)
        assertTrue("Missing or out-of-order second marker: $second", secondIndex > firstIndex)
    }
}
