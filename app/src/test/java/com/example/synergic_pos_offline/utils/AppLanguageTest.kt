package com.example.synergic_pos_offline.utils

import com.example.synergic_pos_offline.utils.PrintLanguage.Language
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How the app's own screens read in another language.
 *
 * The rule these pin down is the one that separates this from [PrintLanguage]: the
 * screen is *transliterated*, not translated. A bill says the Bengali word for tax
 * because a customer reads it; the screen says ট্যাক্স because the operator says
 * "tax" out loud.
 */
class AppLanguageTest {

    private val bn = Language.BENGALI

    @Test
    fun `the spellings the letters get wrong are corrected`() {
        // English writes a sound it does not say - "User" is "yoo-zer" - and writes
        // one letter for two sounds, as the hard c in "Duplicate".
        assertEquals("ইউজার", AppLanguage.tr(bn, "User"))
        assertEquals("ডুপ্লিকেট", AppLanguage.tr(bn, "Duplicate"))
        assertEquals("প্রডাক্ট", AppLanguage.tr(bn, "Product"))
    }

    @Test
    fun `a word the letters already get right is left to the machine`() {
        // Deliberately absent from CORRECTIONS: the transliterator produces this on
        // its own, and a row here would only be a second place to keep it.
        assertEquals("ট্যাক্স", AppLanguage.tr(bn, "Tax"))
    }

    @Test
    fun `a correction reaches every phrase the word appears in`() {
        // The point of correcting word by word: ORDER is written down once and turns
        // up right in every label built out of it.
        assertEquals("অর্ডার ইটেম্স", AppLanguage.tr(bn, "Order Items"))
        assertEquals("কাস্টমার নেম", AppLanguage.tr(bn, "Customer Name"))
    }

    @Test
    fun `punctuation a word arrives wearing is kept`() {
        assertEquals("অ্যাক্টাইভ অর্ডারস (15)", AppLanguage.tr(bn, "Active Orders (15)"))
        assertEquals("টোটাল:", AppLanguage.tr(bn, "Total:"))
    }

    @Test
    fun `figures and currency are not letters and are left alone`() {
        assertEquals("₹ 100.00", AppLanguage.tr(bn, "₹ 100.00"))
        assertEquals("15", AppLanguage.tr(bn, "15"))
    }

    @Test
    fun `English is returned untouched`() {
        assertEquals("Settings", AppLanguage.tr(Language.ENGLISH, "Settings"))
        assertEquals("", AppLanguage.tr(bn, null))
    }

    @Test
    fun `every language has a full row of corrections`() {
        // A short row would silently leave the languages past it on the machine's
        // answer for that word, which is the bug this catches.
        val expected = Language.values().size - 1
        listOf("USER", "PRODUCT", "DUPLICATE", "ORDER", "TOTAL").forEach { key ->
            assertEquals(key, expected, AppLanguage.correctionsFor(key)?.size)
        }
    }
}
