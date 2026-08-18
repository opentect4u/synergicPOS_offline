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
        assertEquals("कॉलगेट", hi("COLGATE"))
        assertEquals("पेप्सी", hi("PEPSI"))
        assertEquals("सोप", hi("SOAP"))
        assertEquals("बुटर", hi("BUTTER"))
    }

    @Test
    fun `romanised names come back close to how they are written`() {
        assertEquals("आटा", hi("AATA"))
        // ATTA's doubled t closes its first syllable, so the bare transliterator
        // reads it ऐटा. The word is in the lexicon, so a bill still prints आटा -
        // see ProductNameTest.
        assertEquals("ऐटा", hi("ATTA"))
        assertEquals("घी", hi("GHEE"))
        assertEquals("चावल", hi("CHAWAL"))
        assertEquals("टाटा", hi("TATA"))
        // Respelled outright: a romanised "a" is the inherent vowel here, which no
        // rule reading English spelling would guess. See [Transliterator.EXCEPTIONS].
        assertEquals("मसाला", hi("MASALA"))
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
    fun `a doubled consonant is one letter, and closes the syllable before it`() {
        // One letter: BUTTER and BUTER land in the same place.
        assertEquals(hi("BUTTER"), hi("BUTER"))
        // But not the same word as the single: in English a doubled consonant is what
        // keeps the vowel before it short, which is the whole reason MAGGI is मैगी and
        // not मागी. JALLEBI and JALEBI are therefore allowed to differ.
        assertEquals("मैगी", hi("MAGGI"))
        assertEquals("मागी", hi("MAGI"))
    }

    @Test
    fun `a nasal before a stop rides on the vowel`() {
        assertEquals("कैंडी", hi("CANDY"))
        assertEquals("सैंड्विच", hi("SANDWICH"))
    }

    @Test
    fun `a word whose only vowel is y is a word, not an acronym`() {
        // FISH FRY once printed as "मछली एफआरवै" - F, R and Y read out one at a time,
        // because the acronym test did not count y as a vowel.
        assertEquals("फ्राइ", hi("FRY"))
        assertEquals("ड्राइ", hi("DRY"))
        // …while a y beside another vowel is still the short ending of CANDY.
        assertEquals("कैंडी", hi("CANDY"))
    }

    @Test
    fun `the short o of HOT is not the long one of SOAP`() {
        // Devanagari marks it; Bengali and Odia leave it to the inherent vowel their
        // consonants already carry, which is why one has a sign here and the other
        // has none.
        assertEquals("हॉट", hi("HOT"))
        assertEquals("হট", Transliterator.to(Language.BENGALI, "HOT"))
        assertEquals("सोप", hi("SOAP"))
        assertEquals("सोडा", hi("SODA"))
    }

    @Test
    fun `the flat a of AND has a vowel of its own`() {
        // Read as a plain "a" this was अंड and অংড - not the word, in either script.
        assertEquals("ऐंड", hi("AND"))
        assertEquals("অ্যান্ড", Transliterator.to(Language.BENGALI, "AND"))
        // …but -al is still faint, and SALT still has its aw.
        assertEquals("मसाला", hi("MASALA"))
        assertEquals("चावल", hi("CHAWAL"))
        assertEquals("सॉल्ट", hi("SALT"))
    }

    @Test
    fun `the names a shop actually types`() {
        // The six brought back from a real catalogue, pinned as they were asked for.
        val bn = { n: String -> Transliterator.to(Language.BENGALI, n) }
        assertEquals("জনসন'স বেবি ক্রিম", bn("JOHNSON'S BABY CREAM"))
        assertEquals("ম্যাগি", bn("MAGGI"))
        assertEquals("ভেজ ফ্রাইড রাইস", bn("VEG FRIED RICE"))
        assertEquals("वेज फ्राइड राइस", hi("VEG FRIED RICE"))
        // The possessive hisses; it is not read out as the letter "ess".
        assertTrue(bn("JOHNSON'S").endsWith("স"))
    }

    @Test
    fun `the romanised words a menu is full of`() {
        // Respelled outright in [Transliterator.EXCEPTIONS]: English spelling gives a
        // rule no way to know that the "a" of PANEER is the inherent vowel while the
        // one of MASALA's second syllable is long, or that TIKKA's doubled k is a
        // conjunct that is actually said while BUTTER's doubled t is not.
        assertEquals("पनीर", hi("PANEER"))
        assertEquals("टिक्का", hi("TIKKA"))
        assertEquals("मसाला", hi("MASALA"))
        assertEquals("चिकन", hi("CHICKEN"))
        assertEquals("मंचूरियन", hi("MANCHURIAN"))
        assertEquals("ब्रिटानिया", hi("BRITANNIA"))
        assertEquals("बिस्कुट", hi("BISCUITS"))
        assertEquals("पैटीज़", hi("PATTIES"))
    }

    @Test
    fun `the y-glide and the z each have their own letter`() {
        // Bengali writes the English y with য় - ব্রিটানিয়া, not ব্রিটানিযা - and
        // Devanagari marks a borrowed z with a nukta.
        assertEquals("ব্রিটানিয়া", Transliterator.to(Language.BENGALI, "BRITANNIA"))
        assertTrue(hi("PATTIES").contains("ज़"))
    }

    @Test
    fun `a restaurant dish reads as the dish`() {
        assertEquals("হট অ্যান্ড সৌর সূপ", Transliterator.to(Language.BENGALI, "HOT AND SOUR SOUP"))
        assertEquals("हॉट ऐंड सौर सूप", hi("HOT AND SOUR SOUP"))
    }

    @Test
    fun `each script writes the nasal its own way`() {
        // Devanagari puts a mark over the vowel; Bengali joins a letter to what
        // follows; Tamil picks the nasal that matches the consonant after it.
        assertTrue(hi("AND").contains("ं"))
        assertTrue(Transliterator.to(Language.BENGALI, "AND").contains("ন্"))
        assertTrue(Transliterator.to(Language.TAMIL, "AND").contains("ண்"))
        // And no script leaves a virama sitting on nothing - SPRING once did.
        Language.values().filter { it != Language.ENGLISH }.forEach { language ->
            val out = Transliterator.to(language, "SPRING")
            assertTrue("$language left a mark on nothing in $out", !out.contains(" ்"))
            assertTrue("$language dropped SPRING", out.isNotBlank())
        }
    }

    @Test
    fun `English ai is the vowel of PLAIN and TRAIN`() {
        assertEquals("प्लेन", hi("PLAIN"))
        assertEquals("ट्रेन", hi("TRAIN"))
    }

    @Test
    fun `an acronym is read out letter by letter`() {
        // The G of PARLE-G is "jee", not a consonant with no vowel after it - and not
        // grams either, which is what it would be after a figure.
        assertEquals("पारले-जी", hi("PARLE-G"))
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
