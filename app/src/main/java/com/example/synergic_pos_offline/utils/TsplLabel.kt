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
 * ## Everything is laid out inside a SAFE BOX, not on the sticker's edges
 *
 * The sticker's pitch and its printable area are not the same rectangle, and the
 * difference is what used to come off the printer wrong. Die-cut stock has rounded
 * corners, the channel cut between the two columns eats a couple of millimetres off
 * each inside edge, and the gap sensor registers the roll to about a millimetre either
 * way. The original layout ran from dot 20 to dot 190 of a 200-dot sticker, so it was
 * relying on all three of those being perfect: the price line, which ended 10 dots -
 * 1.25mm - from the bottom edge, came out sliced in half along the die cut, and the
 * product name at the top fared no better.
 *
 * So nothing is positioned against the sticker's edge any more. [REF_MARGIN_X] and
 * [REF_MARGIN_Y] fence off a safe box inside it, the four rows are stacked from the top
 * of that box down to the bottom of it, and the bars take whatever height is left over
 * ([barsHeight] in [sticker]) instead of a fixed 65 dots - which on this stock makes
 * them taller than they were, not shorter. Change a margin and the whole stack moves
 * with it; there are no hand-placed coordinates left to fall out of step.
 *
 * The second sticker is the same block shifted a whole sticker to the right - +400 dots
 * on the reference stock.
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
     * The border kept clear inside every sticker - 3mm across, 3.5mm down at 203 dpi.
     *
     * Sideways, the stock loses room to the die-cut channel between the two columns: a
     * good 4-5mm of the 50mm pitch, so a name or a price run out to dot 395 is printed
     * onto the cut, not onto the sticker.
     *
     * Down the label, 3.5mm is more than the 2mm gap because the gap alone was not what
     * went wrong. On the label that prompted this, the bottom row was sliced along its
     * middle with the layout ending at dot 190 - so the last few dots of a 200-dot
     * sticker are not reliably on the sticker at all, once the die cut's rounding and
     * about a millimetre of registration drift have both had their share.
     * [REF_MARGIN_Y] is set past that, not up against it.
     *
     * These are the numbers to change if a label comes out with its edges clipped -
     * everything in [sticker] is measured from them.
     */
    private const val REF_MARGIN_X = 24
    private const val REF_MARGIN_Y = 28

    /** Clear space between one row of the label and the next. */
    private const val REF_ROW_GAP = 6

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
                sticker(column * stickerDots, sx, sy, productName, code, price, mrp)
            )
        }

        out.line("PRINT ${rows.coerceAtLeast(1)},1")
        return out.toString()
    }

    /**
     * One sticker's worth of commands, its left edge at [originX] dots.
     *
     * The four rows are stacked inside the safe box rather than dropped at fixed
     * coordinates - see the class doc. Name from the top down, prices from the bottom
     * up, digits above the prices, and the bars filling whatever is left in between, so
     * the block always ends exactly on the bottom margin however the stock is sized.
     */
    private fun sticker(
        originX: Int,
        sx: Float,
        sy: Float,
        productName: String,
        code: String,
        price: Double?,
        mrp: Double?
    ): String {
        fun x(v: Int) = originX + (v * sx).toInt()
        fun y(v: Int) = (v * sy).toInt()

        val left = REF_MARGIN_X
        val right = REF_STICKER_WIDTH - REF_MARGIN_X
        val safeWidth = right - left
        /** Where something [widthDots] wide starts if it is to sit in the middle. */
        fun centred(widthDots: Int) = left + ((safeWidth - widthDots) / 2).coerceAtLeast(0)

        val nameY = REF_MARGIN_Y
        val priceY = REF_HEIGHT - REF_MARGIN_Y - FONT_MEDIUM_H
        val digitsY = priceY - REF_ROW_GAP - FONT_LARGE_H
        val barsY = nameY + FONT_MEDIUM_H + REF_ROW_GAP
        // What the rows above and below did not use. Floored at a height a hand scanner
        // can still resolve: if a stock ever turns out too short for the full stack, a
        // barcode running a little into the name is worth more than one too thin to read.
        val barsHeight = (digitsY - REF_ROW_GAP - barsY).coerceAtLeast(MIN_BAR_HEIGHT)

        val out = StringBuilder()
        // Product name, clipped to the SAFE BOX rather than the sticker - the die-cut
        // channel beside it is not spare room, and TSPL would happily run text across it.
        //
        // CENTRED, like every other row. It used to be anchored at the left margin while
        // the bars and the digits below it were centred, and a sticker with its name and
        // its price hard left under a centred barcode reads as two layouts printed on
        // top of each other rather than one label. TSPL has no alignment argument in the
        // form this uses - see the class note on old firmware - so centring is arithmetic
        // here: a bitmap font is a fixed cell wide, so the text is exactly as wide as its
        // own character count.
        val name = clip(productName, safeWidth, FONT_MEDIUM_W)
        out.text(x(centred(name.length * FONT_MEDIUM_W)), y(nameY), FONT_MEDIUM, name)

        // The bars, with the printer's own human-readable line switched OFF (the 0
        // after the height). The digits go on as their own TEXT below instead, which is
        // what lets them sit on their own row rather than jammed under the bars wherever
        // the firmware decides.
        val digits = clip(code.trim(), safeWidth, FONT_LARGE_W)
        out.barcode(
            x(centred(barcodeWidthDots(code.trim()))), y(barsY),
            symbologyFor(code), (barsHeight * sy).toInt(), code.trim()
        )
        out.text(x(centred(digits.length * FONT_LARGE_W)), y(digitsY), FONT_LARGE, digits)

        // Prices. MRP struck through on the left, what the customer actually pays on the
        // right - the shelf-edge convention. With no MRP on file there is nothing to
        // strike through, so the price goes on its own at the left instead of sitting
        // out on the right with a gap where the MRP would have been.
        val showBoth = price != null && mrp != null && kotlin.math.abs(mrp - price) > 0.001
        if (showBoth) {
            val mrpText = "MRP:${money(mrp!!)}"
            val mrpWidth = mrpText.length * FONT_MEDIUM_W
            // The paid price is set against the RIGHT margin, not at a fixed dot 180.
            // Its width is whatever the figure needs - a four-figure rate is two
            // characters longer than a two-figure one - and anchored on the left it grew
            // off the edge of the sticker. "OUR PRICE:" is dropped for the shorter label
            // first, since losing a word beats losing a digit.
            val priceText = "OUR PRICE:${money(price!!)}".let {
                if (mrpWidth + REF_ROW_GAP + it.length * FONT_MEDIUM_W <= safeWidth) it
                else "PRICE:${money(price)}"
            }
            val priceX = (right - priceText.length * FONT_MEDIUM_W).coerceAtLeast(left)
            // Only when the MRP still has room of its own. A long pair would otherwise
            // print one over the other, which reads as a third, wrong number.
            if (priceX >= left + mrpWidth + REF_ROW_GAP) {
                out.text(x(left), y(priceY), FONT_MEDIUM, mrpText)
                // A filled bar two dots high, drawn across the MRP text. BAR is used
                // rather than the reference's LINE: BAR is in every TSPL2 firmware, LINE
                // is not universally implemented, and a command a printer does not know
                // can take the whole label down with it. The two draw the same thing.
                // Its width is scaled like every other measurement here - left in
                // reference dots it would have struck through the wrong span on any
                // stock but the 50mm one.
                out.line(
                    "BAR ${x(left)},${y(priceY + FONT_MEDIUM_H / 2)}," +
                        "${(mrpWidth * sx).toInt().coerceAtLeast(1)},2"
                )
            }
            out.text(x(priceX), y(priceY), FONT_MEDIUM, priceText)
        } else if (price != null) {
            // Centred, on the same middle line as the name, the bars and the digits.
            // Only the MRP pair above is spread left-and-right, and that is a two-column
            // row on purpose - the struck-through list price beside what is actually
            // charged - rather than a row that failed to be centred.
            val priceText = clip("PRICE:${money(price)}", safeWidth, FONT_MEDIUM_W)
            out.text(
                x(centred(priceText.length * FONT_MEDIUM_W)), y(priceY), FONT_MEDIUM, priceText
            )
        }
        return out.toString()
    }

    /**
     * Roughly how wide the Code 128 symbol for [code] prints, in dots - enough to centre
     * the bars on the sticker, which is all it is used for.
     *
     * The printer renders the symbol itself, so nothing here can ask it how wide the
     * answer came out. The count is the standard one: every symbol character is 11
     * modules, the run carries a start character and a checksum, and the stop pattern is
     * 13 modules. Subset C packs two digits into each character, which is why a 13-digit
     * retail code is barely wider than a 10-digit one - with the odd digit costing a
     * switch back to subset B plus a character of its own.
     *
     * An over- or under-estimate only shifts the bars a few dots off centre; the clamp in
     * [sticker]'s `centred` keeps them inside the margin either way.
     */
    private fun barcodeWidthDots(code: String): Int {
        val characters =
            if (code.isNotEmpty() && code.all { it.isDigit() }) code.length / 2 + (code.length % 2) * 2
            else code.length
        return (11 * (characters + 2) + 13) * BAR_NARROW
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
    ) = line("BARCODE $x,$y,\"$type\",$height,0,0,$BAR_NARROW,$BAR_NARROW,\"$content\"")

    /** The narrow bar, in dots - and with it the whole symbol's width. */
    private const val BAR_NARROW = 2

    /**
     * How short the bars are allowed to get before height is taken from the rows around
     * them instead. About 3mm at 203 dpi, below which hand scanners start to miss the
     * symbol on a sticker that is not held square.
     */
    private const val MIN_BAR_HEIGHT = 24

    // TSC's built-in bitmap fonts, with the cell size each one occupies in dots.
    private const val FONT_MEDIUM = "2"
    private const val FONT_MEDIUM_W = 12
    private const val FONT_MEDIUM_H = 20
    private const val FONT_LARGE = "3"
    private const val FONT_LARGE_W = 16
    private const val FONT_LARGE_H = 24
}
