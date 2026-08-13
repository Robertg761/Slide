package com.slide.ime.quality

/** The input path that produced an aggregate quality signal. */
enum class QualityInputMode {
    TYPED,
    SWIPE,
}

/**
 * The decoder that actually produced a swipe result.
 *
 * [UNKNOWN] is preferable to guessing when a decoder internally fails over without reporting which
 * path won. The collector must never turn an inference into a measurement.
 */
enum class DecoderSource {
    UNKNOWN,
    NEURAL,
    FALLBACK,
}

/** Coarse confidence chosen by the caller on the scoring scale appropriate to that engine. */
enum class ConfidenceBucket {
    UNKNOWN,
    LOW,
    MEDIUM,
    HIGH,
}

/** The privacy-safe result of one typing or final-swipe decision. */
enum class DecisionOutcome {
    TOP_CANDIDATE_COMMITTED,
    LITERAL_COMMITTED,
    NO_CANDIDATE,
    EDITOR_REJECTED,
    STALE,
    CANCELLED,
    FAILED,
}

/** Fixed latency buckets. Raw durations are discarded in the call that records them. */
enum class DecodeLatencyBucket {
    INVALID,
    UNDER_8_MS,
    FROM_8_TO_15_MS,
    FROM_16_TO_31_MS,
    FROM_32_TO_63_MS,
    FROM_64_TO_127_MS,
    FROM_128_TO_255_MS,
    FROM_256_TO_511_MS,
    AT_LEAST_512_MS,
    ;

    companion object {
        fun fromMillis(latencyMillis: Double): DecodeLatencyBucket = when {
            !latencyMillis.isFinite() || latencyMillis < 0.0 -> INVALID
            latencyMillis < 8.0 -> UNDER_8_MS
            latencyMillis < 16.0 -> FROM_8_TO_15_MS
            latencyMillis < 32.0 -> FROM_16_TO_31_MS
            latencyMillis < 64.0 -> FROM_32_TO_63_MS
            latencyMillis < 128.0 -> FROM_64_TO_127_MS
            latencyMillis < 256.0 -> FROM_128_TO_255_MS
            latencyMillis < 512.0 -> FROM_256_TO_511_MS
            else -> AT_LEAST_512_MS
        }
    }
}

/** Fixed candidate-count buckets. A negative count is retained only as an invalid aggregate. */
enum class CandidateCountBucket {
    INVALID,
    ZERO,
    ONE,
    TWO_TO_THREE,
    FOUR_TO_FIVE,
    SIX_OR_MORE,
    ;

    companion object {
        fun fromCount(candidateCount: Int): CandidateCountBucket = when {
            candidateCount < 0 -> INVALID
            candidateCount == 0 -> ZERO
            candidateCount == 1 -> ONE
            candidateCount <= 3 -> TWO_TO_THREE
            candidateCount <= 5 -> FOUR_TO_FIVE
            else -> SIX_OR_MORE
        }
    }
}

/** Explicit user feedback, recorded without the text that prompted it. */
enum class FeedbackSignal {
    EXPLICIT_ALTERNATE_SELECTION,
    IMMEDIATE_UNDO,
    IMMEDIATE_CORRECTION,
}

enum class QualityModel {
    TYPING_SUGGESTER,
    SWIPE_DECODER,
}

enum class ModelReadiness {
    NOT_READY,
    UNAVAILABLE,
    FALLBACK_READY,
    PRIMARY_READY,
}

/** An immutable counter entry. Lists of these always follow enum declaration order. */
data class BucketCount<T : Enum<T>>(
    val bucket: T,
    val count: Long,
)

data class DecisionQualitySnapshot(
    val total: Long,
    val latencies: List<BucketCount<DecodeLatencyBucket>>,
    val candidateCounts: List<BucketCount<CandidateCountBucket>>,
    val confidences: List<BucketCount<ConfidenceBucket>>,
    val outcomes: List<BucketCount<DecisionOutcome>>,
)

data class SwipeQualitySnapshot(
    val decisions: DecisionQualitySnapshot,
    val decoderSources: List<BucketCount<DecoderSource>>,
)

data class InputFeedbackSnapshot(
    val inputMode: QualityInputMode,
    val signals: List<BucketCount<FeedbackSignal>>,
)

data class ModelReadinessSnapshot(
    val model: QualityModel,
    val current: ModelReadiness,
    val observations: List<BucketCount<ModelReadiness>>,
)

/**
 * A complete aggregate view. Its fields and every nested bucket list have deterministic order.
 */
data class TypingQualitySnapshot(
    val swipe: SwipeQualitySnapshot,
    val typed: DecisionQualitySnapshot,
    val feedback: List<InputFeedbackSnapshot>,
    val models: List<ModelReadinessSnapshot>,
)

/**
 * Thread-safe, bounded, in-memory quality measurements for typing and final swipe decisions.
 *
 * Privacy is structural: no public recording method accepts words, characters, surrounding text,
 * editor/application identity, touch coordinates, or wall-clock timestamps. Durations and counts
 * are converted to coarse buckets immediately, and only fixed-size saturating counters remain.
 * This class imports no Android, file, database, serialization, or network API and has no automatic
 * persistence. Callers can only obtain aggregate [snapshot]s or [reset] all aggregates.
 */
class TypingQualityCollector internal constructor(
    private val counterLimit: Long,
) {
    constructor() : this(Long.MAX_VALUE)

    private val lock = Any()
    private val swipeDecisions = DecisionCounters(counterLimit)
    private val typedDecisions = DecisionCounters(counterLimit)
    private val decoderSources = LongArray(DecoderSource.values().size)
    private val feedback = Array(QualityInputMode.values().size) {
        LongArray(FeedbackSignal.values().size)
    }
    private val modelObservations = Array(QualityModel.values().size) {
        LongArray(ModelReadiness.values().size)
    }
    private val currentModelReadiness = Array(QualityModel.values().size) {
        ModelReadiness.NOT_READY
    }

    init {
        require(counterLimit > 0L) { "counterLimit must be positive" }
    }

    fun recordSwipeDecision(
        latencyMillis: Double,
        decoderSource: DecoderSource,
        candidateCount: Int,
        confidence: ConfidenceBucket,
        outcome: DecisionOutcome,
    ) {
        synchronized(lock) {
            swipeDecisions.record(latencyMillis, candidateCount, confidence, outcome)
            decoderSources.incrementAt(decoderSource.ordinal, counterLimit)
        }
    }

    fun recordTypedDecision(
        latencyMillis: Double,
        candidateCount: Int,
        confidence: ConfidenceBucket,
        outcome: DecisionOutcome,
    ) {
        synchronized(lock) {
            typedDecisions.record(latencyMillis, candidateCount, confidence, outcome)
        }
    }

    fun recordExplicitAlternateSelection(inputMode: QualityInputMode) {
        recordFeedback(inputMode, FeedbackSignal.EXPLICIT_ALTERNATE_SELECTION)
    }

    fun recordImmediateUndo(inputMode: QualityInputMode) {
        recordFeedback(inputMode, FeedbackSignal.IMMEDIATE_UNDO)
    }

    fun recordImmediateCorrection(inputMode: QualityInputMode) {
        recordFeedback(inputMode, FeedbackSignal.IMMEDIATE_CORRECTION)
    }

    fun recordModelReadiness(model: QualityModel, readiness: ModelReadiness) {
        synchronized(lock) {
            currentModelReadiness[model.ordinal] = readiness
            modelObservations[model.ordinal].incrementAt(readiness.ordinal, counterLimit)
        }
    }

    fun snapshot(): TypingQualitySnapshot = synchronized(lock) {
        TypingQualitySnapshot(
            swipe = SwipeQualitySnapshot(
                decisions = swipeDecisions.snapshot(),
                decoderSources = orderedCounts(decoderSources),
            ),
            typed = typedDecisions.snapshot(),
            feedback = QualityInputMode.values().map { inputMode ->
                InputFeedbackSnapshot(
                    inputMode = inputMode,
                    signals = orderedCounts(feedback[inputMode.ordinal]),
                )
            },
            models = QualityModel.values().map { model ->
                ModelReadinessSnapshot(
                    model = model,
                    current = currentModelReadiness[model.ordinal],
                    observations = orderedCounts(modelObservations[model.ordinal]),
                )
            },
        )
    }

    fun reset() {
        synchronized(lock) {
            swipeDecisions.reset()
            typedDecisions.reset()
            decoderSources.fill(0L)
            feedback.forEach { it.fill(0L) }
            modelObservations.forEach { it.fill(0L) }
            currentModelReadiness.fill(ModelReadiness.NOT_READY)
        }
    }

    private fun recordFeedback(inputMode: QualityInputMode, signal: FeedbackSignal) {
        synchronized(lock) {
            feedback[inputMode.ordinal].incrementAt(signal.ordinal, counterLimit)
        }
    }
}

private class DecisionCounters(
    private val counterLimit: Long,
) {
    private var total = 0L
    private val latencies = LongArray(DecodeLatencyBucket.values().size)
    private val candidateCounts = LongArray(CandidateCountBucket.values().size)
    private val confidences = LongArray(ConfidenceBucket.values().size)
    private val outcomes = LongArray(DecisionOutcome.values().size)

    fun record(
        latencyMillis: Double,
        candidateCount: Int,
        confidence: ConfidenceBucket,
        outcome: DecisionOutcome,
    ) {
        if (total < counterLimit) total++
        latencies.incrementAt(DecodeLatencyBucket.fromMillis(latencyMillis).ordinal, counterLimit)
        candidateCounts.incrementAt(CandidateCountBucket.fromCount(candidateCount).ordinal, counterLimit)
        confidences.incrementAt(confidence.ordinal, counterLimit)
        outcomes.incrementAt(outcome.ordinal, counterLimit)
    }

    fun snapshot(): DecisionQualitySnapshot = DecisionQualitySnapshot(
        total = total,
        latencies = orderedCounts(latencies),
        candidateCounts = orderedCounts(candidateCounts),
        confidences = orderedCounts(confidences),
        outcomes = orderedCounts(outcomes),
    )

    fun reset() {
        total = 0L
        latencies.fill(0L)
        candidateCounts.fill(0L)
        confidences.fill(0L)
        outcomes.fill(0L)
    }
}

private fun LongArray.incrementAt(index: Int, limit: Long) {
    if (this[index] < limit) this[index]++
}

private inline fun <reified T : Enum<T>> orderedCounts(values: LongArray): List<BucketCount<T>> =
    enumValues<T>().mapIndexed { index, bucket -> BucketCount(bucket, values[index]) }
