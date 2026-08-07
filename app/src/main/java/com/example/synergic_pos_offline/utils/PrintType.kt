package com.example.synergic_pos_offline.utils

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * How anything printed on this till is set: one typeface, one set of sizes, one
 * rule.
 *
 * The bill is what a customer sees most, so the bill is the reference - these are
 * the sizes fragment_bill_classic.xml uses, and every other slip is set to match.
 * Before this existed each printer decided for itself: the bill and the statements
 * were laid out from XML at these sizes, the KOT and the restaurant bill drew
 * themselves on a canvas at a *fraction of the paper width*, and the master list
 * drew itself at a fixed pixel size. Three slips off one till, three different
 * faces, and a rule that was a hyphen run on one and a solid black bar on another.
 *
 * ## Why the sizes convert the way they do
 *
 * A slip built from a layout is laid out on a card [CARD_WIDTH_DP] wide (scaled for
 * the paper), captured, and then scaled again to the head's width. Both scalings
 * cancel: text set at S sp always lands at S * [REFERENCE_PAPER_DOTS] /
 * [CARD_WIDTH_DP] dots on the paper, whatever the roll and whatever the device's
 * screen density. That ratio is what [dots] applies, so a canvas-drawn slip asking
 * for [BODY_SP] gets exactly the height of a body line on the bill.
 *
 * It follows that print sizes are *absolute*, not proportional to the roll: 13sp is
 * the same height of character on 58mm as on 80mm, and narrow paper fits fewer of
 * them per line rather than shrinking them. That is the right way round - a kitchen
 * reading a ticket across a hot pass does not want smaller type because the roll is
 * narrower - and it is why the slips that size themselves off the paper width had
 * to change rather than everything else changing to match them.
 */
object PrintType {

    /**
     * Width in dp the receipt layouts are laid out at, before scaling to paper -
     * and, because of that, **the dial that sets how big everything prints**.
     *
     * A slip is laid out on a card this wide and then stretched to fill the roll, so
     * the narrower the card, the more each character is stretched: printed height is
     * [sp] * [REFERENCE_PAPER_DOTS] / this. Lowering it magnifies every printed slip
     * at once - the layouts and, through [dots], the canvas-drawn ones too - with
     * nothing else to keep in step.
     *
     * What it costs is characters per line: the type grows but the paper does not,
     * so a line holds fewer of them and long item names wrap sooner.
     *
     * At 300 a body line prints about 25 dots tall, which is the height a thermal
     * printer's own built-in font sets at (ESC/POS "Font A" is 24 dots), and about
     * 33 characters fit across 80mm. It was 360, which printed markedly smaller than
     * the printer's own font.
     *
     * 285 was tried from here and taken back out: it was bigger, but the extra size
     * cost two characters a line and read worse on paper for it. Worth knowing
     * before anyone reaches for the same step again.
     *
     * The receipt layouts declare the same width on their cards so the preview on
     * screen is the slip that comes out of the printer; change this and change those
     * with it.
     */
    const val CARD_WIDTH_DP = 300f

    /** Printable dots on 80mm paper - the width [CARD_WIDTH_DP] is measured against. */
    const val REFERENCE_PAPER_DOTS = 576

    // ---- The sizes, in sp, exactly as fragment_bill_classic.xml sets them -----

    /** The store's name at the head of the slip. */
    const val STORE_NAME_SP = 18f

    /** GRAND TOTAL - the one line the customer looks for. */
    const val TOTAL_SP = 17f

    /** What a statement-style slip is called: CUSTOMER LEDGER, BILL WISE REPORT. */
    const val TITLE_SP = 15f

    /** Ordinary text: the bill head, the customer block, the totals. */
    const val BODY_SP = 13f

    /** A line of a table, where five columns share a row and the type has to give. */
    const val TABLE_SP = 12.5f

    /** Secondary text: amount in words, who rang the sale up, a note. */
    const val SMALL_SP = 12f

    /** The store's address, phone and GSTIN under its name. */
    const val STORE_INFO_SP = 11f

    /**
     * The smallest the type is ever set, whatever will not otherwise fit.
     *
     * A thermal head at 203dpi cannot resolve much below this, so a table that only
     * fits by going smaller does not fit at all - it prints as a grey smudge that
     * reads as a fault in the printer.
     */
    const val FLOOR_SP = 7f

    /**
     * The rule drawn between sections - the exact hyphen run `@style/BillDashLine`
     * carries, so a rule built in code and a rule built in XML are the same rule.
     *
     * Deliberately longer than any roll it prints on: the overflow is the point. It
     * is cut off square at the edge of the paper, which is what a printed rule looks
     * like, rather than stopping short or trailing an ellipsis.
     */
    const val RULE = "--------------------------------------------------------"

    /** Space above and below a rule, in dp - `@style/BillDashLine`'s margins. */
    const val RULE_MARGIN_DP = 6f

    // ---- For slips drawn on a canvas ----------------------------------------

    /**
     * The height in dots that [sp] prints at - the same height the layouts print it
     * at, so a canvas-drawn slip and a bill can be held side by side.
     */
    fun dots(sp: Float): Float = sp * REFERENCE_PAPER_DOTS.toFloat() / CARD_WIDTH_DP

    /** A monospace paint set at [sp], for a slip drawn straight onto a canvas. */
    fun paint(
        sp: Float,
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        textSize = dots(sp)
        textAlign = align
    }

    /**
     * How many characters of [paint] fit across [paperDots] of paper, allowing
     * [padding] dots at each edge.
     */
    fun charsAcross(paint: Paint, paperDots: Int, padding: Float = 0f): Int {
        val charWidth = paint.measureText("0").coerceAtLeast(1f)
        return ((paperDots - padding * 2) / charWidth).toInt().coerceAtLeast(1)
    }

    /**
     * Draws the rule across the paper at [y] and returns the y to carry on from.
     *
     * Drawn as hyphens rather than as a filled bar: the bill's rule is a row of
     * characters, and a solid black line beside it reads as a different document
     * from a different program. Cut to the paper rather than wrapped, for the reason
     * [RULE] is over-long in the first place.
     */
    fun drawRule(canvas: Canvas?, y: Float, paperDots: Int, padding: Float): Float {
        val paint = paint(BODY_SP)
        // The full margin above and below, as `@style/BillDashLine` sets it - not
        // half each, which would sit the rule tighter than the one on the bill.
        val margin = dots(RULE_MARGIN_DP)
        val baseline = y + margin - paint.ascent()
        canvas?.drawText(RULE.take(charsAcross(paint, paperDots, padding)), padding, baseline, paint)
        return baseline + paint.descent() + margin
    }
}
