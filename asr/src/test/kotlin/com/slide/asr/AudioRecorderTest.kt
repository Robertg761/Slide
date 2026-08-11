package com.slide.asr

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRecorderTest {

    @Test
    fun `timed out worker blocks replacement and its late data cannot cross captures`() {
        repeat(TIMING_STRESS_REPEATS) {
            assertTimedOutWorkerIsolation()
        }
    }

    private fun assertTimedOutWorkerIsolation() {
        val lateReadGate = CountDownLatch(1)
        val first = FakeBackend(
            actions = listOf(ReadAction.ExternalBlock(lateReadGate, shortArrayOf(12_000))),
            stopUnblocksRead = false,
        )
        val second = FakeBackend(
            actions = listOf(ReadAction.Data(shortArrayOf(8_192)), ReadAction.BlockUntilStop),
        )
        val factory = QueueFactory(first, second)
        val recorder = AudioRecorder(factory, joinTimeoutMs = 20L)

        assertTrue(recorder.start())
        assertTrue(first.readEntered.await(1, TimeUnit.SECONDS))
        assertArrayEquals(FloatArray(0), recorder.stop(), 0f)

        // stop() timed out, so B is refused rather than sharing A's reusable state or buffer.
        assertFalse(recorder.start())
        assertEquals(1, factory.createCount.get())

        lateReadGate.countDown()
        assertTrue(first.released.await(1, TimeUnit.SECONDS))
        assertEquals(1, first.releaseCount.get())
        assertTrue(first.lastDestination?.all { it == 0.toShort() } == true)

        // FakeBackend publishes `released` from inside release(). The worker publishes termination
        // immediately after release() returns, so wait through that intentionally tiny sentinel
        // window instead of assuming the two events are the same event.
        assertTrue(awaitStart(recorder))
        assertTrue(second.secondReadEntered.await(1, TimeUnit.SECONDS))
        val secondAudio = recorder.stop()

        assertArrayEquals(floatArrayOf(8_192f / 32_768f), secondAudio, 0f)
        assertEquals(1, second.releaseCount.get())
        assertEquals(1, first.releaseCount.get())
    }

    @Test
    fun `final chunk delivered as the microphone stops is kept`() {
        repeat(TIMING_STRESS_REPEATS) {
            val backend = FakeBackend(
                actions = listOf(
                    ReadAction.Data(shortArrayOf(1_000)),
                    ReadAction.DataOnStop(shortArrayOf(2_000)),
                ),
            )
            val recorder = AudioRecorder(QueueFactory(backend), joinTimeoutMs = 2_000L)

            assertTrue(recorder.start())
            assertTrue(backend.secondReadEntered.await(1, TimeUnit.SECONDS))
            val audio = recorder.stop()

            // stop() joins the worker before draining, so the buffer AudioRecord releases as it
            // stops — the tail of the last word — is part of the transcript, not clipped off it.
            assertArrayEquals(
                floatArrayOf(1_000f / 32_768f, 2_000f / 32_768f),
                audio,
                0f,
            )
            assertEquals(1, backend.releaseCount.get())
        }
    }

    @Test
    fun `chunk arriving after a timed out stop drained the buffer is discarded`() {
        val lateReadGate = CountDownLatch(1)
        val backend = FakeBackend(
            actions = listOf(ReadAction.ExternalBlock(lateReadGate, shortArrayOf(12_000))),
            stopUnblocksRead = false,
        )
        val recorder = AudioRecorder(backendFactory = QueueFactory(backend), joinTimeoutMs = 20L)

        assertTrue(recorder.start())
        assertTrue(backend.readEntered.await(1, TimeUnit.SECONDS))
        assertArrayEquals(FloatArray(0), recorder.stop(), 0f)

        lateReadGate.countDown()
        assertTrue(backend.released.await(1, TimeUnit.SECONDS))

        // The buffer was already reported empty to the caller. Audio read after that point cannot
        // be resurrected by any later drain, which is what the privacy wipe promises.
        assertArrayEquals(FloatArray(0), recorder.stop(), 0f)
        assertTrue(backend.lastDestination?.all { it == 0.toShort() } == true)
    }

    @Test
    fun `blocked backend stop cannot block the service caller`() {
        val stopGate = CountDownLatch(1)
        val backend = FakeBackend(
            actions = listOf(ReadAction.BlockUntilStop),
            stopGate = stopGate,
        )
        val recorder = AudioRecorder(QueueFactory(backend), joinTimeoutMs = 20L)

        assertTrue(recorder.start())
        assertTrue(backend.readEntered.await(1, TimeUnit.SECONDS))
        val started = System.nanoTime()
        try {
            assertArrayEquals(FloatArray(0), recorder.stop(), 0f)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("stop blocked the caller for ${elapsedMs}ms", elapsedMs < 1_000L)
            assertTrue(backend.stopEntered.await(1, TimeUnit.SECONDS))
            assertFalse(recorder.start())
        } finally {
            stopGate.countDown()
        }

        assertTrue(backend.released.await(1, TimeUnit.SECONDS))
        assertEquals(1, backend.stopCount.get())
        assertEquals(1, backend.releaseCount.get())
    }

    @Test
    fun `read failure reports once releases once and never retains samples`() {
        val backend = FakeBackend(actions = listOf(ReadAction.Fail))
        val recorder = AudioRecorder(QueueFactory(backend), joinTimeoutMs = 100L)
        val ended = CountDownLatch(1)
        var reason: AudioRecorder.EndReason? = null

        assertTrue(
            recorder.start(endListener = AudioRecorder.EndListener {
                reason = it
                ended.countDown()
            }),
        )
        assertTrue(ended.await(1, TimeUnit.SECONDS))
        recorder.cancel()

        assertEquals(AudioRecorder.EndReason.CaptureFailed, reason)
        assertEquals(1, backend.stopCount.get())
        assertEquals(1, backend.releaseCount.get())
        assertArrayEquals(FloatArray(0), recorder.stop(), 0f)
    }

    @Test
    fun `normal cancel stops and releases once and wipes worker short buffer`() {
        val backend = FakeBackend(
            actions = listOf(ReadAction.Data(shortArrayOf(4_096, -4_096)), ReadAction.BlockUntilStop),
        )
        val recorder = AudioRecorder(QueueFactory(backend), joinTimeoutMs = 100L)

        assertTrue(recorder.start())
        assertTrue(backend.secondReadEntered.await(1, TimeUnit.SECONDS))
        recorder.cancel()

        assertEquals(1, backend.stopCount.get())
        assertEquals(1, backend.releaseCount.get())
        assertTrue(backend.lastDestination?.all { it == 0.toShort() } == true)
    }

    @Test
    fun `backend that fails to start is released exactly once`() {
        val backend = FakeBackend(actions = emptyList(), startFailure = true)
        val recorder = AudioRecorder(QueueFactory(backend))

        assertFalse(recorder.start())
        assertEquals(0, backend.stopCount.get())
        assertEquals(1, backend.releaseCount.get())
    }

    @Test
    fun `recording limit auto ends then preserves captured prefix for stop`() {
        val backend = FakeBackend(
            actions = listOf(
                ReadAction.Data(shortArrayOf(1_000, 2_000)),
                ReadAction.Data(shortArrayOf(3_000, 4_000)),
            ),
        )
        val recorder = AudioRecorder(QueueFactory(backend), joinTimeoutMs = 100L, maxSamples = 3)
        val ended = CountDownLatch(1)

        assertTrue(recorder.start(endListener = AudioRecorder.EndListener { ended.countDown() }))
        assertTrue(ended.await(1, TimeUnit.SECONDS))
        val audio = recorder.stop()

        assertArrayEquals(floatArrayOf(1_000f / 32_768f, 2_000f / 32_768f), audio, 0f)
        assertEquals(1, backend.releaseCount.get())
    }

    private fun awaitStart(recorder: AudioRecorder, timeoutMs: Long = 1_000L): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        do {
            if (recorder.start()) return true
            Thread.yield()
        } while (System.nanoTime() < deadline)
        return false
    }

    private class QueueFactory(vararg backends: FakeBackend) : AudioCaptureBackendFactory {
        private val queue = ArrayDeque(backends.toList())
        val createCount = AtomicInteger()

        override fun minimumBufferSize(): Int = 256

        override fun create(bufferBytes: Int): AudioCaptureBackend {
            createCount.incrementAndGet()
            return checkNotNull(if (queue.isEmpty()) null else queue.removeFirst()) {
                "No fake backend left"
            }
        }
    }

    private sealed interface ReadAction {
        data class Data(val values: ShortArray) : ReadAction
        data object Fail : ReadAction
        data object BlockUntilStop : ReadAction
        data class ExternalBlock(val gate: CountDownLatch, val values: ShortArray) : ReadAction

        /** AudioRecord.stop() unblocking a pending read with one last partial buffer. */
        data class DataOnStop(val values: ShortArray) : ReadAction
    }

    private class FakeBackend(
        actions: List<ReadAction>,
        private val stopUnblocksRead: Boolean = true,
        private val startFailure: Boolean = false,
        private val stopGate: CountDownLatch? = null,
    ) : AudioCaptureBackend {
        private val actions = ArrayDeque(actions)
        private val stopSignal = CountDownLatch(1)
        val readEntered = CountDownLatch(1)
        val secondReadEntered = CountDownLatch(1)
        val stopEntered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val stopCount = AtomicInteger()
        val releaseCount = AtomicInteger()
        private val readCount = AtomicInteger()
        @Volatile var lastDestination: ShortArray? = null

        override val isInitialized: Boolean = true

        override fun start() {
            if (startFailure) throw IllegalStateException("synthetic start failure")
        }

        override fun read(destination: ShortArray): Int {
            lastDestination = destination
            val call = readCount.incrementAndGet()
            readEntered.countDown()
            if (call >= 2) secondReadEntered.countDown()
            return when (val action = synchronized(actions) {
                if (actions.isEmpty()) ReadAction.BlockUntilStop else actions.removeFirst()
            }) {
                is ReadAction.Data -> {
                    action.values.copyInto(destination)
                    action.values.size
                }
                ReadAction.Fail -> -1
                ReadAction.BlockUntilStop -> {
                    stopSignal.await(2, TimeUnit.SECONDS)
                    -1
                }
                is ReadAction.ExternalBlock -> {
                    action.gate.await(2, TimeUnit.SECONDS)
                    action.values.copyInto(destination)
                    action.values.size
                }
                is ReadAction.DataOnStop -> {
                    stopSignal.await(2, TimeUnit.SECONDS)
                    action.values.copyInto(destination)
                    action.values.size
                }
            }
        }

        override fun stop() {
            stopCount.incrementAndGet()
            stopEntered.countDown()
            stopGate?.await(5, TimeUnit.SECONDS)
            if (stopUnblocksRead) stopSignal.countDown()
        }

        override fun release() {
            releaseCount.incrementAndGet()
            released.countDown()
        }
    }

    private companion object {
        const val TIMING_STRESS_REPEATS = 25
    }
}
