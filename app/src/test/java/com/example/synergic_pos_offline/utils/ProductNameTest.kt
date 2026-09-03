package com.example.synergic_pos_offline.utils

import com.example.synergic_pos_offline.utils.PrintLanguage.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which half of a product name is translated and which is respelled.
 *
 * The line worth defending is between the two: a word that names the thing has a
 * translation and should get it, and a word that names the maker has none and must
 * not be given one. Nearly every test here is that line in one shape or another.
 */
class ProductNameTest {

    private fun hi(name: String) = ProductName.inPrintLanguage(Language.HINDI, name)
    private fun ta(name: String) = ProductName.inPrintLanguage(Language.TAMIL, name)
    private fun bn(name: String) = ProductName.inPrintLanguage(Language.BENGALI, name)

    // ---- English is untouched ---------------------------------------------------

    @Test
    fun `English changes nothing`() {
        listOf("TATA SALT", "BASMATI RICE 5KG", "").forEach {
            assertEquals(it, ProductName.inPrintLanguage(Language.ENGLISH, it))
        }
    }

    // ---- The word that names the thing is translated ----------------------------

    @Test
    fun `an everyday retail word is translated, not spelled out`() {
        assertEquals("नमक", hi("SALT"))
        assertEquals("உப்பு", ta("SALT"))
        assertEquals("লবণ", bn("SALT"))
        assertEquals("चावल", hi("RICE"))
        assertEquals("साबुन", hi("SOAP"))
        assertEquals("दूध", hi("MILK"))
    }

    @Test
    fun `a phrase that means more than its words is read whole`() {
        // Not "black" and "pepper" - one spice, with one name.
        assertEquals("काली मिर्च", hi("BLACK PEPPER"))
        // Not a finger.
        assertEquals("भिंडी", hi("LADY FINGER"))
        assertEquals("बेसन", hi("GRAM FLOUR"))
    }

    @Test
    fun `a plural is answered by its singular`() {
        assertEquals(hi("EGG"), hi("EGGS"))
        assertEquals(hi("BISCUIT"), hi("BISCUITS"))
    }

    // ---- The word that names the maker is not -----------------------------------

    @Test
    fun `a brand is spelled, never translated`() {
        // A name with a brand in it is spelled all the way through - see
        // `a name is translated only when every word of it is known`.
        assertEquals("टाटा सॉल्ट", hi("TATA SALT"))
        assertEquals("आमुल बुटर", hi("AMUL BUTTER"))
        // Spelled, not translated - COLGATE is a brand, so the whole name is spelled.
        assertEquals("कॉलगेट टूथ्पेस्ट", hi("COLGATE TOOTHPASTE"))
    }

    @Test
    fun `a name is translated only when every word of it is known`() {
        // All of it: both words are trade words.
        assertEquals("सरसों तेल", hi("MUSTARD OIL"))
        assertEquals("मक्खन", hi("BUTTER"))
        // None of it: FRIED is nobody's commodity, so the dish keeps its own name
        // rather than being half-answered as "वेग फ्रीड चावल".
        assertEquals("वेज फ्राइड राइस", hi("VEG FRIED RICE"))
        assertEquals("ভেজ ফ্রাইড রাইস", bn("VEG FRIED RICE"))
        // …and RICE on its own is still the commodity it is.
        assertEquals("चावल", hi("RICE"))
    }

    @Test
    fun `a colour is translated inside a phrase and left alone outside one`() {
        // RED means something in RED CHILLI and nothing in RED LABEL, which is a name.
        // So the colour lives in the phrase table and not in the word table.
        assertEquals("लाल मिर्च", hi("RED CHILLI"))
        assertTrue(
            "RED LABEL is a name, and its first word should have been spelled",
            !hi("RED LABEL").startsWith("लाल")
        )
    }

    @Test
    fun `a word that only looks like a word keeps its own meaning`() {
        // PEN DRIVE is not a pen, and the phrase table is what stops it becoming one.
        assertEquals("पेन ड्राइव", hi("PEN DRIVE"))
        assertEquals("कलम", hi("PEN"))
    }

    // ---- Everything around the words --------------------------------------------

    @Test
    fun `quantities and units come through untouched`() {
        assertEquals("बासमती चावल 5KG", hi("BASMATI RICE 5KG"))
        assertEquals("दूध 500ML", hi("MILK 500ML"))
        assertEquals("सरसों तेल 1L", hi("MUSTARD OIL 1L"))
    }

    @Test
    fun `spacing and punctuation are where they were`() {
        assertEquals("पारले-जी बिस्कुट", hi("PARLE-G BISCUIT"))
        assertTrue(hi("LAYS (MAGIC MASALA)").contains("("))
    }

    @Test
    fun `a name already in another script is left alone`() {
        assertEquals("बासमती चावल", hi("बासमती चावल"))
        // …and a half-typed one has its English half spelled, since बासमती is not a
        // word this file knows and the name is therefore not translated at all.
        assertEquals("बासमती राइस", hi("बासमती RICE"))
    }

    // ---- The safety property -----------------------------------------------------

    @Test
    fun `a word the lexicon will not claim is spelled rather than guessed`() {
        // CANDY has no entry in Odia, deliberately - so Odia spells it instead of
        // printing a word this file was not sure of. That is the fallback that makes
        // a blank cell a safe thing to leave.
        val odia = ProductName.inPrintLanguage(Language.ODIA, "CANDY")
        assertTrue("Odia should have spelled CANDY", odia.isNotBlank())
        assertTrue("Odia should not have been left in Latin", odia.none { it in 'A'..'Z' })
        assertEquals("टॉफी", hi("CANDY"))
    }

    @Test
    fun `an unknown word is spelled, and never dropped`() {
        listOf("ZORBEX", "KWALITY", "XYZ BRAND OIL").forEach { name ->
            Language.values().filter { it != Language.ENGLISH }.forEach { language ->
                val out = ProductName.inPrintLanguage(language, name)
                assertTrue("$language dropped $name", out.isNotBlank())
                assertTrue("$language left $name in Latin", out.none { it in 'A'..'Z' })
            }
        }
    }

    @Test
    fun `every language answers for the core vocabulary`() {
        // Not what the word is - that is the lexicon's business - only that each
        // language produced something in its own letters for the words a shop cannot
        // do without.
        listOf("RICE", "SUGAR", "SALT", "OIL", "MILK", "TEA", "SOAP").forEach { word ->
            Language.values().filter { it != Language.ENGLISH }.forEach { language ->
                val out = ProductName.inPrintLanguage(language, word)
                assertTrue("$language has nothing for $word", out.length >= 2)
                assertTrue("$language left $word in Latin", out.none { it in 'A'..'Z' })
            }
        }
    }

    @Test
    fun `a cell that holds a figure comes back untouched`() {
        // What lets a report declare a name column without having to be sure every
        // row of it is a name - see PeriodReportRenderer.Content.nameColumns. A
        // report that mislabels a money or quantity column loses nothing by it.
        listOf("1,250.00", "5", "0.00", "-", "12.50", "5 PKT", "2 KG").forEach {
            assertEquals(it, hi(it))
        }
    }

    @Test
    fun `null and blank are safe`() {
        assertEquals("", ProductName.inPrintLanguage(Language.HINDI, null))
        assertEquals("   ", ProductName.inPrintLanguage(Language.HINDI, "   "))
    }
}
