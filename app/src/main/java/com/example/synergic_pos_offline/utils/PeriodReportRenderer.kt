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
import com.example.synergic_pos_offline.database.LogoDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Longest edge decoded for a receipt logo; the slots cap well below this. */
private const val LOGO_PX = 480

/** Width the card is laid out at before it is scaled to the paper - see [PrintType]. */
private const val CARD_WIDTH_DP = PrintType.CARD_WIDTH_DP

/** The card's own horizontal padding, per side - receipt_period_report.xml. */
private const val CARD_PADDING_DP = 20

/** Printable dots on 80mm paper - what [CARD_WIDTH_DP] is measured against. */
private const val REFERENCE_PAPER_DOTS = PrintType.REFERENCE_PAPER_DOTS

/**
 * Renders any period report - a date range, a table of rows, a block of totals -
 * onto receipt paper.
 *
 * One renderer rather than one per report. The store's name, its logos and footer
 * lines, the printed-by block and the way a table is fitted to a roll are the same
 * problem whatever the report is about, and they were already solved once in
 * [BillWiseReportRenderer]; solving them again per new report is how five slips off
 * one till end up looking like five different programs.
 *
 * What a report actually supplies is [Content]: its title, its columns, its rows as
 * text, and its summary. The figures are formatted by the caller, because only the
 * caller knows what they mean - this decides how they are set on paper, not what
 * they say.
 */
class PeriodReportRenderer(context: Context) {

    /** Pinned to a standard font scale - see [ReceiptContext]. */
    private val ctx: Context = ReceiptContext.standardFontScale(context)

    /**
     * A report, ready to print.
     *
     * [columns] and every row of [rows] must be the same length - the first cell is
     * the wide one (a bill number, a name) and takes whatever width is spare, and
     * the rest are figures set right-aligned at the width their content needs.
     *
     * [details] is an optional second line printed under row *i*, for the figures
     * that will not fit across a roll beside the others. Written as one labelled
     * string rather than as columns because it is a breakdown of the line above it,
     * not a table of its own. Where a report has none it is left empty.
     */
    data class Content(
        val title: String,
        /** "01-08-2026  to  11-08-2026" */
        val period: String,
        /** What the period holds - "37 bill(s)". */
        val subtitle: String,
        val columns: List<String>,
        val rows: List<List<String>>,
        val details: List<String?> = emptyList(),
        /** The totals, in the order they add up. */
        val summary: List<Pair<String, String>>,
        /** The one figure the report is read for, set larger under the rest. */
        val total: Pair<String, String>,
        /** Printed in place of the table when the period is empty. */
        val emptyNote: String = "Nothing in this period."
    )

    /**
     * Renders [content] to a bitmap without it ever being shown, laid out for a
     * printer whose head is [paperDots] wide (defaults to 80mm).
     *
     * @return null if it could not be rendered, so a caller does not print blank paper
     */
    fun renderToBitmap(
        content: Content,
        printedBy: String,
        paperDots: Int = REFERENCE_PAPER_DOTS
    ): Bitmap? = runCatching {
        val root = LayoutInflater.from(ctx).inflate(R.layout.receipt_period_report, null, false)
        populate(root, content, printedBy, paperDots)

        val card = root.findViewById<View>(R.id.cardPeriodReceipt) ?: return null
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
        android.util.Log.e(TAG, "Could not render ${content.title}", it)
        null
    }

    /** Fills an already-inflated [R.layout.receipt_period_report] in place. */
    fun populate(
        view: View,
        content: Content,
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
                        view.findViewById<TextView>(R.id.tvPeriodStoreName).text = it.uppercase()
                    }
                    setIfPresent(view, R.id.tvPeriodStoreAddress, c.getString(1))
                    setIfPresent(view, R.id.tvPeriodStorePhone, c.getString(2)?.let { "Ph: $it" })
                    setIfPresent(view, R.id.tvPeriodStoreGstin, c.getString(3)?.let { "GSTIN: $it" })
                }
            }

            renderFixedLines(
                db, view, R.id.llPeriodFooterLines,
                DatabaseHelper.Tables.MD_FOOTERS, "footer_text", "footer_number", "footer_type"
            )
            renderLogos(view)

            view.findViewById<TextView>(R.id.tvPeriodTitle).text = content.title.uppercase()
            view.findViewById<TextView>(R.id.tvPeriodRange).text = content.period
            view.findViewById<TextView>(R.id.tvPeriodSubtitle).text = content.subtitle
            view.findViewById<TextView>(R.id.tvPeriodPrintedBy).text = "Printed by: $printedBy"
            view.findViewById<TextView>(R.id.tvPeriodPrintedAt).text =
                "Printed on: " + SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.US).format(Date())

            val rows = view.findViewById<LinearLayout>(R.id.llPeriodRows)
            rows.removeAllViews()

            if (content.rows.isEmpty()) {
                rows.addView(note(content.emptyNote))
            } else {
                val metrics = measureTable(listOf(content.columns) + content.rows, paperDots)
                rows.addView(tableRow(content.columns, metrics, bold = true))
                content.rows.forEachIndexed { i, row ->
                    rows.addView(tableRow(row, metrics))
                    content.details.getOrNull(i)?.takeIf { it.isNotBlank() }
                        ?.let { rows.addView(detailRow(it, metrics, paperDots)) }
                }
            }

            val summary = view.findViewById<LinearLayout>(R.id.llPeriodSummary)
            summary.removeAllViews()
            content.summary.forEach { (label, value) -> summary.addView(amountRow(label, value)) }
            summary.addView(
                amountRow(
                    content.total.first, content.total.second,
                    bold = true, valueSize = PrintType.TOTAL_SP
                )
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error rendering ${content.title}", e)
        }
    }

    // ---- Row builders --------------------------------------------------------

    /**
     * Type size, gutter and fixed column widths the whole table is set at.
     *
     * [fixedPx] is one width per column after the first, which takes the slack.
     */
    private data class TableMetrics(
        val textSize: Float,
        val gutterPx: Int,
        val fixedPx: List<Int>
    )

    /**
     * Works out how the table has to be set for [lines] to fit [paperDots] of paper.
     *
     * A weighted column cannot be trusted with a number: give it less width than the
     * figure in it and Android hard-wraps mid-number ("125000." over "00"), which on
     * a report is not a cosmetic problem. So each fixed column is measured to its own
     * widest value and given exactly that width, and the type steps down through
     * [SIZES] until they plus a readable first column fit the roll. The first column
     * takes whatever is left, being the one value that can be allowed to ellipsize.
     */
    private fun measureTable(lines: List<List<String>>, paperDots: Int): TableMetrics {
        val metrics = ctx.resources.displayMetrics
        val widthDp = CARD_WIDTH_DP.toDouble() * paperDots / REFERENCE_PAPER_DOTS
        val contentPx = ((widthDp - CARD_PADDING_DP * 2) * metrics.density).toFloat()

        val columnCount = lines.maxOf { it.size }
        val widest = (0 until columnCount).map { column ->
            lines.maxOf { it.getOrNull(column)?.length ?: 0 }
        }

        val paint = Paint().apply { typeface = Typeface.MONOSPACE }
        var chosen = SIZES.last()
        var charPx = 0f
        var gutterPx = 0f
        for (size in SIZES) {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, metrics)
            val cw = paint.measureText("0")
            val gutter = (if (size <= 10f) 3f else 6f) * metrics.density
            val needed = widest.sum() * cw + gutter * (columnCount - 1)
            chosen = size
            charPx = cw
            gutterPx = gutter
            if (needed <= contentPx) break
        }

        return TableMetrics(
            textSize = chosen,
            gutterPx = gutterPx.toInt(),
            fixedPx = widest.drop(1).map { (it * charPx).toInt() + 1 }
        )
    }

    /**
     * One line of the table.
     *
     * The first cell is left-aligned and takes the slack; the rest sit at the fixed
     * widths [metrics] measured, so the figures line up down the page.
     */
    private fun tableRow(values: List<String>, metrics: TableMetrics, bold: Boolean = false): View {
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
            addView(cell(values.firstOrNull().orEmpty(), null, alignEnd = false))
            metrics.fixedPx.forEachIndexed { i, widthPx ->
                addView(cell(values.getOrNull(i + 1).orEmpty(), widthPx, alignEnd = true))
            }
        }
    }

    /**
     * A row's second line - the figures that would not fit beside the others.
     *
     * Set a size smaller than the table so it reads as belonging to the row above
     * rather than as another row, and stepped down further if the labels and figures
     * will not fit the roll.
     */
    private fun detailRow(text: String, metrics: TableMetrics, paperDots: Int): View {
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
            LogoDao.LogoType.BILL_HEADER to R.id.ivPeriodHeaderLogo,
            LogoDao.LogoType.BILL_FOOTER to R.id.ivPeriodFooterLogo
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

    private companion object {
        const val TAG = "PeriodReportRenderer"

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
