package com.example.synergic_pos_offline.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a charge's audience survives being saved and read back as.
 *
 * The round trip is the whole point of this type. The column used to hold one word,
 * so a charge ticked for two of the three modes was stored as the nearest word that
 * existed - and came back as a DIFFERENT pair of boxes from the ones that were ticked.
 * Editing a parcel charge to Dine In + QSR, saving, and reopening it showed Takeaway.
 */
class ChargeApplicabilityTest {

    private fun roundTrip(a: ChargeDao.Applicability): ChargeDao.Applicability =
        ChargeDao.Applicability.parse(a.store())

    /** Every one of the eight combinations comes back as itself. */
    @Test
    fun everyCombinationSurvivesTheRoundTrip() {
        val modes = ChargeDao.Mode.entries
        // The power set: 000 through 111 over the three modes.
        for (bits in 0 until (1 shl modes.size)) {
            val picked = modes.filterIndexed { i, _ -> (bits shr i) and 1 == 1 }.toSet()
            val a = ChargeDao.Applicability(picked)
            assertEquals("combination $picked did not survive", a, roundTrip(a))
        }
    }

    /** The case that was actually reported, spelled out. */
    @Test
    fun dineInAndQsrDoesNotComeBackAsTakeaway() {
        val a = ChargeDao.Applicability(setOf(ChargeDao.Mode.DINE_IN, ChargeDao.Mode.QSR))
        val back = roundTrip(a)

        assertTrue("Dine In was lost", back.applies(ChargeDao.Mode.DINE_IN))
        assertTrue("QSR was lost", back.applies(ChargeDao.Mode.QSR))
        assertFalse("Takeaway was invented", back.applies(ChargeDao.Mode.TAKEAWAY))
    }

    /**
     * The words written by every build before this one still read correctly, so a
     * shop's charges keep working across the upgrade with no migration.
     */
    @Test
    fun theOldSingleWordsAreStillUnderstood() {
        // BOTH meant "everywhere", which now includes QSR - a mode that did not exist
        // when the word was written, and which a charge set to "everywhere" wants.
        assertTrue(ChargeDao.Applicability.parse("BOTH").all)
        assertTrue(ChargeDao.Applicability.parse("NONE").none)

        val takeaway = ChargeDao.Applicability.parse("TAKEAWAY")
        assertTrue(takeaway.applies(ChargeDao.Mode.TAKEAWAY))
        assertFalse(takeaway.applies(ChargeDao.Mode.DINE_IN))
        assertFalse(takeaway.applies(ChargeDao.Mode.QSR))

        val dineIn = ChargeDao.Applicability.parse("DINE_IN")
        assertTrue(dineIn.applies(ChargeDao.Mode.DINE_IN))
        assertFalse(dineIn.applies(ChargeDao.Mode.TAKEAWAY))
    }

    /**
     * An unreadable value applies to nothing rather than to everything.
     *
     * The safe way to be wrong: a charge that fails to appear gets noticed and fixed,
     * one that appears on bills it was never meant for is money taken by mistake.
     */
    @Test
    fun rubbishAppliesToNothing() {
        assertTrue(ChargeDao.Applicability.parse("WHATEVER").none)
        assertTrue(ChargeDao.Applicability.parse("DINE_IN,NONSENSE").applies(ChargeDao.Mode.DINE_IN))
        assertFalse(ChargeDao.Applicability.parse("DINE_IN,NONSENSE").all)
    }

    /** A blank column is a row written before the field existed - it applied everywhere. */
    @Test
    fun blankMeansEverywhere() {
        assertTrue(ChargeDao.Applicability.parse(null).all)
        assertTrue(ChargeDao.Applicability.parse("").all)
    }
}
