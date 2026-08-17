package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.UdfWiseItemReportDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the UDF-Wise Item Report onto receipt paper - a classic slip: the machine
 * ID and clock, the date range, a NAME / QTY / AMOUNT table, then a block per UDF
 * (the table) with its items, a QTY/AMT subtotal for the block, and a grand total.
 *
 * A dedicated renderer rather than [PeriodReportRenderer]: this report reads as
 * grouped blocks each closed by their own subtotal, which that renderer's flat table
 * model does not do. It reuses the same receipt scaffolding so it prints like the
 * other slips.
 */
class UdfWiseItemReportRenderer(context: Context) {

    private val ctx: Context = ReceiptContext.standardFontScale(context)

    /** The language this till labels its slips in - see [PrintLanguage]. */
    private val lang: PrintLanguage.Language = PrintLanguage.of(context)

    /** [text] in the till's print language, or as it is where there is no translation. */
    private fun t(text: String): String = PrintLanguage.tr(lang, text)

    fun renderToBitmap(
        report: UdfWiseItemReportDao.Report,
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
        android.util.Log.e(TAG, "Could not render UDF-Wise Item report", it)
        null
    }

    private fun buildCard(report: UdfWiseItemReportDao.Report): View {
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

        root.addView(title(t("UDF-WISE ITEM REPORT")))
        root.addView(rule())
        root.addView(spreadRow(machineId(), stamp("dd-MM-yy"), stamp("HH:mm")))
        root.addView(rule())
        root.addView(spreadRow("F.DT:${shortDate(report.fromDate)}", "TO.DT:${shortDate(report.toDate)}"))
        root.addView(rule())
        // Column heads.
        root.addView(itemRow(t("NAME"), t("QTY"), t("AMOUNT"), bold = true))
        root.addView(rule())

        if (report.groups.isEmpty()) {
            root.addView(centred(t("No items in this period.")))
            root.addView(rule())
            return root
        }

        report.groups.forEach { g ->
            root.addView(band("UDF NO: ${g.udf}"))
            g.items.forEach { item ->
                root.addView(itemRow(item.name, qtyFmt(item.qty), money(item.amount), bold = false))
            }
            root.addView(rule())
            root.addView(spreadRow("${t("QTY")} : ${qtyFmt(g.qty)}", "${t("AMT")} : ${money(g.amount)}"))
            root.addView(rule())
        }

        root.addView(
            fourCol(
                t("TOTAL QTY :"), qtyFmt(report.totalQty),
                t("TOTAL AMT :"), money(report.totalAmount)
            )
        )
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

    /** NAME (left, wide) / QTY / AMOUNT (right) - the column heads and every item. */
    private fun itemRow(name: String, qty: String, amount: String, bold: Boolean): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, gap(), 0, 0)
            val style = if (bold) Typeface.BOLD else Typeface.NORMAL
            addView(cell(name, Gravity.START, 3f, style))
            addView(cell(qty, Gravity.END, 1.1f, style))
            addView(cell(amount, Gravity.END, 1.5f, style))
        }

    private fun cell(text: String, gravity: Int, weight: Float, style: Int): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = PrintType.SMALL_SP
            this.gravity = gravity
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(Typeface.MONOSPACE, style)
            setTextColor(0xFF222222.toInt())
            layoutParams = LinearLayout.LayoutParams(0, -2, weight)
        }

    /** Parts pushed to the edges, spread evenly - the head lines and the subtotals. */
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

    /** Two label/value pairs on one line - the grand total. */
    private fun fourCol(l1: String, v1: String, l2: String, v2: String): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, gap(), 0, 0)
            addView(pairCell(l1, Gravity.START, 1.5f))
            addView(pairCell(v1, Gravity.END, 1f))
            addView(pairCell(l2, Gravity.END, 1.6f))
            addView(pairCell(v2, Gravity.END, 1.2f))
        }

    private fun pairCell(text: String, gravity: Int, weight: Float): TextView = TextView(ctx).apply {
        this.text = text
        textSize = PrintType.SMALL_SP
        this.gravity = gravity
        maxLines = 1
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(0xFF111111.toInt())
        layoutParams = LinearLayout.LayoutParams(0, -2, weight)
    }

    /** A centred band naming the UDF a group of items belongs to. */
    private fun band(text: String): View = TextView(ctx).apply {
        this.text = text
        textSize = PrintType.SMALL_SP
        gravity = Gravity.CENTER
        maxLines = 1
        setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        setTextColor(0xFF222222.toInt())
        setPadding(0, gap(), 0, gap())
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
        const val TAG = "UdfWiseItemReport"
    }
}
