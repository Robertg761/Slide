package com.slide.engine.gesture

import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TypingQualityJsonlTest {
    @Test
    fun `writer emits the closed privacy safe report schema deterministically`() {
        val output = StringWriter()
        TypingQualityJsonlWriter(output).append(
            TypingQualityCase(
                inputKind = TypingQualityInputKind.SWIPE,
                expectedRank = 2,
                committed = true,
                usedFallback = false,
                latencyMillis = 4.25,
                confidence = 0.875,
            ),
        )

        assertEquals(
            "{\"schema_version\":1,\"input_kind\":\"swipe\",\"expected_rank\":2," +
                "\"committed\":true,\"used_fallback\":false,\"latency_ms\":4.250000," +
                "\"confidence\":0.875000}\n",
            output.toString(),
        )
    }

    @Test
    fun `invalid numeric outcomes fail before anything is written`() {
        assertThrows(IllegalArgumentException::class.java) {
            TypingQualityCase(TypingQualityInputKind.SWIPE, 0, true, false, 1.0, 0.5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TypingQualityCase(TypingQualityInputKind.SWIPE, null, false, false, -1.0, 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TypingQualityCase(TypingQualityInputKind.SWIPE, null, false, false, 1.0, Double.NaN)
        }
    }
}
