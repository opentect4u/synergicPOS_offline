package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A quantity reads the same on the screen and on the paper.
 *
 * It did not. Every screen wrote three decimals and every printed document wrote two,
 * so 0.125 kg was quoted on the till and printed as 0.13 on the bill and the KOT.
 */
class QuantityTest {

    /** THE BUG: three decimals survive, on paper as well as on screen. */
    @Test
    fun aThirdDecimalIsNotRoundedAway() {
        assertEquals("0.125", Quantity.text(0.125))
        assertEquals("1.005", Quantity.text(1.005))
        assertEquals("12.375", Quantity.text(12.375))
    }

    /** A whole quantity is whole - 2, never 2.00 or 2.000. */
    @Test
    fun wholeQuantitiesCarryNoDecimals() {
        assertEquals("2", Quantity.text(2.0))
        assertEquals("0", Quantity.text(0.0))
        assertEquals("1000", Quantity.text(1000.0))
    }

    /**
     * And a fraction carries only the places it needs. A quantity is a count of goods,
     * not money: it is never padded out to a fixed width the way a price is.
     */
    @Test
    fun aFractionKeepsOnlyThePlacesItNeeds() {
        assertEquals("0.5", Quantity.text(0.5))
        assertEquals("2.25", Quantity.text(2.25))
        assertEquals("1.5", Quantity.text(1.50))
    }

    /** The field accepts exactly what this prints - see ProductEntryDialog's filter. */
    @Test
    fun theFieldAndThePrintAgreeOnHowManyPlaces() {
        assertEquals(3, Quantity.DECIMALS)
        assertEquals(ProductEntryDialog.QTY_DECIMALS, Quantity.DECIMALS)
    }

    /** The KOT's column reserves room for the widest quantity it can be asked to set. */
    @Test
    fun theWidestSampleCoversEveryPlace() {
        assertEquals("0000.000", Quantity.WIDEST_SAMPLE)
        assertEquals(Quantity.WIDEST_SAMPLE.length, Quantity.text(1234.125).length)
    }
}
