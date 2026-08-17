package com.example.synergic_pos_offline.utils

import com.example.synergic_pos_offline.utils.PrintLanguage.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a product name looks like once it has been respelled.
 *
 * The exact spellings below are this transliterator's judgement, not the only
 * defensible one - English does not state its own pronunciation, so a rule-based
 * reading of it is a good guess. They are pinned anyway, because the value of pinning
 * them is that a change to a rule shows up here as the twenty names it moved rather
 * than as a surprise on a customer's bill.
 *
 * The invariants underneath them are the part that must not move: a quantity is never
 * respelled, a name already in another script is never touched, and nothing ever
 * comes back empty.
 */
class TransliteratorTest {

    private fun hi(name: String) = Transliterator.to(Language.HINDI, name)

    // ---- English changes nothing ----------------------------------------------

    @Test
    fun `English leaves every name exactly as it was typed`() {
        listOf("PARLE-G 100G", "TOOTHPASTE", "बासमती", "").forEach {
            assertEquals(it, Transliterator.to(Language.ENGLISH, it))
        }
        assertTrue(!Transliterator.applies(Language.ENGLISH))
    }

    // ---- The spellings this produces -------------------------------------------

    @Test
    fun `common English product names`() {
        assertEquals("टूथ्पेस्ट", hi("TOOTHPASTE"))
        assertEquals("कोलगेट", hi("COLGATE"))
        assertEquals("पेप्सी", hi("PEPSI"))
        assertEquals("सोप", hi("SOAP"))
        assertEquals("बुटर", hi("BUTTER"))
    }

    @Test
    fun `romanised names come back close to how they are written`() {
        // ATTA and AATA are the same word spelled two ways, and land in one place.
        assertEquals("आटा", hi("AATA"))
        assertEquals("आटा", hi("ATTA"))
        assertEquals("घी", hi("GHEE"))
        assertEquals("चावल", hi("CHAWAL"))
        assertEquals("टाटा", hi("TATA"))
        // Longer than the word wants - मसाला is what a person would write - but
        // "maasaalaa" is still read back as masala. See [Transliterator.lengthenOpenA].
        assertEquals("मासाला", hi("MASALA"))
    }

    @Test
    fun `the silent e at the end of a word is dropped, and does its job first`() {
        // Without the rule these would be कोलगेटे and गटे - a syllable that is not
        // said, on a vowel that is now the wrong length.
        assertEquals("गेट", hi("GATE"))
        assertEquals("नोट", hi("NOTE"))
        assertEquals("पेस्ट", hi("PASTE"))
    }

    @Test
    fun `a doubled consonant is one sound`() {
        assertEquals(hi("BUTTER"), hi("BUTER"))
        assertEquals(hi("JALLEBI"), hi("JALEBI"))
    }

    @Test
    fun `a nasal before a stop rides on the vowel`() {
        assertEquals("कंडी", hi("CANDY"))
        assertEquals("संड्विच", hi("SANDWICH"))
    }

    @Test
    fun `an acronym is read out letter by letter`() {
        // The G of PARLE-G is "jee", not a consonant with no vowel after it - and not
        // grams either, which is what it would be after a figure.
        assertEquals("परले-जी", hi("PARLE-G"))
        assertEquals("टीवी", hi("TV"))
        assertEquals("जी", hi("G"))
    }

    @Test
    fun `the same letter is a unit after a number and a letter after a name`() {
        assertTrue(hi("SUGAR 500 G").endsWith("500 G"))
        assertTrue(hi("PARLE-G").endsWith("जी"))
    }

    // ---- The invariants ---------------------------------------------------------

    @Test
    fun `a quantity is never respelled`() {
        // It has to keep matching the unit printed in the quantity column beside it.
        assertTrue(hi("PEPSI 500ML").endsWith("500ML"))
        assertTrue(hi("BASMATI 5KG").endsWith("5KG"))
        assertTrue(hi("SUGAR 1 KG").endsWith("1 KG"))
    }

    @Test
    fun `a name already in another script is left alone`() {
        listOf("बासमती चावल", "அரிசி", "চাল").forEach { assertEquals(it, hi(it)) }
    }

    @Test
    fun `separators survive`() {
        assertTrue(hi("PARLE-G").contains("-"))
        assertTrue(hi("TATA SALT").contains(" "))
        assertTrue(hi("LAYS (MAGIC)").contains("("))
    }

    @Test
    fun `nothing comes back empty or unchanged by accident`() {
        listOf("TOOTHPASTE", "RICE", "OIL", "TEA", "SALT", "SOAP", "MILK").forEach { name ->
            Language.values().filter { it != Language.ENGLISH }.forEach { language ->
                val out = Transliterator.to(language, name)
                assertTrue("$language dropped $name", out.isNotBlank())
                assertTrue("$language left $name in Latin", out.none { it in 'A'..'Z' })
            }
        }
    }

    @Test
    fun `every script produces something readable for the same name`() {
        // Not asserting the spellings - ten of them would be ten more guesses - only
        // that each script actually produced its own, and none fell through to
        // another's letters or to nothing.
        val spellings = Language.values()
            .filter { it != Language.ENGLISH }
            .associateWith { Transliterator.to(it, "RICE") }
        assertEquals(10, spellings.size)
        spellings.forEach { (language, out) ->
            assertTrue("$language produced nothing", out.length >= 2)
        }
        // Hindi and Marathi share Devanagari; Bengali and Assamese share a script but
        // not every letter of it, so those two may or may not agree on a given name.
        assertEquals(spellings[Language.HINDI], spellings[Language.MARATHI])
        assertTrue(spellings.values.toSet().size >= 7)
    }

    @Test
    fun `a name of nothing but punctuation or digits is untouched`() {
        listOf("500", "---", "1+1", "10%").forEach { assertEquals(it, hi(it)) }
    }

    @Test
    fun `null and blank are safe`() {
        assertEquals("", Transliterator.to(Language.HINDI, null))
        assertEquals("   ", Transliterator.to(Language.HINDI, "   "))
    }
}
