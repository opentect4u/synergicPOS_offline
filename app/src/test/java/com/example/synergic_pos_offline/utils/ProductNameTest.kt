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
        assertEquals("নুন", bn("SALT"))
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
        // The whole point of the split. TATA is the company; SALT is the thing.
        assertEquals("टाटा नमक", hi("TATA SALT"))
        assertEquals("டாடா உப்பு", ta("TATA SALT"))
        assertEquals("आमुल मक्खन", hi("AMUL BUTTER"))
        assertEquals("कोलगेट टूथपेस्ट", hi("COLGATE TOOTHPASTE"))
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
        assertEquals("परले-जी बिस्कुट", hi("PARLE-G BISCUIT"))
        assertTrue(hi("LAYS (MAGIC MASALA)").contains("("))
    }

    @Test
    fun `a name already in another script is left alone`() {
        assertEquals("बासमती चावल", hi("बासमती चावल"))
        // …and a half-typed one gets only the half that is still English.
        assertEquals("बासमती चावल", hi("बासमती RICE"))
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
