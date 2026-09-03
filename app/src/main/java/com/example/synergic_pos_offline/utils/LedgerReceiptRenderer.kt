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
import com.example.synergic_pos_offline.database.CustomerLedgerDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.LogoDao
import java.text.SimpleDateFormat
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

/** The card's own horizontal padding, per side - receipt_ledger.xml again. */
private const val CARD_PADDING_DP = 20

/** Printable dots on 80mm paper - what [CARD_WIDTH_DP] is measured against. */
private const val REFERENCE_PAPER_DOTS = PrintType.REFERENCE_PAPER_DOTS

/**
 * Below this, the statement's four columns are set a point smaller and the year is
 * shortened. 58mm (384 dots) falls under it; 80mm (576) and up keep the full size.
 */
private const val NARROW_PAPER_DOTS = 450

/**
 * Renders a [CustomerLedgerDao.Ledger] onto receipt paper.
 *
 * A ledger is a statement rather than a receipt, so it is laid out as a statement:
 * the opening balance, one block per movement, and the totals that carry it to the
 * closing balance. Each movement takes two printed lines - date and particulars
 * above, the money columns below - because three amount columns and a description
 * will not fit across a roll without one of them being shredded.
 */
class LedgerReceiptRenderer(context: Context) {

    /** Pinned to a standard font scale - see [ReceiptContext]. */
    private val ctx: Context = ReceiptContext.standardFontScale(context)

    /** The language this till labels its slips in - see [PrintLanguage]. */
    private val lang: PrintLanguage.Language = PrintLanguage.of(context)

    /** [text] in the till's print language, or as it is where there is no translation. */
    private fun t(text: String): String = PrintLanguage.tr(lang, text)

    /**
     * One line of the customer block: "MOBILE : 9800000000".
     *
     * [padded] is the English label with the spaces that line the colons up in a
     * monospace face, used as it is so an English statement prints exactly as it
     * always has. A translated label is set from [key] and left unpadded - its script
     * is not monospace, so counting characters would line nothing up.
     */
    private fun custLine(padded: String, key: String, value: String): String =
        (if (lang == PrintLanguage.Language.ENGLISH) padded else t(key)) + ": $value"

    /**
     * Renders the ledger to a bitmap without it ever being shown, laid out for a
     * printer whose head is [paperDots] wide (defaults to 80mm).
     *
     * @return null if it could not be rendered, so a caller does not print blank paper
     */
    fun renderToBitmap(
        ledger: CustomerLedgerDao.Ledger,
        printedBy: String,
        paperDots: Int = REFERENCE_PAPER_DOTS
    ): Bitmap? = runCatching {
        val root = LayoutInflater.from(ctx).inflate(R.layout.receipt_ledger, null, false)
        populate(root, ledger, printedBy, paperDots)

        val card = root.findViewById<View>(R.id.cardLedgerReceipt) ?: return null
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
        android.util.Log.e(TAG, "Could not render the ledger", it)
        null
    }

    /**
     * Fills an already-inflated [R.layout.receipt_ledger] in place.
     *
     * [paperDots] only chooses how tightly the four columns are set - see
     * [NARROW_PAPER_DOTS]; the content is the same on every width.
     */
    fun populate(
        view: View,
        ledger: CustomerLedgerDao.Ledger,
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
                        view.findViewById<TextView>(R.id.tvLedgerStoreName).text = it.uppercase()
                    }
                    setIfPresent(view, R.id.tvLedgerStoreAddress, c.getString(1))
                    setIfPresent(view, R.id.tvLedgerStorePhone, c.getString(2)?.let { "Ph: $it" })
                    setIfPresent(view, R.id.tvLedgerStoreGstin, c.getString(3)?.let { "GSTIN: $it" })
                }
            }

            renderFixedLines(
                db, view, R.id.llLedgerFooterLines,
                DatabaseHelper.Tables.MD_FOOTERS, "footer_text", "footer_number", "footer_type"
            )
            renderLogos(view)

            view.findViewById<TextView>(R.id.tvLedgerTitle)?.text = t("CUSTOMER LEDGER")
            view.findViewById<TextView>(R.id.tvLedgerPeriod).text =
                t("${pretty(ledger.fromDate)}  to  ${pretty(ledger.toDate)}")
            view.findViewById<TextView>(R.id.tvLedgerPrintedBy).text =
                "${t("Printed by")}: $printedBy"

            val customer = ledger.customer
            setIfPresent(view, R.id.tvLedgerCustName, customer.name.takeIf { it.isNotBlank() }
                ?.let { custLine("NAME   ", "NAME", it.uppercase()) })
            setIfPresent(view, R.id.tvLedgerCustMobile, customer.phone.takeIf { it.isNotBlank() }
                ?.let { custLine("MOBILE ", "MOBILE", it) })
            setIfPresent(view, R.id.tvLedgerCustGstin, customer.gstin.takeIf { it.isNotBlank() }
                ?.let { custLine("GSTIN  ", "GSTIN", it) })

            val rows = view.findViewById<LinearLayout>(R.id.llLedgerRows)
            rows.removeAllViews()
            val narrow = paperDots < NARROW_PAPER_DOTS

            // The BALANCE column is a running figure, so the statement has to say
            // what it starts from or the first row's balance comes out of nowhere.
            rows.addView(amountRow(t("OPENING BALANCE"), money(ledger.opening), bold = true))
            rows.addView(divider())

            if (ledger.entries.isEmpty()) {
                rows.addView(note(t("No transactions in this period.")))
            } else {
                // PAID is money the customer handed over, DUE is what a credit sale
                // added to their account - the two directions the account can move,
                // one column each, a dash in the one that did not.
                val lines = ledger.entries.map { entry ->
                    TableLine(
                        date = pretty(entry.date, narrow),
                        paid = if (entry.`in` > 0.0) money(entry.`in`) else "-",
                        due = if (entry.out > 0.0) money(entry.out) else "-",
                        balance = money(entry.balance)
                    )
                }
                val header = TableLine(t("DATE"), t("PAID"), t("DUE"), t("BALANCE"))
                val metrics = measureTable(listOf(header) + lines, paperDots)

                rows.addView(tableRow(header, metrics, bold = true))
                lines.forEach { rows.addView(tableRow(it, metrics)) }
            }

            rows.addView(divider())
            rows.addView(amountRow(t("TOTAL DUE"), money(ledger.closing), bold = true, valueSize = PrintType.TOTAL_SP))

            // A negative closing balance is the customer sitting in credit, which is
            // not what "rupees owed" in words would say, so the line is left off.
            setIfPresent(
                view, R.id.tvLedgerClosingWords,
                if (ledger.closing > 0.005) AmountInWords.of(ledger.closing) else null
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error rendering the ledger", e)
        }
    }

    // ---- Row builders ------------------------------------------------------

    /** The four cells of one statement line, header row included. */
    private data class TableLine(
        val date: String,
        val paid: String,
        val due: String,
        val balance: String
    )

    /** Type size, gutter and money-column widths the whole table is set at. */
    private data class TableMetrics(
        val textSize: Float,
        val gutterPx: Int,
        val paidPx: Int,
        val duePx: Int,
        val balancePx: Int
    )

    /**
     * Works out how the table has to be set for [lines] to fit [paperDots] of paper.
     *
     * A weighted column cannot be trusted with a number: give it less width than the
     * figure in it and Android hard-wraps mid-number ("125000." over "00"), which on
     * a statement is not a cosmetic problem. So each money column is measured to its
     * own widest value and given exactly that width, and the type steps down through
     * [SIZES] until the three of them plus a readable date column fit the roll. The
     * date takes whatever is left, being the one value that can be shortened.
     */
    private fun measureTable(lines: List<TableLine>, paperDots: Int): TableMetrics {
        val metrics = ctx.resources.displayMetrics
        val widthDp = CARD_WIDTH_DP.toDouble() * paperDots / REFERENCE_PAPER_DOTS
        val contentPx = ((widthDp - CARD_PADDING_DP * 2) * metrics.density).toFloat()

        val widest = { pick: (TableLine) -> String -> lines.maxOf { pick(it).length } }
        val dateChars = widest { it.date }
        val paidChars = widest { it.paid }
        val dueChars = widest { it.due }
        val balanceChars = widest { it.balance }

        val paint = Paint().apply { typeface = Typeface.MONOSPACE }
        var chosen = SIZES.last()
        var charPx = 0f
        var gutterPx = 0f
        for (size in SIZES) {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, metrics)
            val cw = paint.measureText("0")
            val gutter = (if (size <= 10f) 3f else 6f) * metrics.density
            val needed = (dateChars + paidChars + dueChars + balanceChars) * cw + gutter * 3
            if (needed <= contentPx) {
                chosen = size
                charPx = cw
                gutterPx = gutter
                break
            }
            chosen = size
            charPx = cw
            gutterPx = gutter
        }

        return TableMetrics(
            textSize = chosen,
            gutterPx = gutterPx.toInt(),
            paidPx = (paidChars * charPx).toInt() + 1,
            duePx = (dueChars * charPx).toInt() + 1,
            balancePx = (balanceChars * charPx).toInt() + 1
        )
    }

    /**
     * One "DATE  PAID  DUE  BALANCE" line.
     *
     * The date is left-aligned and takes the slack; the three money columns are
     * right-aligned at the fixed widths [metrics] measured, so their digits line up
     * down the page and none of them can be squeezed into wrapping.
     */
    private fun tableRow(line: TableLine, metrics: TableMetrics, bold: Boolean = false): View {
        val density = ctx.resources.displayMetrics.density
        fun cell(value: String, widthPx: Int?, alignEnd: Boolean) = TextView(ctx).apply {
            text = value
            textSize = metrics.textSize
            gravity = if (alignEnd) Gravity.END else Gravity.START
            // A gutter before each money column: a right-aligned value would
            // otherwise touch the column before it, and a "-" sitting against the
            // next figure reads as a minus sign.
            if (alignEnd) setPadding(metrics.gutterPx, 0, 0, 0)
            setTypeface(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(0xFF222222.toInt())
            layoutParams =
                if (widthPx == null) LinearLayout.LayoutParams(0, -2, 1f)
                else LinearLayout.LayoutParams(widthPx + metrics.gutterPx, -2)
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
            addView(cell(line.date, null, alignEnd = false))
            addView(cell(line.paid, metrics.paidPx, alignEnd = true))
            addView(cell(line.due, metrics.duePx, alignEnd = true))
            addView(cell(line.balance, metrics.balancePx, alignEnd = true))
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

    /**
     * The rule between sections of the statement.
     *
     * Every value here comes from [PrintType], which is also what `@style/BillDashLine`
     * carries - the rules this builds sit on the same slip as the ones the layout
     * builds, and they used to be a different length at a different spacing.
     */
    private fun divider(): View = TextView(ctx).apply {
        text = PrintType.RULE
        textSize = PrintType.BODY_SP
        // maxLines rather than isSingleLine, which also turns on horizontal
        // scrolling. The rule is deliberately wider than the paper and is meant to be
        // clipped square at the edge, not scrolled.
        maxLines = 1
        ellipsize = null
        setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        setTextColor(0xFF222222.toInt())
        val pad = (PrintType.RULE_MARGIN_DP * ctx.resources.displayMetrics.density).toInt()
        setPadding(0, pad, 0, pad)
    }

    // ---- Shared receipt furniture ------------------------------------------

    private fun renderLogos(view: View) {
        val dao = LogoDao(ctx)
        listOf(
            LogoDao.LogoType.BILL_HEADER to R.id.ivLedgerHeaderLogo,
            LogoDao.LogoType.BILL_FOOTER to R.id.ivLedgerFooterLogo
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

    /**
     * "yyyy-MM-dd" or "yyyy-MM-dd HH:mm:ss" as "dd-MM-yyyy", or "dd-MM-yy" on
     * [narrow] paper - two characters that a 58mm roll would rather spend on the
     * figures than on a century nobody is in any doubt about.
     */
    private fun pretty(value: String, narrow: Boolean = false): String = runCatching {
        val pattern = if (narrow) "dd-MM-yy" else "dd-MM-yyyy"
        SimpleDateFormat(pattern, Locale.US)
            .format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value.take(10))!!)
    }.getOrDefault(value)

    private companion object {
        const val TAG = "LedgerReceiptRenderer"

        /**
         * Type sizes the statement table is tried at, largest first.
         *
         * Starts at the bill's own body size and only steps down when four columns
         * genuinely will not fit the roll, so on 80mm the statement is set like the
         * bill and a narrow roll is the only thing that makes it smaller.
         */
        val SIZES = listOf(
            PrintType.BODY_SP, PrintType.TABLE_SP, PrintType.SMALL_SP,
            11f, 10f, 9f, 8f, PrintType.FLOOR_SP
        )
    }
}
