package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading a weight off the wire.
 *
 * Worth testing where the rest of the USB path is not: this is pure, and a misread
 * here does not fail loudly - it prices the goods at the wrong weight, which nobody
 * catches until the till and the shelf disagree at stocktake.
 */
class ScaleReadingTest {

    private fun parse(
        line: String,
        charCount: Int = 6,
        decimalPosition: Int = 3,
        start: Int = 0,
        end: Int = 0
    ) = ScaleReading.parse(line, charCount, decimalPosition, start, end)

    // ---- The character-count fallback (how tills are configured today) ------

    /** The common indicator: a bare fixed-width digit run, no point of its own. */
    @Test
    fun `a bare digit run takes the decimal position`() {
        assertEquals(1.250, parse("001250")!!, 0.0001)
        assertEquals(0.005, parse("000005")!!, 0.0001)
        assertEquals(12.345, parse("012345")!!, 0.0001)
    }

    /** Status letters and a unit around the digits do not stop it reading. */
    @Test
    fun `padding around the digits is ignored`() {
        assertEquals(1.250, parse("ST,GS,001250kg")!!, 0.0001)
    }

    /** A net-negative or tare reading comes back negative. */
    @Test
    fun `a minus makes the reading negative`() {
        assertEquals(-1.250, parse("-001250")!!, 0.0001)
    }

    /** Too few digits is a clipped line, and is skipped rather than guessed at. */
    @Test
    fun `a short line is skipped`() {
        assertNull(parse("0012"))
        assertNull(parse(""))
        assertNull(parse("   "))
    }

    // ---- The start/end window -----------------------------------------------

    /**
     * The case the window exists for: an indicator whose frame carries digits AFTER
     * the weight. The character count reads the wrong end of it; the window does not.
     */
    @Test
    fun `digits after the weight are what the window is for`() {
        //              1234567890123456
        val frame = "ST,GS,+  1.250kg"
        // Counting the last 6 digits gets the unit's "2" dragged in - wrong.
        assertEquals(1.250, parse(frame, start = 8, end = 14)!!, 0.0001)
    }

    /** The window is counted from 1 and includes both ends. */
    @Test
    fun `the window is one-based and inclusive`() {
        // "1.250" is characters 1..5 of this line.
        assertEquals(1.250, parse("1.250kg", start = 1, end = 5)!!, 0.0001)
        // Dropping the last character of the window loses the final digit.
        assertEquals(1.25, parse("1.250kg", start = 1, end = 4)!!, 0.0001)
    }

    /**
     * A point already in the text is believed. Applying the decimal position on top
     * would divide it a second time - 1.250kg read as 0.001kg.
     */
    @Test
    fun `an existing decimal point is not divided again`() {
        assertEquals(1.250, parse("  1.250", decimalPosition = 3, start = 1, end = 7)!!, 0.0001)
    }

    /** A window over a bare digit run still gets the point inserted. */
    @Test
    fun `a window with no point takes the decimal position`() {
        assertEquals(1.250, parse("XX001250YY", decimalPosition = 3, start = 3, end = 8)!!, 0.0001)
    }

    /** A clipped line is read as far as it goes rather than thrown away. */
    @Test
    fun `a window past the end of the line is clamped`() {
        assertEquals(1.25, parse("1.25", start = 1, end = 20)!!, 0.0001)
    }

    /** A window that catches no digit at all is not a reading. */
    @Test
    fun `a window with no digits is skipped`() {
        assertNull(parse("ST,GS,kg", start = 1, end = 5))
    }

    /**
     * The sign is read from the window when there is one. A window drawn to exclude
     * the sign column was drawn that way deliberately.
     */
    @Test
    fun `the window decides the sign`() {
        //                   1234567890
        assertEquals(-1.250, parse("- 1.250kg", start = 1, end = 7)!!, 0.0001)
        // Same line, window starting after the minus: positive.
        assertEquals(1.250, parse("- 1.250kg", start = 3, end = 7)!!, 0.0001)
    }

    /**
     * Both points zero means no window, and the character count applies - which is
     * what every till configured before the window existed relies on.
     */
    @Test
    fun `zero points fall back to the character count`() {
        assertEquals(1.250, parse("001250", start = 0, end = 0)!!, 0.0001)
    }

    /** An end before the start is not a window, so the count applies instead. */
    @Test
    fun `a backwards window falls back to the character count`() {
        assertEquals(1.250, parse("001250", start = 5, end = 2)!!, 0.0001)
    }
}
