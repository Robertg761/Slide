package com.slide.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedDataPersistenceStateTest {

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
