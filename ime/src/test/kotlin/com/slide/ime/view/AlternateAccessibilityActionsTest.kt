package com.slide.ime.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlternateAccessibilityActionsTest {

    @Test
    fun `each alternate maps to a unique app resource action`() {
        val ids = AlternateAccessibilityActions.snapshot()
        assertEquals(9, ids.size)
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.all { it != 0 })
        // Framework accessibility action resource IDs use the 0x0102xxxx package/type prefix.
        assertTrue(ids.none { it and -0x10000 == 0x0102_0000 })
    }

    @Test
    fun `dispatch lookup is exact and rejects unrelated actions`() {
        AlternateAccessibilityActions.snapshot().forEachIndexed { index, id ->
            assertEquals(index, AlternateAccessibilityActions.indexOf(id))
            assertEquals(id, AlternateAccessibilityActions.idAt(index))
        }
        assertEquals(-1, AlternateAccessibilityActions.indexOf(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        assertNotEquals(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, AlternateAccessibilityActions.idAt(0))
    }
}
