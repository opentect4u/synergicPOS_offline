package com.example.synergic_pos_offline.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a shift is running - the question that decides whether a general user may sign
 * in at all. See LoginFragment.offShift.
 */
class ShiftWindowTest {

    private fun shift(from: String, to: String) =
        ShiftDao.Shift(id = 1, name = "Test", fromTime = from, toTime = to)

    private fun at(clock: String): Int = ShiftDao.minutesOf(clock)!!

    @Test
    fun anOrdinaryShiftCoversItsOwnHours() {
        val morning = shift("06:00", "14:00")

        assertTrue(morning.coversNow(at("06:00")))   // the minute it opens
        assertTrue(morning.coversNow(at("10:30")))
        assertTrue(morning.coversNow(at("13:59")))
        assertFalse(morning.coversNow(at("05:59")))
        assertFalse(morning.coversNow(at("18:00")))
    }

    /**
     * The end is exclusive, so a day divided into back-to-back shifts hands over
     * cleanly instead of having both live for the minute they meet.
     */
    @Test
    fun theEndOfAShiftIsNotPartOfIt() {
        val morning = shift("06:00", "14:00")
        val afternoon = shift("14:00", "22:00")

        assertFalse("14:00 still counted as morning", morning.coversNow(at("14:00")))
        assertTrue("14:00 should be the afternoon", afternoon.coversNow(at("14:00")))
    }

    /**
     * A night shift ends on the clock BEFORE it starts. Read as a plain range it would
     * cover nothing at all, and its operator could never sign in.
     */
    @Test
    fun aShiftCrossingMidnightCoversBothEndsOfTheDay() {
        val night = shift("22:00", "06:00")

        assertTrue("start of the shift", night.coversNow(at("22:00")))
        assertTrue("before midnight", night.coversNow(at("23:45")))
        assertTrue("midnight itself", night.coversNow(at("00:00")))
        assertTrue("after midnight", night.coversNow(at("05:59")))

        assertFalse("the shift has ended", night.coversNow(at("06:00")))
        assertFalse("the middle of the day", night.coversNow(at("13:00")))
        assertFalse(night.coversNow(at("21:59")))
    }

    /**
     * A shift with no usable hours bounds nobody. The master allows one, and it means
     * "nobody set hours here" rather than "nobody may work".
     */
    @Test
    fun aShiftWithoutHoursCoversEverything() {
        listOf(
            shift("", ""),
            shift("06:00", ""),
            shift("", "14:00"),
            shift("rubbish", "14:00"),
            shift("25:00", "14:00"),   // not a clock face
            shift("08:00", "08:00")    // start = end: a whole day
        ).forEach { s ->
            assertTrue("${s.fromTime}-${s.toTime} should not bar anyone", s.coversNow(at("03:00")))
            assertTrue("${s.fromTime}-${s.toTime} should not bar anyone", s.coversNow(at("15:00")))
        }
    }

    @Test
    fun clockFacesAreReadAsMinutesSinceMidnight() {
        assertEquals(0, ShiftDao.minutesOf("00:00"))
        assertEquals(6 * 60, ShiftDao.minutesOf("06:00"))
        assertEquals(23 * 60 + 59, ShiftDao.minutesOf("23:59"))
        assertEquals(14 * 60 + 30, ShiftDao.minutesOf(" 14:30 "))

        assertNull(ShiftDao.minutesOf(null))
        assertNull(ShiftDao.minutesOf(""))
        assertNull(ShiftDao.minutesOf("9"))
        assertNull(ShiftDao.minutesOf("24:00"))
        assertNull(ShiftDao.minutesOf("12:60"))
        assertNull(ShiftDao.minutesOf("noon"))
    }
}
