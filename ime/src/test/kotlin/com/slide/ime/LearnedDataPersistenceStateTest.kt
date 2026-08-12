package com.slide.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedDataPersistenceStateTest {

    @Test
    fun `durable clear after fallback load purges on first authoritative epoch`() {
        val epochs = LearnedDataClearEpochState()
        val loadedWords = mutableListOf("private")

        assertFalse(epochs.observeEpoch(epoch = -1L, latestDeletionGeneration = 0L))
        // The settings screen now persists the marker while the repository retry is in flight.
        if (epochs.observeEpoch(epoch = 7L, latestDeletionGeneration = 1L)) loadedWords.clear()

        assertTrue(loadedWords.isEmpty())
    }

    @Test
    fun `epoch rise clears while corruption reset only establishes a lower baseline`() {
        val epochs = LearnedDataClearEpochState()

        assertFalse(epochs.observeEpoch(epoch = 8L, latestDeletionGeneration = 0L))
        assertTrue(epochs.observeEpoch(epoch = 9L, latestDeletionGeneration = 0L))
        assertFalse(epochs.observeEpoch(epoch = 0L, latestDeletionGeneration = 0L))
        assertTrue(epochs.observeEpoch(epoch = 1L, latestDeletionGeneration = 0L))
    }

    @Test
    fun `live marker signal purges once even when its epoch arrives later`() {
        val epochs = LearnedDataClearEpochState()

        assertFalse(epochs.observeEpoch(epoch = 5L, latestDeletionGeneration = 0L))
        assertTrue(epochs.observeDeletionRequest(generation = 1L))
        // Unrelated settings emissions while disk deletion is slow must preserve new learning.
        assertFalse(epochs.observeEpoch(epoch = 5L, latestDeletionGeneration = 1L))
        assertFalse(epochs.observeEpoch(epoch = 5L, latestDeletionGeneration = 1L))
        // The eventual epoch publication acknowledges, rather than repeats, the same clear.
        assertFalse(epochs.observeEpoch(epoch = 6L, latestDeletionGeneration = 1L))
        assertFalse(epochs.observeDeletionRequest(generation = 1L))
    }

    @Test
    fun `epoch collector catches a marker signal queued behind it without double clearing`() {
        val epochs = LearnedDataClearEpochState()

        assertFalse(epochs.observeEpoch(epoch = 5L, latestDeletionGeneration = 0L))
        assertTrue(epochs.observeEpoch(epoch = 6L, latestDeletionGeneration = 1L))
        assertFalse(epochs.observeDeletionRequest(generation = 1L))
        assertFalse(epochs.observeEpoch(epoch = 6L, latestDeletionGeneration = 1L))
    }

    @Test
    fun `two live clear signals suppress both later epoch acknowledgements`() {
        val epochs = LearnedDataClearEpochState()

        assertFalse(epochs.observeEpoch(epoch = 5L, latestDeletionGeneration = 0L))
        assertTrue(epochs.observeDeletionRequest(generation = 1L))
        assertTrue(epochs.observeDeletionRequest(generation = 2L))
        assertFalse(epochs.observeEpoch(epoch = 6L, latestDeletionGeneration = 2L))
        assertFalse(epochs.observeEpoch(epoch = 7L, latestDeletionGeneration = 2L))
    }

    @Test
    fun `coalesced epoch delivery acknowledges every already signalled clear`() {
        val epochs = LearnedDataClearEpochState()

        assertFalse(epochs.observeEpoch(epoch = 5L, latestDeletionGeneration = 0L))
        assertTrue(epochs.observeDeletionRequest(generation = 1L))
        assertTrue(epochs.observeDeletionRequest(generation = 2L))
        assertFalse(epochs.observeEpoch(epoch = 7L, latestDeletionGeneration = 2L))
        // A later rise without a marker is independent and must still clear.
        assertTrue(epochs.observeEpoch(epoch = 8L, latestDeletionGeneration = 2L))
    }

    @Test
    fun `first old authoritative snapshot does not consume a live clear acknowledgement`() {
        val epochs = LearnedDataClearEpochState()

        assertTrue(epochs.observeDeletionRequest(generation = 1L))
        // This is the pre-increment value already in flight when the marker signal arrived.
        assertFalse(epochs.observeEpoch(epoch = 5L, latestDeletionGeneration = 1L))
        // The request's eventual increment is an acknowledgement, not a second purge.
        assertFalse(epochs.observeEpoch(epoch = 6L, latestDeletionGeneration = 1L))
    }

    @Test
    fun `clear during save makes the old successful result irrelevant`() {
        val state = LearnedDataPersistenceState()
        state.markDirty()
        val oldSave = requireNotNull(state.beginSave())

        state.requestClear()
        state.finishSave(oldSave, saved = true, pendingDeletionResolved = true)

        assertTrue(state.deletionPending)
        assertFalse(state.dirty)
        assertFalse(state.saveInFlight)
    }

    @Test
    fun `failed deletion stays pending and blocks saving until delete work finishes`() {
        val state = LearnedDataPersistenceState()
        state.requestClear()
        val deletion = requireNotNull(state.beginDeletion())
        state.markDirty()

        assertNull(state.beginSave())
        assertTrue(state.finishDeletion(deletion, succeeded = false))

        val retrySave = requireNotNull(state.beginSave())
        assertTrue(retrySave.completePendingDeletionFirst)
        state.finishSave(retrySave, saved = false, pendingDeletionResolved = false)
        assertTrue(state.deletionPending)
        assertTrue(state.dirty)
    }

    @Test
    fun `ordinary save failure does not claim an external clear marker`() {
        val state = LearnedDataPersistenceState()
        state.markDirty()
        val failedSave = requireNotNull(state.beginSave())

        state.finishSave(failedSave, saved = false, pendingDeletionResolved = false)

        assertTrue(state.dirty)
        assertFalse(state.deletionPending)
        val retry = requireNotNull(state.beginSave())
        assertFalse(retry.completePendingDeletionFirst)
    }

    @Test
    fun `teardown finalizes dirty in-flight and clear states but not a clean idle state`() {
        val state = LearnedDataPersistenceState()
        assertFalse(state.needsFinalization)

        state.markDirty()
        assertTrue(state.needsFinalization)
        val save = requireNotNull(state.beginSave())
        assertTrue(state.needsFinalization)
        state.finishSave(save, saved = true, pendingDeletionResolved = true)
        assertFalse(state.needsFinalization)

        state.requestClear()
        assertTrue(state.needsFinalization)
    }

    @Test
    fun `consecutive clear makes the older successful deletion stale and schedules another`() {
        val state = LearnedDataPersistenceState()
        state.requestClear()
        val firstDeletion = requireNotNull(state.beginDeletion())
        state.requestClear()

        assertNull(state.beginDeletion())
        assertFalse(state.finishDeletion(firstDeletion, succeeded = true))

        assertTrue(state.deletionPending)
        assertFalse(state.deletionInFlight)
        assertFalse(state.dirty)
        assertTrue(state.beginDeletion() != null)
    }

    @Test
    fun `load from before a clear cannot publish stale deletion state`() {
        val state = LearnedDataPersistenceState()
        val loadGeneration = state.beginLoad()
        state.requestClear()

        assertFalse(state.finishLoad(loadGeneration, pendingDeletion = false))
        assertTrue(state.deletionPending)
    }
}
