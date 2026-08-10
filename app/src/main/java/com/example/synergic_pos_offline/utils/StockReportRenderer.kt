package com.example.synergic_pos_offline.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.BillHeaderFooterDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.StockReportDao
import com.example.synergic_pos_offline.database.LogoDao
import com.example.synergic_pos_offline.database.StockDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Longest edge decoded for a receipt logo; the slots cap well below this. */
private const val LOGO_PX = 480

/** Width the report card is laid out at - see [PrintType.CARD_WIDTH_DP]. */
private const val CARD_WIDTH_DP = PrintType.CARD_WIDTH_DP

/** The card's own horizontal padding, per side - receipt_stock_report.xml. */
private const val CARD_PADDING_DP = 20

/** Printable dots on 80mm paper - what [CARD_WIDTH_DP] is measured against. */
private const val REFERENCE_PAPER_DOTS = PrintType.REFERENCE_PAPER_DOTS

/**
 * Renders a [StockReportDao.Report] onto receipt paper.
 *
 * A serial, a name of any length, and one figure. Serial and quantity are measured
 * to their widest value and given exactly that; the name takes what is left. A name
 * too long for what is left drops to a line of its own with its quantity beneath -
 * the same arrangement the bill uses, and for the same reason: an item name broken
 * across lines is harder to read than an item taking two.
 */
class StockReportRenderer(context: Context) {

    /** Pinned to a standard font scale - see [ReceiptContext]. */
    private val ctx: Context = ReceiptContext.standardFontScale(context)

    /**
     * Renders the report to a bitmap without it ever being shown, laid out for a
     * printer whose head is [paperDots] wide (defaults to 80mm).
     *
     * @return null if it could not be rendered, so a caller does not print blank paper
     */
    fun renderToBitmap(
        report: StockReportDao.Report,
        printedBy: String,
        paperDots: Int = REFERENCE_PAPER_DOTS
    ): Bitmap? = runCatching {
        val root = LayoutInflater.from(ctx).inflate(R.layout.receipt_stock_report, null, false)
        populate(root, report, printedBy, paperDots)

        val card = root.findViewById<View>(R.id.cardStockReportReceipt) ?: return null
        (card.parent as? ViewGroup)?.removeView(card)

        val widthDp = CARD_WIDTH_DP.toDouble() * paperDots / REFERENCE_PAPER_DOTS
        val widthPx = (widthDp * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        if (card.measuredHeight <= 0) return null
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)

        ReceiptPrinter.capture(card)
    }.getOrElse {
        android.util.Log.e(TAG, "Could not render the stock report", it)
        null
    }

    /** Fills an already-inflated [R.layout.receipt_stock_report] in place. */
    fun populate(
        view: View,
        report: StockReportDao.Report,
        printedBy: String,
        paperDots: Int = REFERENCE_PAPER_DOTS
    ) {
        try {
            val db = DatabaseHelper.getInstance(ctx).readableDatabase

            db.query(
                DatabaseHelper.Tables.MD_REGISTRATION,
                arrayOf("store_name", "address", "phone_no", "store_gstin"),
                null, null, null, null, "store_id ASC", "1"
            ).use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.takeIf { it.isNotBlank() }?.let {
                        view.findViewById<TextView>(R.id.tvStockReportStoreName).text = it.uppercase()
                    }
                    setIfPresent(view, R.id.tvStockReportStoreAddress, c.getString(1))
                    setIfPresent(view, R.id.tvStockReportStorePhone, c.getString(2)?.let { "Ph: $it" })
                    setIfPresent(view, R.id.tvStockReportStoreGstin, c.getString(3)?.let { "GSTIN: $it" })
                }
            }

            renderFixedLines(
                db, view, R.id.llStockReportFooterLines,
                DatabaseHelper.Tables.MD_FOOTERS, "footer_text", "footer_number", "footer_type"
            )
            renderLogos(view)

            view.findViewById<TextView>(R.id.tvStockReportPeriod).text =
                "As at ${pretty(report.takenAt)}"
            view.findViewById<TextView>(R.id.tvStockReportCount).text =
                "${report.itemCount} item(s)"
            view.findViewById<TextView>(R.id.tvStockReportPrintedBy).text = "Printed by: $printedBy"
            view.findViewById<TextView>(R.id.tvStockReportPrintedAt).text =
                "Printed on: " + SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.US).format(Date())

            val rows = view.findViewById<LinearLayout>(R.id.llStockReportRows)
            rows.removeAllViews()

            if (report.isEmpty) {
                rows.addView(note("No products on this till."))
            } else {
                val header = TableLine("SL", "ITEM", "QTY")
                val lines = report.lines.map {
                    TableLine(
                        serial = it.serial.toString(),
                        name = it.name,
                        qty = StockDao.trim(it.quantity)
                    )
                }
                val metrics = measureTable(listOf(header) + lines, paperDots)
                rows.addView(tableRow(header, metrics, bold = true))
                lines.forEach { rows.addView(tableRow(it, metrics)) }
            }

            val summary = view.findViewById<LinearLayout>(R.id.llStockReportSummary)
            summary.removeAllViews()
            summary.addView(amountRow("TOTAL ITEMS", report.itemCount.toString()))
            summary.addView(amountRow("OUT OF STOCK", report.outOfStock.toString()))
            summary.addView(
                amountRow(
                    "TOTAL QUANTITY", StockDao.trim(report.totalQuantity),
                    bold = true, valueSize = PrintType.TOTAL_SP
                )
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error rendering the stock report", e)
        }
    }

    // ---- Row builders --------------------------------------------------------

    /** The four cells of one printed line, header row included. */
    private data class TableLine(
        val serial: String,
        val name: String,
        val qty: String
    )

    /** Type size, gutter and the fixed column widths the whole table is set at. */
    private data class TableMetrics(
        val textSize: Float,
        val gutterPx: Int,
        val serialPx: Int,
        val qtyPx: Int,
        val namePx: Int
    )

    /**
     * Works out how the table has to be set for [lines] to fit [paperDots] of paper.
     *
     * Serial, quantity and price are measured to their own widest value and given
     * exactly that: a column given less width than the figure in it wraps the figure
     * mid-value, which on a report is not a cosmetic problem. The name takes what is
     * left, and the type steps down through [SIZES] until a readable name column
     * survives.
     */
    private fun measureTable(lines: List<TableLine>, paperDots: Int): TableMetrics {
        val metrics = ctx.resources.displayMetrics
        val widthDp = CARD_WIDTH_DP.toDouble() * paperDots / REFERENCE_PAPER_DOTS
        val contentPx = ((widthDp - CARD_PADDING_DP * 2) * metrics.density).toFloat()

        val widest = { pick: (TableLine) -> String -> lines.maxOf { pick(it).length } }
        val serialChars = widest { it.serial }
        val qtyChars = widest { it.qty }

        val paint = Paint().apply { typeface = Typeface.MONOSPACE }
        var chosen = SIZES.last()
        var charPx = 0f
        var gutterPx = 0f
        for (size in SIZES) {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, metrics)
            val cw = paint.measureText("0")
            val gutter = (if (size <= 10f) 3f else 6f) * metrics.density
            // What is left for the name once the figures have taken theirs, in
            // characters. Below this the name is too short to identify an item.
            val figures = (serialChars + qtyChars) * cw + gutter * 2
            chosen = size
            charPx = cw
            gutterPx = gutter
            if (contentPx - figures >= NAME_MIN_CHARS * cw) break
        }

        // The serial carries its gutter inside its width, as trailing space: it is
        // left-aligned, so without one "SL" would sit hard against "ITEM" beside it.
        // The two right-aligned columns get theirs as leading padding when the cell
        // is built, which is why they are added back here rather than counted twice.
        val serialPx = (serialChars * charPx + gutterPx).toInt() + 1
        val qtyPx = (qtyChars * charPx).toInt() + 1
        val namePx = (contentPx - serialPx - (qtyPx + gutterPx))
            .coerceAtLeast(NAME_MIN_CHARS * charPx)

        return TableMetrics(
            textSize = chosen,
            gutterPx = gutterPx.toInt(),
            serialPx = serialPx,
            qtyPx = qtyPx,
            namePx = namePx.toInt()
        )
    }

    /**
     * One "SL  ITEM  QTY" line - or two, where the name will not fit beside
     * its figures, in which case it takes the first to itself.
     */
    private fun tableRow(line: TableLine, metrics: TableMetrics, bold: Boolean = false): View {
        val density = ctx.resources.displayMetrics.density
        val paint = Paint().apply {
            typeface = Typeface.MONOSPACE
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, metrics.textSize, ctx.resources.displayMetrics
            )
        }

        fun cell(value: String, widthPx: Int, alignEnd: Boolean, gutter: Boolean = true) =
            TextView(ctx).apply {
                text = value
                textSize = metrics.textSize
                gravity = if (alignEnd) Gravity.END else Gravity.START
                // maxLines, never isSingleLine: the latter also turns on horizontal
                // scrolling, which lays the text out at its natural width and then
                // scrolls it clean out of the cell.
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (gutter && alignEnd) setPadding(metrics.gutterPx, 0, 0, 0)
                setTypeface(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(0xFF222222.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    if (alignEnd) widthPx + metrics.gutterPx else widthPx, -2
                )
            }

        fun figures(row: LinearLayout) {
            row.addView(cell(line.qty, metrics.qtyPx, alignEnd = true))
        }

        // A name that fits keeps the whole line to itself, figures and all.
        if (paint.measureText(line.name) <= metrics.namePx) {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
                addView(cell(line.serial, metrics.serialPx, alignEnd = false))
                addView(cell(line.name, metrics.namePx, alignEnd = false))
                figures(this)
            }
        }

        // Too long: serial and name take the first line in full, the figures the
        // second, still under their own headings.
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
            addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(cell(line.serial, metrics.serialPx, alignEnd = false))
                    addView(
                        cell(
                            line.name,
                            metrics.namePx + metrics.qtyPx + metrics.gutterPx,
                            alignEnd = false
                        )
                    )
                }
            )
            addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    // Serial and name columns held open, so the figures stay under
                    // the headings instead of sliding left into their place.
                    addView(cell("", metrics.serialPx, alignEnd = false))
                    addView(cell("", metrics.namePx, alignEnd = false))
                    figures(this)
                }
            )
        }
    }

    /** A "LABEL              value" summary line. */
    private fun amountRow(
        label: String,
        value: String,
        bold: Boolean = false,
        valueSize: Float = PrintType.BODY_SP
    ): View {
        val density = ctx.resources.displayMetrics.density
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
            addView(TextView(ctx).apply {
                text = label
                textSize = PrintType.BODY_SP
                setTypeface(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(0xFF222222.toInt())
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            addView(TextView(ctx).apply {
                text = value
                textSize = valueSize
                gravity = Gravity.END
                setTypeface(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(0xFF111111.toInt())
                layoutParams = LinearLayout.LayoutParams(-2, -2)
            })
        }
    }

    private fun note(text: String): View = TextView(ctx).apply {
        this.text = text
        textSize = PrintType.SMALL_SP
        gravity = Gravity.CENTER
        setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        setTextColor(0xFF555555.toInt())
        val pad = (8 * ctx.resources.displayMetrics.density).toInt()
        setPadding(0, pad, 0, pad)
    }

    // ---- Shared receipt furniture --------------------------------------------

    private fun renderLogos(view: View) {
        val dao = LogoDao(ctx)
        listOf(
            LogoDao.LogoType.BILL_HEADER to R.id.ivStockReportHeaderLogo,
            LogoDao.LogoType.BILL_FOOTER to R.id.ivStockReportFooterLogo
        ).forEach { (type, viewId) ->
            val target = view.findViewById<ImageView>(viewId)
            val bitmap = dao.getAll(listOf(type)).lastOrNull()?.image
                ?.takeIf { it.isNotEmpty() }
                ?.let { ImageUtils.decodeThumb(it, LOGO_PX) }

            if (bitmap == null) {
                target.setImageDrawable(null)
                target.visibility = View.GONE
            } else {
                target.setImageBitmap(bitmap)
                target.visibility = View.VISIBLE
            }
        }
    }

    private fun renderFixedLines(
        db: SQLiteDatabase,
        root: View,
        containerId: Int,
        table: String,
        textColumn: String,
        numberColumn: String,
        typeColumn: String
    ) {
        val container = root.findViewById<LinearLayout>(containerId)
        container.removeAllViews()
        db.rawQuery(
            """
            SELECT $textColumn, font_size, is_bold FROM $table
            WHERE is_enabled = 1 AND ($typeColumn IS NULL OR $typeColumn = 'BILL')
            ORDER BY $numberColumn ASC
            """.trimIndent(),
            null
        ).use { c ->
            while (c.moveToNext()) {
                val text = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                val size = BillHeaderFooterDao.FontSize.fromStored(c.getString(1))
                val bold = c.getInt(2) == 1
                container.addView(TextView(ctx).apply {
                    this.text = text
                    gravity = Gravity.CENTER
                    textSize = size.sp
                    setTypeface(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(0xFF333333.toInt())
                    setPadding(0, (2 * ctx.resources.displayMetrics.density).toInt(), 0, 0)
                })
            }
        }
    }

    private fun setIfPresent(root: View, id: Int, value: String?) {
        val tv = root.findViewById<TextView>(id)
        if (value.isNullOrBlank()) {
            tv.visibility = View.GONE
        } else {
            tv.text = value
            tv.visibility = View.VISIBLE
        }
    }

    private fun money(v: Double) = String.format(Locale.US, "%.2f", BillRounding.toPaise(v))

    /** "yyyy-MM-dd HH:mm:ss" as "dd-MM-yyyy hh:mm a". */
    private fun pretty(value: String): String = runCatching {
        SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.US)
            .format(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(value)!!)
    }.getOrDefault(value)

    private companion object {
        const val TAG = "StockReportRenderer"

        /**
         * Type sizes the table is tried at, largest first - the bill's own body size
         * down to the floor, see [PrintType].
         */
        val SIZES = listOf(
            PrintType.BODY_SP, PrintType.TABLE_SP, PrintType.SMALL_SP,
            11f, 10f, 9f, 8f, PrintType.FLOOR_SP
        )

        /**
         * The fewest characters of an item name worth printing beside its figures.
         *
         * The type steps down until at least this much name fits; below it the name
         * would identify nothing and the line may as well take two.
         */
        const val NAME_MIN_CHARS = 10f
    }
}
