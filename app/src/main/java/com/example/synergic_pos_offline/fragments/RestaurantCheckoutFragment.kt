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
    private val taxEnabled get() = arguments?.getBoolean(ARG_TAX_ON) ?: true

    /**
     * Tax Settings' discount, as the Orders screen worked it out - see CartMath.
     *
     * Not recomputed here, for the same reason the charges above are not: the figure
     * the operator was quoted on the cart page is the figure that has to be paid, and
     * a screen that works it out a second time can only ever agree by coincidence.
     *
     * [billDiscount] is the whole-bill amount, in the RAW shape the printed slip's own
     * per-line discount_amount figures are - see [lineDiscounts] - stored, not shown;
     * [discountDisplay] is what the Discount row on THIS screen actually shows, which
     * can differ under a post-tax item-wise discount (see BillBreakdown.discountDisplay
     * on the Orders screen, which this is read from verbatim - the two rows have to
     * agree by construction, not by two screens doing the same sum). [lineDiscounts]
     * is each line's share of [billDiscount] against that line's raw pre-tax base.
     * Which of the two does the work depends on [discountPreTax]: pre-tax, the
     * per-line shares reduce the taxable value, and the bill figure is already inside
     * them; post-tax, the lines are taxed in full and the bill figure comes off once,
     * after tax.
     */
    private val billDiscount get() = arguments?.getDouble(ARG_DISCOUNT) ?: 0.0
    private val discountDisplay get() = arguments?.getDouble(ARG_DISCOUNT_DISPLAY) ?: billDiscount
    private val lineDiscounts: DoubleArray get() = arguments?.getDoubleArray(ARG_LINE_DISCOUNTS) ?: DoubleArray(0)
    private val discountPreTax get() = arguments?.getBoolean(ARG_DISCOUNT_PRE_TAX) ?: true

    /** This line's share of the discount, or 0 where none was passed. */
    private fun discountFor(index: Int): Double = lineDiscounts.getOrElse(index) { 0.0 }

    /**
     * The whole bill, exactly as the Orders screen's own [CartMath.totals] worked it
     * out - CGST, SGST, VAT, and the final payable and round-off, already rounded.
     *
     * PASTED here rather than re-priced: this screen used to run every line back
     * through BillPricing itself and add the pieces up a second time, and the two
     * additions drifted the moment either side gained a rule the other had not been
     * given too - most recently, this screen's own copy never learned about a
     * post-tax item-wise discount's further deduction (CartMath.Totals.itemwiseDiscount)
     * at all, so a discounted table was quoted 1067 on the cart page and asked to pay
     * 1073 here. Reading the Orders screen's own answer instead of a second guess at
     * it is the only way the two are guaranteed to agree - see [payableTotal].
     */
    private val cgst get() = arguments?.getDouble(ARG_CGST) ?: 0.0
    private val sgst get() = arguments?.getDouble(ARG_SGST) ?: 0.0
    private val vat get() = arguments?.getDouble(ARG_VAT) ?: 0.0

    /** The final amount due, already rounded - what Confirm Payment actually charges. */
    private val payableTotal get() = arguments?.getDouble(ARG_PAYABLE) ?: 0.0

    /** The adjustment [payableTotal] applied to reach the rounded figure - 0 when off. */
    private val roundOffAmount get() = arguments?.getDouble(ARG_ROUND_OFF) ?: 0.0

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
        val subtotal = lines.sumOf { it.qty * it.rate }
        // Flat section service charge (₹), applied only to a non-empty order.
        val service = if (subtotal > 0.0) serviceRate else 0.0

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
            // Round off is the Orders screen's own figure too - see [roundOffAmount] -
            // not reworked out here against a total this screen no longer builds.
            discount = billDiscount,
            roundOff = roundOffAmount,
            netAmount = payableTotal,
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
                (tendered - payableTotal).coerceAtLeast(0.0)
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

    /**
     * Draws the line rows and the totals block - the totals PASTED from the Orders
     * screen's own [CartMath.totals] (see [cgst] and its neighbours), not re-priced
     * here. A line's own name/qty/rate/amount are plain facts about the line and
     * cannot drift regardless, so those alone are still worked out on this screen.
     */
    private fun populateItems(root: View) {
        val container = root.findViewById<LinearLayout>(R.id.llCheckoutItems)
        val inflater = LayoutInflater.from(requireContext())
        val subtotal = lines.sumOf { it.qty * it.rate }
        lines.forEach { l ->
            val row = inflater.inflate(R.layout.item_rest_checkout_line, container, false)
            row.findViewById<TextView>(R.id.tvLineName).text = l.name
            row.findViewById<TextView>(R.id.tvLineQty).text = qtyText(l.qty)
            row.findViewById<TextView>(R.id.tvLineRate).text = money(l.rate)
            row.findViewById<TextView>(R.id.tvLineAmount).text = money(l.qty * l.rate)
            container.addView(row)
        }
        // Flat section service charge (₹), applied only to a non-empty order - the
        // same flat figure RestaurantOrdersFragment.computeBill() works out.
        val service = if (subtotal > 0.0) serviceRate else 0.0
        total = payableTotal
        root.findViewById<TextView>(R.id.tvSubtotal).text = "₹ ${money(subtotal)}"
        // The discount, said out loud - the Orders screen's own DISPLAYED figure
        // (see [discountDisplay]), so this row and the panel's Note & tax details
        // row are the same number by construction, not by two screens agreeing.
        //
        // Signed, and hidden at zero, exactly as the panel does it.
        root.findViewById<View>(R.id.rowCheckoutDiscount).visibility =
            if (discountDisplay > 0.0) View.VISIBLE else View.GONE
        root.findViewById<TextView>(R.id.tvCheckoutDiscount).text = "- ₹ ${money(discountDisplay)}"
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
        private const val ARG_TAX_ON = "tax_on"
        private const val ARG_INCLUSIVE = "inclusive"
        private const val ARG_CHARGE_NAMES = "charge_names"
        private const val ARG_CHARGE_AMOUNTS = "charge_amounts"
        private const val ARG_CHARGE_TYPES = "charge_types"
        private const val ARG_DISCOUNT = "discount"
        private const val ARG_DISCOUNT_DISPLAY = "discount_display"
        private const val ARG_LINE_DISCOUNTS = "line_discounts"
        private const val ARG_DISCOUNT_PRE_TAX = "discount_pre_tax"
        private const val ARG_CGST = "cgst"
        private const val ARG_SGST = "sgst"
        private const val ARG_VAT = "vat"
        private const val ARG_PAYABLE = "payable"
        private const val ARG_ROUND_OFF = "round_off"

        /**
         * Builds a checkout for the running order [orderId] on [table] in [section],
         * carrying its items and tax. The order id travels with it so the paid result
         * settles this order rather than the same-numbered table in another section.
         *
         * [hsns], [vats] and [units] are optional and default to empty - a caller that
         * has not looked them up (or a build predating these parameters) still gets a
         * working screen, just without HSN, VAT or a unit on it. Empty/zero in a slot
         * means "this item has none".
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
         * before it knew about discounts at all. [discountDisplay] is what the Discount
         * row on THIS screen actually shows - see the property of the same name.
         *
         * [cgst]/[sgst]/[vat]/[payableTotal]/[roundOffAmount] are
         * `RestaurantOrdersFragment.computeBill()`'s own [CartMath.totals] answer,
         * pasted rather than re-derived - see the properties of the same names for why:
         * a second calculation here, however carefully mirrored, has already twice
         * drifted from the first as each gained a rule the other had not. Zero by
         * default, which is wrong for any order actually carrying tax or a total - callers
         * must pass the Orders screen's real figures; the default only keeps a build
         * predating these parameters from failing to compile.
         */
        fun newInstance(
            orderId: Long, table: String, section: String, customer: String,
            names: ArrayList<String>, qtys: DoubleArray, rates: DoubleArray,
            cgsts: DoubleArray, sgsts: DoubleArray, serviceRate: Double,
            taxEnabled: Boolean, inclusive: Boolean, hsns: ArrayList<String> = arrayListOf(),
            vats: DoubleArray = DoubleArray(0),
            units: ArrayList<String> = arrayListOf(),
            chargeNames: ArrayList<String> = arrayListOf(),
            chargeAmounts: DoubleArray = DoubleArray(0),
            chargeTypes: ArrayList<String> = arrayListOf(),
            discount: Double = 0.0,
            discountDisplay: Double = discount,
            lineDiscounts: DoubleArray = DoubleArray(0),
            discountPreTax: Boolean = true,
            cgst: Double = 0.0,
            sgst: Double = 0.0,
            vat: Double = 0.0,
            payableTotal: Double = 0.0,
            roundOffAmount: Double = 0.0
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
                putBoolean(ARG_TAX_ON, taxEnabled)
                putBoolean(ARG_INCLUSIVE, inclusive)
                putStringArrayList(ARG_CHARGE_NAMES, chargeNames)
                putDoubleArray(ARG_CHARGE_AMOUNTS, chargeAmounts)
                putStringArrayList(ARG_CHARGE_TYPES, chargeTypes)
                putDouble(ARG_DISCOUNT, discount)
                putDouble(ARG_DISCOUNT_DISPLAY, discountDisplay)
                putDoubleArray(ARG_LINE_DISCOUNTS, lineDiscounts)
                putBoolean(ARG_DISCOUNT_PRE_TAX, discountPreTax)
                putDouble(ARG_CGST, cgst)
                putDouble(ARG_SGST, sgst)
                putDouble(ARG_VAT, vat)
                putDouble(ARG_PAYABLE, payableTotal)
                putDouble(ARG_ROUND_OFF, roundOffAmount)
            }
        }
    }
}
