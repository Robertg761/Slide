package com.slide.ime.text

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedInputQueueTest {
    @Test
    fun `swipe then backspace stay in release order`() = runBlocking {
        var editorGeneration = 3L
        val queue = OrderedInputQueue(this) { editorGeneration }
        val decode = CompletableDeferred<Unit>()
        val edits = mutableListOf<String>()

        queue.enqueue { request ->
            decode.await()
            if (queue.isCurrent(request)) edits += "swipe"
        }
        assertTrue(queue.enqueueIfPending { edits += "backspace" })
        decode.complete(Unit)
        queue.awaitIdle()

        assertEquals(listOf("swipe", "backspace"), edits)
        assertFalse(queue.hasPending)
    }

    @Test
    fun `queued backspace sees and consumes the swipe undo record`() = runBlocking {
        val queue = OrderedInputQueue(this) { 3L }
        val decode = CompletableDeferred<Unit>()
        val undo = GestureUndoState()
        var deleted: GestureUndo? = null

        queue.enqueue { request ->
            decode.await()
            if (queue.isCurrent(request)) {
                undo.arm(" that's", 3L, cursorPosition = 18, learnedPair = "said" to "that's")
            }
        }
        assertTrue(
            queue.enqueueIfPending {
                deleted = undo.consume(
                    editorGeneration = 3L,
                    hasSelection = false,
                    textBeforeCursor = " that's",
                    cursorPosition = 18,
                )
            },
        )
        decode.complete(Unit)
        queue.awaitIdle()

        assertEquals(
            GestureUndo(" that's", 3L, 18, "said" to "that's"),
            deleted,
        )
        assertNull(undo.expectedTextLength)
    }

    @Test
    fun `rapid completed swipes are serialized rather than superseded`() = runBlocking {
        val queue = OrderedInputQueue(this) { 8L }
        val firstDecode = CompletableDeferred<Unit>()
        val edits = mutableListOf<String>()

        queue.enqueue { request ->
            firstDecode.await()
            if (queue.isCurrent(request)) edits += "first"
        }
        queue.enqueue { request ->
            if (queue.isCurrent(request)) edits += "second"
        }
        firstDecode.complete(Unit)
        queue.awaitIdle()

        assertEquals(listOf("first", "second"), edits)
    }

    @Test
    fun `queued shift is applied before the following character resolves its case`() = runBlocking {
        val queue = OrderedInputQueue(this) { 8L }
        val decode = CompletableDeferred<Unit>()
        var shifted = false
        var committed = ""

        queue.enqueue { decode.await() }
        assertTrue(queue.enqueueIfPending { shifted = true })
        assertTrue(
            queue.enqueueIfPending {
                committed = resolveCharacterCase("a", shifted)
                shifted = false
            },
        )
        decode.complete(Unit)
        queue.awaitIdle()

        assertEquals("A", committed)
        assertFalse(shifted)
    }

    @Test
    fun `context move during suspended decode rejects the late commit`() = runBlocking {
        val queue = OrderedInputQueue(this) { 8L }
        val decode = CompletableDeferred<Unit>()
        val snapshotCaptured = CompletableDeferred<Unit>()
        var editor = GestureEditorSnapshot(EditorSelection(12, 12), "said ", "next")
        var committed = false

        queue.enqueue { request ->
            val captured = editor
            snapshotCaptured.complete(Unit)
            decode.await()
            if (queue.isCurrent(request) && captured.matches(editor)) committed = true
        }
        snapshotCaptured.await()
        editor = editor.copy(cursor = EditorSelection(4, 4), textBeforeCursor = "moved")
        decode.complete(Unit)
        queue.awaitIdle()

        assertFalse(committed)
    }

    @Test
    fun `editor transition cancels queued and suspended input`() = runBlocking {
        var editorGeneration = 1L
        val queue = OrderedInputQueue(this) { editorGeneration }
        val decode = CompletableDeferred<Unit>()
        val edits = mutableListOf<String>()

        queue.enqueue { request ->
            decode.await()
            if (queue.isCurrent(request)) edits += "stale swipe"
        }
        queue.enqueueIfPending { edits += "stale key" }
        editorGeneration++
        queue.cancel()
        decode.complete(Unit)
        queue.awaitIdle()

        assertTrue(edits.isEmpty())
    }
}
