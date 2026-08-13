package com.slide.engine.gesture

import java.io.Closeable
import java.io.Writer
import java.util.Locale

internal enum class TypingQualityInputKind(val wireName: String) {
    TYPING("typing"),
    SWIPE("swipe"),
}

/** Content-free benchmark outcome matching tools/typing_quality_report.py schema version 1. */
internal data class TypingQualityCase(
    val inputKind: TypingQualityInputKind,
    val expectedRank: Int?,
    val committed: Boolean,
    val usedFallback: Boolean,
    val latencyMillis: Double,
    val confidence: Double,
) {
    init {
        require(expectedRank == null || expectedRank >= 1)
        require(latencyMillis.isFinite() && latencyMillis >= 0.0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

/** Writes only the closed, content-free schema; there is no field through which text can leak. */
internal class TypingQualityJsonlWriter(
    private val destination: Writer,
) : Closeable {
    fun append(case: TypingQualityCase) {
        destination.append("{\"schema_version\":1,\"input_kind\":\"")
        destination.append(case.inputKind.wireName)
        destination.append("\",\"expected_rank\":")
        destination.append(case.expectedRank?.toString() ?: "null")
        destination.append(",\"committed\":${case.committed}")
        destination.append(",\"used_fallback\":${case.usedFallback}")
        destination.append(",\"latency_ms\":${case.latencyMillis.wireNumber()}")
        destination.append(",\"confidence\":${case.confidence.wireNumber()}}\n")
    }

    override fun close() = destination.close()

    private fun Double.wireNumber(): String = String.format(Locale.ROOT, "%.6f", this)
}
