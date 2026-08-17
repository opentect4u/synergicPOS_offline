package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.synergic_pos_offline.database.CustomerItemWiseReportDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the Customer Item-Wise Report - a classic slip: machine ID and clock, the
 * date range, the customer's id and name, then a two-line row per product (name +
 * quantity, then amount + SGST + CGST) and the period totals.
 *
 * Dedicated rather than [PeriodReportRenderer] because of the two-line rows and the
 * left-aligned customer block; it reuses the same receipt scaffolding.
 */
class CustomerItemWiseReportRenderer(context: Context) {

    private val ctx: Context = ReceiptContext.standardFontScale(context)

    /** The language this till labels its slips in - see [PrintLanguage]. */
    private val lang: PrintLanguage.Language = PrintLanguage.of(context)

    /** [text] in the till's print language, or as it is where there is no translation. */
    private fun t(text: String): String = PrintLanguage.tr(lang, text)

    fun renderToBitmap(
        report: CustomerItemWiseReportDao.Report,
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
        android.util.Log.e(TAG, "Could not render customer item-wise report", it)
        null
    }

    private fun buildCard(report: CustomerItemWiseReportDao.Report): View {
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

        root.addView(title(t("CUSTOMER ITEMWISE RPT")))
        root.addView(rule())
        root.addView(spreadRow(machineId(), stamp("dd-MM-yy"), stamp("HH:mm")))
        root.addView(rule())
        root.addView(spreadRow("F.DT:${shortDate(report.fromDate)}", "TO.DT:${shortDate(report.toDate)}"))
        root.addView(rule())
        root.addView(leftLine("${t("CUSTOMER ID")} : ${report.customerId}"))
        root.addView(leftLine("${t("NAME")} : ${report.customerName}"))
        root.addView(rule())
        // Two-line column heads.
        root.addView(twoCol(t("ITEM NAME"), t("QUANTITY"), bold = true))
        root.addView(threeCol(t("AMOUNT"), "SGST", "CGST", bold = true))
        root.addView(rule())

        if (report.items.isEmpty()) {
            root.addView(centred(t("No items in this period.")))
            root.addView(rule())
            return root
        }

        report.items.forEach { item ->
            root.addView(twoCol(item.name, qtyFmt(item.qty), bold = false))
            root.addView(threeCol(money(item.amount), money(item.sgst), money(item.cgst), bold = false))
        }
        root.addView(rule())
        root.addView(totalLine(t("TOTAL QTY :"), qtyFmt(report.totalQty)))
        root.addView(totalLine(t("TOTAL SGST:"), money(report.totalSgst)))
        root.addView(totalLine(t("TOTAL CGST:"), money(report.totalCgst)))
        root.addView(totalLine(t("TOTAL AMT :"), money(report.totalAmount)))
        root.addView(rule())
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

    /** "ITEM NAME" (left, wide) + "QUANTITY" (right) - the head and each item's line 1. */
    private fun twoCol(left: String, right: String, bold: Boolean): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, gap(), 0, 0)
            val style = if (bold) Typeface.BOLD else Typeface.NORMAL
            addView(cell(left, Gravity.START, 1f, style))
            addView(cell(right, Gravity.END, 1f, style))
        }

    /** AMOUNT / SGST / CGST - the head's line 2 and each item's line 2. */
    private fun threeCol(a: String, b: String, c: String, bold: Boolean): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, gap(), 0, 0)
            val style = if (bold) Typeface.BOLD else Typeface.NORMAL
            addView(cell(a, Gravity.START, 1f, style, startPadChars = 2))
            addView(cell(b, Gravity.CENTER, 1f, style))
            addView(cell(c, Gravity.END, 1f, style))
        }

    private fun cell(
        text: String, gravity: Int, weight: Float, style: Int, startPadChars: Int = 0
    ): TextView = TextView(ctx).apply {
        this.text = text
        textSize = PrintType.SMALL_SP
        this.gravity = gravity
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (startPadChars > 0) setPadding((startPadChars * PrintType.SMALL_SP * ctx.resources.displayMetrics.density * 0.6f).toInt(), 0, 0, 0)
        setTypeface(Typeface.MONOSPACE, style)
        setTextColor(0xFF222222.toInt())
        layoutParams = LinearLayout.LayoutParams(0, -2, weight)
    }

    /** A left-aligned "LABEL : value" line - the customer id / name block. */
    private fun leftLine(text: String): View = TextView(ctx).apply {
        this.text = text
        textSize = PrintType.SMALL_SP
        gravity = Gravity.START
        maxLines = 1
        setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        setTextColor(0xFF222222.toInt())
        setPadding(0, gap(), 0, 0)
    }

    /** "TOTAL X :" on the left, the figure pushed to the paper edge. */
    private fun totalLine(label: String, value: String): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, gap(), 0, 0)
        addView(TextView(ctx).apply {
            text = label
            textSize = PrintType.SMALL_SP
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(0xFF111111.toInt())
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        addView(TextView(ctx).apply {
            text = value
            textSize = PrintType.SMALL_SP
            gravity = Gravity.END
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(0xFF111111.toInt())
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
    }

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

    private fun centred(text: String): View = TextView(ctx).apply {
        this.text = text
        textSize = PrintType.SMALL_SP
        gravity = Gravity.CENTER
        setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        setTextColor(0xFF555555.toInt())
        setPadding(0, gap(), 0, gap())
    }

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

    private fun machineId(): String {
        val db = DatabaseHelper.getInstance(ctx).readableDatabase
        val id = db.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("device_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        return "M.ID:" + (id?.takeIf { it.isNotBlank() } ?: "-")
    }

    private fun stamp(pattern: String): String = SimpleDateFormat(pattern, Locale.US).format(Date())

    private fun shortDate(date: String): String = runCatching {
        SimpleDateFormat("dd-MM-yy", Locale.US)
            .format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date.take(10))!!)
    }.getOrDefault(date)

    private fun money(v: Double) = String.format(Locale.US, "%.2f", BillRounding.toPaise(v))
    private fun qtyFmt(v: Double) = String.format(Locale.US, "%.2f", v)

    private fun gap(): Int = ctx.resources.displayMetrics.density.toInt().coerceAtLeast(1)

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
        const val TAG = "CustItemWiseReport"
    }
}
