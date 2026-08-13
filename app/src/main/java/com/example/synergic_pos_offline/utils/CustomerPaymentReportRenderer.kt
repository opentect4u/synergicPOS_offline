package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.synergic_pos_offline.database.CustomerPaymentReportDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the Customer Payment Report onto receipt paper - a classic slip: the
 * machine ID and clock at the head, the date range under it, then a block per
 * collection (customer id/name, when it was taken and its bill no, the amount paid
 * and the balance left). Each block is ruled off from the next, exactly as the till
 * these reports replace prints it.
 *
 * A dedicated renderer rather than [PeriodReportRenderer]: this report reads as
 * stacked blocks, not as a table of columns, so it does not fit that renderer's row
 * model. It reuses the same receipt scaffolding (fixed font scale, monospace rules,
 * the paper-width scaling) so it still comes off the roll like every other slip.
 */
class CustomerPaymentReportRenderer(context: Context) {

    /** Pinned to a standard font scale - see [ReceiptContext]. */
    private val ctx: Context = ReceiptContext.standardFontScale(context)

    /**
     * Renders [report] to a bitmap for a head [paperDots] wide (defaults to 80mm),
     * or null if it could not be rendered.
     */
    fun renderToBitmap(
        report: CustomerPaymentReportDao.Report,
        paperDots: Int = PrintType.REFERENCE_PAPER_DOTS
    ): Bitmap? = runCatching {
        val card = buildCard(report)
        val widthDp = PrintType.CARD_WIDTH_DP.toDouble() * paperDots / PrintType.REFERENCE_PAPER_DOTS
        val widthPx = (widthDp * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        if (card.measuredHeight <= 0) return null
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        val captured = ReceiptPrinter.capture(card) ?: return null
        withBottomMargin(captured, paperDots / 9)
    }.getOrElse {
        android.util.Log.e(TAG, "Could not render customer payment report", it)
        null
    }

    private fun buildCard(report: CustomerPaymentReportDao.Report): View {
        val d = ctx.resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(pad, (18 * d).toInt(), pad, (18 * d).toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        root.addView(title("CUSTOMER PAYMENT REPORT"))
        root.addView(rule())
        // The head: the machine's own id with the clock, then the date range.
        root.addView(spreadRow(machineId(), stamp("dd-MM-yy"), stamp("HH:mm:ss")))
        root.addView(rule())
        root.addView(spreadRow("F.DT:${shortDate(report.fromDate)}", "TO.DT:${shortDate(report.toDate)}"))
        root.addView(rule())

        if (report.entries.isEmpty()) {
            root.addView(centred("No customer payments in this period."))
            root.addView(rule())
            return root
        }

        report.entries.forEach { e ->
            root.addView(spreadRow("C.ID : ${e.customerId}", "C.NAME: ${e.customerName.uppercase()}"))
            root.addView(spreadRow(dateTime(e.paymentDateTime), "B.NO:${e.billNo}"))
            root.addView(kvRow("PAID AMT", money(e.paidAmount)))
            root.addView(kvRow("BALANCE AMT", money(e.balanceAmount)))
            root.addView(rule())
        }
        return root
    }

    // ---- Row builders --------------------------------------------------------

    private fun title(text: String): View = TextView(ctx).apply {
        this.text = text
        textSize = PrintType.BODY_SP + 2f
        gravity = Gravity.CENTER
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(0xFF111111.toInt())
    }

    /** Parts pushed to the edges, spread evenly between - the head lines and each
     *  entry's first two rows. */
    private fun spreadRow(vararg cells: String): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, gap(), 0, 0)
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

    /** "LABEL        : value" - the label padded so its colon lines up with the row
     *  below, the value pushed to the paper edge. */
    private fun kvRow(label: String, value: String): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, gap(), 0, 0)
        addView(TextView(ctx).apply {
            text = label.padEnd(LABEL_WIDTH) + " :"
            textSize = PrintType.SMALL_SP
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextColor(0xFF222222.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        addView(TextView(ctx).apply {
            text = value
            textSize = PrintType.SMALL_SP
            gravity = Gravity.END
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextColor(0xFF111111.toInt())
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
    }

    private fun centred(text: String): View = TextView(ctx).apply {
        this.text = text
        textSize = PrintType.SMALL_SP
        gravity = Gravity.CENTER
        setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        setTextColor(0xFF555555.toInt())
        setPadding(0, gap(), 0, gap())
    }

    /** The hyphen rule, the same one every slip draws. */
    private fun rule(): View = TextView(ctx).apply {
        text = PrintType.RULE
        textSize = PrintType.BODY_SP
        isSingleLine = true
        ellipsize = null
        setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        setTextColor(0xFF222222.toInt())
        setPadding(0, gap(), 0, 0)
    }

    // ---- Data helpers --------------------------------------------------------

    /** The device this till runs on, as "M.ID:<id>". */
    private fun machineId(): String {
        val db = DatabaseHelper.getInstance(ctx).readableDatabase
        val id = db.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("device_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        return "M.ID:" + (id?.takeIf { it.isNotBlank() } ?: "-")
    }

    private fun stamp(pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).format(Date())

    /** "2026-08-13" -> "13-08-26", the F.DT / TO.DT form. */
    private fun shortDate(date: String): String = runCatching {
        SimpleDateFormat("dd-MM-yy", Locale.US)
            .format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date.take(10))!!)
    }.getOrDefault(date)

    /** "2026-08-13 12:19:46" -> "13-08-26 12:19:46". */
    private fun dateTime(value: String): String = runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(value)!!
        SimpleDateFormat("dd-MM-yy HH:mm:ss", Locale.US).format(parsed)
    }.getOrDefault(value)

    private fun money(v: Double) = String.format(Locale.US, "%.2f", BillRounding.toPaise(v))

    /** A small gap above a line so the slip does not print as a solid block. */
    private fun gap(): Int = ctx.resources.displayMetrics.density.toInt().coerceAtLeast(1)

    /** Returns [src] with [bottom] px of white feed added below it (recycling [src]). */
    private fun withBottomMargin(src: Bitmap, bottom: Int): Bitmap {
        if (bottom <= 0) return src
        val out = Bitmap.createBitmap(src.width, src.height + bottom, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        src.recycle()
        return out
    }

    private companion object {
        const val TAG = "CustomerPaymentReport"
        /** Padded label width so the PAID AMT / BALANCE AMT colons line up. */
        const val LABEL_WIDTH = 12
    }
}
