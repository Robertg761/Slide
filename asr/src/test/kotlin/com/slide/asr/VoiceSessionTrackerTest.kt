package com.slide.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionTrackerTest {

    @Test
    fun `canceled session is acknowledged once and all of its later callbacks are stale`() {
        val sessions = VoiceSessionTracker()
        val first = sessions.start()

        assertEquals(first, sessions.beginCancellation())
        assertTrue(sessions.isCancellationPending)
        assertFalse(sessions.accepts(first))
        assertTrue(sessions.acknowledgeCancellation(first))
        assertFalse(sessions.acknowledgeCancellation(first))

        val second = sessions.start()
        assertTrue(second > first)
        assertTrue(sessions.accepts(second))
        assertFalse(sessions.accepts(first))
        assertFalse(sessions.finish(first))
        assertTrue(sessions.accepts(second))
    }

    @Test
    fun `replacement cannot start before cancellation acknowledgement`() {
        val sessions = VoiceSessionTracker()
        val first = sessions.start()
        sessions.beginCancellation()

        assertEquals(VoiceInput.NO_SESSION_ID, sessions.start())
        assertTrue(sessions.acknowledgeCancellation(first))
        assertTrue(sessions.start() != VoiceInput.NO_SESSION_ID)
    }

    @Test
    fun `infrastructure failure is reported only for an active session`() {
        val sessions = VoiceSessionTracker()
        sessions.start()

        assertTrue(sessions.consumeUnexpectedFailure())
        assertFalse(sessions.isActive)
        assertFalse(sessions.consumeUnexpectedFailure())
    }

    @Test
    fun `expected cancellation suppresses disconnect error but is cleared`() {
        val sessions = VoiceSessionTracker()
        sessions.start()
        sessions.beginCancellation()

        assertFalse(sessions.consumeUnexpectedFailure())
        assertFalse(sessions.isCancellationPending)
    }

    @Test
    fun `old service finalizer cannot clear or finish replacement`() {
        val gate = VoiceServiceSessionGate()
        val first = 41L
        val second = 42L

        assertTrue(gate.start(first))
        assertTrue(gate.finish(first)) // invalidate before canceling native work
        assertTrue(gate.start(second))

        assertFalse(gate.finish(first)) // stale finally from A
        assertTrue(gate.isCurrent(second))
        assertTrue(gate.beginFinishing(second))
        assertFalse(gate.beginFinishing(second))
        assertTrue(gate.finish(second))
        assertFalse(gate.hasActiveSession)
    }
}
