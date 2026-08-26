package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.synergic_pos_offline.database.DatabaseHelper

/**
 * The counter coupons that come off with a bill, one per category.
 *
 * A shop with counters - sweets at one, snacks at another - hands the customer a
 * slip per counter rather than sending them round with the whole bill. Each coupon
 * lists only that counter's items and what quantity of each, so the person behind
 * the counter reads one short list instead of finding their three lines among
 * fifteen.
 *
 * The split is by the product's **category**, which is what a counter is in the item
 * master: six items across three categories come off as three coupons, of two, three
 * and one line. An item whose product has no category goes on a coupon of its own
 * headed [UNCATEGORISED] rather than being dropped - a coupon nobody claims is a
 * question at the counter, but a missing item is a customer who paid for something
 * they never got.
 *
 * These carry no prices. A coupon is an instruction to hand something over, and the
 * money has already been settled on the bill the customer is holding; putting a
 * figure on it only invites it to be read as a second demand for payment.
 *
 * Printed as their own bitmaps after the bill, so each one gets the partial cut
 * every slip in a print sequence gets - the half cut a KOT comes off with, which is
 * what lets them be torn apart and handed out separately.
 */
object CouponPrinter {

    /** What the coupon is headed when the product has no category on it. */
    private const val UNCATEGORISED = "OTHER"

    /**
     * Blank space under the last line, in dp - the same clearance a KOT leaves.
     *
     * A coupon is torn off and handed over at a counter, not filed, so the last line
     * wants paper under it rather than sitting on the tear.
     */
    private const val BOTTOM_MARGIN_DP = 20f

    /** One line of a coupon: what to hand over, and how much of it. */
    data class Line(val name: String, val quantity: Double)

    /**
     * One sold line together with the counter it belongs to.
     *
     * The shape both entry points meet at. A saved bill reads its categories out of
     * the database; a restaurant bill prints before the sale is written and has to
     * pass the ones it is already holding. Grouping and rendering happen once, below,
     * on whichever of the two produced the list.
     */
    data class CategorisedLine(val category: String, val name: String, val quantity: Double)

    /**
     * One printed row: what sits on the left, what sits hard against the right, and
     * the paint for both.
     *
     * Two columns rather than one because that is the shape a counter reads - the
     * name to find, the figure to count - and a right-aligned quantity keeps the
     * figures in a column whatever length the names are.
     */
    private class Row(val left: String, val right: String, val paint: Paint)

    /** One counter's coupon for one bill. */
    data class Coupon(
        val category: String,
        val billNumber: String,
        val dateTime: String,
        val lines: List<Line>
    )

    /**
     * The coupons for [receiptNo], or empty when the setting is off or the bill has
     * no items.
     *
     * Reads the setting itself rather than trusting the caller, so every print path
     * that asks for a bill's slips gets the same answer without each having to
     * remember to check.
     */
    fun couponsFor(context: Context, receiptNo: Long, paperDots: Int): List<Bitmap> {
        if (!enabled(context)) return emptyList()

        // The whole thing under one runCatching, rendering included: a coupon is an
        // extra, and nothing that goes wrong producing one may stop the bill it came
        // with from printing. The customer can be sent to the counter without a slip;
        // they cannot be sent away without their bill.
        return runCatching {
            val (billNumber, dateTime, lines) = read(context, receiptNo)
            draw(context, group(lines, billNumber, dateTime), paperDots)
        }.getOrElse {
            android.util.Log.e(TAG, "Could not build the coupons for bill $receiptNo", it)
            emptyList()
        }
    }

    /**
     * The coupons for a sale that has not been written yet.
     *
     * The restaurant prints its bill from an order in memory - the sale lands in the
     * transaction tables only once payment is confirmed - so there is no receipt
     * number to read items back by. That path passes the lines it is already holding,
     * categories and all, and gets the same coupons a saved bill would have produced.
     */
    fun couponsFrom(
        context: Context,
        lines: List<CategorisedLine>,
        billNumber: String,
        dateTime: String,
        paperDots: Int
    ): List<Bitmap> {
        if (!enabled(context)) return emptyList()
        return runCatching {
            draw(context, group(lines, billNumber, dateTime), paperDots)
        }.getOrElse {
            android.util.Log.e(TAG, "Could not build the coupons for bill $billNumber", it)
            emptyList()
        }
    }

    /** Whether this till splits a bill into counter coupons at all. */
    private fun enabled(context: Context): Boolean = runCatching {
        com.example.synergic_pos_offline.database.BillSettingsDao(context).load().couponSplit
    }.getOrDefault(false)

    private fun draw(context: Context, coupons: List<Coupon>, paperDots: Int): List<Bitmap> {
        val language = PrintLanguage.of(context)
        return coupons.map { render(it, paperDots, language) }
    }

    /**
     * Groups [lines] into one coupon per counter, in the order the counters first
     * appear on the bill.
     *
     * Insertion order rather than alphabetical: the bill was rung up in some order
     * and the coupons come off in it, so a counter's slip lands in the same place in
     * the stack every time rather than moving with whatever the categories happen to
     * be called.
     */
    internal fun group(
        lines: List<CategorisedLine>,
        billNumber: String,
        dateTime: String
    ): List<Coupon> {
        val grouped = linkedMapOf<String, MutableList<Line>>()
        lines.forEach { line ->
            val name = line.name.trim()
            if (name.isEmpty()) return@forEach
            val counter = oneLine(line.category).ifBlank { UNCATEGORISED }
            grouped.getOrPut(counter) { mutableListOf() }.add(Line(name, line.quantity))
        }
        return grouped.map { (category, items) -> Coupon(category, billNumber, dateTime, items) }
    }

    private const val TAG = "CouponPrinter"

    /** A saved bill's number, date and sold lines, each with the counter it is on. */
    private fun read(context: Context, receiptNo: Long): Triple<String, String, List<CategorisedLine>> {
        val db = DatabaseHelper.getInstance(context).readableDatabase

        var billNumber = receiptNo.toString()
        var dateTime = ""
        db.rawQuery(
            "SELECT bill_number, bill_date_time FROM ${DatabaseHelper.Tables.TD_BILLS} WHERE receipt_no = ?",
            arrayOf(receiptNo.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.takeIf { it.isNotBlank() }?.let { billNumber = it }
                dateTime = c.getString(1).orEmpty()
            }
        }

        val lines = mutableListOf<CategorisedLine>()
        db.rawQuery(
            """
            SELECT COALESCE(c.category_name, ''), COALESCE(p.product_name, ''), i.quantity
            FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} i
            LEFT JOIN ${DatabaseHelper.Tables.MD_PRODUCTS} p ON i.product_id = p.id
            LEFT JOIN ${DatabaseHelper.Tables.MD_CATEGORY} c ON c.id = p.category_id
            WHERE i.bill_id = ?
            ORDER BY i.id ASC
            """.trimIndent(),
            arrayOf(receiptNo.toString())
        ).use { c ->
            while (c.moveToNext()) {
                lines.add(CategorisedLine(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getDouble(2)))
            }
        }
        return Triple(billNumber, dateTime, lines)
    }

    /**
     * One coupon, drawn to a bitmap [width] dots across.
     *
     * Laid out like the KOT it is torn off beside - same face, same sizes, drawn on
     * a canvas rather than inflated from a layout - so the two documents coming off
     * one till look like they came off one till.
     */
    fun render(
        coupon: Coupon,
        width: Int,
        language: PrintLanguage.Language = PrintLanguage.Language.ENGLISH
    ): Bitmap {
        val title = PrintType.paint(PrintType.STORE_NAME_SP, bold = true)
        val sub = PrintType.paint(PrintType.BODY_SP)
        val header = PrintType.paint(PrintType.BODY_SP, bold = true)
        val item = PrintType.paint(PrintType.BODY_SP, bold = true)

        val padX = width * 0.04f
        val padTop = width * 0.04f
        val padBottom = width * 0.10f + PrintType.dots(BOTTOM_MARGIN_DP)
        val gap = width * 0.012f

        fun t(text: String) = PrintLanguage.tr(language, text)

        val rows = mutableListOf<Row>()
        rows += Row(t("COUPON"), "", title)
        // The counter's own name, which is the category. Not translated: it is what
        // this shop calls that counter, the way a store name or a table code is.
        rows += Row("${t("COUNTER")} : ${coupon.category.uppercase()}", "", sub)
        rows += Row("${t("COUPON NO")}: ${coupon.billNumber}", coupon.dateTime, sub)
        rows += Row(t("ITEM"), t("QUANTITY"), header)
        coupon.lines.forEach { line ->
            rows += Row(
                ProductName.inPrintLanguage(language, line.name).uppercase(),
                qtyText(line.quantity),
                item
            )
        }

        // A rule under the identifying block and another under the column headings,
        // the two places the eye needs a break.
        val ruleBefore = setOf(3, 4)

        fun layout(canvas: Canvas?): Float {
            var y = padTop
            rows.forEachIndexed { index, row ->
                if (index in ruleBefore) y = PrintType.drawRule(canvas, y, width, padX)
                y -= row.paint.ascent()
                if (row.right.isEmpty()) {
                    // A lone line - the title centres, everything else sits left.
                    val centred = row.paint === title
                    row.paint.textAlign = if (centred) Paint.Align.CENTER else Paint.Align.LEFT
                    canvas?.drawText(row.left, if (centred) width / 2f else padX, y, row.paint)
                } else {
                    row.paint.textAlign = Paint.Align.LEFT
                    canvas?.drawText(row.left, padX, y, row.paint)
                    row.paint.textAlign = Paint.Align.RIGHT
                    canvas?.drawText(row.right, width - padX, y, row.paint)
                }
                y += row.paint.descent() + gap
            }
            return y
        }

        val height = (layout(null) + padBottom).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        layout(Canvas(bmp).apply { drawColor(Color.WHITE) })
        return bmp
    }

    /**
     * A category name flattened to one line.
     *
     * The master lets a category be named over more than one line, and the item
     * master shows it that way. A coupon head is drawn with a single drawText call,
     * which has no idea what a line break is - it would come out as a box, or as two
     * words run together. Flattened at grouping time rather than at drawing, so two
     * spellings that differ only in their whitespace land on one coupon instead of
     * two half-empty ones.
     */
    private fun oneLine(raw: String): String = raw.replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")

    /** Whole quantities print without decimals; fractional ones keep two places. */
    private fun qtyText(qty: Double): String =
        if (qty % 1.0 == 0.0) qty.toInt().toString()
        else String.format(java.util.Locale.US, "%.2f", qty)
}
