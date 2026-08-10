package com.slide.ime.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlternatePopupGeometryTest {

    @Test
    fun `wide accent menu is compressed into the viewport`() {
        val count = 12
        val padding = 36f
        val maximumWidth = 1056f
        val cell = AlternatePopupGeometry.cellWidth(
            preferredCellWidth = 120f,
            maximumWidth = maximumWidth,
            itemCount = count,
            horizontalPadding = padding,
            minimumCellWidth = 72f,
            maximumCellWidth = 168f,
        )

        assertTrue(cell * count + padding <= maximumWidth + 0.001f)
        assertEquals((maximumWidth - padding) / count, cell, 0.001f)
    }

    @Test
    fun `short menu keeps a comfortable target width`() {
        assertEquals(
            120f,
            AlternatePopupGeometry.cellWidth(
                preferredCellWidth = 100f,
                maximumWidth = 1056f,
                itemCount = 3,
                horizontalPadding = 36f,
                minimumCellWidth = 120f,
                maximumCellWidth = 168f,
            ),
            0.001f,
        )
    }
}
