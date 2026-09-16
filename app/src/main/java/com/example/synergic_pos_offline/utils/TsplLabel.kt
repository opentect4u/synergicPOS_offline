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
 * PROGRAM: "the label is 50 by 25 millimetres, put this text at these coordinates, put
 * an EAN-13 there, print three of them." The printer renders the bars itself, at its
 * own head resolution, and knows where one label ends and the next begins.
 *
 * Sending it an ESC/POS raster does not produce a worse label; it produces nothing, or
 * a page of rubbish, because the commands mean nothing to it.
 *
 * ## Bars drawn by the printer, not by us
 *
 * This is the reason to prefer TSPL over a bitmap even where both would work. A barcode
 * that has been rasterised at one resolution and reprinted at another has bar widths
 * that no longer land on exact dot boundaries, and the scanner is reading the widths.
 * `BARCODE` hands the printer the digits and lets it lay the bars out in whole dots.
 *
 * ## The dialect kept deliberately old
 *
 * TSPL2 allows an alignment argument on `TEXT` and `BARCODE`. It is not used here.
 * Older TSC firmware - and there is a lot of it still on counters - rejects the whole
 * command when it arrives with an argument it does not know, so a label built with
 * alignment prints perfectly on a new TE200 and comes out blank on a TTP-244. Every
 * command below is the form that has been in TSPL since the beginning: fixed left
 * margin, no alignment argument, and the layout worked out here where the arithmetic
 * can be seen.
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

    /** Left and right margin, in dots - about 2mm, the usual unprintable edge. */
    private const val MARGIN = 16

    /**
     * Builds the whole job: one label definition and an instruction to print [copies].
     *
     * COPIES ARE THE PRINTER'S JOB, not ours. `PRINT 1,<copies>` hands the count to the
     * printer, which repeats the label and feeds the gap between each one. Sending the
     * job three times instead would make the printer re-read the label definition twice
     * for nothing, and on gapped stock it is the printer's own feed - not a guess at how
     * far to advance - that keeps the bars on the labels rather than across the joins.
     *
     * @param widthMm  the label's width in mm - the stock, not the paper roll's liner
     * @param heightMm the label's height in mm
     * @param gapMm    the gap between one label and the next, 0 on continuous stock
     */
    fun build(
        widthMm: Int,
        heightMm: Int,
        gapMm: Int,
        productName: String,
        code: String,
        price: Double? = null,
        shopName: String? = null,
        copies: Int = 1
    ): ByteArray {
        val widthDots = widthMm * DOTS_PER_MM
        val heightDots = heightMm * DOTS_PER_MM
        val usableWidth = widthDots - MARGIN * 2

        val out = StringBuilder()
        out.line("SIZE $widthMm mm,$heightMm mm")
        out.line("GAP $gapMm mm,0 mm")
        // 1 = printing away from the operator, which is how a TSC sits on a counter with
        // the label coming out readable. REFERENCE fixes the origin at the label's own
        // top-left so the coordinates below mean what they say.
        out.line("DIRECTION 1")
        out.line("REFERENCE 0,0")
        out.line("DENSITY 8")
        out.line("SPEED 4")
        // Clears the image buffer. Without it the previous label is still in there and
        // prints again underneath this one.
        out.line("CLS")

        // Laid out top to bottom, each block claiming the height it needs. The barcode
        // is given whatever is left after the text, with a floor under it - bars below
        // about 40 dots get unreliable to read, so a label too short to carry them is
        // better printed with the text crowded than with a barcode that will not scan.
        var y = MARGIN / 2

        shopName?.takeIf { it.isNotBlank() }?.let {
            out.text(MARGIN, y, FONT_SMALL, clip(it.uppercase(Locale.getDefault()), usableWidth, FONT_SMALL_W))
            y += FONT_SMALL_H + 4
        }

        out.text(MARGIN, y, FONT_MEDIUM, clip(productName, usableWidth, FONT_MEDIUM_W))
        y += FONT_MEDIUM_H + 6

        // Reserved before the barcode is placed, so the price cannot be pushed off the
        // bottom of a short label by a tall barcode.
        val priceHeight = if (price != null) FONT_MEDIUM_H + 4 else 0
        val readableHeight = 24
        val barsHeight = (heightDots - y - priceHeight - readableHeight - MARGIN)
            .coerceAtLeast(MIN_BARS_HEIGHT)

        out.barcode(MARGIN, y, symbologyFor(code), barsHeight, code.trim())
        y += barsHeight + readableHeight

        if (price != null) {
            out.text(MARGIN, y, FONT_MEDIUM, "Rs " + String.format(Locale.US, "%.2f", price))
        }

        out.line("PRINT 1,${copies.coerceAtLeast(1)}")
        // TSPL is a text protocol and the printer's parser is byte-oriented; anything
        // outside Latin-1 would be sent as multi-byte UTF-8 and printed as mojibake, so
        // the string is narrowed to what the printer's own character set can render.
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * `EAN13` for a code that really is one, `128` for everything else.
     *
     * A catalogue holds codes that were typed in or imported off a packet, and a
     * printer asked to lay out an EAN-13 from something that is not one prints nothing
     * at all. Code 128 accepts any ASCII, so the label always comes out and always
     * scans back as whatever is stored against the product.
     */
    private fun symbologyFor(code: String): String =
        if (BarcodeGenerator.isValidEan13(code.trim())) "EAN13" else "128"

    /**
     * Cuts [text] to what will fit across [widthDots] in a font [charWidth] dots wide.
     *
     * TSPL does not wrap, clip or ellipsise - a line too long simply runs off the edge
     * of the label and is lost, taking no ink with it and giving no sign it was ever
     * there. So the cut is made here, with an ellipsis, which at least says the name
     * was longer than the label.
     */
    private fun clip(text: String, widthDots: Int, charWidth: Int): String {
        val max = (widthDots / charWidth).coerceAtLeast(1)
        val clean = text.replace('"', '\'').trim()
        return if (clean.length <= max) clean else clean.take((max - 1).coerceAtLeast(1)) + "."
    }

    private fun StringBuilder.line(command: String) {
        append(command).append("\r\n")
    }

    /** `TEXT x,y,"font",rotation,x-mult,y-mult,"content"` - the seven-argument form. */
    private fun StringBuilder.text(x: Int, y: Int, font: String, content: String) =
        line("TEXT $x,$y,\"$font\",0,1,1,\"$content\"")

    /**
     * `BARCODE x,y,"type",height,readable,rotation,narrow,wide,"content"`.
     *
     * `readable` is 1, so the printer puts the digits under the bars itself - the line
     * an operator keys in when a label will not scan. Narrow 2 / wide 2 is the ratio
     * that keeps a 13-digit EAN inside a 50mm label at 203 dpi; narrower bars fit more
     * but start to fall below what a hand scanner reliably resolves.
     */
    private fun StringBuilder.barcode(
        x: Int, y: Int, type: String, height: Int, content: String
    ) = line("BARCODE $x,$y,\"$type\",$height,1,0,2,2,\"$content\"")

    // TSC's built-in bitmap fonts, with the cell size each one occupies in dots.
    private const val FONT_SMALL = "1"
    private const val FONT_SMALL_W = 8
    private const val FONT_SMALL_H = 12
    private const val FONT_MEDIUM = "3"
    private const val FONT_MEDIUM_W = 12
    private const val FONT_MEDIUM_H = 24

    /** Below this the bars stop resolving reliably on a hand scanner. */
    private const val MIN_BARS_HEIGHT = 40
}
