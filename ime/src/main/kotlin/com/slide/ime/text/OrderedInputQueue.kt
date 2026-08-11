package com.slide.ime.text

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Session identity supplied to an ordered action that may suspend for model inference. */
internal data class OrderedInputRequest(
    val epoch: Long,
    val editorGeneration: Long,
)

/**
 * Bounded evidence that a queued edit still belongs to the text it was accepted for.
 *
 * The editor generation catches the field being swapped, but an app can empty or rewrite the same
 * field underneath a queued keystroke without any transition at all — a send button clearing the
 * box while a swipe is still decoding. Applying the key anyway drops the letter into whatever
 * replaced it. This is deliberately cheap: a short run of text behind the cursor and the position
 * already cached, never an extraction of the whole field, because it is checked on the path that
 * runs for every key released during a decode.
 *
 * Each link in the chain re-captures this after its own edit, so the keyboard's own mutations
 * always agree and only a change from elsewhere is a mismatch.
 */
internal data class OrderedInputGuard(
    val selection: EditorSelection?,
    val textBeforeCursor: String?,
) {
    /**
     * Whether the editor still looks like the one this was captured from.
     *
     * Evidence missing on either side is not a mismatch. An editor that will not answer a question
     * has not thereby said the answer changed, and silently swallowing keys the user has already
     * pressed is the worse failure of the two.
     */
    fun stillApplies(now: OrderedInputGuard): Boolean {
        if (selection != null && now.selection != null && selection != now.selection) return false
        if (
            textBeforeCursor != null &&
            now.textBeforeCursor != null &&
            textBeforeCursor != now.textBeforeCursor
        ) {
            return false
        }
        return true
    }
}

/**
 * Serializes completed swipes and the edits that immediately follow them.
 *
 * Touch feedback remains synchronous, while committed editor mutations wait for earlier final
 * inference. Cancellation is reserved for editor/session transitions rather than newer user input.
 * This class is main-thread confined, matching InputMethodService callbacks.
 */
internal class OrderedInputQueue(
    private val scope: CoroutineScope,
    private val currentEditorGeneration: () -> Long,
) {
    private var tail: Job? = null
    private var epoch = 0L
    private val jobs = LinkedHashSet<Job>()

    val hasPending: Boolean
        get() {
            val pending = tail ?: return false
            if (!pending.isCompleted) return true
            if (tail === pending) tail = null
            return false
        }

    fun enqueue(action: suspend (OrderedInputRequest) -> Unit) {
        val predecessor = tail
        val request = OrderedInputRequest(epoch, currentEditorGeneration())
        lateinit var queued: Job
        queued = scope.launch(start = CoroutineStart.LAZY) {
            predecessor?.join()
            if (predecessor != null) yield()
            if (!isCurrent(request)) return@launch
            action(request)
        }
        jobs += queued
        tail = queued
        queued.invokeOnCompletion {
            jobs -= queued
            if (tail === queued) tail = null
        }
        queued.start()
    }

    fun enqueueIfPending(action: () -> Unit): Boolean {
        if (!hasPending) return false
        enqueue { action() }
        return true
    }

    fun isCurrent(request: OrderedInputRequest): Boolean =
        request.epoch == epoch &&
            request.editorGeneration == currentEditorGeneration()

    fun cancel() {
        epoch++
        jobs.toList().forEach(Job::cancel)
        jobs.clear()
        tail = null
    }

    suspend fun awaitIdle() {
        tail?.join()
    }
}
