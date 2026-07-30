package com.example.synergic_pos_offline.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Typeface
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

/** Width the receipt card is laid out at for 80mm paper, matching receipt_payment.xml. */
private const val CARD_WIDTH_DP = 360

/** Printable dots on 80mm paper - the reference [CARD_WIDTH_DP] was designed for. */
private const val REFERENCE_PAPER_DOTS = 576

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

    /** Everything the slip states, as of the moment the collection was taken. */
    data class Receipt(
        val receiptNumber: String,
        /** "yyyy-MM-dd HH:mm:ss", split into the printed date and time. */
        val dateTime: String,
        val cashier: String,
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

        ReceiptPrinter.capture(card)
    }.getOrElse {
        android.util.Log.e(TAG, "Could not render payment receipt ${receipt.receiptNumber}", it)
        null
    }

    /** Fills an already-inflated [R.layout.receipt_payment] in place. */
    fun populate(view: View, receipt: Receipt) {
        try {
            val db = DatabaseHelper.getInstance(ctx).readableDatabase

            db.query(
                DatabaseHelper.Tables.MD_REGISTRATION,
                arrayOf("store_name", "address", "phone_no", "store_gstin"),
                null, null, null, null, "store_id ASC", "1"
            ).use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) {
                        view.findViewById<TextView>(R.id.tvPayStoreName).text = name.uppercase()
                    }
                    setIfPresent(view, R.id.tvPayStoreAddress, c.getString(1))
                    setIfPresent(view, R.id.tvPayStorePhone, c.getString(2)?.let { "Ph: $it" })
                    setIfPresent(view, R.id.tvPayStoreGstin, c.getString(3)?.let { "GSTIN: $it" })
                }
            }

            renderFixedLines(
                db, view, R.id.llPayHeaderLines,
                DatabaseHelper.Tables.MD_HEADERS, "header_text", "header_number", "header_type"
            )
            renderFixedLines(
                db, view, R.id.llPayFooterLines,
                DatabaseHelper.Tables.MD_FOOTERS, "footer_text", "footer_number", "footer_type"
            )
            renderLogos(view)

            val (date, time) = splitDateTime(receipt.dateTime)
            view.findViewById<TextView>(R.id.tvPayDate).text = date
            view.findViewById<TextView>(R.id.tvPayTime).text = time
            view.findViewById<TextView>(R.id.tvPayReceiptNo).text = "RECEIPT NO: ${receipt.receiptNumber}"
            view.findViewById<TextView>(R.id.tvPayCreatedBy).text = "Received by: ${receipt.cashier}"

            // Name, phone and GSTIN are the customer's identity on a receipt they may
            // have to produce later, so all three print whenever they are on file -
            // Bill Settings' "Customer Details" governs a sale bill, not this.
            setIfPresent(view, R.id.tvPayCustName, receipt.customerName.takeIf { it.isNotBlank() }
                ?.let { "NAME   : ${it.uppercase()}" })
            setIfPresent(view, R.id.tvPayCustMobile, receipt.customerPhone.takeIf { it.isNotBlank() }
                ?.let { "MOBILE : $it" })
            setIfPresent(view, R.id.tvPayCustGstin, receipt.customerGstin.takeIf { it.isNotBlank() }
                ?.let { "GSTIN  : $it" })

            val summary = view.findViewById<LinearLayout>(R.id.llPaySummary)
            summary.removeAllViews()
            summary.addView(row("MODE", receipt.mode.uppercase()))
            summary.addView(row("PREVIOUS DUE", money(receipt.previousDue)))
            summary.addView(row("AMOUNT PAID", money(receipt.amountPaid), bold = true, valueSize = 20f))
            summary.addView(row("TOTAL DUE", money(receipt.totalDue), bold = true))
            summary.addView(row("TOTAL PAID", money(receipt.totalPaid)))
            summary.addView(row("CREDIT AVAILABLE", money(receipt.creditLimit)))

            setIfPresent(view, R.id.tvPayAmountWords, AmountInWords.of(receipt.amountPaid))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error rendering payment receipt ${receipt.receiptNumber}", e)
        }
    }

    /** One "LABEL            value" line in the figures block. */
    private fun row(label: String, value: String, bold: Boolean = false, valueSize: Float = 13f): View {
        val density = ctx.resources.displayMetrics.density
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
            addView(TextView(ctx).apply {
                text = label
                textSize = 13f
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

    /** The store's header / footer logos, hidden when no image is set for a slot. */
    private fun renderLogos(view: View) {
        val dao = LogoDao(ctx)
        listOf(
            LogoDao.LogoType.BILL_HEADER to R.id.ivPayHeaderLogo,
            LogoDao.LogoType.BILL_FOOTER to R.id.ivPayFooterLogo
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

    /**
     * The operator's configured BILL header or footer lines - the same ones a sale
     * bill carries, so a payment slip is recognisably from the same counter.
     */
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
                SimpleDateFormat("HH:mm", Locale.US).format(parsed)
        } else value to ""
    } catch (_: Exception) {
        value to ""
    }

    private companion object {
        const val TAG = "PaymentReceiptRenderer"
    }
}
