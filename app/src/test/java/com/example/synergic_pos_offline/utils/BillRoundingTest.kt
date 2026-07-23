package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillRoundingTest {

    private val delta = 0.0001

    @Test
    fun `rounds up and records what was added`() {
        assertEquals(79.0, BillRounding.payable(78.75), delta)
        assertEquals(0.25, BillRounding.roundOff(78.75), delta)
    }

    @Test
    fun `rounds down and records what was knocked off`() {
        assertEquals(78.0, BillRounding.payable(78.20), delta)
        assertEquals(-0.20, BillRounding.roundOff(78.20), delta)
    }

    @Test
    fun `a half rupee rounds up`() {
        assertEquals(79.0, BillRounding.payable(78.50), delta)
        assertEquals(0.50, BillRounding.roundOff(78.50), delta)
    }

    @Test
    fun `a whole amount is left alone`() {
        assertEquals(80.0, BillRounding.payable(80.0), delta)
        assertEquals(0.0, BillRounding.roundOff(80.0), delta)
        assertTrue(BillRounding.isExact(80.0))
        assertFalse(BillRounding.isExact(78.75))
    }

    @Test
    fun `a total assembled from percentages still rounds correctly`() {
        // 78.499999 is 78.50 once normalised to paise, so it must round up.
        assertEquals(79.0, BillRounding.payable(78.499999), delta)
        // And 78.4 must not.
        assertEquals(78.0, BillRounding.payable(78.4), delta)
    }

    @Test
    fun `the adjustment always reconciles the taxed value to the payable`() {
        listOf(15.75, 31.50, 78.75, 94.50, 110.25, 141.75, 0.49, 0.50, 1.01).forEach { amount ->
            assertEquals(
                "round off must bridge $amount to its payable",
                BillRounding.payable(amount),
                amount + BillRounding.roundOff(amount),
                0.005
            )
        }
    }

    @Test
    fun `zero stays zero`() {
        assertEquals(0.0, BillRounding.payable(0.0), delta)
        assertEquals(0.0, BillRounding.roundOff(0.0), delta)
    }
}
