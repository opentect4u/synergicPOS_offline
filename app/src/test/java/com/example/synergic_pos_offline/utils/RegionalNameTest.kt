package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which name a slip prints: the one the shop wrote, or the one the app guesses.
 *
 * [RegionalName.map] needs a database and is not covered here; the rule that decides
 * between the two does not, and it is the rule every bill and KOT line goes through.
 */
class RegionalNameTest {

    private val hindi = PrintLanguage.Language.HINDI
    private val saved = mapOf("TATA SALT" to "टाटा नमक")

    /** A name the shop wrote wins over anything the lexicon would produce. */
    @Test
    fun theShopsOwnNameIsUsedWhenThereIsOne() {
        assertEquals("टाटा नमक", RegionalName.forPrint(saved, hindi, "TATA SALT"))
    }

    /**
     * Looked up case- and space-insensitively, because a bill line carries the name as
     * it was SOLD - the renderer upper-cases it first, and a stored name may have been
     * typed with a stray trailing space.
     */
    @Test
    fun theLookupIgnoresCaseAndSurroundingSpace() {
        listOf("tata salt", "Tata Salt", "  TATA SALT  ").forEach {
            assertEquals("looked up '$it'", "टाटा नमक", RegionalName.forPrint(saved, hindi, it))
        }
    }

    /**
     * Nothing saved falls back to the machine translation - so a catalogue nobody has
     * edited prints exactly as it did before this column existed.
     */
    @Test
    fun withoutASavedNameItFallsBackToTheTranslation() {
        val name = "SUGAR"
        assertEquals(
            ProductName.inPrintLanguage(hindi, name),
            RegionalName.forPrint(saved, hindi, name)
        )
        // And with no saved names at all, every line takes that path.
        assertEquals(
            ProductName.inPrintLanguage(hindi, "TATA SALT"),
            RegionalName.forPrint(emptyMap(), hindi, "TATA SALT")
        )
    }

    /**
     * An English slip still gets the shop's own name where one is saved.
     *
     * The regional name belongs to the PRODUCT, in the language the product master was
     * set to - it is not a function of what language the slip's own words are in. A
     * shop that prints its headings in English still sells टाटा नमक.
     */
    @Test
    fun theSavedNameDoesNotDependOnThePrintLanguage() {
        assertEquals(
            "टाटा नमक",
            RegionalName.forPrint(saved, PrintLanguage.Language.ENGLISH, "TATA SALT")
        )
    }

    /**
     * A master moved from Hindi to Bangla prints no Hindi.
     *
     * The scenario the per-language table exists for. A shop that has named its whole
     * catalogue in Hindi and then moves the master to Bangla is re-entering it, one
     * product at a time - and until a product's turn comes, the Hindi name it already
     * has must NOT be what prints on a Bangla slip.
     *
     * That falls out of [RegionalName.map] asking for one language's rows: the map a
     * Bangla master builds simply does not contain the Hindi names, so every
     * un-re-entered product takes the fallback and the bill reads as one document.
     * The Hindi rows are still there, untouched, for a shop that moves back.
     */
    @Test
    fun aMasterMovedToBanglaPrintsNoHindi() {
        val bengali = PrintLanguage.Language.BENGALI
        // What a Bangla master sees: TATA SALT re-entered, SUGAR not yet - even though
        // SUGAR is named in Hindi, which lives in rows this map never asked for.
        val banglaMaster = mapOf("TATA SALT" to "টাটা লবণ")

        assertEquals("টাটা লবণ", RegionalName.forPrint(banglaMaster, bengali, "TATA SALT"))
        assertEquals(
            ProductName.inPrintLanguage(bengali, "SUGAR"),
            RegionalName.forPrint(banglaMaster, bengali, "SUGAR")
        )
        // Nothing on that slip is the Hindi name the shop has not got to yet.
        assertEquals("टाटा नमक", saved["TATA SALT"])
        assertEquals(null, banglaMaster["SUGAR"])
    }

    /** With nothing saved, English is left exactly as it was typed. */
    @Test
    fun englishWithNothingSavedIsUntouched() {
        assertEquals(
            "SUGAR",
            RegionalName.forPrint(emptyMap(), PrintLanguage.Language.ENGLISH, "SUGAR")
        )
    }
}
