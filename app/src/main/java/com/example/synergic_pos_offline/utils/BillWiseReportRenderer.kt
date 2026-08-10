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
import com.example.synergic_pos_offline.database.BillWiseReportDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.LogoDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Longest edge decoded for a receipt logo; the slots cap well below this. */
private const val LOGO_PX = 480

/**
 * Width the card is laid out at before it is scaled to the paper.
 *
 * Taken from [PrintType.CARD_WIDTH_DP], the one dial for how big every slip
 * prints - each renderer used to carry its own copy of this number, so making
 * the print bigger meant finding all five and hoping none was missed.
 */
private const val CARD_WIDTH_DP = PrintType.CARD_WIDTH_DP

/** The card's own horizontal padding, per side - receipt_bill_wise_report.xml again. */
private const val CARD_PADDING_DP = 20

/** Printable dots on 80mm paper - what [CARD_WIDTH_DP] is measured against. */
private const val REFERENCE_PAPER_DOTS = PrintType.REFERENCE_PAPER_DOTS

/**
 * Renders a [BillWiseReportDao.Report] onto receipt paper.
 *
 * A bill carries nine figures on this report and a roll fits about four across, so
 * each bill takes two printed lines: the number, how it was paid and what it came
 * to on the first, and the tax and discount that make up the difference on the
 * second. Squeezing all nine onto one line would set the type so small that the
 * report could not be read, which is the only thing a printed report is for.
 */
class BillWiseReportRenderer(context: Context) {

    /** Pinned to a standard font scale - see [ReceiptContext]. */
    private val ctx: Context = ReceiptContext.standardFontScale(context)

    /**
     * Renders the report to a bitmap without it ever being shown, laid out for a
     * printer whose head is [paperDots] wide (defaults to 80mm).
     *
     * @return null if it could not be rendered, so a caller does not print blank paper
     */
    fun renderToBitmap(
        report: BillWiseReportDao.Report,
        printedBy: String,
        paperDots: Int = REFERENCE_PAPER_DOTS
    ): Bitmap? = runCatching {
        val root = LayoutInflater.from(ctx).inflate(R.layout.receipt_bill_wise_report, null, false)
        populate(root, report, printedBy, paperDots)

        val card = root.findViewById<View>(R.id.cardReportReceipt) ?: return null
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
        android.util.Log.e(TAG, "Could not render the bill wise report", it)
        null
    }

    /** Fills an already-inflated [R.layout.receipt_bill_wise_report] in place. */
    fun populate(
        view: View,
        report: BillWiseReportDao.Report,
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
                        view.findViewById<TextView>(R.id.tvReportStoreName).text = it.uppercase()
                    }
                    setIfPresent(view, R.id.tvReportStoreAddress, c.getString(1))
                    setIfPresent(view, R.id.tvReportStorePhone, c.getString(2)?.let { "Ph: $it" })
                    setIfPresent(view, R.id.tvReportStoreGstin, c.getString(3)?.let { "GSTIN: $it" })
                }
            }

            renderFixedLines(
                db, view, R.id.llReportFooterLines,
                DatabaseHelper.Tables.MD_FOOTERS, "footer_text", "footer_number", "footer_type"
            )
            renderLogos(view)

            view.findViewById<TextView>(R.id.tvReportPeriod).text =
                "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}"
            view.findViewById<TextView>(R.id.tvReportBillCount).text =
                "${report.billCount} bill(s)"
            view.findViewById<TextView>(R.id.tvReportPrintedBy).text = "Printed by: $printedBy"
            view.findViewById<TextView>(R.id.tvReportPrintedAt).text =
                "Printed on: " + SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.US).format(Date())

            val rows = view.findViewById<LinearLayout>(R.id.llReportRows)
            rows.removeAllViews()

            if (report.isEmpty) {
                rows.addView(note("No bills in this period."))
            } else {
                val header = TableLine("BILL NO", "MODE", "MRP", "AMOUNT")
                val lines = report.lines.map {
                    TableLine(
                        billNo = it.billNumber,
                        mode = it.payMode,
                        mrp = money(it.mrp),
                        amount = money(it.netAmount)
                    )
                }
                val metrics = measureTable(listOf(header) + lines, paperDots)

                rows.addView(tableRow(header, metrics, bold = true))
                report.lines.forEachIndexed { i, line ->
                    rows.addView(tableRow(lines[i], metrics))
                    rows.addView(taxRow(line, metrics, paperDots))
                }
            }

            val summary = view.findViewById<LinearLayout>(R.id.llReportSummary)
            summary.removeAllViews()
            // The parts first, then the adjustment, then what they came to - the
            // order the figures actually add up in.
            buildList {
                add("TOTAL BILLS" to report.billCount.toString())
                add("TOTAL MRP" to money(report.totalMrp))
                add("TOTAL CGST" to money(report.totalCgst))
                add("TOTAL SGST" to money(report.totalSgst))
                add("TOTAL IGST" to money(report.totalIgst))
                // Only where the period holds VAT bills - see Report.hasVat. Without
                // this their tax would be missing from the totals altogether.
                if (report.hasVat) add("TOTAL VAT" to money(report.totalVat))
                add("TOTAL DISCOUNT" to money(report.totalDiscount))
                add("TOTAL ROUND OFF" to money(report.totalRoundOff))
            }.forEach { (label, value) -> summary.addView(amountRow(label, value)) }
            summary.addView(
                amountRow("TOTAL BILL AMOUNT", money(report.totalAmount), bold = true, valueSize = PrintType.TOTAL_SP)
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error rendering the bill wise report", e)
        }
    }

    // ---- Row builders --------------------------------------------------------

    /** The four cells of a bill's first printed line, header row included. */
    private data class TableLine(
        val billNo: String,
        val mode: String,
        val mrp: String,
        val amount: String
    )

    /** Type size, gutter and fixed column widths the whole table is set at. */
    private data class TableMetrics(
        val textSize: Float,
        val gutterPx: Int,
        val modePx: Int,
        val mrpPx: Int,
        val amountPx: Int
    )

    /**
     * Works out how the table has to be set for [lines] to fit [paperDots] of paper.
     *
     * A weighted column cannot be trusted with a number: give it less width than the
     * figure in it and Android hard-wraps mid-number ("125000." over "00"), which on
     * a report is not a cosmetic problem. So each fixed column is measured to its own
     * widest value and given exactly that width, and the type steps down through
     * [SIZES] until they plus a readable bill number fit the roll. The bill number
     * takes whatever is left, being the one value that can be allowed to ellipsize.
     */
    private fun measureTable(lines: List<TableLine>, paperDots: Int): TableMetrics {
        val metrics = ctx.resources.displayMetrics
        val widthDp = CARD_WIDTH_DP.toDouble() * paperDots / REFERENCE_PAPER_DOTS
        val contentPx = ((widthDp - CARD_PADDING_DP * 2) * metrics.density).toFloat()

        val widest = { pick: (TableLine) -> String -> lines.maxOf { pick(it).length } }
        val billChars = widest { it.billNo }
        val modeChars = widest { it.mode }
        val mrpChars = widest { it.mrp }
        val amountChars = widest { it.amount }

        val paint = Paint().apply { typeface = Typeface.MONOSPACE }
        var chosen = SIZES.last()
        var charPx = 0f
        var gutterPx = 0f
        for (size in SIZES) {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, metrics)
            val cw = paint.measureText("0")
            val gutter = (if (size <= 10f) 3f else 6f) * metrics.density
            val needed = (billChars + modeChars + mrpChars + amountChars) * cw + gutter * 3
            chosen = size
            charPx = cw
            gutterPx = gutter
            if (needed <= contentPx) break
        }

        return TableMetrics(
            textSize = chosen,
            gutterPx = gutterPx.toInt(),
            modePx = (modeChars * charPx).toInt() + 1,
            mrpPx = (mrpChars * charPx).toInt() + 1,
            amountPx = (amountChars * charPx).toInt() + 1
        )
    }

    /**
     * A bill's first line: "BILL NO  MODE  MRP  AMOUNT".
     *
     * The number is left-aligned and takes the slack; the other three sit at the
     * fixed widths [metrics] measured, so the figures line up down the page.
     */
    private fun tableRow(line: TableLine, metrics: TableMetrics, bold: Boolean = false): View {
        val density = ctx.resources.displayMetrics.density
        fun cell(value: String, widthPx: Int?, alignEnd: Boolean) = TextView(ctx).apply {
            text = value
            textSize = metrics.textSize
            gravity = if (alignEnd) Gravity.END else Gravity.START
            // maxLines, never isSingleLine: the latter also turns on horizontal
            // scrolling, and a right-aligned scrolling cell draws its text outside
            // itself - the figure prints blank in a cell of exactly the right width.
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            // A gutter before each fixed column: a right-aligned value would
            // otherwise touch the column before it.
            if (alignEnd) setPadding(metrics.gutterPx, 0, 0, 0)
            setTypeface(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(0xFF222222.toInt())
            layoutParams =
                if (widthPx == null) LinearLayout.LayoutParams(0, -2, 1f)
                else LinearLayout.LayoutParams(widthPx + metrics.gutterPx, -2)
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (2 * density).toInt(), 0, 0)
            addView(cell(line.billNo, null, alignEnd = false))
            addView(cell(line.mode, metrics.modePx, alignEnd = true))
            addView(cell(line.mrp, metrics.mrpPx, alignEnd = true))
            addView(cell(line.amount, metrics.amountPx, alignEnd = true))
        }
    }

    /**
     * A bill's second line: the tax and discount behind the amount above it.
     *
     * Written as one string rather than as columns - it is a breakdown of the line
     * above, not a table of its own, and labelling each figure means it can be read
     * without a heading of its own to look up. Set a size smaller than the table so
     * it reads as belonging to the bill above rather than as another bill, and
     * stepped down further if the labels and figures will not fit the roll.
     */
    private fun taxRow(
        line: BillWiseReportDao.Line,
        metrics: TableMetrics,
        paperDots: Int
    ): View {
        // Broken out under the regime this bill was actually raised under, read from
        // the settings snapshot frozen onto it. A VAT bill printed with CGST and
        // SGST columns of 0.00 would read as a bill that charged no tax, when it
        // charged VAT; and the till may since have been switched to the other
        // regime, which is exactly what the snapshot is there to survive.
        val text = if (line.isVat) {
            "  VAT ${money(line.vat)}  DISC ${money(line.discount)}"
        } else {
            "  CGST ${money(line.cgst)}  SGST ${money(line.sgst)}" +
                "  IGST ${money(line.igst)}  DISC ${money(line.discount)}"
        }

        val displayMetrics = ctx.resources.displayMetrics
        val widthDp = CARD_WIDTH_DP.toDouble() * paperDots / REFERENCE_PAPER_DOTS
        val contentPx = ((widthDp - CARD_PADDING_DP * 2) * displayMetrics.density).toFloat()
        val paint = Paint().apply { typeface = Typeface.MONOSPACE }
        // Never larger than the line it belongs under, and never below the floor -
        // past that it stops being legible on a thermal head at all.
        var size = (metrics.textSize - 1f).coerceAtLeast(SIZES.last())
        while (size > SIZES.last()) {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, displayMetrics)
            if (paint.measureText(text) <= contentPx) break
            size -= 1f
        }

        return TextView(ctx).apply {
            this.text = text
            textSize = size
            maxLines = 1
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextColor(0xFF555555.toInt())
            setPadding(0, 0, 0, (3 * displayMetrics.density).toInt())
        }
    }

    /** A "LABEL              value" summary line. */
    private fun amountRow(label: String, value: String, bold: Boolean = false, valueSize: Float = PrintType.BODY_SP): View {
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
            LogoDao.LogoType.BILL_HEADER to R.id.ivReportHeaderLogo,
            LogoDao.LogoType.BILL_FOOTER to R.id.ivReportFooterLogo
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

    /** "yyyy-MM-dd" as "dd-MM-yyyy". */
    private fun pretty(value: String): String = runCatching {
        SimpleDateFormat("dd-MM-yyyy", Locale.US)
            .format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value.take(10))!!)
    }.getOrDefault(value)

    private companion object {
        const val TAG = "BillWiseReportRenderer"

        /**
         * Type sizes the table is tried at, largest first.
         *
         * Starts at the bill's own body size and only steps down when the columns
         * genuinely will not fit the roll - see [PrintType].
         */
        val SIZES = listOf(
            PrintType.BODY_SP, PrintType.TABLE_SP, PrintType.SMALL_SP,
            11f, 10f, 9f, 8f, PrintType.FLOOR_SP
        )
    }
}
