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

/** Printable dots on 80mm paper - what [CARD_WIDTH_DP] is measured against. */
private const val REFERENCE_PAPER_DOTS = PrintType.REFERENCE_PAPER_DOTS

/**
 * Renders the slip handed over when a customer settles part or all of what they
 * owe, from the [com.example.synergic_pos_offline.database.AdvancePaymentDao.Collection]
 * that collection produced.
 *
 * This is an acknowledgement of money received, not a sale: nothing was supplied,
 * so there are no line items and no tax on it. It carries the store's own header
 * and footer lines and logos, so it comes off the roll looking like every other
 * slip the till prints.
 *
 * Needs a themed context - an Activity or a Fragment's context - because it
 * inflates and measures real views.
 */
class PaymentReceiptRenderer(context: Context) {

    /** Pinned to a standard font scale - see [ReceiptContext]. */
    private val ctx: Context = ReceiptContext.standardFontScale(context)

    /** The language this till labels its slips in - see [PrintLanguage]. */
    private val lang: PrintLanguage.Language = PrintLanguage.of(context)

    /** [text] in the till's print language, or as it is where there is no translation. */
    private fun t(text: String): String = PrintLanguage.tr(lang, text)

    /**
     * Every label this slip can print, so the label column can be sized to hold the
     * widest of them.
     *
     * Only used off English, where [kvRow]'s character padding cannot align anything -
     * see there. Listed rather than collected as the rows are built because the rows
     * go into two separate containers, and the two have to agree on where the colons
     * sit or the slip reads as two tables.
     */
    private val labelWidthPx: Int by lazy {
        val paint = Paint().apply {
            typeface = Typeface.MONOSPACE
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, PrintType.BODY_SP, ctx.resources.displayMetrics
            )
        }
        listOf("CID", "NAME", "GSTIN", "PREV. BALANCE", "CASH RECEIVED", "TOTAL BALANCE")
            .maxOf { paint.measureText("${t(it)} :") }.toInt() + 1
    }

    /** Everything the slip states, as of the moment the collection was taken. */
    data class Receipt(
        val receiptNumber: String,
        /** "yyyy-MM-dd HH:mm:ss", split into the printed date and time. */
        val dateTime: String,
        val cashier: String,
        /** The customer's master id, printed as CID. */
        val customerId: Long,
        val customerName: String,
        val customerPhone: String,
        /** Printed only when the customer has one on file. */
        val customerGstin: String,
        val previousDue: Double,
        val amountPaid: Double,
        val totalDue: Double,
        val totalPaid: Double,
        val creditLimit: Double,
        val mode: String
    )

    /**
     * Renders the receipt to a bitmap without it ever being shown, laid out for a
     * printer whose head is [paperDots] wide (defaults to 80mm). The card is
     * measured on its own, unbounded in height, and its width scales with the paper
     * so a 58mm slip prints at the same font size and simply wraps more.
     *
     * @return null if it could not be rendered, so a caller does not print blank paper
     */
    fun renderToBitmap(receipt: Receipt, paperDots: Int = REFERENCE_PAPER_DOTS): Bitmap? = runCatching {
        val root = LayoutInflater.from(ctx).inflate(R.layout.receipt_payment, null, false)
        populate(root, receipt)

        val card = root.findViewById<View>(R.id.cardPayReceipt) ?: return null
        (card.parent as? ViewGroup)?.removeView(card)

        val widthDp = CARD_WIDTH_DP.toDouble() * paperDots / REFERENCE_PAPER_DOTS
        val widthPx = (widthDp * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        if (card.measuredHeight <= 0) return null
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)

        val captured = ReceiptPrinter.capture(card) ?: return null
        // The card is captured to its exact height, so the footer sits flush against the
        // tear line. Add a white feed margin below it (scaled to the paper width) so the
        // footer clears the cut - the same clearance the sale bill leaves.
        withBottomMargin(captured, paperDots / 9)
    }.getOrElse {
        android.util.Log.e(TAG, "Could not render payment receipt ${receipt.receiptNumber}", it)
        null
    }

    /** Fills an already-inflated [R.layout.receipt_payment] in place. */
    fun populate(view: View, receipt: Receipt) {
        try {
            view.findViewById<TextView>(R.id.tvPayTitle).text = t("CUSTOMER BILL")
            view.findViewById<TextView>(R.id.tvPayBillNo).text =
                "${t("B. NO")}:${receipt.receiptNumber}"

            val (date, time) = splitDateTime(receipt.dateTime)
            view.findViewById<TextView>(R.id.tvPayDate).text = date
            view.findViewById<TextView>(R.id.tvPayTime).text = time

            // Customer + money-received block. CID, NAME and the two figures always
            // print; GSTIN only when the customer has one on file.
            val summary = view.findViewById<LinearLayout>(R.id.llPaySummary)
            summary.removeAllViews()
            summary.addView(kvRow(t("CID"), receipt.customerId.toString()))
            summary.addView(kvRow(t("NAME"), receipt.customerName.uppercase().ifBlank { "-" }))
            receipt.customerGstin.takeIf { it.isNotBlank() }?.let {
                summary.addView(kvRow("GSTIN", it))
            }
            summary.addView(kvRow(t("PREV. BALANCE"), money(receipt.previousDue)))
            summary.addView(kvRow(t("CASH RECEIVED"), money(receipt.amountPaid)))

            // The running balance after this collection, set apart between two rules.
            val total = view.findViewById<LinearLayout>(R.id.llPayTotal)
            total.removeAllViews()
            total.addView(kvRow(t("TOTAL BALANCE"), money(receipt.totalDue), bold = true))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error rendering payment receipt ${receipt.receiptNumber}", e)
        }
    }

    /**
     * One "LABEL         : value" line. The label is padded to a fixed width so the
     * colons line up in a column, and the value is right-aligned to the paper edge.
     */
    private fun kvRow(label: String, value: String, bold: Boolean = false): View {
        val density = ctx.resources.displayMetrics.density
        val style = if (bold) Typeface.BOLD else Typeface.NORMAL
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
            addView(TextView(ctx).apply {
                // Padding to a character count only aligns anything in a face where
                // every character is one width, which none of the print language's
                // scripts is set in. Off English the label column is measured instead
                // and every label given that width, which puts the colons in a column
                // whatever the script.
                val english = lang == PrintLanguage.Language.ENGLISH
                text = if (english) label.padEnd(LABEL_WIDTH) + " :" else "$label :"
                textSize = PrintType.BODY_SP
                setTypeface(Typeface.MONOSPACE, style)
                setTextColor(0xFF222222.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    if (english) LinearLayout.LayoutParams.WRAP_CONTENT else labelWidthPx,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            addView(TextView(ctx).apply {
                text = value
                textSize = PrintType.BODY_SP
                gravity = Gravity.END
                setTypeface(Typeface.MONOSPACE, style)
                setTextColor(0xFF111111.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    /** Returns [src] with [bottom] px of white space added below it (recycling [src]). */
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

    /** Fills a receipt line, or hides it when there is nothing to print there. */
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

    private fun splitDateTime(value: String): Pair<String, String> = try {
        val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(value)
        if (parsed != null) {
            SimpleDateFormat("dd-MM-yyyy", Locale.US).format(parsed) to
                SimpleDateFormat("HH:mm:ss", Locale.US).format(parsed)
        } else value to ""
    } catch (_: Exception) {
        value to ""
    }

    private companion object {
        const val TAG = "PaymentReceiptRenderer"
        /** Label column width (chars) so the "LABEL : value" colons line up. */
        const val LABEL_WIDTH = 13
    }
}
