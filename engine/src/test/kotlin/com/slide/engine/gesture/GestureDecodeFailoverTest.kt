package com.slide.engine.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureDecodeFailoverTest {
    private val keys = GestureFixtures.qwerty()
    private val points = GestureFixtures.trace("hello", keys)

    @Test
    fun `empty primary result uses deterministic fallback`() {
        val fallback = FakeDecoder(listOf(GestureCandidate("hello", 3f)))
        val failover = GestureDecodeFailover(fallback)

        val result = failover.decode(points, keys, true, null, null) { emptyList() }

        assertEquals("hello", result.single().word)
        assertEquals(1, fallback.calls)
        assertEquals(GestureDecoderSource.FALLBACK, failover.lastSource)
    }

    @Test
    fun `runtime failure disables primary but keeps swipe usable`() {
        val fallback = FakeDecoder(listOf(GestureCandidate("world", 2f)))
        val failover = GestureDecodeFailover(fallback)
        var primaryCalls = 0

        repeat(2) {
            val result = failover.decode(points, keys, true, null, null) {
                primaryCalls++
                throw IllegalStateException("native model mismatch")
            }
            assertEquals("world", result.single().word)
        }

        assertEquals(1, primaryCalls)
        assertEquals(2, fallback.calls)
        assertEquals(GestureDecoderSource.FALLBACK, failover.lastSource)
    }

    @Test
    fun `valid primary result does not pay for fallback`() {
        val fallback = FakeDecoder(listOf(GestureCandidate("fallback", 1f)))
        val failover = GestureDecodeFailover(fallback)

        val result = failover.decode(points, keys, true, null, null) {
            listOf(GestureCandidate("neural", 4f))
        }

        assertEquals("neural", result.single().word)
        assertEquals(0, fallback.calls)
        assertEquals(GestureDecoderSource.NEURAL, failover.lastSource)
    }

    @Test
    fun `one letter neural result cannot suppress a gesture fallback`() {
        val fallback = FakeDecoder(listOf(GestureCandidate("hello", 3f)))
        val failover = GestureDecodeFailover(fallback)

        val result = failover.decode(points, keys, true, null, null) {
            listOf(GestureCandidate("h", 10f))
        }

        assertEquals("hello", result.single().word)
        assertEquals(1, fallback.calls)
        assertEquals(GestureDecoderSource.FALLBACK, failover.lastSource)
    }

    @Test
    fun `empty primary and fallback report no source`() {
        val failover = GestureDecodeFailover(FakeDecoder(emptyList()))

        assertEquals(emptyList<GestureCandidate>(), failover.decode(points, keys, true, null, null) { emptyList() })
        assertEquals(GestureDecoderSource.NONE, failover.lastSource)
    }

    private class FakeDecoder(
        private val result: List<GestureCandidate>,
    ) : GestureDecodingEngine {
        var calls = 0

        override fun decode(
            points: List<GesturePoint>,
            keys: GestureKeyMap,
            blockOffensive: Boolean,
            previousWord: String?,
            previousPreviousWord: String?,
        ): List<GestureCandidate> {
            calls++
            return result
        }
    }
}
