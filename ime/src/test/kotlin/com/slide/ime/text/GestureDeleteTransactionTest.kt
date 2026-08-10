package com.slide.ime.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureDeleteTransactionTest {
    @Test
    fun `accepted range delete stays atomic`() {
        var unitCalls = 0
        val result = GestureDeleteTransaction.delete(
            units = 7,
            deleteRange = { it == 7 },
            deleteUnit = { unitCalls++; true },
        )

        assertTrue(result.fullyDeleted)
        assertFalse(result.usedUnitFallback)
        assertEquals(0, unitCalls)
    }

    @Test
    fun `rejected range delete falls back to the complete verified length`() {
        var unitCalls = 0
        val result = GestureDeleteTransaction.delete(
            units = 7,
            deleteRange = { false },
            deleteUnit = { unitCalls++; true },
        )

        assertTrue(result.fullyDeleted)
        assertTrue(result.usedUnitFallback)
        assertEquals(7, result.deletedUnits)
        assertEquals(7, unitCalls)
    }

    @Test
    fun `partial unit failure reports exactly what changed`() {
        var accepted = 0
        val result = GestureDeleteTransaction.delete(
            units = 7,
            deleteRange = { false },
            deleteUnit = { accepted++ < 3 },
        )

        assertFalse(result.fullyDeleted)
        assertEquals(3, result.deletedUnits)
    }
}
