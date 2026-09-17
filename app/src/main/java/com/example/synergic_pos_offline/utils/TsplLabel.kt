package com.example.synergic_pos_offline.utils

import java.util.Locale

/**
 * A barcode label written in TSPL, the language TSC label printers speak.
 *
 * ## Not the receipt path
 *
 * The bill printer is an ESC/POS receipt printer, and the only way to get anything onto
 * it is to draw a picture and send the raster - which is what [ThermalPrinter.print]
 * does all day. A TSC printer is a different machine with a different job. It is fed a
 * PROGRAM: "the label is 100 by 25 millimetres, put this text at these coordinates, put
 * a barcode there, print three of them." The printer renders the bars itself, at its own
 * head resolution, and knows where one label ends and the next begins.
 *
 * ## The stock is TWO STICKERS WIDE
 *
 * This is the thing that makes the arithmetic here worth reading. What the printer feeds
 * is a 100mm label; what the shop sticks on a product is one of the two 50mm stickers
 * printed across it. So one FEED is two labels, and an operator asking for ten labels
 * wants ten stickers - five feeds - not ten feeds of two.
 *
 * [build] therefore divides: full rows of [across] stickers, then a last row carrying
 * the remainder. Seven stickers on two-up stock is `PRINT 3,1` of a two-sticker label
 * followed by `PRINT 1,1` of a label with only its left half filled. Both blocks go in
 * one job, which TSPL allows - each is its own CLS...PRINT.
 *
 * ## The layout is a known-good one
 *
 * The command sequence, the coordinates, the fonts and the density are taken from a
 * TSPL template already proven against this shop's own TSC printer, rather than worked
 * out from the manual. Within one 50mm sticker (400 dots at 203 dpi):
 *
 * ```
 * TEXT 20,20     product name        font 2
 * BARCODE 80,60  the code            Code 128 or EAN-13, 65 dots tall, digits off
 * TEXT 100,130   the code in digits  font 3
 * TEXT 20,170    MRP                 font 2, struck through
 * TEXT 180,170   OUR PRICE           font 2
 * ```
 *
 * The second sticker is the same block shifted a whole sticker to the right - +400 dots
 * on the reference stock, which is exactly what the template does by hand.
 *
 * ## The dialect kept deliberately old
 *
 * TSPL2 allows an alignment argument on `TEXT` and `BARCODE`. It is not used here.
 * Older TSC firmware - and there is a lot of it still on counters - rejects the whole
 * command when it arrives with an argument it does not know, so a label built with
 * alignment prints perfectly on a new TE200 and comes out blank on a TTP-244.
 */
object TsplLabel {

    /**
     * Dots per millimetre on the print head.
     *
     * 8 is 203 dpi, which is what all but the high-end TSC units are. A 300 dpi printer
     * (12 dots/mm) would lay this label out at two thirds the size - everything scales
     * from here, so that is the one number to change if a label comes out small.
     */
    const val DOTS_PER_MM = 8

    /**
     * THE STOCK, fixed.
     *
     * A 100mm roll, 25mm tall, carrying two 50mm stickers side by side, with a 2mm gap
     * between one 100mm label and the next. These are the shop's own measurements, not
     * a guess, and they are constants rather than a question on the print popup because
     * a shop buys one kind of label stock and works through it. Asking for four numbers
     * before every print was asking the counter to re-describe the roll it has been
     * using all week, and a slip on any one of them puts the bars across a gap.
     *
     * Change them here if the roll ever changes.
     */
    const val LABEL_WIDTH_MM = 100
    const val LABEL_HEIGHT_MM = 25
    const val LABEL_GAP_MM = 2
    const val STICKERS_ACROSS = 2

    /** One STICKER of that stock, in dots - 50 x 25mm at 203 dpi. */
    private const val REF_STICKER_WIDTH = 400
    private const val REF_HEIGHT = 200

    /**
     * Builds the whole job for [copies] STICKERS of one product.
     *
     * The stock is not a parameter - see [LABEL_WIDTH_MM] and its neighbours. The only
     * thing that changes from one print to the next is how many stickers are wanted.
     *
     * @param copies how many STICKERS are wanted, not how many feeds
     * @param price  what the customer pays; printed as OUR PRICE when [mrp] is also
     *               given, and as the only price line when it is not
     * @param mrp    the listed price, printed struck through beside [price]. Left off
     *               entirely when it is absent or equal to [price] - striking through a
     *               number to show the same number beside it says nothing
     */
    fun build(
        productName: String,
        code: String,
        price: Double? = null,
        mrp: Double? = null,
        copies: Int = 1
    ): ByteArray {
        val perRow = STICKERS_ACROSS
        val wanted = copies.coerceAtLeast(1)
        val fullRows = wanted / perRow
        val remainder = wanted % perRow

        val out = StringBuilder()
        if (fullRows > 0) {
            out.append(block(perRow, fullRows, productName, code, price, mrp))
        }
        // The odd one out. Same physical label - the stock does not change - with only
        // its first cell filled, so the operator gets the count they asked for instead
        // of one spare sticker per print that has to be peeled off and binned.
        if (remainder > 0) {
            out.append(block(remainder, 1, productName, code, price, mrp))
        }
        // TSPL is a text protocol and the printer's parser is byte-oriented; anything
        // outside Latin-1 would be sent as multi-byte UTF-8 and printed as mojibake, so
        // the string is narrowed to what the printer's own character set can render.
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * One sample sticker, for the Test Print button on a label printer.
     *
     * A real label rather than a page of text: the point of testing a label printer is
     * to see whether a sticker comes out the right way up, inside its own edges, with
     * bars a scanner can read - and none of that can be told from a slip of words.
     *
     * A price of zero is passed deliberately, so the test exercises the price line too;
     * nothing is being priced, since nothing is being labelled.
     */
    fun sample(): ByteArray = build(
        productName = "TEST PRINT",
        code = "1234567890",
        price = 0.0,
        copies = 1
    )

    /**
     * One label definition and the instruction to print [rows] of it.
     *
     * [across] is how many sticker cells the stock has; [fill] is how many of them this
     * label actually prints into. They differ only on the last, part-filled row.
     *
     * COPIES ARE THE PRINTER'S JOB. `PRINT <rows>,1` hands the count over and the
     * printer repeats the label, feeding the gap between each one. Sending the job N
     * times instead would make it re-read the definition N times for nothing, and on
     * gapped stock it is the printer's own feed - not a guess at how far to advance -
     * that keeps the bars on the labels rather than across the joins.
     */
    private fun block(
        fill: Int,
        rows: Int,
        productName: String,
        code: String,
        price: Double?,
        mrp: Double?
    ): String {
        val stickerDots = LABEL_WIDTH_MM * DOTS_PER_MM / STICKERS_ACROSS
        val heightDots = LABEL_HEIGHT_MM * DOTS_PER_MM
        // Both are 1.0 on this stock - the sticker is exactly the 400 x 200 dots the
        // reference template was drawn for - so its proven coordinates go out untouched.
        // They stay as a scale rather than being folded away so that changing the stock
        // constants above moves the layout with it instead of leaving it in a corner.
        val sx = stickerDots.toFloat() / REF_STICKER_WIDTH
        val sy = heightDots.toFloat() / REF_HEIGHT

        val out = StringBuilder()
        out.line("SIZE $LABEL_WIDTH_MM mm, $LABEL_HEIGHT_MM mm")
        out.line("GAP $LABEL_GAP_MM mm, 0 mm")
        out.line("DENSITY 10")
        out.line("SPEED 4")
        out.line("DIRECTION 1")
        out.line("REFERENCE 0,0")
        // Clears the image buffer. Without it the previous label is still in there and
        // prints again underneath this one.
        out.line("CLS")

        repeat(fill) { column ->
            out.append(
                sticker(column * stickerDots, sx, sy, stickerDots, productName, code, price, mrp)
            )
        }

        out.line("PRINT ${rows.coerceAtLeast(1)},1")
        return out.toString()
    }

    /** One sticker's worth of commands, its left edge at [originX] dots. */
    private fun sticker(
        originX: Int,
        sx: Float,
        sy: Float,
        stickerDots: Int,
        productName: String,
        code: String,
        price: Double?,
        mrp: Double?
    ): String {
        fun x(v: Int) = originX + (v * sx).toInt()
        fun y(v: Int) = (v * sy).toInt()

        val out = StringBuilder()
        // Product name, clipped to the STICKER rather than the label - the neighbour's
        // cell is not spare room, and TSPL would happily run the text across it.
        out.text(x(20), y(20), FONT_MEDIUM, clip(productName, stickerDots - (40 * sx).toInt(), FONT_MEDIUM_W))

        // The bars, with the printer's own human-readable line switched OFF (the 0
        // after the height). The digits go on as their own TEXT below instead, which is
        // what lets them sit where the template puts them rather than jammed under the
        // bars wherever the firmware decides.
        out.barcode(x(80), y(60), symbologyFor(code), (65 * sy).toInt(), code.trim())
        out.text(x(100), y(130), FONT_LARGE, code.trim())

        // Prices. MRP struck through on the left, what the customer actually pays on the
        // right - the shelf-edge convention. With no MRP on file there is nothing to
        // strike through, so the price goes on its own at the left instead of sitting
        // out on the right with a gap where the MRP would have been.
        val showBoth = price != null && mrp != null && kotlin.math.abs(mrp - price) > 0.001
        if (showBoth) {
            val mrpText = "MRP:${money(mrp!!)}"
            out.text(x(20), y(170), FONT_MEDIUM, mrpText)
            // A filled bar two dots high, drawn across the MRP text. BAR is used rather
            // than the reference's LINE: BAR is in every TSPL2 firmware, LINE is not
            // universally implemented, and a command a printer does not know can take
            // the whole label down with it. The two draw the same thing.
            out.line("BAR ${x(20)},${y(180)},${mrpText.length * FONT_MEDIUM_W},2")
            out.text(x(180), y(170), FONT_MEDIUM, "OUR PRICE:${money(price!!)}")
        } else if (price != null) {
            out.text(x(20), y(170), FONT_MEDIUM, "PRICE:${money(price)}")
        }
        return out.toString()
    }

    /**
     * CODE 128, ALWAYS - as the proven template does.
     *
     * There was an EAN13 branch here for codes that validate as one, on the reasoning
     * that a 13-digit retail code deserves a real EAN-13 symbol. It printed a mangled
     * barcode on the actual machine, and the reason is a trap in TSPL rather than
     * anything about the code: `BARCODE ... "EAN13"` expects the TWELVE data digits and
     * computes the thirteenth check digit itself. Handed all thirteen - which is what
     * the catalogue stores, and rightly so - it encodes the wrong thing.
     *
     * Passing `code.take(12)` would satisfy it, but then the app is silently deciding
     * that the last digit of a stored barcode is disposable, which is only true while
     * every code in the catalogue really is a well-formed EAN-13. Code 128 takes the
     * string exactly as stored, scans back as exactly what is in the database, and
     * accepts the codes that came in off a packet or a spreadsheet too. That is the
     * symbology the reference template used and it is the one that came out right.
     */
    private fun symbologyFor(code: String): String = "128"

    /**
     * Cuts [text] to what will fit across [widthDots] in a font [charWidth] dots wide.
     *
     * TSPL does not wrap, clip or ellipsise - a line too long simply runs off the edge
     * of the sticker and onto its neighbour, taking that sticker's own name with it. So
     * the cut is made here, with a full stop, which at least says the name was longer
     * than the label.
     */
    private fun clip(text: String, widthDots: Int, charWidth: Int): String {
        val max = (widthDots / charWidth).coerceAtLeast(1)
        val clean = text.replace('"', '\'').trim()
        return if (clean.length <= max) clean else clean.take((max - 1).coerceAtLeast(1)) + "."
    }

    /** No currency symbol: "₹" is not in the printer's Latin-1 character set. */
    private fun money(v: Double) = String.format(Locale.US, "%.2f", v)

    private fun StringBuilder.line(command: String) {
        append(command).append("\r\n")
    }

    /** `TEXT x,y,"font",rotation,x-mult,y-mult,"content"` - the seven-argument form. */
    private fun StringBuilder.text(x: Int, y: Int, font: String, content: String) =
        line("TEXT $x,$y,\"$font\",0,1,1,\"$content\"")

    /**
     * `BARCODE x,y,"type",height,readable,rotation,narrow,wide,"content"`.
     *
     * `readable` is 0 - the digits are printed separately, see [sticker]. Narrow 2 /
     * wide 2 is the reference ratio, and it keeps a 13-digit code inside a 50mm sticker
     * at 203 dpi; narrower bars fit more but start to fall below what a hand scanner
     * reliably resolves.
     */
    private fun StringBuilder.barcode(
        x: Int, y: Int, type: String, height: Int, content: String
    ) = line("BARCODE $x,$y,\"$type\",$height,0,0,2,2,\"$content\"")

    // TSC's built-in bitmap fonts, with the cell size each one occupies in dots.
    private const val FONT_MEDIUM = "2"
    private const val FONT_MEDIUM_W = 12
    private const val FONT_LARGE = "3"
}
