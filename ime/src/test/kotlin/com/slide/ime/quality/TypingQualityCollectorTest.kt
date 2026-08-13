package com.slide.ime.quality

import java.io.File
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingQualityCollectorTest {
    @Test
    fun `records only deterministic aggregate buckets`() {
        val collector = TypingQualityCollector()

        collector.recordSwipeDecision(
            latencyMillis = 7.99,
            decoderSource = DecoderSource.NEURAL,
            candidateCount = 3,
            confidence = ConfidenceBucket.HIGH,
            outcome = DecisionOutcome.TOP_CANDIDATE_COMMITTED,
        )
        collector.recordSwipeDecision(
            latencyMillis = 128.0,
            decoderSource = DecoderSource.FALLBACK,
            candidateCount = 0,
            confidence = ConfidenceBucket.LOW,
            outcome = DecisionOutcome.NO_CANDIDATE,
        )
        collector.recordTypedDecision(
            latencyMillis = Double.NaN,
            candidateCount = -1,
            confidence = ConfidenceBucket.UNKNOWN,
            outcome = DecisionOutcome.FAILED,
        )
        collector.recordExplicitAlternateSelection(QualityInputMode.SWIPE)
        collector.recordImmediateUndo(QualityInputMode.SWIPE)
        collector.recordImmediateCorrection(QualityInputMode.TYPED)
        collector.recordModelReadiness(QualityModel.TYPING_SUGGESTER, ModelReadiness.PRIMARY_READY)
        collector.recordModelReadiness(QualityModel.SWIPE_DECODER, ModelReadiness.FALLBACK_READY)

        val snapshot = collector.snapshot()
        assertEquals(2L, snapshot.swipe.decisions.total)
        assertEquals(1L, snapshot.swipe.decisions.latencies.count(DecodeLatencyBucket.UNDER_8_MS))
        assertEquals(1L, snapshot.swipe.decisions.latencies.count(DecodeLatencyBucket.FROM_128_TO_255_MS))
        assertEquals(1L, snapshot.swipe.decoderSources.count(DecoderSource.NEURAL))
        assertEquals(1L, snapshot.swipe.decoderSources.count(DecoderSource.FALLBACK))
        assertEquals(1L, snapshot.swipe.decisions.candidateCounts.count(CandidateCountBucket.TWO_TO_THREE))
        assertEquals(1L, snapshot.swipe.decisions.outcomes.count(DecisionOutcome.TOP_CANDIDATE_COMMITTED))
        assertEquals(1L, snapshot.typed.latencies.count(DecodeLatencyBucket.INVALID))
        assertEquals(1L, snapshot.typed.candidateCounts.count(CandidateCountBucket.INVALID))
        assertEquals(
            1L,
            snapshot.feedback.forMode(QualityInputMode.SWIPE)
                .signals.count(FeedbackSignal.EXPLICIT_ALTERNATE_SELECTION),
        )
        assertEquals(
            1L,
            snapshot.feedback.forMode(QualityInputMode.TYPED)
                .signals.count(FeedbackSignal.IMMEDIATE_CORRECTION),
        )
        assertEquals(
            ModelReadiness.FALLBACK_READY,
            snapshot.models.forModel(QualityModel.SWIPE_DECODER).current,
        )

        assertEquals(snapshot, collector.snapshot())
        assertEquals(snapshot.toString(), collector.snapshot().toString())
    }

    @Test
    fun `invalid and boundary measurements are handled without throwing`() {
        assertEquals(DecodeLatencyBucket.INVALID, DecodeLatencyBucket.fromMillis(-0.001))
        assertEquals(DecodeLatencyBucket.INVALID, DecodeLatencyBucket.fromMillis(Double.NaN))
        assertEquals(DecodeLatencyBucket.INVALID, DecodeLatencyBucket.fromMillis(Double.POSITIVE_INFINITY))
        assertEquals(DecodeLatencyBucket.UNDER_8_MS, DecodeLatencyBucket.fromMillis(0.0))
        assertEquals(DecodeLatencyBucket.FROM_8_TO_15_MS, DecodeLatencyBucket.fromMillis(8.0))
        assertEquals(DecodeLatencyBucket.FROM_16_TO_31_MS, DecodeLatencyBucket.fromMillis(16.0))
        assertEquals(DecodeLatencyBucket.AT_LEAST_512_MS, DecodeLatencyBucket.fromMillis(Double.MAX_VALUE))

        assertEquals(CandidateCountBucket.INVALID, CandidateCountBucket.fromCount(-1))
        assertEquals(CandidateCountBucket.ZERO, CandidateCountBucket.fromCount(0))
        assertEquals(CandidateCountBucket.ONE, CandidateCountBucket.fromCount(1))
        assertEquals(CandidateCountBucket.TWO_TO_THREE, CandidateCountBucket.fromCount(3))
        assertEquals(CandidateCountBucket.FOUR_TO_FIVE, CandidateCountBucket.fromCount(5))
        assertEquals(CandidateCountBucket.SIX_OR_MORE, CandidateCountBucket.fromCount(Int.MAX_VALUE))
    }

    @Test
    fun `all counters saturate at their configured bound`() {
        val collector = TypingQualityCollector(counterLimit = 2L)
        repeat(10) {
            collector.recordSwipeDecision(
                latencyMillis = 10.0,
                decoderSource = DecoderSource.NEURAL,
                candidateCount = 1,
                confidence = ConfidenceBucket.MEDIUM,
                outcome = DecisionOutcome.TOP_CANDIDATE_COMMITTED,
            )
            collector.recordImmediateUndo(QualityInputMode.SWIPE)
            collector.recordModelReadiness(QualityModel.SWIPE_DECODER, ModelReadiness.PRIMARY_READY)
        }

        val snapshot = collector.snapshot()
        assertEquals(2L, snapshot.swipe.decisions.total)
        assertEquals(2L, snapshot.swipe.decisions.latencies.count(DecodeLatencyBucket.FROM_8_TO_15_MS))
        assertEquals(2L, snapshot.swipe.decoderSources.count(DecoderSource.NEURAL))
        assertEquals(
            2L,
            snapshot.feedback.forMode(QualityInputMode.SWIPE).signals.count(FeedbackSignal.IMMEDIATE_UNDO),
        )
        assertEquals(
            2L,
            snapshot.models.forModel(QualityModel.SWIPE_DECODER)
                .observations.count(ModelReadiness.PRIMARY_READY),
        )
    }

    @Test
    fun `concurrent writers produce complete aggregate counts`() {
        val collector = TypingQualityCollector()
        val workers = 8
        val recordsPerWorker = 2_000
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val executor = Executors.newFixedThreadPool(workers)

        repeat(workers) { worker ->
            executor.execute {
                start.await()
                repeat(recordsPerWorker) { record ->
                    if ((worker + record) % 2 == 0) {
                        collector.recordSwipeDecision(
                            latencyMillis = 20.0,
                            decoderSource = DecoderSource.FALLBACK,
                            candidateCount = 5,
                            confidence = ConfidenceBucket.MEDIUM,
                            outcome = DecisionOutcome.TOP_CANDIDATE_COMMITTED,
                        )
                    } else {
                        collector.recordTypedDecision(
                            latencyMillis = 4.0,
                            candidateCount = 2,
                            confidence = ConfidenceBucket.LOW,
                            outcome = DecisionOutcome.LITERAL_COMMITTED,
                        )
                    }
                }
                done.countDown()
            }
        }

        start.countDown()
        assertTrue(done.await(20, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS))

        val expectedPerMode = (workers * recordsPerWorker / 2).toLong()
        val snapshot = collector.snapshot()
        assertEquals(expectedPerMode, snapshot.swipe.decisions.total)
        assertEquals(expectedPerMode, snapshot.typed.total)
        assertEquals(
            expectedPerMode,
            snapshot.swipe.decoderSources.count(DecoderSource.FALLBACK),
        )
        assertEquals(
            expectedPerMode,
            snapshot.typed.outcomes.count(DecisionOutcome.LITERAL_COMMITTED),
        )
    }

    @Test
    fun `reset erases every aggregate and current readiness`() {
        val collector = TypingQualityCollector()
        collector.recordSwipeDecision(
            40.0,
            DecoderSource.UNKNOWN,
            2,
            ConfidenceBucket.UNKNOWN,
            DecisionOutcome.STALE,
        )
        collector.recordImmediateUndo(QualityInputMode.SWIPE)
        collector.recordModelReadiness(QualityModel.SWIPE_DECODER, ModelReadiness.PRIMARY_READY)

        collector.reset()

        val snapshot = collector.snapshot()
        assertEquals(0L, snapshot.swipe.decisions.total)
        assertTrue(snapshot.swipe.decoderSources.all { it.count == 0L })
        assertTrue(snapshot.feedback.all { mode -> mode.signals.all { it.count == 0L } })
        assertTrue(snapshot.models.all { it.current == ModelReadiness.NOT_READY })
        assertTrue(snapshot.models.all { model -> model.observations.all { it.count == 0L } })
    }

    @Test
    fun `recording API cannot accept text identity coordinates or timestamps`() {
        val forbiddenTypes = setOf(
            String::class.java,
            Char::class.javaPrimitiveType,
            Char::class.javaObjectType,
            CharSequence::class.java,
            CharArray::class.java,
            File::class.java,
            URL::class.java,
            Socket::class.java,
        )
        val recordingMethods = TypingQualityCollector::class.java.declaredMethods
            .filter { it.name.startsWith("record") }
        assertTrue(recordingMethods.isNotEmpty())
        recordingMethods.forEach { method ->
            method.parameterTypes.forEach { type ->
                assertFalse("${method.name} accepts forbidden $type", type in forbiddenTypes)
                assertFalse("${method.name} accepts an array", type.isArray)
                assertFalse("${method.name} accepts text", CharSequence::class.java.isAssignableFrom(type))
                assertFalse("${method.name} accepts a collection", Collection::class.java.isAssignableFrom(type))
                assertFalse("${method.name} accepts a map", Map::class.java.isAssignableFrom(type))
            }
        }

        val forbiddenNameFragments = listOf(
            "word", "character", "text", "application", "editor", "touch", "coordinate",
            "timestamp", "history", "eventlog",
        )
        TypingQualityCollector::class.java.declaredFields.forEach { field ->
            val compactName = field.name.lowercase().replace("_", "")
            assertTrue(
                "collector field could retain sensitive data: ${field.name}",
                forbiddenNameFragments.none(compactName::contains),
            )
            assertFalse(CharSequence::class.java.isAssignableFrom(field.type))
            assertFalse(Collection::class.java.isAssignableFrom(field.type))
            assertFalse(Map::class.java.isAssignableFrom(field.type))
        }
    }

    private fun <T : Enum<T>> List<BucketCount<T>>.count(bucket: T): Long =
        single { it.bucket == bucket }.count

    private fun List<InputFeedbackSnapshot>.forMode(mode: QualityInputMode): InputFeedbackSnapshot =
        single { it.inputMode == mode }

    private fun List<ModelReadinessSnapshot>.forModel(model: QualityModel): ModelReadinessSnapshot =
        single { it.model == model }
}
