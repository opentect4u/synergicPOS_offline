package com.example.synergic_pos_offline.utils

import com.example.synergic_pos_offline.utils.PrintLanguage.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What the printer is allowed to rewrite, and - more of these - what it is not.
 *
 * A bill is a document a customer checks and a shop files, so the interesting cases
 * here are the ones where nothing should change: a figure, a product name, a
 * statutory short form. A translation that went further than it was told to would be
 * a worse bug than one that did not go far enough, and it would show up on paper.
 */
class PrintLanguageTest {

    // ---- English is exactly what it always was --------------------------------

    @Test
    fun `English is left alone, whatever it says`() {
        listOf("TOTAL", "GRAND TOTAL", "37 bill(s)", "SGST @ 2.50%", "BILL NO: 12").forEach {
            assertEquals(it, PrintLanguage.tr(Language.ENGLISH, it))
        }
    }

    @Test
    fun `an unknown language code reads as English`() {
        assertEquals(Language.ENGLISH, Language.fromStored("ZZ"))
        assertEquals(Language.ENGLISH, Language.fromStored(null))
        assertEquals(Language.ENGLISH, Language.fromStored(""))
    }

    @Test
    fun `a stored code, and the English name, both read back`() {
        assertEquals(Language.TAMIL, Language.fromStored("TA"))
        assertEquals(Language.TAMIL, Language.fromStored("ta"))
        assertEquals(Language.TAMIL, Language.fromStored("Tamil"))
    }

    // ---- The labels that do translate -----------------------------------------

    @Test
    fun `a known label translates`() {
        assertEquals("कुल", PrintLanguage.tr(Language.HINDI, "TOTAL"))
        assertEquals("மொத்தக் கூடுதல்", PrintLanguage.tr(Language.TAMIL, "GRAND TOTAL"))
    }

    @Test
    fun `a compound is built from its words`() {
        // Neither of these is written down; both are assembled from words that are.
        assertEquals("कुल कर", PrintLanguage.tr(Language.HINDI, "TOTAL TAX"))
        assertEquals("बिक्री रिपोर्ट", PrintLanguage.tr(Language.HINDI, "SALE REPORT"))
    }

    @Test
    fun `every language has a word for every entry`() {
        // A blank cell in the dictionary would print the English silently; this is
        // the check that says so out loud instead.
        val labels = listOf("TOTAL", "AMOUNT", "QTY", "BILL", "DISCOUNT", "REPORT", "NAME")
        Language.values().filter { it != Language.ENGLISH }.forEach { language ->
            labels.forEach { label ->
                assertNotEquals(
                    "$language has no word for $label",
                    label, PrintLanguage.tr(language, label)
                )
            }
        }
    }

    // ---- The shapes a printed label comes in ----------------------------------

    @Test
    fun `a label keeps its value and its separator`() {
        assertEquals("बिल नं: 12", PrintLanguage.tr(Language.HINDI, "BILL NO: 12"))
        assertEquals("नाम  : SOMNATH", PrintLanguage.tr(Language.HINDI, "NAME  : SOMNATH"))
    }

    @Test
    fun `a rate is a figure in every language`() {
        // CGST is statutory and stays; only the word beside it could have changed,
        // and there is no word beside it here.
        assertEquals("CGST @ 2.50%", PrintLanguage.tr(Language.HINDI, "CGST @ 2.50%"))
        // The alignment padding an English label carries goes with it: it counts
        // characters, and a translated label is not set in a face where that means
        // anything. Where a slip needs its labels aligned it measures them instead.
        assertEquals("कर%", PrintLanguage.tr(Language.HINDI, "TAX% "))
    }

    @Test
    fun `a count keeps its number`() {
        assertEquals("37 बिल", PrintLanguage.tr(Language.HINDI, "37 bill(s)"))
        assertEquals("6 वस्तुएँ", PrintLanguage.tr(Language.HINDI, "6 item(s)"))
    }

    @Test
    fun `a date range only translates the joining word`() {
        assertEquals(
            "01-08-2026  से  11-08-2026",
            PrintLanguage.tr(Language.HINDI, "01-08-2026  to  11-08-2026")
        )
    }

    @Test
    fun `two labelled figures on one line are translated apart`() {
        assertEquals(
            "मात्रा : 12   राशि : 640.00",
            PrintLanguage.tr(Language.HINDI, "QTY : 12   AMT : 640.00")
        )
    }

    @Test
    fun `a subtitle of two parts keeps its divider`() {
        assertEquals(
            "3 संचालक · 41 बिल",
            PrintLanguage.tr(Language.HINDI, "3 operator(s) · 41 bill(s)")
        )
    }

    @Test
    fun `each line of a stacked label is translated on its own`() {
        assertEquals(
            "वस्तु: 3\nमात्रा: 5",
            PrintLanguage.tr(Language.HINDI, "ITEM: 3\nQTY: 5")
        )
    }

    // ---- What must never be rewritten -----------------------------------------

    @Test
    fun `statutory short forms print as themselves`() {
        listOf("SGST", "CGST", "IGST", "VAT", "GSTIN", "HSN", "KOT", "UDF").forEach {
            assertEquals(it, PrintLanguage.tr(Language.HINDI, it))
        }
    }

    @Test
    fun `a short form inside a label survives the label being translated`() {
        assertEquals("कुल SGST", PrintLanguage.tr(Language.HINDI, "TOTAL SGST"))
        assertEquals("VAT राशि", PrintLanguage.tr(Language.HINDI, "VAT AMOUNT"))
    }

    @Test
    fun `the shop's own words are left alone`() {
        listOf(
            "SHRI BALAJI PROVISION STORE",
            "TOOTHPASTE 100G",
            "SOMNATH THAKUR",
            "9800000000",
            "640.00",
            "01-08-2026"
        ).forEach { assertEquals(it, PrintLanguage.tr(Language.HINDI, it)) }
    }

    @Test
    fun `a label this file has never been told about stays in English`() {
        // The rule the whole thing rests on: no guessing. An untranslated label is
        // an inconvenience; an invented one on a tax invoice is not.
        assertEquals("FREIGHT INWARD", PrintLanguage.tr(Language.HINDI, "FREIGHT INWARD"))
        assertEquals("TOTAL FREIGHT", PrintLanguage.tr(Language.HINDI, "TOTAL FREIGHT"))
    }

    @Test
    fun `blank and empty input come back as they went in`() {
        assertEquals("", PrintLanguage.tr(Language.HINDI, null))
        assertEquals("", PrintLanguage.tr(Language.HINDI, ""))
        assertEquals("   ", PrintLanguage.tr(Language.HINDI, "   "))
    }

    // ---- The lists the renderers hand over ------------------------------------

    @Test
    fun `a list of column headings translates the ones it knows`() {
        assertEquals(
            listOf("बिल", "राशि", "SGST", "CUSTOM"),
            PrintLanguage.tr(Language.HINDI, listOf("BILL", "AMOUNT", "SGST", "CUSTOM"))
        )
    }

    @Test
    fun `a summary translates its labels and not its figures`() {
        assertEquals(
            listOf("कुल बिल" to "41", "कुल राशि" to "12,480.00"),
            PrintLanguage.trLabels(
                Language.HINDI, listOf("TOTAL BILLS" to "41", "TOTAL AMOUNT" to "12,480.00")
            )
        )
    }
}
