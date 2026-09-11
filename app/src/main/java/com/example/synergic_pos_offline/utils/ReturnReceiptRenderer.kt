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
import com.example.synergic_pos_offline.database.BillSettingsDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.LogoDao
import com.example.synergic_pos_offline.database.ReturnDao
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

/** Below this the table is set larger with shorter headings - as the bill does. */
private const val NARROW_PAPER_DOTS = 450

private const val NARROW_ITEM_SP = 14f
private const val WIDE_ITEM_SP = 12.5f
private const val NARROW_BODY_SP = 15f
private const val NARROW_ROW_SPACING = 0.5f

/**
 * Renders the slip handed over when goods are taken back.
 *
 * Deliberately the bill's own shape - same columns, same totals block, same 2-inch
 * treatment - because it is read the same way and often against the bill it came
 * off. What differs is what it says: it is headed SALE RETURN, names the original
 * bill when there is one, and its total is what the customer gets back.
 */
class ReturnReceiptRenderer(context: Context) {

    /** Pinned to a standard font scale - see [ReceiptContext]. */
    private val ctx: Context = ReceiptContext.standardFontScale(context)

    /** The language this till labels its slips in - see [PrintLanguage]. */
    private val lang: PrintLanguage.Language = PrintLanguage.of(context)

    /** [text] in the till's print language, or as it is where there is no translation. */
    private fun t(text: String): String = PrintLanguage.tr(lang, text)

    /**
     * Renders the return to a bitmap without it ever being shown, laid out for a
     * printer whose head is [paperDots] wide (defaults to 80mm).
     *
     * @return null if it could not be rendered, so a caller does not print blank paper
     */
    fun renderToBitmap(
        result: ReturnDao.Result,
        cashier: String,
        paperDots: Int = REFERENCE_PAPER_DOTS
    ): Bitmap? = runCatching {
        val root = LayoutInflater.from(ctx).inflate(R.layout.receipt_return, null, false)
        populate(root, result, cashier, paperDots)

        val card = root.findViewById<View>(R.id.cardReturnReceipt) ?: return null
        (card.parent as? ViewGroup)?.removeView(card)

        val widthDp = CARD_WIDTH_DP.toDouble() * paperDots / REFERENCE_PAPER_DOTS
        val widthPx = (widthDp * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        if (card.measuredHeight <= 0) return null
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)

        withFeed(ReceiptPrinter.capture(card) ?: return null)
    }.getOrElse {
        android.util.Log.e(TAG, "Could not render return ${result.returnNumber}", it)
        null
    }

    /**
     * [src] with [EXTRA_FEED_LINES] blank lines of paper fed under it.
     *
     * The same two lines a kitchen ticket gets, for the same reason - see
     * [KotPrinter.EXTRA_FEED_LINES]. On most counter printers the last line stops
     * under the print HEAD rather than past the tear bar, so a slip torn straight
     * after printing takes the bottom of itself with it: on a return that is the
     * "Returned by" line, and on a narrow roll the total above it.
     *
     * ## Why two LINES rather than a number of dots
     *
     * Print sizes here are absolute - 13sp is the same height of character on 58mm as
     * on 80mm (see PrintType) - so a line is the one unit that means the same length
     * of paper on every roll. Measured off a paint at the slip's own body size, so the
     * feed matches the document rather than being a figure picked in dots.
     *
     * The card is captured at the device's density and scaled to the head afterwards,
     * and text inside it is set in sp rather than scaled with the card - so the two
     * scalings cancel and this lands as two body lines on the paper, whatever the roll
     * and whatever the screen.
     */
    private fun withFeed(src: Bitmap): Bitmap {
        val paint = android.graphics.Paint().apply {
            textSize = PrintType.BODY_SP * ctx.resources.displayMetrics.density
        }
        val feed = (EXTRA_FEED_LINES * (paint.descent() - paint.ascent())).toInt()
        if (feed <= 0) return src
        val out = Bitmap.createBitmap(src.width, src.height + feed, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply {
            // White, not transparent: the printer reads dark pixels as burn, and an
            // unpainted bitmap is not guaranteed to be either.
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        src.recycle()
        return out
    }

    /** Fills an already-inflated [R.layout.receipt_return] in place. */
    fun populate(
        view: View,
        result: ReturnDao.Result,
        cashier: String,
        paperDots: Int = REFERENCE_PAPER_DOTS
    ) {
        try {
            val db = DatabaseHelper.getInstance(ctx).readableDatabase
            val narrow = paperDots < NARROW_PAPER_DOTS
            val itemSp = if (narrow) NARROW_ITEM_SP else WIDE_ITEM_SP
            val bodySp = if (narrow) NARROW_BODY_SP else 13f
            val spacing = if (narrow) NARROW_ROW_SPACING else 1f

            db.query(
                DatabaseHelper.Tables.MD_REGISTRATION,
                arrayOf("store_name", "address", "phone_no", "store_gstin"),
                null, null, null, null, "store_id ASC", "1"
            ).use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.takeIf { it.isNotBlank() }?.let {
                        view.findViewById<TextView>(R.id.tvReturnStoreName).text = it.uppercase()
                    }
                    setIfPresent(view, R.id.tvReturnStoreAddress, c.getString(1))
                    setIfPresent(view, R.id.tvReturnStorePhone, c.getString(2)?.let { "Ph: $it" })
                    setIfPresent(view, R.id.tvReturnStoreGstin, c.getString(3)?.let { "GSTIN: $it" })
                }
            }

            renderFixedLines(
                db, view, R.id.llReturnHeaderLines,
                DatabaseHelper.Tables.MD_HEADERS, "header_text", "header_number", "header_type"
            )
            renderFixedLines(
                db, view, R.id.llReturnFooterLines,
                DatabaseHelper.Tables.MD_FOOTERS, "footer_text", "footer_number", "footer_type"
            )
            renderLogos(view)

            val (date, time) = splitDateTime(result.dateTime)
            view.findViewById<TextView>(R.id.tvReturnDate).text = date
            view.findViewById<TextView>(R.id.tvReturnTime).text = time
            view.findViewById<TextView>(R.id.tvReturnTitle)?.text = t("SALE RETURN")
            view.findViewById<TextView>(R.id.tvReturnNo).text =
                "${t("RETURN NO")}: ${result.returnNumber}"
            view.findViewById<TextView>(R.id.tvReturnCreatedBy).text =
                "${t("Returned by")}: $cashier"
            // Only a bill-wise return has an original to point back at; an item-wise
            // one is taken on the item alone.
            setIfPresent(
                view, R.id.tvReturnAgainstBill,
                result.originalBillNumber?.let { "${t("AGAINST BILL")}: $it" }
            )
            renderCustomer(db, view, result.id)

            // Headings, sized - and on a 2-inch roll shortened - like the bill's.
            listOf(
                R.id.tvReturnColItem to "SR.NO ITEM", R.id.tvReturnColQty to "QTY",
                R.id.tvReturnColRate to "PER UNIT PRICE", R.id.tvReturnColAmount to "NET AMT"
            ).forEach { (id, label) ->
                view.findViewById<TextView>(id).let {
                    it.text = t(label)
                    it.textSize = itemSp
                }
            }
            if (narrow) {
                view.findViewById<TextView>(R.id.tvReturnColRate).text = t("RATE")
                view.findViewById<TextView>(R.id.tvReturnColAmount).text = t("AMOUNT")
            }

            val items = view.findViewById<LinearLayout>(R.id.llReturnItems)
            items.removeAllViews()
            result.lines.forEachIndexed { index, line ->
                items.addView(itemRow(index + 1, line, itemSp, spacing))
            }

            // The refund stated the way it was worked out - gross, less discount,
            // plus each tax - so it reconciles against the bill it came off. The
            // rows come from [ReturnDao.summaryRows], the same list the return
            // screen shows, so paper and screen cannot disagree.
            val summary = view.findViewById<LinearLayout>(R.id.llReturnSummary)
            summary.removeAllViews()
            ReturnDao.summaryRows(result.lines).forEach { line ->
                summary.addView(
                    summaryRow(
                        label = t(line.label),
                        value = if (line.isMoney) money(line.value) else count(line.value),
                        sizeSp = itemSp,
                        spacing = spacing,
                        bold = line.emphasis,
                        valueSize = if (line.emphasis) (if (narrow) 18f else 15f) else itemSp
                    )
                )
            }

            setIfPresent(view, R.id.tvReturnAmountWords, AmountInWords.of(result.totalAmount))

            if (narrow) {
                listOf(
                    R.id.tvReturnDate, R.id.tvReturnTime, R.id.tvReturnNo,
                    R.id.tvReturnAgainstBill, R.id.tvReturnCustName, R.id.tvReturnCustMobile,
                    R.id.tvReturnCustGstin, R.id.tvReturnAmountWords, R.id.tvReturnCreatedBy
                ).forEach { view.findViewById<TextView>(it)?.textSize = bodySp }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error rendering return ${result.returnNumber}", e)
        }
    }

    // ---- Rows --------------------------------------------------------------

    /** Serial and name on one line, the figures in their columns underneath. */
    private fun itemRow(
        serial: Int,
        line: ReturnDao.ReturnLine,
        sizeSp: Float,
        spacing: Float
    ): View {
        val density = ctx.resources.displayMetrics.density
        val gap = 6 * density * spacing
        return LinearLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            orientation = LinearLayout.VERTICAL
            setPadding(0, gap.toInt(), 0, gap.toInt())

            addView(TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2)
                // The product's name in the print language - see [ProductName]. The
                // same treatment the sale bill gives it, so a return reads as the
                // bill it came off.
                text = "$serial ${ProductName.inPrintLanguage(lang, line.name)}"
                gravity = Gravity.START
                typeface = Typeface.MONOSPACE
                textSize = sizeSp
                setTextColor(0xFF222222.toInt())
            })

            addView(LinearLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2)
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (2 * density * spacing).toInt(), 0, 0)
                addView(cell(qtyText(line.quantity), 1.8f, Gravity.CENTER, sizeSp))
                addView(cell(money(line.rate), 3f, Gravity.END, sizeSp))
                addView(cell(money(line.amount), 2.4f, Gravity.END, sizeSp))
            })
        }
    }

    private fun summaryRow(
        label: String,
        value: String,
        sizeSp: Float,
        spacing: Float,
        bold: Boolean = false,
        valueSize: Float = sizeSp
    ): View {
        val density = ctx.resources.displayMetrics.density
        val gap = 2 * density * spacing
        return LinearLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, gap.toInt(), 0, gap.toInt())
            addView(TextView(ctx).apply {
                text = label
                textSize = sizeSp
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

    private fun cell(text: String, weight: Float, gravity: Int, sizeSp: Float): TextView =
        TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, weight)
            this.text = text
            this.gravity = gravity
            typeface = Typeface.MONOSPACE
            textSize = sizeSp
            setTextColor(0xFF222222.toInt())
        }

    // ---- Shared receipt furniture ------------------------------------------

    private fun renderLogos(view: View) {
        val dao = LogoDao(ctx)
        listOf(
            LogoDao.LogoType.BILL_HEADER to R.id.ivReturnHeaderLogo,
            LogoDao.LogoType.BILL_FOOTER to R.id.ivReturnFooterLogo
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

    /**
     * Who the money went back to, printed the way the BILL prints it.
     *
     * ## Read from the sale, not from the return
     *
     * A return row records what came back, not who brought it - so the customer is
     * followed through the original bill: its `customer_id` for somebody on the
     * master, and the payment row's own `cust_name`/`cust_phone`/`cust_gstin` for a
     * walk-in typed at the counter. The same two places [BillReceiptRenderer] reads,
     * so the return and the sale it came off name the same person.
     *
     * An ITEM-WISE return has no bill behind it - it is taken on the item alone - so
     * there is nobody to name and all three lines stay hidden. That is a fact about
     * the return, not a fault: nothing was captured to print.
     *
     * ## The setting decides which lines
     *
     * "Customer Details" in Bill Settings, exactly as on the bill. A shop printing
     * only the mobile on its sales gets only the mobile on its returns; one printing
     * nothing gets nothing. A line is still only shown where the sale actually
     * captured that detail - the setting chooses what to print, never what to invent.
     */
    private fun renderCustomer(db: SQLiteDatabase, view: View, returnId: Long) {
        var name: String? = null
        var phone: String? = null
        var gstin: String? = null

        runCatching {
            db.rawQuery(
                """
                SELECT COALESCE(NULLIF(TRIM(c.customer_name), ''), NULLIF(TRIM(p.cust_name), '')),
                       COALESCE(NULLIF(TRIM(c.phone_number), ''), NULLIF(TRIM(p.cust_phone), '')),
                       COALESCE(NULLIF(TRIM(c.gstin), ''), NULLIF(TRIM(p.cust_gstin), ''))
                FROM ${DatabaseHelper.Tables.TD_SALE_RETURNS} r
                LEFT JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = r.original_bill_id
                LEFT JOIN ${DatabaseHelper.Tables.MD_CUSTOMERS} c ON c.id = b.customer_id
                LEFT JOIN ${DatabaseHelper.Tables.TD_PAYMENTS} p ON p.bill_id = b.receipt_no
                WHERE r.id = ?
                ORDER BY p.id ASC
                LIMIT 1
                """.trimIndent(),
                arrayOf(returnId.toString())
            ).use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)?.takeIf { it.isNotBlank() }
                    phone = c.getString(1)?.takeIf { it.isNotBlank() }
                    gstin = c.getString(2)?.takeIf { it.isNotBlank() }
                }
            }
        }

        val details = runCatching { BillSettingsDao(ctx).load().customerDetails }.getOrNull()
        val showMobile = details == BillSettingsDao.CustomerDetails.ONLY_MOBILE ||
            details == BillSettingsDao.CustomerDetails.MOBILE_NAME ||
            details == BillSettingsDao.CustomerDetails.MOBILE_NAME_GSTIN
        val showName = details == BillSettingsDao.CustomerDetails.MOBILE_NAME ||
            details == BillSettingsDao.CustomerDetails.MOBILE_NAME_GSTIN ||
            details == BillSettingsDao.CustomerDetails.ONLY_NAME
        val showGstin = details == BillSettingsDao.CustomerDetails.MOBILE_NAME_GSTIN ||
            details == BillSettingsDao.CustomerDetails.ONLY_GSTIN

        setIfPresent(view, R.id.tvReturnCustName,
            if (showName) name?.let { "${t("NAME")}: ${it.uppercase()}" } else null)
        setIfPresent(view, R.id.tvReturnCustMobile,
            if (showMobile) phone?.let { "${t("MOBILE")}: $it" } else null)
        setIfPresent(view, R.id.tvReturnCustGstin,
            if (showGstin) gstin?.let { "${t("GSTIN")}: $it" } else null)
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

    /** The item count, which is the one summary row that is not an amount. */
    private fun count(v: Double) = v.toInt().toString()

    /** Whole quantities print without decimals; fractional ones keep two places. */
    private fun qtyText(qty: Double): String = Quantity.text(qty)

    private fun splitDateTime(value: String): Pair<String, String> = runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(value)!!
        SimpleDateFormat("dd-MM-yyyy", Locale.US).format(parsed) to
            SimpleDateFormat("HH:mm", Locale.US).format(parsed)
    }.getOrDefault(value to "")

    private companion object {
        const val TAG = "ReturnReceiptRenderer"

        /** Blank lines fed after a return slip - see [withFeed]. */
        const val EXTRA_FEED_LINES = 2
    }
}
