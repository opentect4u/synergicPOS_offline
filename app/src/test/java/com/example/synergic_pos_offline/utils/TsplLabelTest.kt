package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TSPL a TSC printer is actually sent.
 *
 * Worth testing where almost nothing else about printing is: the output is a pure
 * function of its arguments, and the only other way to find out whether a coordinate is
 * right is to feed a roll of labels through a printer. A command in the wrong place here
 * is a wasted roll.
 */
class TsplLabelTest {

    /**
     * A real EAN-13: twelve digits plus the check digit they actually compute to.
     *
     * Built rather than typed, because a hand-written "looks like a barcode" string is
     * almost never a valid one - the first draft of this test used 2010293847561, whose
     * check digit should be 3.
     */
    private val validEan = "201029384756".let { it + BarcodeGenerator.checkDigit(it) }

    /**
     * The stock is fixed - a 100mm roll carrying two 50mm stickers - so the only thing
     * a caller varies is the product and how many stickers it wants.
     */
    private fun build(
        name: String = "Rice 1kg",
        code: String = validEan,
        price: Double? = 120.0,
        mrp: Double? = null,
        copies: Int = 1
    ) = String(
        TsplLabel.build(
            productName = name, code = code, price = price, mrp = mrp, copies = copies
        ),
        Charsets.ISO_8859_1
    )

    // ---- The stock ---------------------------------------------------------

    /**
     * SIZE is the whole roll the printer FEEDS, not one sticker off it, and the gap is
     * between one 100mm label and the next. Getting these two confused is what puts the
     * bars across the join.
     */
    @Test
    fun `the stock is the shop's own, stated once`() {
        val tspl = build()
        assertTrue(tspl, tspl.contains("SIZE 100 mm, 25 mm"))
        assertTrue(tspl, tspl.contains("GAP 2 mm, 0 mm"))
        assertTrue(tspl, tspl.contains("DENSITY 10"))
        assertTrue(tspl, tspl.contains("SPEED 4"))
        assertTrue(tspl, tspl.contains("DIRECTION 1"))
        assertTrue(tspl, tspl.contains("REFERENCE 0,0"))
        assertTrue(tspl, tspl.contains("CLS"))
    }

    /** The proven template's coordinates, untouched, for the left-hand sticker. */
    @Test
    fun `the sticker keeps the proven coordinates`() {
        val tspl = build()
        assertTrue(tspl, tspl.contains("""TEXT 20,20,"2",0,1,1,"Rice 1kg""""))
        assertTrue(tspl, tspl.contains("""TEXT 100,130,"3",0,1,1,"$validEan""""))
        assertTrue(tspl, tspl.contains("""TEXT 20,170,"2",0,1,1,"PRICE:120.00""""))
    }

    /** Every command ends CRLF, which is what the printer's parser breaks on. */
    @Test
    fun `commands are CRLF terminated`() {
        assertTrue(build().endsWith("\r\n"))
        assertFalse(build().contains("\n\n"))
    }

    // ---- Two stickers to a feed --------------------------------------------

    /**
     * The count the operator types is STICKERS. On two-up stock ten stickers is five
     * feeds of a two-sticker label - asking the printer for ten feeds would hand over
     * twenty.
     */
    @Test
    fun `an even count halves the feeds`() {
        val tspl = build(copies = 10)
        assertTrue(tspl, tspl.contains("PRINT 5,1"))
        assertEquals("one block only", 1, Regex("PRINT ").findAll(tspl).count())
        // Both cells filled: the second sticker sits a whole sticker to the right.
        assertTrue(tspl, tspl.contains("TEXT 20,20,"))
        assertTrue(tspl, tspl.contains("TEXT 420,20,"))
    }

    /**
     * An odd count cannot come out of two-up stock evenly, so the last feed prints its
     * left cell only - otherwise every odd order hands over one spare sticker.
     */
    @Test
    fun `an odd count prints a part-filled last label`() {
        val tspl = build(copies = 7)
        assertTrue(tspl, tspl.contains("PRINT 3,1"))
        assertTrue(tspl, tspl.contains("PRINT 1,1"))
        assertEquals("two blocks", 2, Regex("PRINT ").findAll(tspl).count())
        // Each block clears the buffer before drawing into it.
        assertEquals(2, Regex("\\bCLS\\b").findAll(tspl).count())
        val last = tspl.substringAfterLast("CLS")
        assertTrue(last, last.contains("TEXT 20,20,"))
        assertFalse(last, last.contains("TEXT 420,20,"))
    }

    /** One sticker is a single part-filled feed, not a full one. */
    @Test
    fun `a single sticker fills one cell`() {
        val tspl = build(copies = 1)
        assertEquals(1, Regex("PRINT ").findAll(tspl).count())
        assertTrue(tspl, tspl.contains("PRINT 1,1"))
        assertTrue(tspl, tspl.contains("TEXT 20,20,"))
        assertFalse(tspl, tspl.contains("TEXT 420,20,"))
    }

    /** A count of zero would tell the printer to print nothing at all. */
    @Test
    fun `copies floor at one`() {
        assertTrue(build(copies = 0).contains("PRINT 1,1"))
    }

    /**
     * The second sticker is the first shifted a whole sticker right - +400 dots, which
     * is what the proven template does by hand.
     */
    @Test
    fun `the second sticker is offset by one sticker width`() {
        val tspl = build(copies = 2)
        assertTrue(tspl, tspl.contains("""TEXT 420,20,"2",0,1,1,"Rice 1kg""""))
        assertTrue(tspl, tspl.contains("""BARCODE 480,60,"128",65,0,0,2,2,"$validEan""""))
        assertTrue(tspl, tspl.contains("""TEXT 500,130,"3",0,1,1,"$validEan""""))
    }

    // ---- The barcode -------------------------------------------------------

    /**
     * ALWAYS Code 128 - never EAN13.
     *
     * TSPL's EAN13 wants the twelve data digits and computes the check digit itself, so
     * handing it the full thirteen the catalogue stores encodes the wrong thing. That
     * came off the machine as a mangled symbol. Code 128 takes the string exactly as
     * stored.
     */
    @Test
    fun `every code prints as Code 128`() {
        listOf(validEan, "ABC-123", "12345", "999999999999999").forEach { code ->
            val tspl = build(code = code)
            assertTrue("$code -> $tspl", tspl.contains(""""128""""))
            assertFalse("$code -> $tspl", tspl.contains("EAN13"))
        }
    }

    /** The printer's own readable line is off; the digits are placed by us. */
    @Test
    fun `barcode suppresses the built-in human readable line`() {
        assertTrue(build().contains("""BARCODE 80,60,"128",65,0,0,2,2,"$validEan""""))
    }

    // ---- Prices ------------------------------------------------------------

    /** MRP struck through, selling price beside it - the shelf-edge convention. */
    @Test
    fun `an MRP above the price prints struck through`() {
        val tspl = build(price = 120.0, mrp = 135.0)
        assertTrue(tspl, tspl.contains("""TEXT 20,170,"2",0,1,1,"MRP:135.00""""))
        assertTrue(tspl, tspl.contains("""TEXT 180,170,"2",0,1,1,"OUR PRICE:120.00""""))
        assertTrue(tspl, tspl.contains("BAR 20,180,"))
    }

    /**
     * Striking a line through a number to show the same number beside it says nothing,
     * so an MRP equal to the price collapses to one plain price line.
     */
    @Test
    fun `an MRP equal to the price prints one price and no strike`() {
        val tspl = build(price = 120.0, mrp = 120.0)
        assertTrue(tspl, tspl.contains("""TEXT 20,170,"2",0,1,1,"PRICE:120.00""""))
        assertFalse(tspl, tspl.contains("BAR "))
        assertFalse(tspl, tspl.contains("OUR PRICE"))
    }

    /** No price on file leaves the line off rather than pricing the goods at nothing. */
    @Test
    fun `no price prints no price line`() {
        val tspl = build(price = null, mrp = null)
        assertFalse(tspl, tspl.contains("PRICE"))
        assertFalse(tspl, tspl.contains("MRP"))
    }

    // ---- Text that would not fit -------------------------------------------

    /**
     * A name longer than the STICKER is cut here, because TSPL does not wrap or clip -
     * it runs the text off the sticker and onto its neighbour, over that sticker's own
     * name.
     */
    @Test
    fun `a long product name is clipped to the sticker`() {
        val tspl = build(name = "X".repeat(200))
        val printed = Regex("""TEXT 20,20,"2",0,1,1,"([^"]*)"""").find(tspl)!!.groupValues[1]
        // A 50mm sticker at 203 dpi is 400 dots; font 2 is 12 wide, less the margins.
        assertTrue("was ${printed.length} chars", printed.length <= 30)
        assertTrue(printed.endsWith("."))
    }

    /** A quote in a product name would close the TSPL string argument early. */
    @Test
    fun `quotes in a name cannot break out of the command`() {
        val tspl = build(name = """6" Pipe""")
        val line = tspl.lines().first { it.startsWith("TEXT 20,20") }
        assertEquals("one opening and one closing quote pair", 4, line.count { it == '"' })
    }
}
