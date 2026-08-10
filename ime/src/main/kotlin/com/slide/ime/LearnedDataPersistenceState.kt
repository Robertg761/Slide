package com.slide.ime

/**
 * Main-thread state machine for learned-data IO.
 *
 * File operations stay in SlideInputMethodService, while this class owns the transitions that
 * decide whether an old result is still relevant, whether a save is allowed, and whether teardown
 * has work to hand to the process-lifetime finalizer.
 */
internal class LearnedDataPersistenceState {

    @Volatile
    var generation: Long = 0L
        private set

    var dirty: Boolean = false
        private set

    var loadInFlight: Boolean = false
        private set

    var saveInFlight: Boolean = false
        private set

    var deletionInFlight: Boolean = false
        private set

    var deletionPending: Boolean = false
        private set

    fun markDirty() {
        dirty = true
    }

    fun beginLoad(): Long {
        check(!loadInFlight)
        loadInFlight = true
        return generation
    }

    /** Finishes a load and returns whether its result still belongs to the current generation. */
    fun finishLoad(loadGeneration: Long, pendingDeletion: Boolean?): Boolean {
        loadInFlight = false
        if (!isCurrent(loadGeneration)) return false
        if (pendingDeletion != null) deletionPending = pendingDeletion
        return true
    }

    fun requestClear() {
        generation++
        dirty = false
        deletionPending = true
    }

    fun beginDeletion(): DeletionTicket? {
        if (deletionInFlight) return null
        deletionInFlight = true
        return DeletionTicket(generation)
    }

    /** Finishes deletion, returning false when a newer clear request made this result stale. */
    fun finishDeletion(ticket: DeletionTicket, succeeded: Boolean): Boolean {
        check(deletionInFlight)
        deletionInFlight = false
        if (!isCurrent(ticket.generation)) return false
        deletionPending = !succeeded
        return true
    }

    fun beginSave(): SaveTicket? {
        if (!dirty || loadInFlight || saveInFlight || deletionInFlight) return null
        dirty = false
        saveInFlight = true
        return SaveTicket(generation, completePendingDeletionFirst = deletionPending)
    }

    /** Accepts only the result of a save from the current clear generation. */
    fun finishSave(
        ticket: SaveTicket,
        saved: Boolean?,
        pendingDeletionResolved: Boolean,
    ) {
        saveInFlight = false
        if (!isCurrent(ticket.generation) || saved == null) return
        if (!saved) {
            // A save can fail because the settings app placed a clear marker before its epoch
            // reached this service. Do not claim ownership of that marker or a later retry could
            // remove it and resurrect this service's stale in-memory snapshot.
            dirty = true
            return
        }
        if (ticket.completePendingDeletionFirst && pendingDeletionResolved) deletionPending = false
    }

    fun isCurrent(candidateGeneration: Long): Boolean = candidateGeneration == generation

    val clearOutstanding: Boolean
        get() = deletionInFlight || deletionPending

    val needsFinalization: Boolean
        get() = dirty || saveInFlight || clearOutstanding

    data class SaveTicket(
        val generation: Long,
        val completePendingDeletionFirst: Boolean,
    )

    data class DeletionTicket(val generation: Long)
}
