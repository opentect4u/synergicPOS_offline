package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class AmountInWordsTest {

    @Test
    fun `spells the amounts currently on file`() {
        assertEquals("Rupees Fifteen and Seventy Five Paise Only", AmountInWords.of(15.75))
        assertEquals("Rupees Thirty One and Fifty Paise Only", AmountInWords.of(31.50))
        assertEquals("Rupees Seventy Eight and Seventy Five Paise Only", AmountInWords.of(78.75))
    }

    @Test
    fun `omits paise when the amount is whole`() {
        assertEquals("Rupees One Hundred Only", AmountInWords.of(100.0))
        assertEquals("Rupees Zero Only", AmountInWords.of(0.0))
    }

    @Test
    fun `carries a rounded fraction into rupees instead of printing 100 paise`() {
        assertEquals("Rupees Ten Only", AmountInWords.of(9.999))
    }

    @Test
    fun `groups by lakh and crore rather than western thousands`() {
        assertEquals("Rupees One Thousand Only", AmountInWords.of(1_000.0))
        assertEquals("Rupees One Lakh Only", AmountInWords.of(100_000.0))
        assertEquals("Rupees One Crore Only", AmountInWords.of(10_000_000.0))
        assertEquals(
            "Rupees Twelve Lakh Thirty Four Thousand Five Hundred Sixty Seven and Fifty Paise Only",
            AmountInWords.of(1_234_567.50)
        )
    }

    @Test
    fun `handles a place value above ninety nine`() {
        assertEquals("Rupees Two Hundred Fifty Crore Only", AmountInWords.of(2_500_000_000.0))
    }

    @Test
    fun `handles the teens and round tens`() {
        assertEquals("Rupees Nineteen Only", AmountInWords.of(19.0))
        assertEquals("Rupees Twenty Only", AmountInWords.of(20.0))
        assertEquals("Rupees Ninety Nine and Ninety Nine Paise Only", AmountInWords.of(99.99))
    }

    @Test
    fun `falls back to zero for negative or invalid input`() {
        assertEquals("Rupees Zero Only", AmountInWords.of(-5.0))
        assertEquals("Rupees Zero Only", AmountInWords.of(Double.NaN))
    }
}
