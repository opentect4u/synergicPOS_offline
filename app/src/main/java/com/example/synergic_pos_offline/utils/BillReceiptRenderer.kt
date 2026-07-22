package com.example.synergic_pos_offline.utils

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

/** Width the receipt card is laid out at for 80mm paper, matching fragment_bill.xml. */
private const val CARD_WIDTH_DP = 360

/** Printable dots on 80mm paper - the reference [CARD_WIDTH_DP] was designed for. */
private const val REFERENCE_PAPER_DOTS = 576

/**
 * Below this, the item/tax rows switch from fixed weighted columns to single
 * full-width lines. A weighted column narrow enough to be smaller than one number
 * forces Android to hard-wrap mid-digit (e.g. "350.0" / "0"); a single line gets the
 * whole card's width and only ever wraps at a space, never inside a number. 58mm
 * (384 dots) falls under this; 80mm (576) and up keep the original column table.
 */
private const val NARROW_PAPER_DOTS = 450

/**
 * Fills a receipt layout from the bill tables.
 *
 * Split out of the bill screen so a receipt can be produced without one being on
 * display: checkout prints the moment a sale is completed, and the operator never
 * leaves the till. Both paths render the same layout from the same query, so an
 * auto-print and a later reprint from the bill screen are identical slips.
 *
 * Needs a themed context - an Activity or a Fragment's context - because it
 * inflates and measures real views.
 */
class BillReceiptRenderer(private val ctx: Context) {

    /** One printed line item: serial + name, quantity, unit price, amount. */
    private data class BillItem(
        val sr: Int,
        val name: String,
        val qty: String,
        val price: String,
        val amount: String
    )

    /**
     * Totals accumulated from the line items rather than read back from the
     * `td_bills` header, so the printed receipt always adds up to what is listed
     * on it. [discount] is the one figure the items cannot supply: it is stored
     * per bill, not per line, so it is passed in from the header.
     */
    private data class BillTotals(
        val itemsSubtotal: Double = 0.0,
        val cgst: Double = 0.0,
        val sgst: Double = 0.0,
        val otherTax: Double = 0.0,
        val discount: Double = 0.0
    ) {
        val base: Double get() = (itemsSubtotal - discount).coerceAtLeast(0.0)
        val tax: Double get() = cgst + sgst + otherTax
        val grandTotal: Double get() = base + tax
    }

    /**
     * Renders the bill to a bitmap without it ever being shown, laid out for a printer
     * whose head is [paperDots] wide (defaults to 80mm).
     *
     * The card is detached from the inflated hierarchy and measured on its own,
     * unbounded in height. Its width scales with the paper rather than being fixed, so
     * the printer scales every paper size by the same factor: a 58mm slip prints at the
     * same font size as an 80mm one and simply wraps more text, instead of coming out
     * as a shrunk 80mm.
     *
     * @return null if the bill could not be rendered, so a caller does not print blank paper
     */
    fun renderToBitmap(receiptNo: Long, paperDots: Int = REFERENCE_PAPER_DOTS): Bitmap? = runCatching {
        val root = LayoutInflater.from(ctx).inflate(R.layout.fragment_bill, null, false)

        // The print button floats over the receipt and would be drawn onto the paper.
        root.findViewById<View>(R.id.btnPrintBill)?.visibility = View.GONE
        populate(root, receiptNo, paperDots)

        val card = root.findViewById<View>(R.id.cardReceipt) ?: return null
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
        android.util.Log.e(TAG, "Could not render bill $receiptNo", it)
        null
    }

    /**
     * Fills an already-inflated receipt layout in place, for the on-screen bill.
     *
     * [paperDots] chooses the item/tax row style: the default (80mm's width) keeps
     * the usual column table, unchanged from before. Pass the printer's actual
     * [paperDots] when this is heading to a printer, so a narrow paper switches to
     * full-width lines - see [NARROW_PAPER_DOTS].
     */
    fun populate(view: View, receiptNo: Long, paperDots: Int = REFERENCE_PAPER_DOTS) {
        try {
            val db = DatabaseHelper.getInstance(ctx).readableDatabase

            // Store identity and tax registration, printed at the head of the bill.
            db.query(
                DatabaseHelper.Tables.MD_REGISTRATION,
                arrayOf("store_name", "address", "phone_no", "store_gstin"),
                null, null, null, null, "store_id ASC", "1"
            ).use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) {
                        view.findViewById<TextView>(R.id.tvStoreName).text = name.uppercase()
                    }
                    setIfPresent(view, R.id.tvStoreAddress, c.getString(1))
                    setIfPresent(view, R.id.tvStorePhone, c.getString(2)?.let { "Ph: $it" })
                    setIfPresent(view, R.id.tvStoreGstin, c.getString(3)?.let { "GSTIN: $it" })
                }
            }

            renderFixedLines(
                db, view, R.id.llBillHeaderLines,
                DatabaseHelper.Tables.MD_HEADERS, "header_text", "header_number", "header_type"
            )
            renderLogos(view)

            // Bill header + totals.
            var billNumber = ""
            var dateTime = ""
            var customerId: Long? = null
            var operatorId: Long? = null
            var createdBy: String? = null
            var billType: String? = null
            var amountInWords: String? = null
            var discount = 0.0
            var storedNetAmount = 0.0
            var roundOff = 0.0
            db.rawQuery(
                """
                SELECT bill_number, bill_date_time, bill_date, customer_id,
                       tot_discount_amount, net_amount, operator_id, created_by, bill_type,
                       tot_round_off_amount, amount_in_words
                FROM ${DatabaseHelper.Tables.TD_BILLS} WHERE receipt_no = ?
                """.trimIndent(),
                arrayOf(receiptNo.toString())
            ).use { c ->
                if (!c.moveToFirst()) return
                billNumber = c.getString(0) ?: receiptNo.toString()
                dateTime = c.getString(1) ?: c.getString(2) ?: ""
                customerId = if (c.isNull(3)) null else c.getLong(3)
                operatorId = if (c.isNull(6)) null else c.getLong(6)
                createdBy = c.getString(7)
                billType = c.getString(8)
                roundOff = c.getDouble(9)
                amountInWords = c.getString(10)
                // Discounts are recorded per bill, not per line, so this one figure
                // still has to come from the header; every other total is derived
                // from the printed line items below.
                discount = c.getDouble(4)
                storedNetAmount = c.getDouble(5)
            }

            view.findViewById<TextView>(R.id.tvBillNo).text = "BILL NO: $billNumber"
            view.findViewById<TextView>(R.id.tvName).text = "NAME  : ${customerName(db, customerId, receiptNo)}"
            // Moved to the foot of the bill, where "created by" belongs.
            view.findViewById<TextView>(R.id.tvBillCreatedBy).text =
                "Created by: ${cashierName(db, operatorId, createdBy)}"

            // The customer's own GSTIN, when the sale was billed to a business.
            setIfPresent(
                view, R.id.tvCustGstin,
                customerGstin(db, receiptNo)?.let { "GSTIN : $it" }
            )
            val (date, time) = splitDateTime(dateTime)
            if (date.isNotEmpty()) view.findViewById<TextView>(R.id.tvDate).text = date
            if (time.isNotEmpty()) view.findViewById<TextView>(R.id.tvTime).text = time

            // Line items, plus the totals summed from those same lines.
            val narrow = paperDots < NARROW_PAPER_DOTS
            val (items, lineTotals) = loadItems(db, receiptNo)
            val llItems = view.findViewById<LinearLayout>(R.id.llItems)
            llItems.removeAllViews()
            items.forEach { llItems.addView(if (narrow) buildItemRowNarrow(it) else buildItemRow(it)) }

            val totals = lineTotals.copy(discount = discount)

            // Tax block: one consolidated GST row derived from the line items.
            val llTaxRows = view.findViewById<LinearLayout>(R.id.llTaxRows)
            llTaxRows.removeAllViews()
            if (totals.base > 0 && totals.tax > 0) {
                val rate = totals.tax / totals.base * 100.0
                val rateText = String.format(Locale.US, "%.2f%%", rate)
                val bAmt = money(totals.base)
                val sgst = money(totals.sgst)
                val cgst = money(totals.cgst)
                val total = money(totals.grandTotal)
                llTaxRows.addView(
                    if (narrow) buildTaxRowNarrow(rateText, bAmt, sgst, cgst, total)
                    else buildTaxRow(rateText, bAmt, sgst, cgst, total)
                )
            }

            // Round off is whatever the bill recorded, not something worked out here:
            // the printed total has to match the amount that was actually charged.
            //
            // net_amount is stored already rounded, so the adjustment is only added
            // to a total summed from the line items - adding it to the stored figure
            // would count it twice.
            val payable = if (items.isEmpty()) storedNetAmount else totals.grandTotal + roundOff
            view.findViewById<TextView>(R.id.tvBillGrandTotal).text = money(payable)
            view.findViewById<View>(R.id.llRoundOff).visibility =
                if (kotlin.math.abs(roundOff) > 0.001) {
                    view.findViewById<TextView>(R.id.tvRoundOff).text =
                        (if (roundOff > 0) "+ " else "- ") + money(kotlin.math.abs(roundOff))
                    View.VISIBLE
                } else View.GONE

            // Prefer what the bill stored, so a reprint reads exactly as the original.
            setIfPresent(
                view, R.id.tvAmountWords,
                amountInWords?.takeIf { it.isNotBlank() } ?: AmountInWords.of(payable)
            )

            renderPayment(db, view, receiptNo, billType)

            renderFixedLines(
                db, view, R.id.llBillFooterLines,
                DatabaseHelper.Tables.MD_FOOTERS, "footer_text", "footer_number", "footer_type"
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error loading bill $receiptNo", e)
        }
    }

    /** Reads the printed lines and sums the receipt totals in the same pass. */
    private fun loadItems(db: SQLiteDatabase, receiptNo: Long): Pair<List<BillItem>, BillTotals> {
        val list = mutableListOf<BillItem>()
        var subtotalSum = 0.0
        var cgstSum = 0.0
        var sgstSum = 0.0
        var otherTaxSum = 0.0
        db.rawQuery(
            """
            SELECT i.product_id, i.quantity, i.rate, i.item_subtotal, i.item_total, p.product_name,
                   i.discount_amount, i.cgst_amount, i.sgst_amount, i.igst_amount, i.vat_amount
            FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} i
            LEFT JOIN ${DatabaseHelper.Tables.MD_PRODUCTS} p ON i.product_id = p.id
            WHERE i.bill_id = ?
            ORDER BY i.id ASC
            """.trimIndent(),
            arrayOf(receiptNo.toString())
        ).use { c ->
            var sr = 1
            while (c.moveToNext()) {
                val qty = c.getDouble(1)
                val rate = c.getDouble(2)
                val subtotal = if (c.isNull(3)) rate * qty else c.getDouble(3)
                val name = c.getString(5)?.takeIf { it.isNotBlank() } ?: "Item"

                // The printed AMOUNT column is net of any per-line discount, so the
                // listed figures are what the subtotal adds up from.
                val lineNet = (subtotal - c.getDouble(6)).coerceAtLeast(0.0)
                subtotalSum += lineNet
                cgstSum += c.getDouble(7)
                sgstSum += c.getDouble(8)
                otherTaxSum += c.getDouble(9) + c.getDouble(10)

                list.add(
                    BillItem(
                        sr = sr++,
                        name = name.uppercase(),
                        qty = qtyText(qty),
                        price = money(rate),
                        amount = money(lineNet)
                    )
                )
            }
        }
        return list to BillTotals(
            itemsSubtotal = subtotalSum,
            cgst = cgstSum,
            sgst = sgstSum,
            otherTax = otherTaxSum
        )
    }

    /**
     * Draws the configured bill logos at the head and foot of the receipt.
     *
     * Decoded at a modest size: the receipt card is 360dp wide and the slots cap
     * out well below that, so pushing a full-resolution image through would cost
     * memory for pixels nobody sees. The most recently added logo of each type
     * wins, which is what an operator replacing an old one expects.
     */
    private fun renderLogos(view: View) {
        val dao = LogoDao(ctx)
        listOf(
            LogoDao.LogoType.BILL_HEADER to R.id.ivBillHeaderLogo,
            LogoDao.LogoType.BILL_FOOTER to R.id.ivBillFooterLogo
        ).forEach { (type, viewId) ->
            val target = view.findViewById<android.widget.ImageView>(viewId)
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

    /**
     * Renders the operator's configured header or footer lines.
     *
     * Both tables have the same shape - numbered, ordered, individually enabled,
     * and typed BILL or KOT - so one routine serves each end of the receipt. Only
     * BILL lines are printed here; KOT lines belong on a kitchen ticket.
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
                // Size and weight come from the master; an unrecognised size falls
                // back to MEDIUM rather than silently printing at the wrong scale.
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

    /** The customer's GSTIN as captured on the payment, for a business sale. */
    private fun customerGstin(db: SQLiteDatabase, receiptNo: Long): String? {
        db.rawQuery(
            "SELECT cust_gstin FROM ${DatabaseHelper.Tables.TD_PAYMENTS} WHERE bill_id = ? LIMIT 1",
            arrayOf(receiptNo.toString())
        ).use { c ->
            if (c.moveToFirst()) return c.getString(0)?.takeIf { it.isNotBlank() }
        }
        return null
    }

    /**
     * Prints how the bill was paid. A sale can be settled in more than one payment,
     * so every row recorded against the bill gets a line. If nothing was recorded -
     * a credit sale is billed now and collected later - the bill's own type stands
     * in, so the receipt never goes out with the payment silently blank.
     */
    private fun renderPayment(db: SQLiteDatabase, view: View, receiptNo: Long, billType: String?) {
        val ll = view.findViewById<LinearLayout>(R.id.llBillPayment)
        ll.removeAllViews()

        val modes = mutableListOf<String>()
        db.rawQuery(
            """
            SELECT payment_mode FROM ${DatabaseHelper.Tables.TD_PAYMENTS}
            WHERE bill_id = ? ORDER BY id ASC
            """.trimIndent(),
            arrayOf(receiptNo.toString())
        ).use { c ->
            while (c.moveToNext()) {
                c.getString(0)?.takeIf { it.isNotBlank() }?.let { modes.add(it.uppercase()) }
            }
        }

        if (modes.isEmpty()) billType?.takeIf { it.isNotBlank() }?.let { modes.add(it.uppercase()) }
        if (modes.isEmpty()) return

        modes.forEach { mode ->
            val row = baseRow()
            row.addView(cell("PAY MODE", 1f, Gravity.START))
            row.addView(cell(mode, 1f, Gravity.END))
            ll.addView(row)
        }
    }

    /**
     * Login id of the operator who generated the bill. Resolved from the bill's own
     * `operator_id` rather than the current session, so reprinting an older bill
     * still credits whoever actually rang it up. Falls back to `created_by`, the
     * login id stamped on the row, which is all that survives if that operator has
     * since been removed from md_users.
     */
    private fun cashierName(db: SQLiteDatabase, operatorId: Long?, createdBy: String?): String {
        if (operatorId != null) {
            db.query(
                DatabaseHelper.Tables.MD_USERS, arrayOf("user_id", "user_name"),
                "id=?", arrayOf(operatorId.toString()), null, null, null, "1"
            ).use { c ->
                if (c.moveToFirst()) {
                    val id = c.getString(0)?.takeIf { it.isNotBlank() }
                        ?: c.getString(1)?.takeIf { it.isNotBlank() }
                    if (id != null) return id.uppercase()
                }
            }
        }
        return createdBy?.takeIf { it.isNotBlank() }?.uppercase() ?: "---"
    }

    /** Resolves the display name: customer master first, then the payment record. */
    private fun customerName(db: SQLiteDatabase, customerId: Long?, receiptNo: Long): String {
        if (customerId != null) {
            db.query(
                DatabaseHelper.Tables.MD_CUSTOMERS, arrayOf("customer_name"),
                "id=?", arrayOf(customerId.toString()), null, null, null, "1"
            ).use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n.uppercase()
                }
            }
        }
        db.query(
            DatabaseHelper.Tables.TD_PAYMENTS, arrayOf("cust_name"),
            "bill_id=?", arrayOf(receiptNo.toString()), null, null, "id ASC", "1"
        ).use { c ->
            if (c.moveToFirst()) {
                val n = c.getString(0)
                if (!n.isNullOrBlank()) return n.uppercase()
            }
        }
        return "GUEST"
    }

    /** Builds a 4-column monospace item row matching the header columns. */
    private fun buildItemRow(item: BillItem): View {
        val row = baseRow()
        row.addView(cell("${item.sr} ${item.name}", 3.4f, Gravity.START))
        row.addView(cell(item.qty, 2f, Gravity.CENTER))
        row.addView(cell(item.price, 2f, Gravity.END))
        row.addView(cell(item.amount, 2.2f, Gravity.END))
        return row
    }

    /** Builds a 5-column monospace tax row matching the tax header columns. */
    private fun buildTaxRow(rate: String, bAmt: String, sgst: String, cgst: String, total: String): View {
        val row = baseRow()
        row.addView(cell(rate, 2f, Gravity.START))
        row.addView(cell(bAmt, 2f, Gravity.CENTER))
        row.addView(cell(sgst, 2f, Gravity.CENTER))
        row.addView(cell(cgst, 2f, Gravity.CENTER))
        row.addView(cell(total, 2.2f, Gravity.END))
        return row
    }

    /**
     * Item row for narrow paper: name on its own line, quantity/price/amount on the
     * next as one plain string. Each line gets the card's full width and wraps only
     * at a space if it must - never mid-number, unlike the fixed-width columns
     * [buildItemRow] uses (safe on 80mm, where there is room to spare).
     */
    private fun buildItemRowNarrow(item: BillItem): View {
        val container = narrowContainer()
        container.addView(narrowLine("${item.sr}. ${item.name}"))
        container.addView(narrowLine("  ${item.qty} x ${item.price} = ${item.amount}"))
        return container
    }

    /** Tax block for narrow paper: same values as [buildTaxRow], as full-width lines. */
    private fun buildTaxRowNarrow(rate: String, bAmt: String, sgst: String, cgst: String, total: String): View {
        val container = narrowContainer()
        container.addView(narrowLine("GST $rate   Taxable $bAmt"))
        container.addView(narrowLine("CGST $cgst   SGST $sgst"))
        container.addView(narrowLine("TOTAL TAX $total"))
        return container
    }

    private fun narrowContainer(): LinearLayout {
        val density = ctx.resources.displayMetrics.density
        return LinearLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
        }
    }

    private fun narrowLine(text: String): TextView = TextView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        this.text = text
        gravity = Gravity.START
        typeface = Typeface.MONOSPACE
        textSize = 12.5f
        setTextColor(0xFF222222.toInt())
    }

    private fun baseRow(): LinearLayout {
        val density = ctx.resources.displayMetrics.density
        return LinearLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
        }
    }

    private fun cell(text: String, weight: Float, gravity: Int): TextView = TextView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        this.text = text
        this.gravity = gravity
        typeface = Typeface.MONOSPACE
        textSize = 12.5f
        setTextColor(0xFF222222.toInt())
    }

    private fun money(v: Double) = String.format(Locale.US, "%.2f", v)

    /** Whole quantities print without decimals; fractional ones keep two places. */
    private fun qtyText(qty: Double): String =
        if (qty % 1.0 == 0.0) qty.toInt().toString() else String.format(Locale.US, "%.2f", qty)

    private fun splitDateTime(value: String): Pair<String, String> {
        return try {
            val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(value)
            if (parsed != null) {
                SimpleDateFormat("dd-MM-yyyy", Locale.US).format(parsed) to
                    SimpleDateFormat("HH:mm", Locale.US).format(parsed)
            } else value to ""
        } catch (_: Exception) {
            value to ""
        }
    }

    companion object {
        private const val TAG = "BillReceiptRenderer"

        /**
         * Logs the print against the bill. The first one is the ORIGINAL; anything
         * after it is a REPRINT, which is the distinction an audit cares about.
         *
         * Shared so a checkout auto-print and a bill-screen reprint are counted the
         * same way - otherwise the audit trail depends on which screen printed.
         */
        fun recordPrint(ctx: Context, receiptNo: Long) {
            runCatching {
                val db = DatabaseHelper.getInstance(ctx).writableDatabase
                val already = db.rawQuery(
                    "SELECT count(*) FROM ${DatabaseHelper.Tables.TD_BILL_PRINTS} WHERE bill_id = ?",
                    arrayOf(receiptNo.toString())
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

                db.insert(
                    DatabaseHelper.Tables.TD_BILL_PRINTS, null,
                    ContentValues().apply {
                        put("bill_id", receiptNo)
                        put("print_type", if (already == 0) "ORIGINAL" else "REPRINT")
                        put("print_date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                        put("created_by", SessionManager.currentUser?.userId)
                    }
                )
            }.onFailure { android.util.Log.e(TAG, "Could not record the print", it) }
        }
    }
}
