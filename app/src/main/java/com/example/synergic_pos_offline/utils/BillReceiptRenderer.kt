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
import com.example.synergic_pos_offline.database.BillSettingsDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.LogoDao
import com.example.synergic_pos_offline.database.TaxSettingsDao
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

    /**
     * One printed line item: serial + name, quantity, unit price, discount and the
     * net it leaves.
     *
     * [netAmount] is the line's gross - quantity x [price] - less its [discount], so
     * an undiscounted line's net is simply its gross and [discount] is null there,
     * printed as a dash.
     */
    private data class BillItem(
        val sr: Int,
        val name: String,
        val qty: String,
        val price: String,
        val netAmount: String,
        val hsn: String? = null,
        val discount: String? = null
    )

    /**
     * Totals accumulated from the line items rather than read back from the
     * `td_bills` header, so the printed receipt always adds up to what is listed
     * on it. [discount] is the one figure the items cannot supply: it is stored
     * per bill, not per line, so it is passed in from the header.
     *
     * A pre-tax discount is spread across the lines at billing time, so it already
     * shows up inside [itemsSubtotal] via each line's own `discount_amount`
     * ([itemDiscountApplied] is that spread total). A post-tax discount instead
     * leaves every line untouched and applies once, after tax, at the bill level -
     * [remainingDiscount] is whichever part of [discount] the lines have not
     * already accounted for, so it is never subtracted twice.
     */
    private data class BillTotals(
        val itemsSubtotal: Double = 0.0,
        val cgst: Double = 0.0,
        val sgst: Double = 0.0,
        val vat: Double = 0.0,
        val otherTax: Double = 0.0,
        val discount: Double = 0.0,
        val itemDiscountApplied: Double = 0.0,
        val itemDiscountListed: Double = 0.0,
        val grossMrp: Double = 0.0,
        val qtyCount: Double = 0.0,
        val itemCount: Int = 0
    ) {
        val base: Double get() = itemsSubtotal
        val tax: Double get() = cgst + sgst + vat + otherTax
        val remainingDiscount: Double get() = (discount - itemDiscountApplied).coerceAtLeast(0.0)
        val grandTotal: Double get() = (base + tax - remainingDiscount).coerceAtLeast(0.0)

        /**
         * The whole discount the customer got, stated against the listed prices it
         * came off: what the lines already priced in ([itemDiscountListed]) plus any
         * bill-level discount not folded into them ([remainingDiscount]). Printed
         * against the summary's own listed-price AMT, so the two chain: AMT less this
         * discount, plus the tax below it, is the TOTAL.
         */
        val totalDiscount: Double get() = itemDiscountListed + remainingDiscount
    }

    /**
     * One rate slab in the tax summary. A bill can mix products taxed at different
     * rates, so the tax is reported one line per rate - not a single blended rate,
     * which is meaningless on a tax invoice (a 10% and a 5% line do not average to
     * a real "7.35%"). For GST [cgstRate]/[sgstRate] label the split; for VAT only
     * [vatRate]/[vat] are used.
     */
    private data class TaxSlab(
        val cgstRate: Double,
        val sgstRate: Double,
        val vatRate: Double,
        val base: Double,
        val cgst: Double,
        val sgst: Double,
        val vat: Double
    ) {
        val tax: Double get() = cgst + sgst + vat
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
     * A sale that has not been written yet, standing in for the three transaction
     * tables so a bill can be rendered before it exists - what the checkout screen
     * previews. Everything else a receipt prints (store identity, header/footer lines
     * and logos, and the Bill/Tax settings themselves) comes from the masters either
     * way, so a draft renders through exactly the same code as the saved bill and
     * cannot drift from it.
     *
     * With no settings snapshot to read, a draft falls back to the *live* settings -
     * which is what a preview wants: it shows what this sale would print right now.
     */
    data class Draft(
        val billNumber: String,
        val dateTime: String,
        val cashier: String,
        val customer: Customer,
        val items: List<Item>,
        val discount: Double,
        val roundOff: Double,
        val netAmount: Double,
        val paymentModes: List<String>
    ) {
        /** As captured on the sale; each field printed only where the settings ask. */
        data class Customer(
            val name: String? = null,
            val phone: String? = null,
            val gstin: String? = null,
            val address: String? = null
        )

        /**
         * One line as it will be written to `td_bill_items`. [discountAmount] is
         * stated against the line's raw pre-tax base, the same shape the DAO stores,
         * so [BillPricing] prices it identically here and there.
         */
        data class Item(
            val name: String,
            val quantity: Double,
            val rate: Double,
            val cgstRate: Double = 0.0,
            val sgstRate: Double = 0.0,
            val vatRate: Double = 0.0,
            val discountAmount: Double = 0.0,
            val hsn: String? = null
        )
    }

    /**
     * Fills an already-inflated receipt layout in place, for the on-screen bill.
     *
     * [paperDots] chooses the item/tax row style: the default (80mm's width) keeps
     * the usual column table, unchanged from before. Pass the printer's actual
     * [paperDots] when this is heading to a printer, so a narrow paper switches to
     * full-width lines - see [NARROW_PAPER_DOTS].
     *
     * [draft] renders a sale that has not been saved yet, in which case [receiptNo]
     * is unused; without one the bill is read from the transaction tables as usual.
     */
    fun populate(view: View, receiptNo: Long, paperDots: Int = REFERENCE_PAPER_DOTS, draft: Draft? = null) {
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
            var settingsSnapshotJson: String? = null
            if (draft != null) {
                billNumber = draft.billNumber
                dateTime = draft.dateTime
                roundOff = draft.roundOff
                discount = draft.discount
                storedNetAmount = draft.netAmount
            } else db.rawQuery(
                """
                SELECT bill_number, bill_date_time, bill_date, customer_id,
                       tot_discount_amount, net_amount, operator_id, created_by, bill_type,
                       tot_round_off_amount, amount_in_words, settings_snapshot
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
                settingsSnapshotJson = c.getString(11)
                // Discounts are recorded per bill, not per line, so this one figure
                // still has to come from the header; every other total is derived
                // from the printed line items below.
                discount = c.getDouble(4)
                storedNetAmount = c.getDouble(5)
            }

            // Whichever Bill/Tax Settings were active when this bill was made - not
            // necessarily what is live now - so a reprint reads exactly as it did on
            // the day. Older bills saved before this existed fall back to today's
            // settings, the only information there is for them.
            val snapshot = BillSettingsSnapshot.parse(settingsSnapshotJson)
            val liveSettings by lazy { BillSettingsDao(ctx).load() }
            val hsnCode = snapshot?.hsnCode ?: liveSettings.hsnCode
            val customerDetails = snapshot?.customerDetails ?: liveSettings.customerDetails
            val customerAddressPrinting = snapshot?.customerAddressPrinting ?: liveSettings.customerAddressPrinting
            val totalAmountFontSize = snapshot?.totalAmountFontSize ?: liveSettings.totalAmountFontSize
            val roundOffSetting = snapshot?.roundOff ?: liveSettings.roundOff
            val amountInWordsSetting = snapshot?.amountInWords ?: liveSettings.amountInWords
            val taxRegime = snapshot?.taxRegime ?: run {
                val taxSettings = TaxSettingsDao(ctx).load()
                GstCalculator.regimeFor(taxSettings.gstEnabled, taxSettings.vatEnabled)
            }
            val discountPreTax = snapshot?.discountPreTax
                ?: (TaxSettingsDao(ctx).load().discountPosition == TaxSettingsDao.DiscountPosition.PRE_TAX)
            val inclusive = snapshot?.inclusive ?: run {
                val taxSettings = TaxSettingsDao(ctx).load()
                when (taxRegime) {
                    GstCalculator.TaxRegime.GST -> taxSettings.gstMode == TaxSettingsDao.GstMode.INCLUSIVE
                    GstCalculator.TaxRegime.VAT -> taxSettings.vatMode == TaxSettingsDao.GstMode.INCLUSIVE
                    GstCalculator.TaxRegime.NONE -> false
                }
            }

            view.findViewById<TextView>(R.id.tvBillNo).text = "BILL NO: $billNumber"
            // Moved to the foot of the bill, where "created by" belongs.
            view.findViewById<TextView>(R.id.tvBillCreatedBy).text =
                "Created by: ${draft?.cashier ?: cashierName(db, operatorId, createdBy)}"

            // Which of mobile/name/gstin print is driven by "Customer Details"; the
            // address line is a separate on/off. Each still only shows when the
            // sale actually captured it - the settings choose what to print, not
            // what to fabricate.
            val cust = draft?.customer?.let {
                CustomerInfo(it.name?.uppercase(), it.phone, it.gstin, it.address?.uppercase())
            } ?: loadCustomerInfo(db, customerId, receiptNo)
            val showMobile = customerDetails == BillSettingsDao.CustomerDetails.ONLY_MOBILE ||
                customerDetails == BillSettingsDao.CustomerDetails.MOBILE_NAME ||
                customerDetails == BillSettingsDao.CustomerDetails.MOBILE_NAME_GSTIN
            val showName = customerDetails == BillSettingsDao.CustomerDetails.MOBILE_NAME ||
                customerDetails == BillSettingsDao.CustomerDetails.MOBILE_NAME_GSTIN ||
                customerDetails == BillSettingsDao.CustomerDetails.ONLY_NAME
            val showGstin = customerDetails == BillSettingsDao.CustomerDetails.MOBILE_NAME_GSTIN ||
                customerDetails == BillSettingsDao.CustomerDetails.ONLY_GSTIN

            setIfPresent(view, R.id.tvCustMobile, if (showMobile) cust.phone?.let { "MOBILE : $it" } else null)
            setIfPresent(view, R.id.tvName, if (showName) cust.name?.let { "NAME  : $it" } else null)
            setIfPresent(view, R.id.tvCustGstin, if (showGstin) cust.gstin?.let { "GSTIN : $it" } else null)
            setIfPresent(view, R.id.tvCustAddress, if (customerAddressPrinting) cust.address?.let { "ADDRESS: $it" } else null)

            val (date, time) = splitDateTime(dateTime)
            if (date.isNotEmpty()) view.findViewById<TextView>(R.id.tvDate).text = date
            if (time.isNotEmpty()) view.findViewById<TextView>(R.id.tvTime).text = time

            // Line items, plus the totals summed from those same lines.
            val narrow = paperDots < NARROW_PAPER_DOTS
            val raws = if (draft != null) {
                draftRawLines(draft, hsnCode, taxRegime, inclusive, discountPreTax)
            } else {
                readRawLines(db, receiptNo, hsnCode)
            }
            val (items, lineTotals, taxSlabs) = loadItems(raws, inclusive)
            val llItems = view.findViewById<LinearLayout>(R.id.llItems)
            llItems.removeAllViews()
            // The DISC column earns its place only when a line actually carries a
            // discount. A bill-wise discount comes off the taxed total instead, so it
            // leaves every line alone and the column would print nothing but dashes.
            val showDisc = items.any { it.discount != null }
            view.findViewById<TextView>(R.id.tvColDisc).visibility =
                if (showDisc) View.VISIBLE else View.GONE
            items.forEach {
                llItems.addView(if (narrow) buildItemRowNarrow(it) else buildItemRow(it, showDisc))
            }

            val totals = lineTotals.copy(discount = discount)
            val isGst = taxRegime == GstCalculator.TaxRegime.GST

            // Round off is whatever the bill recorded, not something worked out here:
            // the printed total has to match the amount that was actually charged.
            //
            // net_amount is stored already rounded, so the adjustment is only added
            // to a total summed from the line items - adding it to the stored figure
            // would count it twice.
            val payable = if (items.isEmpty()) storedNetAmount else totals.grandTotal + roundOff

            // Bill summary: item count / qty / gross, each tax rate on its own line,
            // discount and totals - laid out as "label : value" lines. Replaces the
            // old base-amount tax table.
            val netSize = if (totalAmountFontSize == BillSettingsDao.FontSize.BIG) 20f else 15f
            val llSummary = view.findViewById<LinearLayout>(R.id.llSummary)
            llSummary.removeAllViews()
            // AMT is the AMOUNT column's own total - every line's gross - so the
            // customer can add the column up and land on it.
            llSummary.addView(
                summaryHead(
                    "ITEM: ${totals.itemCount}  QTY: ${qtyText(totals.qtyCount)}",
                    "AMT: ${money(totals.grossMrp)}"
                )
            )
            // A pre-tax discount reduces the taxable value, so it reads before the
            // tax; a post-tax discount comes off after tax is charged on the full
            // amount, so it reads after TOTAL GST.
            val showDiscount = totals.totalDiscount > 0.005
            if (showDiscount && discountPreTax) {
                llSummary.addView(summaryRow("DISCOUNT", money(totals.totalDiscount)))
            }
            taxSlabs.forEach { slab ->
                if (isGst) {
                    llSummary.addView(summaryRow("CGST @${rate(slab.cgstRate)}%", money(slab.cgst)))
                    llSummary.addView(summaryRow("SGST @${rate(slab.sgstRate)}%", money(slab.sgst)))
                } else {
                    llSummary.addView(summaryRow("VAT @${rate(slab.vatRate)}%", money(slab.vat)))
                }
            }
            if (totals.tax > 0.005) {
                llSummary.addView(summaryRow(if (isGst) "TOTAL GST" else "TOTAL VAT", money(totals.tax)))
            }
            if (showDiscount && !discountPreTax) {
                llSummary.addView(summaryRow("DISCOUNT", money(totals.totalDiscount)))
            }
            llSummary.addView(summaryRow("TOTAL", money(totals.grandTotal)))
            if (roundOffSetting) {
                llSummary.addView(summaryRow("ROUND OFF", money(roundOff)))
            }
            llSummary.addView(summaryRow("NET AMT", money(payable), bold = true, valueSize = netSize))

            // Prefer what the bill stored, so a reprint reads exactly as the original.
            if (amountInWordsSetting) {
                setIfPresent(
                    view, R.id.tvAmountWords,
                    amountInWords?.takeIf { it.isNotBlank() } ?: AmountInWords.of(payable)
                )
            } else {
                view.findViewById<TextView>(R.id.tvAmountWords).visibility = View.GONE
            }

            renderPayment(view, draft?.paymentModes ?: paymentModes(db, receiptNo, billType))

            renderFixedLines(
                db, view, R.id.llBillFooterLines,
                DatabaseHelper.Tables.MD_FOOTERS, "footer_text", "footer_number", "footer_type"
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error loading bill $receiptNo", e)
        }
    }

    /**
     * One line's priced figures, whether they were read back from `td_bill_items` or
     * priced from a [Draft] that has not been written yet. Everything the receipt
     * prints is derived from these, so both sources produce an identical bill.
     */
    private data class RawLine(
        val name: String,
        val qty: Double,
        val rate: Double,
        val subtotal: Double,
        val itemTotal: Double,
        val discountAmount: Double,
        val cgst: Double,
        val sgst: Double,
        val vat: Double,
        val cgstRate: Double,
        val sgstRate: Double,
        val vatRate: Double,
        val hsn: String?
    )

    /**
     * Reads the stored lines of a saved bill.
     *
     * [includeHsn] mirrors the Bill Settings "HSN Code" toggle: the column is only
     * fetched and printed when it is on. Each figure is taken at the paisa it prints
     * at, so the receipt's totals are summed from the same numbers the lines show -
     * bills written before that was stored this way are brought to the same footing
     * here, rather than reprinting a total a paisa off its own lines.
     */
    private fun readRawLines(db: SQLiteDatabase, receiptNo: Long, includeHsn: Boolean): List<RawLine> {
        val raws = mutableListOf<RawLine>()
        db.rawQuery(
            """
            SELECT i.product_id, i.quantity, i.rate, i.item_subtotal, i.item_total, p.product_name,
                   i.discount_amount, i.cgst_amount, i.sgst_amount, i.igst_amount, i.vat_amount, p.hsn_code,
                   i.cgst_rate, i.sgst_rate, i.vat_rate
            FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} i
            LEFT JOIN ${DatabaseHelper.Tables.MD_PRODUCTS} p ON i.product_id = p.id
            WHERE i.bill_id = ?
            ORDER BY i.id ASC
            """.trimIndent(),
            arrayOf(receiptNo.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val qty = c.getDouble(1)
                val rate = c.getDouble(2)
                val subtotal = BillRounding.toPaise(if (c.isNull(3)) rate * qty else c.getDouble(3))
                raws.add(
                    RawLine(
                        name = c.getString(5)?.takeIf { it.isNotBlank() } ?: "Item",
                        qty = qty,
                        rate = rate,
                        subtotal = subtotal,
                        itemTotal = BillRounding.toPaise(if (c.isNull(4)) subtotal else c.getDouble(4)),
                        discountAmount = c.getDouble(6),
                        cgst = BillRounding.toPaise(c.getDouble(7)),
                        sgst = BillRounding.toPaise(c.getDouble(8)),
                        vat = BillRounding.toPaise(c.getDouble(10)),
                        cgstRate = c.getDouble(12),
                        sgstRate = c.getDouble(13),
                        vatRate = c.getDouble(14),
                        hsn = if (includeHsn) c.getString(11)?.takeIf { it.isNotBlank() } else null
                    )
                )
            }
        }
        return raws
    }

    /**
     * Prices a [Draft]'s lines the way they will be written, through the same
     * [BillPricing] the DAO uses - so the preview cannot quote a line the sale will
     * not go on to charge.
     */
    private fun draftRawLines(
        draft: Draft,
        includeHsn: Boolean,
        regime: GstCalculator.TaxRegime,
        inclusive: Boolean,
        discountPreTax: Boolean
    ): List<RawLine> = draft.items.map { item ->
        val priced = BillPricing.price(
            rate = item.rate,
            quantity = item.quantity,
            cgstRate = item.cgstRate,
            sgstRate = item.sgstRate,
            vatRate = item.vatRate,
            discountAmount = item.discountAmount,
            regime = regime,
            inclusive = inclusive,
            discountPreTax = discountPreTax
        )
        RawLine(
            name = item.name,
            qty = item.quantity,
            rate = item.rate,
            subtotal = priced.subtotal,
            itemTotal = priced.itemTotal,
            discountAmount = item.discountAmount,
            cgst = priced.cgst,
            sgst = priced.sgst,
            vat = priced.vat,
            cgstRate = item.cgstRate,
            sgstRate = item.sgstRate,
            vatRate = item.vatRate,
            hsn = if (includeHsn) item.hsn?.takeIf { it.isNotBlank() } else null
        )
    }

    /** Turns priced lines into the printed rows, the receipt totals and the tax
     *  slabs, in one pass. */
    private fun loadItems(raws: List<RawLine>, inclusive: Boolean): Triple<List<BillItem>, BillTotals, List<TaxSlab>> {
        val list = mutableListOf<BillItem>()
        var subtotalSum = 0.0
        var cgstSum = 0.0
        var sgstSum = 0.0
        var vatSum = 0.0
        var itemDiscountSum = 0.0
        var itemDiscountListedSum = 0.0
        var grossSum = 0.0
        var qtySum = 0.0
        // Taxed base/tax grouped by combined rate (scaled x100 for a clean key), so
        // the summary prints one line per distinct rate rather than one blended row.
        // Each entry holds [cgstRate, sgstRate, vatRate, base, cgst, sgst, vat].
        val slabs = LinkedHashMap<Long, DoubleArray>()
        run {
            raws.forEach { raw ->
                val qty = raw.qty
                val rate = raw.rate
                val subtotal = raw.subtotal
                val itemTotal = raw.itemTotal
                val name = raw.name
                val cgstAmt = raw.cgst
                val sgstAmt = raw.sgst
                val vatAmt = raw.vat

                // The tax block still needs each line's taxable (pre-tax) base, net
                // of its discount share - recovered from what was stored rather than
                // re-derived, so it can never drift from item_total, which already
                // accounts for inclusive/exclusive pricing and however the discount
                // was applied.
                val lineNet = (itemTotal - cgstAmt - sgstAmt - vatAmt).coerceAtLeast(0.0)
                subtotalSum += lineNet
                cgstSum += cgstAmt
                sgstSum += sgstAmt
                vatSum += vatAmt
                grossSum += subtotal
                qtySum += qty

                val cgstRate = raw.cgstRate
                val sgstRate = raw.sgstRate
                val vatRate = raw.vatRate

                // Bucket this line into its rate slab so the summary can report each
                // rate on its own line. Only taxed lines form a slab - an exempt
                // (0%) line contributes no tax line.
                if (cgstAmt + sgstAmt + vatAmt > 0.0) {
                    val key = Math.round((cgstRate + sgstRate + vatRate) * 100.0)
                    val acc = slabs.getOrPut(key) { DoubleArray(7) }
                    acc[0] = cgstRate
                    acc[1] = sgstRate
                    acc[2] = vatRate
                    acc[3] += lineNet
                    acc[4] += cgstAmt
                    acc[5] += sgstAmt
                    acc[6] += vatAmt
                }

                // The discount the customer got, stated against the line's listed
                // price - the terms it was set in - so a "3% off 100" reads as 3.00
                // whichever way the price is taxed. That means comparing like with
                // like: an inclusive price already carries tax, so it is measured
                // against the line total; an exclusive one is measured pre-tax,
                // against the taxable net. Taking the drop in the tax-inclusive
                // total instead would gross an exclusive 3.00 up to 3.15.
                //
                // Both sides come from the stored figures, so the column still
                // reconciles with the totals rather than being re-derived from the
                // configured rate.
                val lineDiscount = raw.discountAmount
                val lineNetListed = if (inclusive) itemTotal else lineNet
                val lineDiscountListed = (subtotal - lineNetListed).coerceAtLeast(0.0)
                itemDiscountSum += lineDiscount
                itemDiscountListedSum += lineDiscountListed
                val hsn = raw.hsn
                val disc = if (lineDiscountListed > 0.005) money(lineDiscountListed) else null

                // The printed NET AMT column is the line's gross - quantity x listed
                // price - less the DISC beside it, so the two columns read together
                // and the summary chains: AMT (the gross the lines add up to) less
                // DISCOUNT, plus the tax below it, reaches the same TOTAL.
                list.add(
                    BillItem(
                        sr = list.size + 1,
                        name = name.uppercase(),
                        qty = qtyText(qty),
                        price = money(rate),
                        netAmount = money(lineNetListed),
                        hsn = hsn,
                        discount = disc
                    )
                )
            }
        }
        val totals = BillTotals(
            itemsSubtotal = subtotalSum,
            cgst = cgstSum,
            sgst = sgstSum,
            vat = vatSum,
            itemDiscountApplied = itemDiscountSum,
            itemDiscountListed = itemDiscountListedSum,
            grossMrp = grossSum,
            qtyCount = qtySum,
            itemCount = list.size
        )
        // Highest rate first, the usual order on a tax invoice.
        val taxSlabs = slabs.entries
            .sortedByDescending { it.key }
            .map { (_, acc) -> TaxSlab(acc[0], acc[1], acc[2], acc[3], acc[4], acc[5], acc[6]) }
        return Triple(list, totals, taxSlabs)
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

    /** Mobile, name, GSTIN and address as captured on the sale - each nullable, printed
     *  only where the Bill Settings "Customer Details" / "Customer Address Printing"
     *  toggles call for it and the sale actually captured it. */
    private data class CustomerInfo(
        val name: String?,
        val phone: String?,
        val gstin: String?,
        val address: String?
    )

    /**
     * Resolves the sale's customer details: the customer master first (it is the
     * more complete, more current record), falling back to whatever was typed into
     * the payment for a walk-in with no master record. The address only ever comes
     * from the master - a payment row has nowhere to store one.
     */
    private fun loadCustomerInfo(db: SQLiteDatabase, customerId: Long?, receiptNo: Long): CustomerInfo {
        var name: String? = null
        var phone: String? = null
        var gstin: String? = null
        var address: String? = null

        if (customerId != null) {
            db.query(
                DatabaseHelper.Tables.MD_CUSTOMERS,
                arrayOf("customer_name", "phone_number", "gstin", "customer_address"),
                "id=?", arrayOf(customerId.toString()), null, null, null, "1"
            ).use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)?.takeIf { it.isNotBlank() }
                    phone = c.getString(1)?.takeIf { it.isNotBlank() }
                    gstin = c.getString(2)?.takeIf { it.isNotBlank() }
                    address = c.getString(3)?.takeIf { it.isNotBlank() }
                }
            }
        }

        if (name == null || phone == null || gstin == null) {
            db.query(
                DatabaseHelper.Tables.TD_PAYMENTS,
                arrayOf("cust_name", "cust_phone", "cust_gstin"),
                "bill_id=?", arrayOf(receiptNo.toString()), null, null, "id ASC", "1"
            ).use { c ->
                if (c.moveToFirst()) {
                    if (name == null) name = c.getString(0)?.takeIf { it.isNotBlank() }
                    if (phone == null) phone = c.getString(1)?.takeIf { it.isNotBlank() }
                    if (gstin == null) gstin = c.getString(2)?.takeIf { it.isNotBlank() }
                }
            }
        }

        return CustomerInfo(name?.uppercase(), phone, gstin, address?.uppercase())
    }

    /**
     * Prints how the bill was paid. A sale can be settled in more than one payment,
     * so every row recorded against the bill gets a line. If nothing was recorded -
     * a credit sale is billed now and collected later - the bill's own type stands
     * in, so the receipt never goes out with the payment silently blank.
     */
    private fun paymentModes(db: SQLiteDatabase, receiptNo: Long, billType: String?): List<String> {
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
        return modes
    }

    private fun renderPayment(view: View, modes: List<String>) {
        val ll = view.findViewById<LinearLayout>(R.id.llBillPayment)
        ll.removeAllViews()
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

    /**
     * An item block: the name (and HSN, if shown) on its own full-width row, with
     * the QTY / PRICE / DISC / NET AMT columns lined up beneath it under the header.
     *
     * [showDisc] drops the DISC column - header included, so the rest stay lined up -
     * for a bill whose lines carry no discount at all. Where the column is printed, a
     * dash fills it on whichever lines were not discounted.
     */
    private fun buildItemRow(item: BillItem, showDisc: Boolean): View {
        val density = ctx.resources.displayMetrics.density
        val container = LinearLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
        }

        val name = buildString {
            append("${item.sr} ${item.name}")
            if (item.hsn != null) append("\nHSN: ${item.hsn}")
        }
        container.addView(TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            text = name
            gravity = Gravity.START
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setTextColor(0xFF222222.toInt())
        })

        val values = LinearLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        // Weights mirror the QTY / PER UNIT PRICE / DISC / NET AMT header in
        // fragment_bill.xml - change one and the other has to follow.
        values.addView(cell(item.qty, 1.8f, Gravity.CENTER))
        values.addView(cell(item.price, 3f, Gravity.END))
        if (showDisc) values.addView(cell(item.discount ?: "-", 1.8f, Gravity.END))
        values.addView(cell(item.netAmount, 2.4f, Gravity.END))
        container.addView(values)
        return container
    }

    /** A summary line without a colon column: left label and a right-aligned value
     *  (used for the "ITEM: n QTY: q ... AMT: x" header of the summary block). */
    private fun summaryHead(left: String, right: String): View {
        val row = summaryRowContainer()
        row.addView(summaryCell(left, 1f, Gravity.START, bold = true, size = 12.5f))
        row.addView(summaryCell(right, 1f, Gravity.END, bold = true, size = 12.5f))
        return row
    }

    /** A "label : value" summary line, colons aligned in their own thin column. */
    private fun summaryRow(label: String, value: String, bold: Boolean = false, valueSize: Float = 12.5f): View {
        val row = summaryRowContainer()
        row.addView(summaryCell(label, 3f, Gravity.START, bold, 12.5f))
        row.addView(summaryCell(":", 0.4f, Gravity.START, bold, 12.5f))
        row.addView(summaryCell(value, 3f, Gravity.END, bold, valueSize))
        return row
    }

    private fun summaryRowContainer(): LinearLayout {
        val density = ctx.resources.displayMetrics.density
        return LinearLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
        }
    }

    private fun summaryCell(text: String, weight: Float, gravity: Int, bold: Boolean, size: Float): TextView =
        TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            this.text = text
            this.gravity = gravity
            setTypeface(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            textSize = size
            setTextColor(0xFF222222.toInt())
        }

    /** A tax rate trimmed of a needless ".00" - "5" not "5.00", but "2.50" kept. */
    private fun rate(v: Double): String =
        if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.2f", v)

    /**
     * Item row for narrow paper: name on its own line, quantity/price/net on the
     * next as one plain string. Each line gets the card's full width and wraps only
     * at a space if it must - never mid-number, unlike the fixed-width columns
     * [buildItemRow] uses (safe on 80mm, where there is room to spare).
     *
     * An undiscounted line's net is its gross, so "qty x price = net" reads as the
     * sum it is; a discounted one splits the discount and net onto their own lines
     * rather than printing an equation that does not add up.
     */
    private fun buildItemRowNarrow(item: BillItem): View {
        val container = narrowContainer()
        container.addView(narrowLine("${item.sr}. ${item.name}"))
        if (item.hsn != null) container.addView(narrowLine("  HSN: ${item.hsn}"))
        if (item.discount == null) {
            container.addView(narrowLine("  ${item.qty} x ${item.price} = ${item.netAmount}"))
        } else {
            container.addView(narrowLine("  ${item.qty} x ${item.price}"))
            container.addView(narrowLine("  DISC: ${item.discount}"))
            container.addView(narrowLine("  NET AMT: ${item.netAmount}"))
        }
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

    private fun money(v: Double) = String.format(Locale.US, "%.2f", BillRounding.toPaise(v))

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
