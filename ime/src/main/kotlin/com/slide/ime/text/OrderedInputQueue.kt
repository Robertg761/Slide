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
