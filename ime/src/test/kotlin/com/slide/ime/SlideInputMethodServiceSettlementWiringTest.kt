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
            method("handleSpace"),
            "if (!finish.settled) return finish.callbackPossible",
            "connection.commitText(text, 1)",
        )
        assertOrdered(
            method("handleCharacter"),
            "if (!finish.settled) return callbackPossible",
            "val committed = connection.commitText(text, 1)",
        )
    }

    @Test
    fun `space batches composition settlement with its separator callback`() {
        val space = method("handleSpace")
        assertOrdered(space, "connection.beginBatchEdit()", "val finish = finishComposing(connection)")
        assertOrdered(space, "val finish = finishComposing(connection)", "connection.commitText(text, 1)")
        assertOrdered(
            space,
            "callbackPossible = finish.callbackPossible || editorChanged",
            "} finally {\n            connection.endBatchEdit()",
        )
        assertOrdered(space, "connection.endBatchEdit()", "updateShiftFromCursor()")
    }

    @Test
    fun `swipe and whole-word delete stop before their dependent edit`() {
        assertOrdered(
            method("decodeAndCommitGesture"),
            "if (!finish.settled)",
            "commitGestureWord(connection, best.word, selectionBeforeCommit)",
        )
        assertOrdered(
            method("processDeleteWordGesture"),
            "if (!finish.settled)",
            "val selected = connection.getSelectedText(0)",
        )
    }

    @Test
    fun `mid-word edits stop when abandon cannot settle the active region`() {
        val characters = method("handleCharacter")
        assertOrdered(
            characters,
            "val abandonment = abandonComposing(connection)",
            "if (!abandonment.settled) return callbackPossible",
        )
        val delete = method("handleDelete")
        assertOrdered(
            delete,
            "val abandonment = abandonComposing(connection)",
            "if (!abandonment.settled) return callbackPossible",
        )
    }

    @Test
    fun `settlement failures retain composing state`() {
        val finish = method("finishComposing")
        assertOrdered(
            finish,
            "if (!settlement.settled)",
            "composing.setLength(0)",
        )
        val abandon = method("abandonComposing")
        assertOrdered(
            abandon,
            "if (!settlement.settled) return settlement",
            "composing.setLength(0)",
        )
    }

    @Test
    fun `rejected suggestion retains the accepted replacement and never learns it`() {
        val suggestion = method("pickTypedSuggestion")
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
        val finish = method("finishComposing")
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
        val alternative = method("pickGestureAlternative")
        assertOrdered(
            alternative,
            "if (!transaction.replaced)",
            "gestureAdaptation.observeAlternative(rejectedAdaptiveWord, word)",
        )
        assertTrue(alternative.contains("!incognito"))

        val undo = method("deleteLastGestureCommit")
        assertOrdered(
            undo,
            ") ?: return false",
            "gestureAdaptation.observeImmediateUndo(undo.adaptiveWord)",
        )
        assertTrue(undo.contains("!incognito"))
    }

    @Test
    fun `both gesture decoders pass through one adaptive and measured seam`() {
        val decode = method("decodeGesture")
        assertOrdered(decode, "decoder.decode(", "gestureAdaptation.rerank(raw)")
        assertOrdered(decode, "lastDecoderSource", "gestureAdaptation.rerank(raw)")
    }

    @Test
    fun `tap captures the completed swipe boundary before consuming gesture undo`() {
        val commit = method("processKeyCommit")
        assertOrdered(
            commit,
            "val swipedWordBehindCursor =",
            "gestureUndoState.invalidate()",
        )
        // The boundary is worth nothing unless the character handler actually receives it.
        assertTrue(
            commit.contains(
                "handleCharacter(connection, appliedText, touchX, touchY, swipedWordBehindCursor)",
            ),
        )
    }

    private fun method(name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("Missing method $name", start >= 0)
        // Service methods close at class indentation. A neighboring helper can be renamed or
        // removed without changing this method's settlement contract.
        val end = Regex("(?m)^    }$").find(source, start)?.range?.last
        assertTrue("Missing closing brace for $name", end != null)
        return source.substring(start, requireNotNull(end) + 1)
    }

    private fun assertOrdered(body: String, first: String, second: String) {
        val firstIndex = body.indexOf(first)
        val secondIndex = body.indexOf(second, firstIndex + first.length)
        assertTrue("Missing first marker: $first", firstIndex >= 0)
        assertTrue("Missing or out-of-order second marker: $second", secondIndex > firstIndex)
    }
}
