package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * Restaurant checkout — order review + payment panel. Opened from the Orders
 * screen's "Bill & Pay" for a table order (a take-away settles through its own
 * quick-payment dialog instead - see RestaurantOrdersFragment.showQuickPayment).
 */
class RestaurantCheckoutFragment : Fragment(), TitledScreen {

    override val screenTitle = "Checkout"

    private data class Line(
        val name: String, val qty: Double, val rate: Double, val cgstRate: Double, val sgstRate: Double,
        val hsn: String? = null, val vatRate: Double = 0.0, val unit: String? = null
    )

    // The selected order's items, passed in from the Orders screen.
    private val lines: List<Line> by lazy {
        val names = arguments?.getStringArrayList(ARG_NAMES) ?: arrayListOf()
        val qtys = arguments?.getDoubleArray(ARG_QTYS) ?: DoubleArray(0)
        val rates = arguments?.getDoubleArray(ARG_RATES) ?: DoubleArray(0)
        val cgsts = arguments?.getDoubleArray(ARG_CGSTS) ?: DoubleArray(0)
        val sgsts = arguments?.getDoubleArray(ARG_SGSTS) ?: DoubleArray(0)
        val vats = arguments?.getDoubleArray(ARG_VATS)
        val hsns = arguments?.getStringArrayList(ARG_HSNS)
        val units = arguments?.getStringArrayList(ARG_UNITS)
        names.mapIndexed { i, n ->
            Line(
                n, qtys.getOrElse(i) { 1.0 }, rates.getOrElse(i) { 0.0 },
                cgsts.getOrElse(i) { 0.0 }, sgsts.getOrElse(i) { 0.0 },
                hsns?.getOrNull(i)?.takeIf { it.isNotBlank() },
                vats?.getOrElse(i) { 0.0 } ?: 0.0,
                units?.getOrNull(i)?.takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * The shop's own extra charges (Parcel Charge among them) that apply to this
     * order - already worked out and filtered by order type on the Orders screen
     * (see RestaurantOrdersFragment.computeBill), so this screen only has to total
     * and show them, not recompute or re-filter anything.
     */
    private val charges: List<Triple<String, Double, String>> by lazy {
        val names = arguments?.getStringArrayList(ARG_CHARGE_NAMES) ?: arrayListOf()
        val amounts = arguments?.getDoubleArray(ARG_CHARGE_AMOUNTS) ?: DoubleArray(0)
        val types = arguments?.getStringArrayList(ARG_CHARGE_TYPES) ?: arrayListOf()
        names.mapIndexed { i, n -> Triple(n, amounts.getOrElse(i) { 0.0 }, types.getOrNull(i) ?: "AMOUNT") }
    }

    private val orderId get() = arguments?.getLong(ARG_ORDER_ID) ?: -1L
    private val tableNo get() = arguments?.getString(ARG_TABLE).orEmpty()
    private val section get() = arguments?.getString(ARG_SECTION).orEmpty()

    /**
     * How this order's table is written on screen and on the bill. Table codes repeat
     * across sections, so "Table 1" on its own does not say which room was served -
     * the section goes with the number wherever the table is named.
     */
    private val tableLabel: String
        get() = when {
            tableNo.startsWith("TA-", ignoreCase = true) -> tableNo.replace("TA-", "Token #")
            section.isBlank() -> tableNo
            else -> "$tableNo ($section)"
        }
    private val customer get() = arguments?.getString(ARG_CUSTOMER)?.ifBlank { "Walk-in" } ?: "Walk-in"
    private val serviceRate get() = arguments?.getDouble(ARG_SERVICE_RATE) ?: 0.0
    private val gstEnabled get() = arguments?.getBoolean(ARG_GST_ON) ?: true
    private val vatEnabled get() = arguments?.getBoolean(ARG_VAT_ON) ?: false
    private val taxInclusive get() = arguments?.getBoolean(ARG_INCLUSIVE) ?: false

    /**
     * Tax Settings' discount, as the Orders screen worked it out - see CartMath.
     *
     * Not recomputed here, for the same reason the charges above are not: the figure
     * the operator was quoted on the cart page is the figure that has to be paid, and
     * a screen that works it out a second time can only ever agree by coincidence.
     *
     * [billDiscount] is the whole-bill amount; [lineDiscounts] is each line's share of
     * it against that line's raw pre-tax base. Which of the two does the work depends
     * on [discountPreTax]: pre-tax, the per-line shares reduce the taxable value, and
     * the bill figure is already inside them; post-tax, the lines are taxed in full
     * and the bill figure comes off once, after tax.
     */
    private val billDiscount get() = arguments?.getDouble(ARG_DISCOUNT) ?: 0.0
    private val lineDiscounts: DoubleArray get() = arguments?.getDoubleArray(ARG_LINE_DISCOUNTS) ?: DoubleArray(0)
    private val discountPreTax get() = arguments?.getBoolean(ARG_DISCOUNT_PRE_TAX) ?: true

    /** This line's share of the discount, or 0 where none was passed. */
    private fun discountFor(index: Int): Double = lineDiscounts.getOrElse(index) { 0.0 }

    /**
     * Which of a line's own CGST/SGST or VAT rate is actually charged - see
     * BillPricing.price. A line is taxed under whichever it carries a rate for,
     * regardless of this; the regime only gates whether ANY tax applies at all,
     * off when both GST and VAT are switched off.
     */
    private val taxRegime get() = com.example.synergic_pos_offline.utils.GstCalculator.regimeFor(gstEnabled, vatEnabled)

    private var total = 0.0

    /** Whether the operator has typed in Amount Tendered - see [fillTenderedWithTotal]. */
    private var tenderedEdited = false

    /** True while this screen is writing that field, so its own write is not read as typing. */
    private var fillingTendered = false
    private var payMethod = "Cash"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_restaurant_checkout, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Take-away carries a "TA-n" token, not a table — label it accordingly.
        val heading = if (tableNo.startsWith("TA-", ignoreCase = true))
            "Take Away  •  $tableLabel"
        else "Table: $tableLabel"
        view.findViewById<TextView>(R.id.tvCoTable).text = "$heading     Customer: $customer"
        populateItems(view)

        // Payment method selection — always re-read the current theme colour.
        mapOf(R.id.btnPayCash to "Cash", R.id.btnPayCard to "Card", R.id.btnPayOnline to "Online")
            .forEach { (id, name) ->
                view.findViewById<MaterialButton>(id).setOnClickListener {
                    payMethod = name
                    restyle(view)
                    showUpiQr(view)
                }
            }

        // Amount tendered → change due.
        val etTendered = view.findViewById<TextInputEditText>(R.id.etTendered)
        val tvChange = view.findViewById<TextView>(R.id.tvChangeDue)
        etTendered.addTextChangedListener {
            // A change the operator made, not one fillTenderedWithTotal made - after
            // this the field stops being auto-filled. See that function.
            if (!fillingTendered) tenderedEdited = true
            val tendered = com.example.synergic_pos_offline.utils.Amounts.parse(it?.toString())
            tvChange.text = if (tendered != null && tendered >= total) "₹ ${money(tendered - total)}" else "—"
        }

        view.findViewById<MaterialButton>(R.id.btnReceiptPreview).setOnClickListener {
            showReceiptPreview()
        }
        view.findViewById<MaterialButton>(R.id.btnBackToBilling).setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        view.findViewById<MaterialButton>(R.id.btnConfirmPay).setOnClickListener {
            // Hand the paid table + method back to the Orders screen, which prints the
            // receipt (with preview) and settles/removes the table — like the grocery flow.
            parentFragmentManager.setFragmentResult(
                RESULT_PAID, android.os.Bundle().apply {
                    // The order's own id: the table code alone would match the same
                    // numbered table in another section.
                    putLong(ARG_ORDER_ID, orderId)
                    putString(ARG_TABLE, tableNo)
                    putString(ARG_SECTION, section)
                    putString(ARG_PAY_METHOD, payMethod)
                    // Cash tendered (0 when not entered) so the Orders screen can book
                    // the change and print the amount returned on the receipt.
                    putDouble(
                        ARG_TENDERED,
                        com.example.synergic_pos_offline.utils.Amounts.parse(etTendered.text?.toString()) ?: 0.0
                    )
                }
            )
            parentFragmentManager.popBackStack()
        }

        // Re-apply our accents after MainActivity's global theme pass.
        view.post { if (isAdded) restyle(view) }
    }

    override fun onResume() {
        super.onResume()
        view?.let { v -> v.post { if (isAdded) restyle(v) } }
    }

    /** Called by MainActivity when the palette colour changes — recolour instantly. */
    fun onThemeChanged() {
        view?.let { v -> v.post { if (isAdded) restyle(v) } }
    }

    /** Renders the bill in the grocery format and shows it in a scrollable dialog. */
    private fun showReceiptPreview() {
        val ctx = requireContext()
        var subtotal = 0.0; var taxable = 0.0; var cgst = 0.0; var sgst = 0.0; var vat = 0.0
        lines.forEachIndexed { i, l ->
            val p = com.example.synergic_pos_offline.utils.BillPricing.price(
                l.rate, l.qty, l.cgstRate, l.sgstRate, l.vatRate,
                discountFor(i), taxRegime, taxInclusive, discountPreTax
            )
            subtotal += l.qty * l.rate; taxable += p.taxable; cgst += p.cgst; sgst += p.sgst; vat += p.vat
        }
        // Flat section service charge (₹), applied only to a non-empty order.
        val service = if (subtotal > 0.0) serviceRate else 0.0
        val chargesTotal = charges.sumOf { it.second }
        // A POST-TAX discount comes off here, once, after tax - pre-tax it is already
        // inside each line's taxable value above and must not be taken twice. This is
        // CartMath.totals' own rule; the two have to match or the slip and the screen
        // that printed it would quote different money.
        // FROM THE TAXABLE VALUE, not the gross subtotal. Under a PRE-TAX discount the
        // reduction lives inside each line's taxable value, so a total built on the
        // listed subtotal adds the discount straight back on - which is what this did,
        // quoting a pre-tax discounted table its full undiscounted price. Taxable + tax
        // is the same figure the inclusive shortcut used to give when there was no
        // discount, and the right one when there is. Matches CartMath.totals.
        val goods = taxable + cgst + sgst + vat
        val netTotal = (goods - (if (discountPreTax) 0.0 else billDiscount)).coerceAtLeast(0.0) +
            service + chargesTotal
        val roundOffOn = runCatching {
            com.example.synergic_pos_offline.database.BillSettingsDao(ctx).load().roundOff
        }.getOrDefault(false)

        // The table as the bill's own field - see Draft.table - rather than smuggled
        // in as the customer's name, which the Customer Details setting could switch
        // off and take the table number off the bill with it.
        val receiptTable = if (tableNo.startsWith("TA-", ignoreCase = true))
            "Take Away $tableLabel" else tableLabel
        val draft = com.example.synergic_pos_offline.utils.BillReceiptRenderer.Draft(
            billNumber = com.example.synergic_pos_offline.database.BillDao(ctx).nextBillNumber(),
            dateTime = java.text.SimpleDateFormat("dd-MM-yyyy hh:mm a", java.util.Locale.getDefault()).format(java.util.Date()),
            cashier = com.example.synergic_pos_offline.utils.SessionManager.currentUser?.userId ?: "—",
            customer = com.example.synergic_pos_offline.utils.BillReceiptRenderer.Draft.Customer(
                phone = customer.takeIf { it != "Walk-in" && it.isNotBlank() }
            ),
            table = receiptTable,
            items = lines.mapIndexed { i, it ->
                com.example.synergic_pos_offline.utils.BillReceiptRenderer.Draft.Item(
                    name = it.name, quantity = it.qty.toDouble(), rate = it.rate,
                    cgstRate = it.cgstRate, sgstRate = it.sgstRate, vatRate = it.vatRate, hsn = it.hsn,
                    unit = it.unit,
                    // The share the Orders screen worked out for this line. The preview
                    // priced every line at full while showing a discounted total, so
                    // the lines on it did not add up to its own foot.
                    discountAmount = discountFor(i)
                )
            },
            // Bill Settings' Round Off is the only say in whether this rounds - on,
            // it rounds every total regardless of what is in the cart; off, the exact
            // taxed figure stands.
            discount = billDiscount,
            roundOff = if (roundOffOn) BillRounding.roundOff(netTotal) else 0.0,
            netAmount = if (roundOffOn) BillRounding.payable(netTotal) else BillRounding.toPaise(netTotal),
            paymentModes = listOf(payMethod.uppercase(java.util.Locale.US)),
            serviceCharge = service,
            // Already worked out and filtered by order type on the Orders screen -
            // "BOTH" here just means "keep it", since that decision was already made.
            charges = charges.map { it.first to it.second },
            chargeTypes = charges.map { it.third },
            chargeApplicabilities = charges.map { "BOTH" },
            orderType = if (tableNo.startsWith("TA-", ignoreCase = true)) "TAKEAWAY" else "DINE_IN",
            returnAmount = run {
                val tendered = view?.findViewById<TextInputEditText>(R.id.etTendered)
                    ?.text?.toString()?.toDoubleOrNull() ?: 0.0
                val payable = if (roundOffOn) BillRounding.payable(netTotal) else BillRounding.toPaise(netTotal)
                (tendered - payable).coerceAtLeast(0.0)
            }
        )
        val paperDots = com.example.synergic_pos_offline.database.OperatingPrinterDao(ctx).getAll()
            .firstOrNull { it.printFlag.equals("B", ignoreCase = true) }
            ?.let { com.example.synergic_pos_offline.utils.ThermalPrinter.configFor(it)?.paperDots } ?: 576
        val bmp = com.example.synergic_pos_offline.utils.BillReceiptRenderer(ctx).renderDraftToBitmap(draft, paperDots)
        if (bmp == null) {
            android.widget.Toast.makeText(ctx, "Could not render the receipt", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        showPreviewDialog(bmp)
    }

    /** A simple scrollable image dialog for the receipt bitmap. */
    private fun showPreviewDialog(bitmap: android.graphics.Bitmap) {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val previewW = dp(300)
        val scaled = android.graphics.Bitmap.createScaledBitmap(
            bitmap, previewW, (bitmap.height.toFloat() / bitmap.width * previewW).toInt().coerceAtLeast(1), true
        )
        val iv = android.widget.ImageView(ctx).apply { setImageBitmap(scaled); setBackgroundColor(android.graphics.Color.WHITE) }
        val scroll = android.widget.ScrollView(ctx).apply {
            addView(iv); setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(android.graphics.Color.WHITE)
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Receipt Preview")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    /**
     * Puts the bill total in Amount Tendered, so the common case needs no typing.
     *
     * Most customers hand over the exact amount - a card, an app, or counted-out cash
     * - and the operator was retyping a figure already on the screen above, to be told
     * the change is zero. Prefilled, exact payment is one tap; anything else is
     * overtyped, which is the case that actually needs a number entered.
     *
     * ONLY WHILE UNTOUCHED. Once the operator has typed something the field is theirs:
     * a total that reasserted itself would wipe a part-typed amount every time the
     * figure moved. That is what [tenderedEdited] tracks - it is set by the watcher,
     * which is the only thing that fires when a person types, and cleared by this
     * function's own writes so they do not look like typing.
     */
    private fun fillTenderedWithTotal(root: View) {
        if (tenderedEdited) return
        val field = root.findViewById<TextInputEditText>(R.id.etTendered) ?: return
        // Ungrouped: this value is read back, and money()'s thousands comma does not
        // survive the trip - see Amounts. Here it failed quietly rather than loudly:
        // no validation refused it, so any bill over ₹999 settled with the tendered
        // amount recorded as zero and no change worked out.
        val text = com.example.synergic_pos_offline.utils.Amounts.editable(total)
        if (field.text?.toString() == text) return
        fillingTendered = true
        field.setText(text)
        field.setSelection(text.length)
        fillingTendered = false
    }

    private fun populateItems(root: View) {
        val container = root.findViewById<LinearLayout>(R.id.llCheckoutItems)
        val inflater = LayoutInflater.from(requireContext())
        var subtotal = 0.0; var taxable = 0.0; var cgst = 0.0; var sgst = 0.0; var vat = 0.0
        lines.forEachIndexed { index, l ->
            val row = inflater.inflate(R.layout.item_rest_checkout_line, container, false)
            row.findViewById<TextView>(R.id.tvLineName).text = l.name
            row.findViewById<TextView>(R.id.tvLineQty).text = qtyText(l.qty)
            row.findViewById<TextView>(R.id.tvLineRate).text = money(l.rate)
            row.findViewById<TextView>(R.id.tvLineAmount).text = money(l.qty * l.rate)
            container.addView(row)
            // Per-line CGST/SGST or VAT, honouring the store Tax Settings (GST/VAT
            // on/off, inclusive) - see [taxRegime]. Which of the two a line actually
            // carries a rate for is the line's own business, not the till's.
            val p = com.example.synergic_pos_offline.utils.BillPricing.price(
                l.rate, l.qty, l.cgstRate, l.sgstRate, l.vatRate,
                discountFor(index), taxRegime, taxInclusive, discountPreTax
            )
            subtotal += l.qty * l.rate
            taxable += p.taxable
            cgst += p.cgst
            sgst += p.sgst
            vat += p.vat
        }
        // Flat section service charge (₹), applied only to a non-empty order - the
        // same flat figure RestaurantOrdersFragment.computeBill() and this screen's
        // own showReceiptPreview() use. This used to be worked out as a PERCENTAGE of
        // subtotal instead, which is not what the section's service charge is.
        val service = if (subtotal > 0.0) serviceRate else 0.0
        // The shop's own extra charges - Parcel Charge among them - already worked
        // out and filtered by order type on the Orders screen; joined last, on top of
        // the taxed goods, same as CartMath.Totals.total does for every other screen.
        val chargesTotal = charges.sumOf { it.second }
        // See showReceiptPreview: from the taxable value, and the discount only comes
        // off here when it is post-tax.
        val goods = taxable + cgst + sgst + vat
        val exactTotal = (goods - (if (discountPreTax) 0.0 else billDiscount)).coerceAtLeast(0.0) +
            service + chargesTotal
        // Bill Settings' Round Off is the only say in whether this rounds - see
        // RestaurantOrdersFragment.roundOffOn(). Applied here, on the class field
        // that everything below (the total shown, the Confirm button, the tendered
        // validation, the QR amount, and ARG_TENDERED handed back to the Orders
        // screen) reads from, so all of it agrees on the one payable figure.
        val roundOffOn = runCatching {
            com.example.synergic_pos_offline.database.BillSettingsDao(requireContext()).load().roundOff
        }.getOrDefault(false)
        total = if (roundOffOn) BillRounding.payable(exactTotal) else BillRounding.toPaise(exactTotal)
        root.findViewById<TextView>(R.id.tvSubtotal).text = "₹ ${money(subtotal)}"
        // The discount, said out loud. It has always been taken off the total here -
        // pre-tax inside each line's taxable value, post-tax off `goods` above - but
        // the breakup never carried a line for it, so the page showed a subtotal of
        // 220 and a total of 210 with nothing to account for the difference. The
        // figure is the Orders screen's own (see billDiscount), so this row and the
        // panel's Note & tax details row are the same number by construction.
        //
        // Signed, and hidden at zero, exactly as the panel does it.
        root.findViewById<View>(R.id.rowCheckoutDiscount).visibility =
            if (billDiscount > 0.0) View.VISIBLE else View.GONE
        root.findViewById<TextView>(R.id.tvCheckoutDiscount).text = "- ₹ ${money(billDiscount)}"
        root.findViewById<TextView>(R.id.tvService).text = "₹ ${money(service)}"

        val llCharges = root.findViewById<LinearLayout>(R.id.llCheckoutCharges)
        llCharges.removeAllViews()
        charges.forEach { (name, amount, _) ->
            val row = inflater.inflate(R.layout.row_order_charge, llCharges, false)
            row.findViewById<TextView>(R.id.tvChargeLabel).text = name
            row.findViewById<TextView>(R.id.tvChargeValue).text = "₹ ${money(amount)}"
            llCharges.addView(row)
        }

        root.findViewById<TextView>(R.id.tvCgst).text = "₹ ${money(cgst)}"
        root.findViewById<TextView>(R.id.tvSgst).text = "₹ ${money(sgst)}"
        root.findViewById<TextView>(R.id.tvTotalAmount).text = "₹ ${money(total)}"
        root.findViewById<MaterialButton>(R.id.btnConfirmPay).text = "Confirm Payment  ( ₹ ${money(total)} )"
        fillTenderedWithTotal(root)
        // The code is drawn for the total, so it is redrawn wherever the total is.
        showUpiQr(root)
    }

    /** Shows the scan-to-pay code while Online is the chosen mode. */
    private fun showUpiQr(root: View) {
        com.example.synergic_pos_offline.utils.CheckoutUpiQr.bind(
            root, total, online = payMethod.equals("Online", ignoreCase = true)
        )
    }

    /** Reads the CURRENT theme colour fresh (never captured), so it can't go stale. */
    private fun restyle(root: View) {
        val accent = ThemeManager.getThemeColor(requireContext())
        val white = android.graphics.Color.WHITE
        val strokePx = (root.resources.displayMetrics.density * 1.5f).toInt()
        fun filled(id: Int) = root.findViewById<MaterialButton>(id).apply {
            backgroundTintList = ColorStateList.valueOf(accent); setTextColor(white)
            iconTint = ColorStateList.valueOf(white); strokeWidth = 0
        }
        fun outlined(id: Int) = root.findViewById<MaterialButton>(id).apply {
            backgroundTintList = ColorStateList.valueOf(white); setTextColor(accent)
            strokeColor = ColorStateList.valueOf(accent); strokeWidth = strokePx
            iconTint = ColorStateList.valueOf(accent)
        }
        outlined(R.id.btnReceiptPreview); outlined(R.id.btnBackToBilling)
        filled(R.id.btnConfirmPay)
        root.findViewById<TextView>(R.id.tvTotalAmount).setTextColor(accent)
        root.findViewById<TextView>(R.id.tvChangeDue).setTextColor(accent)

        // Payment method: selected filled, others outlined.
        mapOf(R.id.btnPayCash to "Cash", R.id.btnPayCard to "Card", R.id.btnPayOnline to "Online")
            .forEach { (id, name) -> if (name == payMethod) filled(id) else outlined(id) }
    }

    private fun money(v: Double): String = String.format(java.util.Locale.US, "%,.2f", v)

    private fun qtyText(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

    companion object {
        const val RESULT_PAID = "restaurant_checkout_paid"
        const val ARG_ORDER_ID = "order_id"
        const val ARG_TABLE = "table"
        const val ARG_SECTION = "section"
        const val ARG_PAY_METHOD = "pay_method"
        const val ARG_TENDERED = "tendered"
        private const val ARG_CUSTOMER = "customer"
        private const val ARG_NAMES = "names"
        private const val ARG_QTYS = "qtys"
        private const val ARG_RATES = "rates"
        private const val ARG_CGSTS = "cgsts"
        private const val ARG_SGSTS = "sgsts"
        private const val ARG_VATS = "vats"
        private const val ARG_HSNS = "hsns"
        private const val ARG_UNITS = "units"
        private const val ARG_SERVICE_RATE = "service_rate"
        private const val ARG_GST_ON = "gst_on"
        private const val ARG_VAT_ON = "vat_on"
        private const val ARG_INCLUSIVE = "inclusive"
        private const val ARG_CHARGE_NAMES = "charge_names"
        private const val ARG_CHARGE_AMOUNTS = "charge_amounts"
        private const val ARG_CHARGE_TYPES = "charge_types"
        private const val ARG_DISCOUNT = "discount"
        private const val ARG_LINE_DISCOUNTS = "line_discounts"
        private const val ARG_DISCOUNT_PRE_TAX = "discount_pre_tax"

        /**
         * Builds a checkout for the running order [orderId] on [table] in [section],
         * carrying its items and tax. The order id travels with it so the paid result
         * settles this order rather than the same-numbered table in another section.
         *
         * [hsns], [vats] and [units] are optional and default to empty - a caller that
         * has not looked them up (or a build predating these parameters) still gets a
         * working screen, just without HSN, VAT or a unit on it. Empty/zero in a slot
         * means "this item has none". [vatEnabled] defaults off for the same reason.
         *
         * [chargeNames]/[chargeAmounts]/[chargeTypes] are the shop's own extra charges
         * - Parcel Charge among them - already worked out and filtered by this order's
         * type on the Orders screen (see RestaurantOrdersFragment.computeBill). Empty
         * by default so a caller that has not looked them up still gets a working
         * screen, just without a charges line.
         *
         * [discount], [lineDiscounts] and [discountPreTax] carry Tax Settings' discount
         * across, worked out once on the Orders screen by CartMath rather than a second
         * time here. [lineDiscounts] is each line's share against its raw pre-tax base -
         * the shape BillPricing takes - and is what makes a PRE-TAX discount reduce the
         * taxable value line by line; [discount] is the whole-bill figure a POST-TAX one
         * comes off after tax. Zero by default, which prices exactly as this screen did
         * before it knew about discounts at all.
         */
        fun newInstance(
            orderId: Long, table: String, section: String, customer: String,
            names: ArrayList<String>, qtys: DoubleArray, rates: DoubleArray,
            cgsts: DoubleArray, sgsts: DoubleArray, serviceRate: Double,
            gstEnabled: Boolean, inclusive: Boolean, hsns: ArrayList<String> = arrayListOf(),
            vats: DoubleArray = DoubleArray(0), vatEnabled: Boolean = false,
            units: ArrayList<String> = arrayListOf(),
            chargeNames: ArrayList<String> = arrayListOf(),
            chargeAmounts: DoubleArray = DoubleArray(0),
            chargeTypes: ArrayList<String> = arrayListOf(),
            discount: Double = 0.0,
            lineDiscounts: DoubleArray = DoubleArray(0),
            discountPreTax: Boolean = true
        ): RestaurantCheckoutFragment = RestaurantCheckoutFragment().apply {
            arguments = android.os.Bundle().apply {
                putLong(ARG_ORDER_ID, orderId)
                putString(ARG_TABLE, table)
                putString(ARG_SECTION, section)
                putString(ARG_CUSTOMER, customer)
                putStringArrayList(ARG_NAMES, names)
                putDoubleArray(ARG_QTYS, qtys)
                putDoubleArray(ARG_RATES, rates)
                putDoubleArray(ARG_CGSTS, cgsts)
                putDoubleArray(ARG_SGSTS, sgsts)
                putDoubleArray(ARG_VATS, vats)
                putStringArrayList(ARG_HSNS, hsns)
                putStringArrayList(ARG_UNITS, units)
                putDouble(ARG_SERVICE_RATE, serviceRate)
                putBoolean(ARG_GST_ON, gstEnabled)
                putBoolean(ARG_VAT_ON, vatEnabled)
                putBoolean(ARG_INCLUSIVE, inclusive)
                putStringArrayList(ARG_CHARGE_NAMES, chargeNames)
                putDoubleArray(ARG_CHARGE_AMOUNTS, chargeAmounts)
                putStringArrayList(ARG_CHARGE_TYPES, chargeTypes)
                putDouble(ARG_DISCOUNT, discount)
                putDoubleArray(ARG_LINE_DISCOUNTS, lineDiscounts)
                putBoolean(ARG_DISCOUNT_PRE_TAX, discountPreTax)
            }
        }
    }
}
