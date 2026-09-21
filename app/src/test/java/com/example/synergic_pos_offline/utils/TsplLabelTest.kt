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

    /**
     * One sticker carries all four things, in top-to-bottom order.
     *
     * Asserted by ORDER rather than by exact coordinates. The layout computes its
     * positions from the stock and the font metrics - it centres the bars, and sizes
     * them from whatever room the text leaves - so pinning 20,20 here only records
     * whatever the arithmetic happened to produce the day it was written, and breaks
     * the moment anyone tunes it without anything actually being wrong.
     *
     * What must not change is that all four are present, each below the last.
     */
    @Test
    fun `a sticker carries name, bars, digits and price in that order`() {
        val tspl = build()
        val name = yOf(tspl, """TEXT (\d+),(\d+),"2",0,1,1,"Rice 1kg"""")
        val bars = yOf(tspl, """BARCODE (\d+),(\d+),"128"""")
        val digits = yOf(tspl, """TEXT (\d+),(\d+),"3",0,1,1,"$validEan"""")
        val price = yOf(tspl, """TEXT (\d+),(\d+),"2",0,1,1,"PRICE:120.00"""")
        assertTrue("name $name < bars $bars", name < bars)
        assertTrue("bars $bars < digits $digits", bars < digits)
        assertTrue("digits $digits < price $price", digits < price)
    }

    /**
     * Every row on a sticker shares one middle line.
     *
     * This is the bug that reached paper: the name and the price were anchored at the
     * left margin while the bars and the digits were centred, so a printed label read as
     * two layouts stacked rather than one. TSPL's `TEXT` has no alignment argument in the
     * seven-argument form the builder uses, so centring is arithmetic - which is exactly
     * the kind of thing that silently stops being true.
     *
     * A bitmap font is a fixed cell wide, so a row's width is its character count times
     * that cell, and its centre is computable from the command alone.
     */
    @Test
    fun `every text row is centred on the sticker`() {
        val tspl = build(name = "Chicken Lollypop", code = "2897330055552", price = 220.0)
        val sticker = TsplLabel.LABEL_WIDTH_MM * TsplLabel.DOTS_PER_MM / TsplLabel.STICKERS_ACROSS
        val middle = sticker / 2

        val rows = Regex("""TEXT (\d+),\d+,"([23])",0,1,1,"([^"]*)"""").findAll(tspl).toList()
        assertEquals("name, digits and price", 3, rows.size)
        rows.forEach { m ->
            val x = m.groupValues[1].toInt()
            val cell = if (m.groupValues[2] == "3") 16 else 12
            val centre = x + m.groupValues[3].length * cell / 2
            // A character cell cannot be split, so a row whose width is an odd number of
            // cells lands half a cell off centre. Anything beyond that is a row that was
            // not centred at all.
            assertTrue("\"${m.groupValues[3]}\" centres on $centre, not $middle",
                kotlin.math.abs(centre - middle) <= cell / 2)
        }
    }

    /** Everything a sticker draws has to land inside the 25mm label, 200 dots at 203dpi. */
    @Test
    fun `nothing is drawn past the bottom of the label`() {
        val tspl = build(price = 120.0, mrp = 135.0)
        Regex("""(?:TEXT|BARCODE|BAR) (\d+),(\d+)""").findAll(tspl).forEach { m ->
            val y = m.groupValues[2].toInt()
            assertTrue("${m.value} is at y=$y, past the 200-dot label", y < 200)
        }
    }

    /** The y of the first match of [pattern], whose second group is the y coordinate. */
    private fun yOf(tspl: String, pattern: String): Int =
        Regex(pattern).find(tspl)?.groupValues?.get(2)?.toInt()
            ?: throw AssertionError("not found: $pattern\nin:\n$tspl")

    /** The x of the first match of [pattern], whose first group is the x coordinate. */
    private fun xOf(tspl: String, pattern: String): Int =
        Regex(pattern).find(tspl)?.groupValues?.get(1)?.toInt()
            ?: throw AssertionError("not found: $pattern\nin:\n$tspl")

    /** Every x a product name is drawn at - one per filled sticker cell. */
    private fun nameXs(tspl: String): List<Int> =
        Regex("""TEXT (\d+),\d+,"2",0,1,1,"Rice 1kg"""").findAll(tspl)
            .map { it.groupValues[1].toInt() }.toList()

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
        assertEquals("two stickers drawn", 2, nameXs(tspl).size)
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
        // The last feed carries one sticker, not two.
        assertEquals("one sticker on the last feed", 1, nameXs(tspl.substringAfterLast("CLS")).size)
    }

    /** One sticker is a single part-filled feed, not a full one. */
    @Test
    fun `a single sticker fills one cell`() {
        val tspl = build(copies = 1)
        assertEquals(1, Regex("PRINT ").findAll(tspl).count())
        assertTrue(tspl, tspl.contains("PRINT 1,1"))
        assertEquals("one sticker only", 1, nameXs(tspl).size)
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
        // 100mm of stock, two across, 8 dots/mm - so one sticker is 400 dots. Derived
        // from the constants rather than typed, so changing the stock moves the test
        // with the code instead of against it.
        val sticker = TsplLabel.LABEL_WIDTH_MM * TsplLabel.DOTS_PER_MM / TsplLabel.STICKERS_ACROSS
        assertEquals(400, sticker)

        val names = nameXs(tspl)
        assertEquals("two stickers", 2, names.size)
        assertEquals("second sticker is one sticker to the right", names[0] + sticker, names[1])

        // Every element of the second sticker moves with it, not just the name.
        val bars = Regex("""BARCODE (\d+),""").findAll(tspl).map { it.groupValues[1].toInt() }.toList()
        assertEquals(2, bars.size)
        assertEquals(bars[0] + sticker, bars[1])
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
        assertTrue(build(), build().contains("""BARCODE""") && Regex("""BARCODE \d+,\d+,"128",\d+,0,0,""").containsMatchIn(build()))
    }

    // ---- Prices ------------------------------------------------------------

    /** MRP struck through, selling price beside it - the shelf-edge convention. */
    @Test
    fun `an MRP above the price prints struck through`() {
        val tspl = build(price = 120.0, mrp = 135.0)
        assertTrue(tspl, Regex("""TEXT \d+,\d+,"2",0,1,1,"MRP:135.00"""").containsMatchIn(tspl))
        assertTrue(tspl, Regex("""TEXT \d+,\d+,"2",0,1,1,"OUR PRICE:120.00"""").containsMatchIn(tspl))
        assertTrue(tspl, Regex("""BAR \d+,\d+,\d+,2""").containsMatchIn(tspl))
    }

    /**
     * Striking a line through a number to show the same number beside it says nothing,
     * so an MRP equal to the price collapses to one plain price line.
     */
    @Test
    fun `an MRP equal to the price prints one price and no strike`() {
        val tspl = build(price = 120.0, mrp = 120.0)
        assertTrue(tspl, Regex("""TEXT \d+,\d+,"2",0,1,1,"PRICE:120.00"""").containsMatchIn(tspl))
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
        val printed = Regex("""TEXT \d+,\d+,"2",0,1,1,"([^"]*)"""").find(tspl)!!.groupValues[1]
        // A 50mm sticker at 203 dpi is 400 dots; font 2 is 12 wide, less the margins.
        assertTrue("was ${printed.length} chars", printed.length <= 30)
        assertTrue(printed.endsWith("."))
    }

    /** A quote in a product name would close the TSPL string argument early. */
    @Test
    fun `quotes in a name cannot break out of the command`() {
        val tspl = build(name = """6" Pipe""")
        val line = tspl.lines().first { it.contains("Pipe") }
        assertEquals("one opening and one closing quote pair", 4, line.count { it == '"' })
    }
}
