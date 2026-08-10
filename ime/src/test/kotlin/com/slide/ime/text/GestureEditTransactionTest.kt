package com.slide.ime.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureEditTransactionTest {
    @Test
    fun `does not append replacement when deletion is rejected`() {
        val operations = mutableListOf<String>()
        val result = GestureEditTransaction.replace(
            original = " Teresa",
            replacement = " that's",
            deleteBeforeCursor = {
                operations += "delete:$it"
                false
            },
            commit = {
                operations += "commit:$it"
                true
            },
        )

        assertFalse(result.replaced)
        assertFalse(result.deleted)
        assertEquals(listOf("delete:7"), operations)
    }

    @Test
    fun `restores original when replacement commit is rejected`() {
        val operations = mutableListOf<String>()
        val result = GestureEditTransaction.replace(
            original = " wrong",
            replacement = " right",
            deleteBeforeCursor = {
                operations += "delete:$it"
                true
            },
            commit = {
                operations += "commit:$it"
                it == " wrong"
            },
        )

        assertFalse(result.replaced)
        assertTrue(result.deleted)
        assertTrue(result.restoredOriginal)
        assertEquals(listOf("delete:6", "commit: right", "commit: wrong"), operations)
    }

    @Test
    fun `successful replacement does not restore original`() {
        val operations = mutableListOf<String>()
        val result = GestureEditTransaction.replace(
            original = " Teresa",
            replacement = " that's",
            deleteBeforeCursor = {
                operations += "delete:$it"
                true
            },
            commit = {
                operations += "commit:$it"
                true
            },
        )

        assertTrue(result.replaced)
        assertEquals(listOf("delete:7", "commit: that's"), operations)
    }
}
