package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTableTest {

    @Test
    fun `spare room is shared out so the table fills the card`() {
        val stretched = ReportTable.stretch(intArrayOf(100, 200, 100), availablePx = 800)
        assertEquals("should fill the card exactly", 800, stretched.sum())
        // Twice as wide before, twice as wide after.
        assertEquals(2.0, stretched[1].toDouble() / stretched[0], 0.02)
    }

    @Test
    fun `a table wider than the card keeps its widths and scrolls`() {
        val mins = intArrayOf(300, 400, 300)
        assertArrayEquals(mins, ReportTable.stretch(mins, availablePx = 500))
    }

    @Test
    fun `an exactly-fitting table is left alone`() {
        val mins = intArrayOf(250, 250)
        assertArrayEquals(mins, ReportTable.stretch(mins, availablePx = 500))
    }

    @Test
    fun `rounding never leaves a gap at the right edge`() {
        // Widths that do not divide evenly into the space available.
        val stretched = ReportTable.stretch(intArrayOf(70, 260, 100, 120), availablePx = 1237)
        assertEquals(1237, stretched.sum())
        assertTrue("no column may collapse", stretched.all { it > 0 })
    }

    @Test
    fun `nothing to lay out is handled rather than divided by zero`() {
        assertArrayEquals(intArrayOf(), ReportTable.stretch(intArrayOf(), availablePx = 800))
        assertArrayEquals(intArrayOf(0, 0), ReportTable.stretch(intArrayOf(0, 0), availablePx = 800))
    }
}
