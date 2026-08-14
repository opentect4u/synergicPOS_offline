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
 * the bill wise slip; solving them again per new report is how five slips off
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
        val total: Pair<String, String>? = null,
        /**
         * A closing row of the table itself, in the table's own columns.
         *
         * For a report whose total is a row of figures rather than one figure -
         * "TOTAL :  6  4852.00" - where each has to land under the column it totals.
         * Stated as a label/value pair in [total] it would sit at the right-hand edge
         * instead, under whichever column happened to be last.
         */
        val footerRow: List<String>? = null,
        /** Printed in place of the table when the period is empty. */
        val emptyNote: String = "Nothing in this period.",
        /** How the slip is laid out - see [Style]. */
        val style: Style = Style.STANDARD,
        /**
         * "F.DT:01-08-26" and "TO.DT:11-08-26", set one at each end of a line.
         * [Style.CLASSIC] only; a standard slip states its range as [period].
         */
        val range: Pair<String, String>? = null,
        /**
         * A band printed across the slip above row *i* - "DAY : 07-08-2026" - for the
         * reports that group their rows rather than labelling each one.
         *
         * A grouped report has nothing to put in a first column: every row under the
         * band belongs to it, and repeating the date down the side of a single-row
         * group would say it twice. Empty on a report that labels its rows.
         */
        val bands: List<String?> = emptyList(),
        /**
         * A note set across the foot of the slip, under its own rule - a caveat the
         * figures have to be read with, not a footer line of the shop's.
         */
        val footerNote: String? = null,
        /**
         * Heads a *second* line printed under every row, in columns of its own.
         *
         * For a report carrying more figures than a roll holds across, where breaking
         * them over two lines beats setting the type too small to read: the item's
         * name and quantity on one line, its money on the next. Null for the reports
         * that fit on one.
         *
         * Both lines spread their cells evenly rather than sizing each to its content,
         * because the two have different column counts - measured widths would leave
         * the figures of one line sitting under nothing in particular on the other.
         */
        val columns2: List<String>? = null,
        /** The second line of row *i*, in [columns2]'s columns. */
        val rows2: List<List<String>> = emptyList(),
        /**
         * Rule off every row from the next.
         *
         * For a two-line row, where without a separator the second line of one item
         * and the first of the next read as a single block and the eye loses which
         * figures belong to which name.
         */
        val ruleBetweenRows: Boolean = false,
        /**
         * Divide the roll evenly between the columns instead of sizing each to its
         * widest value.
         *
         * For a table of two or three columns, where measured widths pack every
         * figure against the right-hand edge and leave the rest of the paper blank.
         * A table of five or six wants the opposite - there, even columns would
         * starve the wide ones - so this is off by default.
         */
        val evenColumns: Boolean = false,
        /**
         * Whether the first column is set to the right like the rest.
         *
         * A first column holding a name or a code reads from the left; one holding a
         * bill number reads as a figure, and lines up with the figures beside it.
         * Only meaningful with [evenColumns].
         */
        val alignFirstColumnEnd: Boolean = false,
        /**
         * Set every column down the middle of its own share of the roll.
         *
         * For a table of two or three short values, where flushing them to the edges
         * leaves them stranded at opposite ends of the paper with nothing between.
         * Only meaningful with [evenColumns], and it overrides [alignFirstColumnEnd].
         */
        val centreColumns: Boolean = false,
        /**
         * Label / value lines set between the range and the table - what this run of
         * the report was asked for, where that is a thing rather than a date.
         *
         * The value is centred rather than pushed right: it is an answer to the label
         * beside it, not a figure in a column of figures.
         */
        val heading: List<Pair<String, String>> = emptyList(),
        /**
         * A line above the column heads, in the table's own columns.
         *
         * For a report run against one thing - an item, an operator - where naming it
         * over the column it governs says more than a caption above the table would.
         * Only meaningful with [evenColumns].
         */
        val columnsAbove: List<String>? = null,
        /**
         * Replaces the machine-ID line at the head of a [Style.CLASSIC] slip.
         *
         * A report is identified by the till that ran it; a *bill* is identified by
         * its own number. Same three-part line either way - something on the left, the
         * date in the middle, the clock on the right - so the difference is what goes
         * in it, not how it is set.
         */
        val headLine: Triple<String, String, String>? = null
    )

    /**
     * How a slip is laid out.
     *
     * [CLASSIC] is the format the tills these reports replace have always printed,
     * and the one shopkeepers read without having to be told how: no store block,
     * the machine's own ID and the clock at the head, the range under it, and the
     * totals as a plain "TOTAL X :" list. [STANDARD] is the store-headed slip the
     * rest of this till prints - a logo, the store's name and GSTIN, footer lines and
     * who printed it.
     */
    enum class Style { STANDARD, CLASSIC }

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
            val classic = content.style == Style.CLASSIC

            // A classic slip identifies the till by the machine's own ID at the head,
            // not by a store block - so the whole block, its logos and its footer
            // lines come off rather than being printed above a slip that then names
            // the same shop again a line later.
            listOf(
                R.id.tvPeriodStoreName, R.id.tvPeriodStoreAddress, R.id.tvPeriodStorePhone,
                R.id.tvPeriodStoreGstin, R.id.ivPeriodHeaderLogo, R.id.ivPeriodFooterLogo,
                R.id.llPeriodFooterLines, R.id.tvPeriodPrintedBy, R.id.tvPeriodPrintedAt,
                R.id.tvPeriodSummaryHeading, R.id.tvPeriodTitleRule
            ).forEach { view.findViewById<View>(it).visibility = if (classic) View.GONE else View.VISIBLE }

            if (!classic) {
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

                view.findViewById<TextView>(R.id.tvPeriodPrintedBy).text = "Printed by: $printedBy"
                view.findViewById<TextView>(R.id.tvPeriodPrintedAt).text =
                    "Printed on: " + SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.US).format(Date())
            }

            // The report's own closing note, under its own rule. On a classic slip it
            // stands alone; on a standard one it follows the shop's footer lines.
            content.footerNote?.takeIf { it.isNotBlank() }?.let { note ->
                val container = view.findViewById<LinearLayout>(R.id.llPeriodFooterLines)
                if (classic) container.removeAllViews()
                container.visibility = View.VISIBLE
                container.addView(wrapped(note))
                container.addView(rule())
            }

            view.findViewById<TextView>(R.id.tvPeriodTitle).text = content.title.uppercase()

            // The head block: on a classic slip the machine ID with the clock, then
            // the range at either end of its own line. On a standard one, the range
            // spelled out and what the period holds under it.
            val head = view.findViewById<LinearLayout>(R.id.llPeriodHead)
            head.removeAllViews()
            if (classic) {
                // Ruling the title off from the head. The layout's own rule above the
                // title is hidden on a classic slip - there is no store block up there
                // for it to rule off - so this is where that line belongs.
                head.addView(rule())
                val (left, middle, right) = content.headLine
                    ?: Triple(machineId(db), stamp("dd-MM-yy"), stamp("HH:mm:ss"))
                head.addView(spreadRow(left, middle, right))
                // And one *between* the two head lines. The block is closed by the
                // layout's rule below it, so drawing another here would stack two -
                // and on a report with no range line, three.
                content.range?.let { (from, to) ->
                    head.addView(rule())
                    head.addView(spreadRow(from, to))
                }
                // What the report was run for, ruled off from the range above it. The
                // trailing blank cell centres the value instead of pushing it right.
                if (content.heading.isNotEmpty()) {
                    head.addView(rule())
                    content.heading.forEach { (label, value) ->
                        head.addView(spreadRow(label, value, ""))
                    }
                }
            } else {
                head.addView(centred(content.period))
                head.addView(centred(content.subtitle))
            }

            val rows = view.findViewById<LinearLayout>(R.id.llPeriodRows)
            rows.removeAllViews()

            if (content.rows.isEmpty()) {
                rows.addView(note(content.emptyNote))
            } else if (content.columns2 != null) {
                // Two lines per row, each spread evenly across the roll.
                val size = measureEven(
                    listOf(content.columns) + content.rows,
                    listOf(content.columns2) + content.rows2,
                    paperDots
                )
                rows.addView(evenRow(content.columns, size, bold = true))
                rows.addView(evenRow(content.columns2, size, bold = true))
                rows.addView(rule())
                content.rows.forEachIndexed { i, row ->
                    if (content.ruleBetweenRows && i > 0) rows.addView(rule())
                    rows.addView(evenRow(row, size))
                    content.rows2.getOrNull(i)?.let { rows.addView(evenRow(it, size)) }
                }
            } else if (content.evenColumns) {
                // One line per row, its columns sharing the roll equally.
                val size = measureEven(
                    listOf(content.columns) + content.rows +
                        listOfNotNull(content.footerRow) + listOfNotNull(content.columnsAbove),
                    emptyList(),
                    paperDots
                )
                val firstEnd = content.alignFirstColumnEnd
                val mid = content.centreColumns
                content.columnsAbove?.let {
                    rows.addView(evenRow(it, size, bold = true, firstAtEnd = firstEnd, centred = mid))
                }
                rows.addView(evenRow(content.columns, size, bold = true, firstAtEnd = firstEnd, centred = mid))
                rows.addView(rule())
                content.rows.forEach {
                    rows.addView(evenRow(it, size, firstAtEnd = firstEnd, centred = mid))
                }
                content.footerRow?.let {
                    rows.addView(rule())
                    rows.addView(evenRow(it, size, firstAtEnd = firstEnd, centred = mid))
                }
            } else {
                // The footer row is measured with the rest, so its figures sit in the
                // same columns they total rather than in columns of their own.
                val body = listOf(content.columns) + content.rows +
                    listOfNotNull(content.footerRow)
                val metrics = measureTable(body, paperDots)

                rows.addView(tableRow(content.columns, metrics, bold = true))
                // A rule under the heading, ruling it off from the figures it names.
                // Not where the first row opens a band: that draws its own rule above
                // itself, and two together read as a gap rather than a rule.
                if (content.bands.firstOrNull().isNullOrBlank()) rows.addView(rule())
                content.rows.forEachIndexed { i, row ->
                    // The band this row sits under, where the report groups its rows.
                    content.bands.getOrNull(i)?.takeIf { it.isNotBlank() }?.let {
                        rows.addView(rule())
                        rows.addView(centred(it))
                        rows.addView(rule())
                    }
                    rows.addView(tableRow(row, metrics))
                    content.details.getOrNull(i)?.takeIf { it.isNotBlank() }
                        ?.let { rows.addView(detailRow(it, metrics, paperDots)) }
                }
                content.footerRow?.let {
                    rows.addView(rule())
                    rows.addView(tableRow(it, metrics, bold = true))
                }
            }

            // A report whose total is a row of the table has nothing to put here, and
            // the whole block comes off rather than printing an empty heading.
            val hasSummary = content.summary.isNotEmpty() || content.total != null
            listOf(R.id.llPeriodSummaryBlock, R.id.tvPeriodSummaryRule).forEach {
                view.findViewById<View>(it).visibility = if (hasSummary) View.VISIBLE else View.GONE
            }

            val summary = view.findViewById<LinearLayout>(R.id.llPeriodSummary)
            summary.removeAllViews()
            // A classic slip's totals are one plain list: same weight, same size, the
            // last line no different from the first. "TOTAL AMOUNT :" already says
            // which one it is, and a column of figures has to stay an even column to
            // be read down at a glance.
            content.summary.forEach { (label, value) ->
                summary.addView(amountRow(label, value, bold = false))
            }
            content.total?.let { (label, value) ->
                summary.addView(
                    amountRow(
                        label, value,
                        bold = !classic,
                        valueSize = if (classic) PrintType.BODY_SP else PrintType.TOTAL_SP
                    )
                )
            }
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
            setPadding(0, ROW_GAP_PX(), 0, 0)
            addView(cell(values.firstOrNull().orEmpty(), null, alignEnd = false))
            metrics.fixedPx.forEachIndexed { i, widthPx ->
                addView(cell(values.getOrNull(i + 1).orEmpty(), widthPx, alignEnd = true))
            }
        }
    }

    /**
     * The type size that lets both halves of a two-line table fit [paperDots].
     *
     * Each line divides the roll evenly between its own cells, so a cell has
     * `width / cells` to play with and the binding constraint is the widest value in
     * the busiest line. Both halves are measured together and set at one size: two
     * sizes would make the second line read as a footnote rather than as the rest of
     * the row.
     */
    private fun measureEven(
        first: List<List<String>>,
        second: List<List<String>>,
        paperDots: Int
    ): Float {
        val metrics = ctx.resources.displayMetrics
        val widthDp = CARD_WIDTH_DP.toDouble() * paperDots / REFERENCE_PAPER_DOTS
        val contentPx = ((widthDp - CARD_PADDING_DP * 2) * metrics.density).toFloat()

        // What one line needs is its cell count times its longest cell, since every
        // cell is given the same width.
        // Zero for a table that is not there - a single-line report passes no second.
        val demand = { lines: List<List<String>> ->
            if (lines.isEmpty()) 0
            else lines.maxOf { it.size }.coerceAtLeast(1) *
                lines.maxOf { row -> row.maxOfOrNull { it.length } ?: 0 }
        }
        val needed = maxOf(demand(first), demand(second))

        val paint = Paint().apply { typeface = Typeface.MONOSPACE }
        for (size in SIZES) {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, metrics)
            if (needed * paint.measureText("0") <= contentPx) return size
        }
        return SIZES.last()
    }

    /**
     * One line of a two-line table: the first cell to the left, the rest to the right
     * of their own equal shares of the roll.
     *
     * Equal shares rather than measured widths, so the columns of a line land in the
     * same places whatever is in them - and so a line of four figures and a line of
     * two still read as one table rather than two.
     */
    private fun evenRow(
        values: List<String>,
        size: Float,
        bold: Boolean = false,
        firstAtEnd: Boolean = false,
        centred: Boolean = false
    ): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ROW_GAP_PX(), 0, 0)
            values.forEachIndexed { i, value ->
                addView(TextView(ctx).apply {
                    text = value
                    textSize = size
                    gravity = when {
                        centred -> Gravity.CENTER
                        i == 0 && !firstAtEnd -> Gravity.START
                        else -> Gravity.END
                    }
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTypeface(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(0xFF222222.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
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
            setPadding(0, 0, 0, ROW_GAP_PX())
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
            setPadding(0, ROW_GAP_PX(), 0, ROW_GAP_PX())
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

    /**
     * A line with its parts pushed to the edges and spread evenly between - the
     * "M.ID … date … time" and "F.DT … TO.DT" lines of a classic slip.
     *
     * Laid out rather than padded with spaces: the number of spaces that centres a
     * value depends on how long the values either side of it are, and a machine ID
     * one digit longer would silently shunt the clock off the edge of the roll.
     */
    private fun spreadRow(vararg cells: String): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, ROW_GAP_PX(), 0, 0)
        cells.forEachIndexed { i, value ->
            addView(TextView(ctx).apply {
                text = value
                textSize = PrintType.SMALL_SP
                maxLines = 1
                gravity = when (i) {
                    0 -> Gravity.START
                    cells.lastIndex -> Gravity.END
                    else -> Gravity.CENTER
                }
                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                setTextColor(0xFF222222.toInt())
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
        }
    }

    /** A line across the middle - a band, or the range on a standard slip. */
    private fun centred(text: String): View = TextView(ctx).apply {
        this.text = text
        textSize = PrintType.SMALL_SP
        gravity = Gravity.CENTER
        maxLines = 1
        setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        setTextColor(0xFF222222.toInt())
        setPadding(0, ROW_GAP_PX(), 0, ROW_GAP_PX())
    }

    /** A centred note that may run to more than one line, and wraps rather than clips. */
    private fun wrapped(text: String): View = TextView(ctx).apply {
        this.text = text
        textSize = PrintType.SMALL_SP
        gravity = Gravity.CENTER
        setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        setTextColor(0xFF222222.toInt())
        setPadding(0, ROW_GAP_PX(), 0, ROW_GAP_PX())
    }

    /**
     * The hyphen rule, the same one `@style/BillDashLine` draws.
     *
     * Deliberately longer than any roll: it is cut off square at the edge of the
     * paper, which is what a printed rule looks like, rather than stopping short.
     */
    private fun rule(): View = TextView(ctx).apply {
        text = PrintType.RULE
        textSize = PrintType.BODY_SP
        isSingleLine = true
        ellipsize = null
        setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        setTextColor(0xFF222222.toInt())
    }

    /** The machine this till runs on - what a classic slip is identified by. */
    private fun machineId(db: SQLiteDatabase): String {
        val id = db.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("device_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        return "M.ID:" + (id?.takeIf { it.isNotBlank() } ?: "-")
    }

    private fun stamp(pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).format(Date())

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

    /**
     * The gap above and below a printed line, in pixels.
     *
     * One dp, not the four these rows used to carry between them. On a roll that gap
     * is dead paper on every line of every report, and a table reads better tight -
     * the type's own leading already separates the rows. Kept as a value rather than
     * removed altogether so a table does not print as a solid block.
     */
    private fun ROW_GAP_PX(): Int = ctx.resources.displayMetrics.density.toInt().coerceAtLeast(1)

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
