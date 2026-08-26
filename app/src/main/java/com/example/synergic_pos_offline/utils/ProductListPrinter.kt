package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.synergic_pos_offline.database.DatabaseHelper
import java.util.Locale

/**
 * The item master as a printed table: PRODUCT DETAILS, one product per line.
 *
 * The generic master printout ([DataTableFragment]'s) writes a *block* per record -
 * a heading, then a "Column: value" line under it - which is right for a customer or
 * a unit, where there are a handful of records and each has fields worth reading in
 * full. It is wrong for the catalogue: a shop with three hundred products gets three
 * hundred blocks and a metre of paper, and no way to run a finger down a column of
 * rates to find the one that is wrong.
 *
 * This prints what somebody checking a price list actually wants - a column of names
 * against a column of figures - so the whole catalogue fits in the space the blocks
 * spent on twenty of it.
 *
 * The tax columns are the *rate's*, not the product's: a product is priced through
 * md_product_rates and its GST is set there. A product with more than one rate gets
 * a line per rate, because they are genuinely different prices and printing only the
 * first would quietly hide the rest.
 */
object ProductListPrinter {

    /** What the slip is headed - the name this document goes by on the counter. */
    private const val TITLE = "PRODUCT DETAILS"

    /**
     * Character widths of the fixed columns; the name gets whatever is left.
     *
     * Kept tight because the name is what has to fit. At these widths an 80mm roll
     * leaves about seventeen characters for a product name, which takes most of the
     * catalogue on one line; widening the figures by a character each would cost
     * three of those and wrap a great deal more of it.
     */
    // Four rather than three so the widest heading, "PID", still has a space after
    // it. A two-digit id leaves its own gap; the heading would not.
    private const val PID_W = 4
    private const val TAX_W = 6
    private const val AMOUNT_W = 8

    /**
     * How far a wrapped name is indented on its continuation line, so it reads as
     * more of the line above rather than as a new product with no number.
     */
    private const val WRAP_INDENT = PID_W + 1

    /** One printed row of the table, already split into its columns. */
    internal data class Row(
        val pid: String,
        val name: String,
        val cgst: String,
        val sgst: String,
        val amount: String
    )

    /**
     * Renders the products with [ids] as a table [paperDots] across.
     *
     * [ids] are product ids - what the screen's rows are keyed by - so the slip
     * carries exactly the records that were ticked, in catalogue order rather than
     * in the order they happened to be tapped.
     */
    fun render(context: Context, ids: Collection<String>, paperDots: Int): Bitmap? {
        val rows = runCatching { read(context, ids) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return null

        // Set a size down from the bill's body. Five columns will not fit across a
        // roll at body size - the name column comes out nine characters wide, which
        // wraps most of the catalogue - and a price list is a document that gets
        // scanned down rather than read across, which is what small type is for.
        val body = PrintType.paint(PrintType.SMALL_SP)
        val bold = PrintType.paint(PrintType.SMALL_SP, bold = true)
        val titleP = PrintType.paint(PrintType.STORE_NAME_SP, bold = true, align = Paint.Align.CENTER)

        val pad = 12f
        val lineH = (PrintType.dots(PrintType.SMALL_SP) * 1.45f).toInt()
        val across = PrintType.charsAcross(body, paperDots, pad)
        // Whatever the fixed columns do not take. Floored at something a name is
        // still readable in, so a very narrow roll wraps more rather than printing a
        // column one letter wide.
        val nameW = nameWidthFor(across)

        // A heavy rule above and below the headings, which is what separates the
        // column labels from the figures at a glance - see the printed reference.
        val heavy = "=".repeat(across)

        val lines = ArrayList<Pair<String, Boolean>>()
        fun add(text: String, emphasised: Boolean = false) { lines.add(text to emphasised) }

        add(heavy)
        add(row(Row("PID", "NAME", "CGST%", "SGST%", "AMOUNT"), nameW), true)
        add(heavy)
        rows.forEach { r ->
            val wrapped = wrap(r.name, nameW)
            add(row(r.copy(name = wrapped.first()), nameW))
            // A name too long for its column runs on underneath, carrying no figures:
            // repeating them would read as a second product at the same price.
            wrapped.drop(1).forEach { rest ->
                add(" ".repeat(WRAP_INDENT) + rest)
            }
        }
        add(heavy)
        add("${rows.size} product(s)")

        val topMargin = lineH * 2
        val titleH = lineH * 2
        val bottomMargin = lineH * 3
        val height = topMargin + titleH + lines.size * lineH + bottomMargin
        val bitmap = Bitmap.createBitmap(paperDots, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }

        var y = topMargin.toFloat() + lineH
        canvas.drawText(TITLE, paperDots / 2f, y, titleP)
        y += titleH
        lines.forEach { (text, emphasised) ->
            canvas.drawText(text, pad, y, if (emphasised) bold else body)
            y += lineH
        }
        return bitmap
    }

    /**
     * What is left for the name once the fixed columns have taken theirs.
     *
     * Floored at something a name is still readable in, so a very narrow roll wraps
     * more rather than printing a column one letter wide.
     */
    internal fun nameWidthFor(across: Int): Int =
        (across - PID_W - TAX_W * 2 - AMOUNT_W).coerceAtLeast(8)

    /**
     * One line of the table, padded into its columns.
     *
     * Built as a single padded string and drawn with one call rather than as five
     * separate draws at measured offsets: the slip is set in a monospace face - see
     * [PrintType] - so a space is exactly as wide as a digit, and letting the
     * characters do the aligning keeps the header and its figures in step by
     * construction.
     */
    internal fun row(r: Row, nameW: Int): String = buildString {
        append(r.pid.take(PID_W).padEnd(PID_W))
        append(r.name.take(nameW).padEnd(nameW))
        append(r.cgst.padStart(TAX_W))
        append(r.sgst.padStart(TAX_W))
        append(r.amount.padStart(AMOUNT_W))
    }

    /** [text] broken to at most [width] characters a line, on spaces where it can. */
    internal fun wrap(text: String, width: Int): List<String> {
        if (text.length <= width) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > width) {
            val space = rest.lastIndexOf(' ', width)
            val cut = if (space > width / 2) space else width
            out.add(rest.substring(0, cut).trimEnd())
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    /**
     * The chosen products with their rates, in catalogue order.
     *
     * A product with no rate row still prints, at zeroes: it is in the catalogue and
     * a price list that silently omits it is how an unpriced product stays unpriced.
     */
    private fun read(context: Context, ids: Collection<String>): List<Row> {
        val numeric = ids.mapNotNull { it.toIntOrNull() }
        if (numeric.isEmpty()) return emptyList()
        val placeholders = numeric.joinToString(",") { "?" }

        val out = ArrayList<Row>()
        DatabaseHelper.getInstance(context).readableDatabase.rawQuery(
            """
            SELECT p.id, p.product_name,
                   COALESCE(r.cgst_rate, 0), COALESCE(r.sgst_rate, 0),
                   COALESCE(COALESCE(r.sell_price, r.sale_price), r.rate, 0)
            FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p
            LEFT JOIN ${DatabaseHelper.Tables.MD_PRODUCT_RATES} r ON r.product_id = p.id
            WHERE p.id IN ($placeholders)
            ORDER BY p.id ASC, r.id ASC
            """.trimIndent(),
            numeric.map { it.toString() }.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Row(
                        pid = c.getInt(0).toString(),
                        name = c.getString(1).orEmpty().trim().uppercase(Locale.US),
                        cgst = money(c.getDouble(2)),
                        sgst = money(c.getDouble(3)),
                        amount = money(c.getDouble(4))
                    )
                )
            }
        }
        return out
    }

    private fun money(value: Double): String = String.format(Locale.US, "%.2f", value)
}
