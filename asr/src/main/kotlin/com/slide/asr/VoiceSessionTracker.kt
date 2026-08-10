package com.slide.asr

/**
 * Owns the keyboard side of the voice protocol.
 *
 * A canceled native decode can finish after a replacement session has started. Editor identity is
 * not enough to distinguish those two sessions because both can belong to the same text field, so
 * every request gets a monotonically increasing id and every callback is filtered through here.
 */
internal class VoiceSessionTracker {
    private var nextSessionId = VoiceInput.NO_SESSION_ID
    private var activeSessionId = VoiceInput.NO_SESSION_ID
    private var cancellingSessionId = VoiceInput.NO_SESSION_ID

    val isActive: Boolean get() = activeSessionId != VoiceInput.NO_SESSION_ID
    val isCancellationPending: Boolean
        get() = cancellingSessionId != VoiceInput.NO_SESSION_ID

    /** Starts a session, or returns zero while another session is active or still closing. */
    fun start(): Long {
        if (isActive || isCancellationPending) return VoiceInput.NO_SESSION_ID
        nextSessionId = if (nextSessionId == Long.MAX_VALUE) 1L else nextSessionId + 1L
        activeSessionId = nextSessionId
        return activeSessionId
    }

    fun currentSessionId(): Long = activeSessionId

    fun accepts(sessionId: Long): Boolean =
        sessionId != VoiceInput.NO_SESSION_ID && sessionId == activeSessionId

    /** Completes only the session named by the callback; a stale callback changes nothing. */
    fun finish(sessionId: Long): Boolean {
        if (!accepts(sessionId)) return false
        activeSessionId = VoiceInput.NO_SESSION_ID
        return true
    }

    /** Moves the active id aside until the service acknowledges that cancellation with Idle. */
    fun beginCancellation(): Long {
        val sessionId = activeSessionId
        if (sessionId == VoiceInput.NO_SESSION_ID) return sessionId
        activeSessionId = VoiceInput.NO_SESSION_ID
        cancellingSessionId = sessionId
        return sessionId
    }

    /** Accepts exactly one cancellation acknowledgement and rejects every later callback for it. */
    fun acknowledgeCancellation(sessionId: Long): Boolean {
        if (sessionId == VoiceInput.NO_SESSION_ID || sessionId != cancellingSessionId) return false
        cancellingSessionId = VoiceInput.NO_SESSION_ID
        return true
    }

    /** Ends local protocol state and returns whether an interrupted active session needs an error. */
    fun consumeUnexpectedFailure(): Boolean {
        val shouldReport = isActive
        activeSessionId = VoiceInput.NO_SESSION_ID
        cancellingSessionId = VoiceInput.NO_SESSION_ID
        return shouldReport
    }

    fun reset() {
        activeSessionId = VoiceInput.NO_SESSION_ID
        cancellingSessionId = VoiceInput.NO_SESSION_ID
    }
}

/** Main-thread service state whose generation guards every asynchronous finalizer. */
internal class VoiceServiceSessionGate {
    private var activeSessionId = VoiceInput.NO_SESSION_ID
    private var finishing = false

    val hasActiveSession: Boolean get() = activeSessionId != VoiceInput.NO_SESSION_ID

    fun start(sessionId: Long): Boolean {
        if (sessionId == VoiceInput.NO_SESSION_ID || hasActiveSession) return false
        activeSessionId = sessionId
        finishing = false
        return true
    }

    fun isCurrent(sessionId: Long): Boolean =
        sessionId != VoiceInput.NO_SESSION_ID && sessionId == activeSessionId

    fun beginFinishing(sessionId: Long): Boolean {
        if (!isCurrent(sessionId) || finishing) return false
        finishing = true
        return true
    }

    /** Invalidates before cancellation so the old coroutine's finally cannot affect its successor. */
    fun finish(sessionId: Long): Boolean {
        if (!isCurrent(sessionId)) return false
        activeSessionId = VoiceInput.NO_SESSION_ID
        finishing = false
        return true
    }

    fun currentSessionId(): Long = activeSessionId
}
