package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.AppSettingsDao
import com.example.synergic_pos_offline.database.BillDao
import com.example.synergic_pos_offline.database.BillSettingsDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.CustomerDao
import com.example.synergic_pos_offline.database.TaxSettingsDao
import com.example.synergic_pos_offline.utils.CustomerCardDialog
import com.example.synergic_pos_offline.utils.BillReceiptRenderer
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.GstCalculator
import com.example.synergic_pos_offline.utils.PrinterSetup
import com.example.synergic_pos_offline.utils.ProductEntryDialog
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.ThemeManager
import com.example.synergic_pos_offline.utils.ThermalPrinter
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import android.widget.ArrayAdapter
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-process hand-off of the current sale from billing to checkout. */
object CheckoutSession {
    data class Line(
        val name: String, val sku: String, var price: Double, var qty: Int,
        val productId: Long? = null,
        /** Per-product tax rates from md_product_rates, carried so checkout and the
         *  bill tax each line at the rate its product actually charges. [vatRate] is
         *  only meaningful when Tax Settings has VAT active instead of GST. */
        val cgstRate: Double = 0.0,
        val sgstRate: Double = 0.0,
        val vatRate: Double = 0.0,
        /** The rate's own pre-configured discount (Tax Settings' item-wise discount).
         *  [itemDiscType] is "P"/"A" (percent/amount) or null when none is configured. */
        val itemDiscValue: Double = 0.0,
        val itemDiscType: String? = null
    )
    data class HeldBill(
        val label: String, val lines: List<Line>,
        val discountMode: GstCalculator.DiscountMode = GstCalculator.DiscountMode.PERCENT,
        val discountValue: Double = 0.0,
        val coupon: Boolean,
        val customerName: String? = null,
        val customerPhone: String? = null,
        val customerData: Map<String, Any?>? = null
    )

    var lines: MutableList<Line> = mutableListOf()

    /** The whole-bill discount entered on the cart page, carried through to checkout. */
    var discountMode: GstCalculator.DiscountMode = GstCalculator.DiscountMode.PERCENT
    var discountValue: Double = 0.0
    var customerName: String? = null
    var customerPhone: String? = null

    /** md_customers.id of the attached customer, when one was picked in billing. */
    var customerId: Long? = null
    // The order number is not held here: it is derived from the bills already
    // saved, so it survives a restart and cannot drift from what gets printed.
    var heldOrders: MutableList<HeldBill> = mutableListOf()
    var restoredBill: HeldBill? = null

    /**
     * Set when a sale completes and the operator chooses to start another. The
     * billing screen consumes it on resume and resets itself. A flag rather than a
     * fragment result because the billing view is destroyed while checkout is on
     * top, so there is nothing listening at the moment the sale finishes.
     */
    var startFreshSale: Boolean = false
}

/**
 * POS checkout screen, modelled on the shared design: a bill preview on the left
 * (editable line items / receipt view) and a payment panel on the right (mode,
 * cash / card / wallet / split, receipt delivery, complete). Responsive width
 * for the payment panel.
 */
class PosCheckoutFragment : Fragment(), TitledScreen {

    override val screenTitle = "Checkout"

    private enum class Method { CASH, CREDIT, CARD, ONLINE }

    // Working copy of the sale (edits here don't touch billing).
    private val lines = CheckoutSession.lines.map { it.copy() }.toMutableList()

    // Whole-bill discount entered on the cart page; read-only here.
    private val discountMode = CheckoutSession.discountMode
    private val discountValue = CheckoutSession.discountValue
    private val taxSettings by lazy { TaxSettingsDao(requireContext()).load() }

    /** Whether the whole-bill discount comes off before GST (Tax Settings' Discount Position). */
    private val discountPreTax by lazy { taxSettings.discountPosition == TaxSettingsDao.DiscountPosition.PRE_TAX }

    /** GST and VAT are mutually exclusive in Tax Settings; NONE when neither is on. */
    private val taxRegime by lazy { GstCalculator.regimeFor(taxSettings.gstEnabled, taxSettings.vatEnabled) }

    /** Whether the listed price already includes whichever tax is active. */
    private val taxInclusive by lazy {
        when (taxRegime) {
            GstCalculator.TaxRegime.GST -> taxSettings.gstMode == TaxSettingsDao.GstMode.INCLUSIVE
            GstCalculator.TaxRegime.VAT -> taxSettings.vatMode == TaxSettingsDao.GstMode.INCLUSIVE
            GstCalculator.TaxRegime.NONE -> false
        }
    }

    /** Item-wise discount: each product's own pre-configured discount applies instead
     *  of the whole-bill discount carried over from the cart page. */
    private val itemwiseDiscountActive by lazy {
        taxSettings.discountEnabled && taxSettings.discountType == TaxSettingsDao.DiscountType.ITEM_WISE
    }

    private var editMode = true
    private var method = Method.CASH
    private var accent = 0

    /** Loaded once when the screen opens; App Settings doesn't change mid-checkout. */
    private lateinit var appSettings: AppSettingsDao.AppSettings

    /**
     * Whether to ask for a tendered amount and show change for a cash sale. Off
     * whenever "Cash Reception" itself is off, and also whenever "Payment Mode" is
     * off - with no mode picker there is nothing to switch away from cash, so the
     * simplest checkout just takes the exact amount.
     */
    private fun cashReceptionEnabled() = appSettings.paymentMode && appSettings.cashReception

    private var creditCustomerName = ""
    private var creditCustomerPhone = ""
    private var creditCustomerAddress = ""
    private var creditCustomerGstin = ""

    private lateinit var root: View
    private val clockHandler = Handler(Looper.getMainLooper())
    private lateinit var clockRunnable: Runnable

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_pos_checkout, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view
        val ctx = requireContext()
        accent = ThemeManager.getThemeColor(ctx)
        val density = resources.displayMetrics.density

        appSettings = AppSettingsDao(ctx).load()
        // No mode picker means there is nothing to pay by but cash.
        if (!appSettings.paymentMode) method = Method.CASH
        id<View>(R.id.sectionPaymentModePicker).visibility =
            if (appSettings.paymentMode) View.VISIBLE else View.GONE

        // Header
        id<TextView>(R.id.tvOrder).text = BillDao(ctx).nextBillNumber()
        id<TextView>(R.id.tvCustName).text = CheckoutSession.customerName ?: "Guest"
        id<TextView>(R.id.tvCustSub).text = CheckoutSession.customerPhone ?: "Walk-in"
        id<TextView>(R.id.tvCustInitials).text =
            (CheckoutSession.customerName ?: "Guest").split(" ")
                .mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString("")

        id<MaterialButton>(R.id.btnBackBilling).setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        id<MaterialButton>(R.id.btnHold).setOnClickListener { onHold() }
        id<MaterialButton>(R.id.btnHeld).setOnClickListener { showHeldDialog() }
        id<android.widget.ImageButton>(R.id.btnCustInfo).setOnClickListener { showCustomerDetails() }

        // Accent bars
        id<View>(R.id.barLeftTotal).setBackgroundColor(accent)
        id<View>(R.id.barAmountDue).setBackgroundColor(accent)

        // Mode toggle
        id<MaterialButton>(R.id.btnModeEdit).setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        id<MaterialButton>(R.id.btnModeReceipt).setOnClickListener { setMode(false) }

        // Add line
        setupAddItem()

        // Payment mode tiles
        id<MaterialButton>(R.id.btnCash).setOnClickListener { setMethod(Method.CASH) }
        id<MaterialButton>(R.id.btnCredit).setOnClickListener { setMethod(Method.CREDIT) }
        id<MaterialButton>(R.id.btnCard).setOnClickListener { setMethod(Method.CARD) }
        id<MaterialButton>(R.id.btnOnline).setOnClickListener { setMethod(Method.ONLINE) }

        // Cash inputs
        id<TextInputEditText>(R.id.etCash).addTextChangedListener(watcher { refreshTotals() })

        // Credit inputs
        id<TextInputEditText>(R.id.etCredit).addTextChangedListener(watcher { refreshTotals() })

        // Complete
        id<MaterialButton>(R.id.btnComplete).setOnClickListener { complete() }

        // Apply styles to payment buttons BEFORE theme to avoid conflicts
        applyTileStyles()

        // Theme everything
        ThemeManager.applyTheme(view)

        // Toggles and selection states: Manually override the global theme for the ACTIVE button.
        setMode(editMode)
        setMethod(method)
        applyTileStyles()
        updateHeldButton()

        // Render items and calculate totals
        renderItems()
        refreshTotals()

        clockRunnable = object : Runnable {
            override fun run() {
                id<TextView>(R.id.tvClock).text = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                clockHandler.postDelayed(this, 30_000)
            }
        }
        clockRunnable.run()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clockHandler.removeCallbacks(clockRunnable)
    }

    override fun onResume() {
        super.onResume()
        updateHeldButton()
        // MainActivity re-themes the whole window in onFragmentResumed (which fires
        // after onResume), overriding the payment-tile selection styling. Defer a
        // re-apply so the selected/unselected tile styles win.
        root.post {
            applyTileStyles()
            updateHeldButton()
        }
    }

    // ---- Left: items -------------------------------------------------------

    private fun renderItems() {
        val ll = id<LinearLayout>(R.id.llItems)
        ll.removeAllViews()
        // The DISCOUNT column (and its header) is item-wise only; a bill-wise sale
        // has no per-line discount, so the list reads exactly as it did before.
        val showDiscount = itemwiseDiscountActive
        id<View>(R.id.llItemsHeader).visibility = if (showDiscount) View.VISIBLE else View.GONE
        lines.forEachIndexed { index, line ->
            val row = layoutInflater.inflate(R.layout.item_checkout_line, ll, false)
            // Serial number only where the header labels one, so a bill-wise sale's
            // list reads exactly as it did before.
            row.findViewById<TextView>(R.id.tvName).text =
                if (showDiscount) "${index + 1}  ${line.name}" else line.name
            row.findViewById<TextView>(R.id.tvQty).text = "Qty: ${line.qty}"
            // NET AMT: the line's gross less its discount, as on the receipt.
            row.findViewById<TextView>(R.id.tvPrice).text = money(lineNetAmount(line))
            row.findViewById<TextView>(R.id.tvDiscount).apply {
                visibility = if (showDiscount) View.VISIBLE else View.GONE
                text = lineDiscountText(line)
            }

            ThemeManager.applyTheme(row)
            ll.addView(row)
        }
    }

    /**
     * Catalog behind the "Add item" box. Loaded once on first use rather than at
     * screen start, since most checkouts never add a line.
     */
    private val catalog: List<ProductEntryDialog.Product> by lazy { loadCatalog() }

    private fun loadCatalog(): List<ProductEntryDialog.Product> {
        val list = mutableListOf<ProductEntryDialog.Product>()
        try {
            val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
            db.rawQuery(
                """
                SELECT p.id, p.product_name, p.bar_code, p.hsn_code,
                       c.category_name, r.rate, r.cgst_rate, r.sgst_rate, r.vat_rate,
                       r.discount, r.discount_type
                FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p
                LEFT JOIN ${DatabaseHelper.Tables.MD_CATEGORY} c ON c.id = p.category_id
                LEFT JOIN ${DatabaseHelper.Tables.MD_PRODUCT_RATES} r ON r.id = (
                    SELECT id FROM ${DatabaseHelper.Tables.MD_PRODUCT_RATES}
                    WHERE product_id = p.id ORDER BY "default" DESC, id ASC LIMIT 1
                )
                ORDER BY p.product_name COLLATE NOCASE
                """.trimIndent(),
                null
            ).use { c ->
                while (c.moveToNext()) {
                    list.add(
                        ProductEntryDialog.Product(
                            id = c.getLong(0).toString(),
                            name = c.getString(1)?.takeIf { it.isNotBlank() } ?: "Item",
                            sku = c.getString(2).orEmpty(),
                            category = c.getString(4).orEmpty(),
                            price = if (c.isNull(5)) 0.0 else c.getDouble(5),
                            hsn = c.getString(3)?.takeIf { it.isNotBlank() } ?: "0000",
                            cgst = if (c.isNull(6)) 0.0 else c.getDouble(6),
                            sgst = if (c.isNull(7)) 0.0 else c.getDouble(7),
                            vat = if (c.isNull(8)) 0.0 else c.getDouble(8),
                            discValue = if (c.isNull(9)) 0.0 else c.getDouble(9),
                            discType = c.getString(10)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PosCheckoutFragment", "Could not load the product catalog", e)
        }
        return list
    }

    /**
     * Wires the "Add item" box: typing filters the catalog, picking a suggestion
     * opens the same product dialog the billing screen uses, and confirming adds
     * the line here.
     */
    private fun setupAddItem() {
        val input = id<MaterialAutoCompleteTextView>(R.id.actAddItem)
        val adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_list_item_1, catalog.map { it.name }
        )
        input.setAdapter(adapter)

        input.setOnItemClickListener { _, _, position, _ ->
            // The adapter filters, so map the tapped row back through it rather than
            // indexing the unfiltered catalog.
            val name = adapter.getItem(position)
            val product = catalog.firstOrNull { it.name == name }
            input.setText("")
            if (product == null) toast("Product not found") else showAddDialog(product)
        }
    }

    private fun showAddDialog(product: ProductEntryDialog.Product) {
        ProductEntryDialog.show(
            context = requireContext(),
            inflater = layoutInflater,
            product = product,
            confirmLabel = "Add to bill",
            taxRegime = taxRegime,
            taxInclusive = taxInclusive,
            itemwiseDiscountActive = itemwiseDiscountActive,
            discountPreTax = discountPreTax
        ) { qty, rate ->
            lines.add(
                CheckoutSession.Line(
                    name = product.name,
                    sku = product.sku,
                    price = rate,
                    qty = qty,
                    productId = product.id.toLongOrNull(),
                    cgstRate = product.cgst,
                    sgstRate = product.sgst,
                    vatRate = product.vat,
                    itemDiscValue = product.discValue,
                    itemDiscType = product.discType
                )
            )
            renderItems()
            refreshTotals()
        }
    }


    // ---- Customer ----------------------------------------------------------

    /** Whatever is on file for the customer this sale is being billed to. */
    private fun currentCustomer(): CustomerDao.Customer? {
        val phone = creditCustomerPhone.ifEmpty { CheckoutSession.customerPhone.orEmpty() }
        return runCatching { CustomerDao(requireContext()).findByPhone(phone) }.getOrNull()
    }

    /** Shows what is on file for the customer, behind the info button. */
    private fun showCustomerDetails() {
        val onFile = currentCustomer()
        val name = onFile?.name?.takeIf { it.isNotBlank() }
            ?: creditCustomerName.ifEmpty { CheckoutSession.customerName.orEmpty() }
        val phone = onFile?.phone?.takeIf { it.isNotBlank() }
            ?: creditCustomerPhone.ifEmpty { CheckoutSession.customerPhone.orEmpty() }

        if (name.isBlank() && phone.isBlank()) {
            toast("No customer on this sale")
            return
        }

        CustomerCardDialog.show(
            context = requireContext(),
            inflater = layoutInflater,
            customer = CustomerCardDialog.Customer(
                name = name,
                phone = phone,
                // Fall back to what was typed in the credit dialog when the sale is
                // being billed to someone not yet filled in on the master.
                address = onFile?.address?.takeIf { it.isNotBlank() } ?: creditCustomerAddress,
                gstin = onFile?.gstin?.takeIf { it.isNotBlank() } ?: creditCustomerGstin,
                creditEnabled = onFile?.creditEnabled ?: false,
                creditLimit = onFile?.creditLimit ?: 0.0,
                balance = onFile?.balance ?: 0.0
            ),
            status = if (onFile == null) "NOT ON FILE" else "BILLING TO",
            note = if (onFile == null) {
                "This customer is not in the customer master yet."
            } else null
        )
    }

    /**
     * A credit sale has to be attributable, so it needs a name and a phone.
     *
     * Both are usually already known - the sale carries a customer from billing - so
     * the details are pulled from the master and used as they are. The dialog only
     * appears when something is actually missing, prefilled with whatever is on file
     * so the operator types the gap rather than the whole record.
     */
    private fun ensureCreditCustomer() {
        // Already captured earlier in this checkout.
        if (creditCustomerName.isNotBlank() && creditCustomerPhone.isNotBlank()) {
            updateHeaderWithCustomer()
            return
        }

        val onFile = currentCustomer()
        if (onFile != null && onFile.name.isNotBlank() && onFile.phone.isNotBlank()) {
            creditCustomerName = onFile.name
            creditCustomerPhone = onFile.phone
            creditCustomerAddress = onFile.address
            creditCustomerGstin = onFile.gstin
            updateHeaderWithCustomer()
            toast("Credit billed to ${onFile.name}")
            return
        }

        // Prefill the gaps we can, then ask for the rest.
        if (creditCustomerPhone.isBlank()) {
            creditCustomerPhone = onFile?.phone?.takeIf { it.isNotBlank() }
                ?: CheckoutSession.customerPhone.orEmpty()
        }
        if (creditCustomerName.isBlank()) {
            creditCustomerName = onFile?.name?.takeIf { it.isNotBlank() }
                ?: CheckoutSession.customerName.orEmpty()
        }
        if (creditCustomerAddress.isBlank()) creditCustomerAddress = onFile?.address.orEmpty()
        if (creditCustomerGstin.isBlank()) creditCustomerGstin = onFile?.gstin.orEmpty()

        showCreditCustomerDialog()
    }

    /**
     * Writes details captured for a credit sale back to the customer master, so the
     * same gaps are not asked for again on the next visit. Only fills blanks - it
     * never overwrites something already recorded against the customer.
     */
    private fun saveCreditCustomerDetails() {
        val onFile = currentCustomer() ?: return
        val merged = onFile.copy(
            name = onFile.name.ifBlank { creditCustomerName },
            address = onFile.address.ifBlank { creditCustomerAddress },
            gstin = onFile.gstin.ifBlank { creditCustomerGstin }
        )
        if (merged == onFile) return
        runCatching { CustomerDao(requireContext()).update(onFile.id, merged) }
            .onFailure { android.util.Log.e("PosCheckoutFragment", "Could not save details", it) }
    }

    // ---- Mode / method / receipt selection --------------------------------

    private fun setMode(edit: Boolean) {
        editMode = edit
        id<View>(R.id.scrollEdit).visibility = if (edit) View.VISIBLE else View.GONE
        id<View>(R.id.scrollReceipt).visibility = if (edit) View.GONE else View.VISIBLE
        val e = id<MaterialButton>(R.id.btnModeEdit)
        val r = id<MaterialButton>(R.id.btnModeReceipt)
        if (edit) { 
            styleFilled(e)
            styleOutlined(r) 
        } else { 
            styleOutlined(e)
            styleFilled(r) 
        }
        if (!edit) renderReceipt()
    }

    private fun setMethod(m: Method) {
        method = m
        id<View>(R.id.sectionCash).visibility = if (m == Method.CASH) View.VISIBLE else View.GONE
        if (m == Method.CASH) {
            val exact = !cashReceptionEnabled()
            id<View>(R.id.cashReceptionFields).visibility = if (exact) View.GONE else View.VISIBLE
            id<View>(R.id.sectionCashExact).visibility = if (exact) View.VISIBLE else View.GONE
        }
        id<View>(R.id.sectionCredit).visibility = if (m == Method.CREDIT) View.VISIBLE else View.GONE
        id<View>(R.id.sectionTerminal).visibility =
            if (m == Method.CARD) View.VISIBLE else View.GONE
        id<View>(R.id.sectionOnline).visibility = if (m == Method.ONLINE) View.VISIBLE else View.GONE
        val title = titleFor(m)

        if (m == Method.CARD) {
            id<TextView>(R.id.tvTerminalTitle).text = "$title terminal connected"
            id<TextView>(R.id.tvTerminalMsg).text =
                "Present $title on the reader for ${money(total())}, then confirm below."
        }

        id<TextView>(R.id.tvPayingBy).text = "Paying by $title"
        applyTileStyles()
        refreshTotals()

        if (m == Method.CREDIT) {
            ensureCreditCustomer()
        }
    }


    private fun applyTileStyles() {
        listOf(
            R.id.btnCash to Method.CASH, R.id.btnCredit to Method.CREDIT,
            R.id.btnCard to Method.CARD, R.id.btnOnline to Method.ONLINE
        ).forEach { (bId, value) ->
            val b = id<MaterialButton>(bId)
            if (value == method) {
                styleOutlined(b)
            } else {
                styleFilled(b)
            }
        }
    }

    // ---- Totals ------------------------------------------------------------

    private fun subtotal() = lines.sumOf { it.price * it.qty }

    /** The whole-bill discount carried from the cart page. Item-wise discount
     *  replaces this mechanism entirely - each line prices its own discount in
     *  [lineTax] - so there is nothing left for a whole-bill figure to add. */
    private fun discountAmt(): Double {
        if (itemwiseDiscountActive) return 0.0
        return GstCalculator.discountAmount(discountBase(), discountMode, discountValue)
    }

    /**
     * What a whole-bill discount is a percentage *of*: the bill with its tax already
     * on, since a bill-wise discount is always taken off after tax. 20% off a 110.00
     * sale carrying 5.50 of GST is 23.10, not 22.00.
     *
     * Under inclusive pricing this is the listed subtotal itself - the price already
     * carries its tax - so the two only differ when tax is added on top.
     *
     * Safe from recursing back into [discountAmt]: a post-tax discount leaves every
     * line taxed in full, so the lines are priced here with no discount at all.
     */
    private fun discountBase(): Double {
        val sub = subtotal()
        return lines.sumOf {
            val t = lineTax(it, sub, 0.0)
            t.taxable + t.cgst + t.sgst + t.vat
        }
    }

    /** The discount as a percentage of what it was taken off, purely for
     *  display/records - the actual math always works from [discountAmt], whichever
     *  way it was entered. */
    private fun discountPctForDisplay(): Double {
        val base = discountBase()
        return if (base > 0) discountAmt() / base * 100.0 else 0.0
    }

    /**
     * A line's taxable value, CGST, SGST and VAT - see [lineTax]. [discount] is
     * the further amount still to come off to reach the line's actual sale price
     * under a post-tax, exclusive item-wise discount; zero in every other case.
     */
    private data class LineTax(val taxable: Double, val cgst: Double, val sgst: Double, val vat: Double, val discount: Double = 0.0) {
        /**
         * Every figure taken to the nearest paisa - the precision it is reported at.
         * Totals are summed from these, not from the raw fractions behind them, so
         * the bill's parts add up to the total printed under them: two halves of a
         * 5% slab on 106.70 report 2.67 each, and the total is 112.04, not the
         * 112.035 the unrounded 2.6675s would quietly render as 112.03.
         */
        fun toPaise() = LineTax(
            BillRounding.toPaise(taxable), BillRounding.toPaise(cgst),
            BillRounding.toPaise(sgst), BillRounding.toPaise(vat), BillRounding.toPaise(discount)
        )
    }

    /** [lineTaxRaw], with every figure taken to the paisa it is reported at. */
    private fun lineTax(
        line: CheckoutSession.Line,
        grossSubtotal: Double = subtotal(),
        discAmt: Double = discountAmt()
    ): LineTax = lineTaxRaw(line, grossSubtotal, discAmt).toPaise()

    /**
     * Resolves a line's taxable value and tax against the active regime (GST or
     * VAT, whichever Tax Settings has switched on) and whether the listed price
     * already includes that tax.
     *
     * Under item-wise discount, the line prices itself from its own pre-configured
     * discount (snapshotted at add-time) - see [GstCalculator.priceItem] -
     * ignoring [grossSubtotal]/[discAmt] entirely, since the whole-bill discount
     * carried from the cart page is always zero whenever item-wise is active. For
     * a post-tax, exclusive item-wise discount, [GstCalculator.ItemPricing.discount]
     * carries a further amount the caller still has to take off - taxable/cgst/
     * sgst/vat there are the GST-compliant, pre-discount reporting figures.
     *
     * Otherwise, the whole-bill discount is spread over the line and taken off
     * before tax only when Tax Settings has the discount pre-tax; applied
     * post-tax, the line is taxed on its full amount and the discount is left for
     * the caller to take off the bill's total once, separately ([LineTax.discount]
     * is always zero here - that whole-bill deduction happens once, at the bill
     * level, not per line).
     */
    private fun lineTaxRaw(line: CheckoutSession.Line, grossSubtotal: Double, discAmt: Double): LineTax {
        val gross = line.price * line.qty
        val rate = when (taxRegime) {
            GstCalculator.TaxRegime.GST -> line.cgstRate + line.sgstRate
            GstCalculator.TaxRegime.VAT -> line.vatRate
            GstCalculator.TaxRegime.NONE -> 0.0
        }

        if (itemwiseDiscountActive && line.itemDiscValue > 0.0 && line.itemDiscType != null) {
            val mode = if (line.itemDiscType == "A") GstCalculator.DiscountMode.AMOUNT else GstCalculator.DiscountMode.PERCENT
            val pricing = GstCalculator.priceItem(gross, rate, taxInclusive, discountPreTax, mode, line.itemDiscValue)
            return when (taxRegime) {
                GstCalculator.TaxRegime.GST -> LineTax(
                    pricing.taxable,
                    GstCalculator.taxAmount(pricing.taxable, line.cgstRate),
                    GstCalculator.taxAmount(pricing.taxable, line.sgstRate),
                    0.0,
                    pricing.discount
                )
                GstCalculator.TaxRegime.VAT -> LineTax(
                    pricing.taxable, 0.0, 0.0, GstCalculator.taxAmount(pricing.taxable, line.vatRate), pricing.discount
                )
                GstCalculator.TaxRegime.NONE -> LineTax(pricing.taxable, 0.0, 0.0, 0.0, pricing.discount)
            }
        }

        val rawBase = GstCalculator.taxableBase(gross, rate, taxInclusive)
        val taxable = if (discountPreTax) {
            GstCalculator.taxableValueSpread(rawBase, gross, grossSubtotal, discAmt)
        } else {
            rawBase
        }
        return when (taxRegime) {
            GstCalculator.TaxRegime.GST -> LineTax(
                taxable,
                GstCalculator.taxAmount(taxable, line.cgstRate),
                GstCalculator.taxAmount(taxable, line.sgstRate),
                0.0
            )
            GstCalculator.TaxRegime.VAT -> LineTax(taxable, 0.0, 0.0, GstCalculator.taxAmount(taxable, line.vatRate))
            GstCalculator.TaxRegime.NONE -> LineTax(taxable, 0.0, 0.0, 0.0)
        }
    }

    /** "GST", "VAT" or plain "TAX" (neither switched on), matching the active regime. */
    private fun taxLabelText(): String = when (taxRegime) {
        GstCalculator.TaxRegime.GST -> "GST"
        GstCalculator.TaxRegime.VAT -> "VAT"
        GstCalculator.TaxRegime.NONE -> "TAX"
    }

    /**
     * What this line's item-wise discount took off, stated against the listed price
     * it was configured against - so "3% off 100" reads as 3.00 whether the price
     * is inclusive or exclusive of tax.
     *
     * Deliberately *not* derived from the drop in the tax-inclusive sale price: an
     * item-wise discount is always applied pre-tax, so on an exclusive price that
     * drop is the discount grossed up by the tax rate (3.15 on a 5% slab), which is
     * not the discount the operator set or the customer was quoted.
     *
     * Zero unless an item-wise discount is active - a bill-wise discount is not a
     * per-line figure, it only moves the bill's total.
     */
    private fun lineDiscount(line: CheckoutSession.Line): Double {
        if (!itemwiseDiscountActive) return 0.0
        if (line.itemDiscValue <= 0.0 || line.itemDiscType == null) return 0.0
        val mode = if (line.itemDiscType == "A") GstCalculator.DiscountMode.AMOUNT else GstCalculator.DiscountMode.PERCENT
        return GstCalculator.discountAmount(line.price * line.qty, mode, line.itemDiscValue)
    }

    /** The discount column's text: the amount taken off, or a dash when nothing was. */
    private fun lineDiscountText(line: CheckoutSession.Line): String {
        val d = lineDiscount(line)
        return if (d > 0.005) "- ${money(d)}" else "-"
    }

    /**
     * The NET AMT column: the line's gross less its item-wise discount, in the terms
     * the price is listed in. On an inclusive price that is what the customer pays;
     * on an exclusive one the tax is added to it in the summary below.
     *
     * With no discount to take off - a bill-wise sale, or a product with none
     * configured - this is simply the gross, which is what the column showed before
     * any of the discount work.
     */
    private fun lineNetAmount(line: CheckoutSession.Line): Double =
        ((line.price * line.qty) - lineDiscount(line)).coerceAtLeast(0.0)

    /**
     * The discount this line carries onto the bill, expressed against its raw pre-tax
     * base - the shape `td_bill_items.discount_amount` is stored in.
     *
     * Under item-wise discount that is the product's own pre-configured discount,
     * translated so the DAO's pre-tax pipeline reproduces the sale price
     * [GstCalculator.priceItem] works out on screen. Otherwise it is the line's share
     * of the whole-bill discount, but only when that discount is pre-tax; applied
     * post-tax - which a bill-wise discount always is - the line is taxed in full and
     * the discount comes off the bill's total instead.
     *
     * Shared by the saved bill and the receipt preview so the two cannot disagree.
     */
    private fun lineDiscountForBill(line: CheckoutSession.Line): Double {
        val gross = line.price * line.qty
        val rate = when (taxRegime) {
            GstCalculator.TaxRegime.GST -> line.cgstRate + line.sgstRate
            GstCalculator.TaxRegime.VAT -> line.vatRate
            GstCalculator.TaxRegime.NONE -> 0.0
        }
        if (itemwiseDiscountActive && line.itemDiscValue > 0.0 && line.itemDiscType != null) {
            val mode = if (line.itemDiscType == "A") GstCalculator.DiscountMode.AMOUNT else GstCalculator.DiscountMode.PERCENT
            return GstCalculator.itemDiscountAgainstRawBase(gross, rate, taxInclusive, discountPreTax, mode, line.itemDiscValue)
        }
        val sub = subtotal()
        return if (discountPreTax && sub > 0) gross / sub * discountAmt() else 0.0
    }

    private fun cgstAmt() = lines.sumOf { lineTax(it).cgst }
    private fun sgstAmt() = lines.sumOf { lineTax(it).sgst }
    private fun vatAmt() = lines.sumOf { lineTax(it).vat }
    private fun taxableSum() = lines.sumOf { lineTax(it).taxable }

    /** The further amount still owed to lines' own item-wise discount, on top of
     *  [taxableSum]/[cgstAmt]/[sgstAmt]/[vatAmt] - see [lineTax]. Zero unless
     *  item-wise discount is active. */
    private fun itemwiseDiscountSum() = lines.sumOf { lineTax(it).discount }

    /** Tax at each product's own rate, not one blanket rate across the bill. CGST+SGST
     *  and VAT are mutually exclusive - only one regime is ever active - so summing all three is safe. */
    private fun taxAmt() = cgstAmt() + sgstAmt() + vatAmt()

    /**
     * Taxed value of the bill, before it is rounded to whole rupees.
     * [itemwiseDiscountSum] is the one further deduction not yet reflected in
     * [taxableSum]/[taxAmt] - a post-tax, exclusive item-wise discount - and is
     * zero (so a no-op) in every other case.
     */
    private fun taxedTotal(): Double {
        val extra = itemwiseDiscountSum()
        return if (discountPreTax) {
            (taxableSum() + taxAmt() - extra).coerceAtLeast(0.0)
        } else {
            (taxableSum() + taxAmt() - discountAmt() - extra).coerceAtLeast(0.0)
        }
    }

    private fun roundOffAmt() = BillRounding.roundOff(taxedTotal())

    /**
     * What the customer actually pays. Everything downstream - the amount due, the
     * cash validation, the figure written to the bill - works from this, so the
     * receipt, the payment record and the till all agree on one number.
     */
    private fun total() = BillRounding.payable(taxedTotal())

    /** "Discount (10%)" or "Discount (₹50.00)", matching however it was entered. */
    private fun discountLabelText(): String = "Discount (" + (
        if (discountMode == GstCalculator.DiscountMode.PERCENT) "${discountValuePctText()}%" else money(discountValue)
    ) + ")"

    /** [discountValue] as a percentage, without a trailing ".0" for whole numbers. */
    private fun discountValuePctText(): String =
        if (discountValue % 1.0 == 0.0) discountValue.toInt().toString()
        else String.format(Locale.US, "%.1f", discountValue)

    private fun refreshTotals() {
        val disc = discountLabelText()
        id<TextView>(R.id.tvSubtotal).text = money(subtotal())
        // Item-wise discount has no single whole-bill figure to show here - each
        // line already prices its own discount in what it charges.
        id<View>(R.id.rowDiscount).visibility = if (itemwiseDiscountActive) View.GONE else View.VISIBLE
        id<TextView>(R.id.tvDiscountLabel).text = disc
        id<TextView>(R.id.tvDiscountAmt).text = "- ${money(discountAmt())}"
        id<TextView>(R.id.tvTaxLabel).text = taxLabelText()
        id<TextView>(R.id.tvTax).text = money(taxAmt())

        val roundOff = roundOffAmt()
        id<View>(R.id.rowRoundOff).visibility = if (kotlin.math.abs(roundOff) > 0.001) {
            id<TextView>(R.id.tvRoundOff).text =
                (if (roundOff > 0) "+ " else "- ") + money(kotlin.math.abs(roundOff))
            View.VISIBLE
        } else View.GONE

        id<TextView>(R.id.tvLeftTotal).text = money(total())
        id<TextView>(R.id.tvAmountDue).text = money(total())

        // Update item count
        val itemCount = lines.sumOf { it.qty }
        id<TextView>(R.id.tvPayItemCount).text = "Items: $itemCount"

        // Cash change - exact amount (no tendered/change entry) when Cash Reception is off
        val tendered = if (cashReceptionEnabled()) {
            id<TextInputEditText>(R.id.etCash).text?.toString()?.toDoubleOrNull() ?: 0.0
        } else {
            total()
        }
        id<TextView>(R.id.tvChange).text = money((tendered - total()).coerceAtLeast(0.0))
        id<TextView>(R.id.tvCashExactAmount).text = money(total())

        // Credit balance due
        val creditPaid = id<TextInputEditText>(R.id.etCredit).text?.toString()?.toDoubleOrNull() ?: 0.0
        id<TextView>(R.id.tvBalanceDue).text = money((total() - creditPaid).coerceAtLeast(0.0))

        // Complete enabled?
        val can = total() > 0 && when (method) {
            Method.CASH -> tendered >= total() - 0.001
            else -> true
        }
        val btn = id<MaterialButton>(R.id.btnComplete)
        btn.isEnabled = can
        btn.alpha = if (can) 1f else 0.45f
        btn.text = "Complete Checkout · ${money(total())}"

        if (!editMode) renderReceipt()
    }

    // ---- Receipt preview ---------------------------------------------------

    /** The bill layout itself, inflated into the receipt pane once and refilled on
     *  every render. */
    private val receiptView: View by lazy {
        val host = id<FrameLayout>(R.id.scrollReceipt)
        layoutInflater.inflate(R.layout.fragment_bill, host, false).also { bill ->
            // Printing is the checkout button's job; the preview only shows the bill.
            bill.findViewById<View>(R.id.btnPrintBill)?.visibility = View.GONE
            host.addView(bill)
        }
    }

    /**
     * The bill this sale would print, rendered by the same [BillReceiptRenderer] the
     * bill screen and the printer use - from a draft, since the sale has not been
     * written yet.
     *
     * Every Bill and Tax setting therefore reaches the preview exactly as it reaches
     * the paper: header and footer lines, logos, HSN, which customer details print,
     * round off, amount in words, the total's font size, the item columns and the
     * per-slab tax summary. Nothing here re-implements any of it.
     */
    private fun renderReceipt() {
        BillReceiptRenderer(requireContext()).populate(receiptView, 0L, draft = buildDraft())
    }

    /**
     * This sale in the shape the renderer reads a saved bill in. Lines carry the same
     * per-line discount [generateBill] will write ([lineDiscountForBill]) and are
     * priced by the same [com.example.synergic_pos_offline.utils.BillPricing], so the
     * preview cannot quote a figure the completed sale will not.
     */
    private fun buildDraft(): BillReceiptRenderer.Draft {
        val onFile = currentCustomer()
        val name = creditCustomerName.ifEmpty { onFile?.name ?: CheckoutSession.customerName.orEmpty() }
        val phone = creditCustomerPhone.ifEmpty { onFile?.phone ?: CheckoutSession.customerPhone.orEmpty() }
        val gstin = creditCustomerGstin.ifEmpty { onFile?.gstin.orEmpty() }
        val address = creditCustomerAddress.ifEmpty { onFile?.address.orEmpty() }
        return BillReceiptRenderer.Draft(
            billNumber = BillDao(requireContext()).nextBillNumber(),
            dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
            cashier = SessionManager.currentUser?.userId?.uppercase() ?: "---",
            customer = BillReceiptRenderer.Draft.Customer(
                name = name.ifEmpty { null },
                phone = phone.ifEmpty { null },
                gstin = gstin.ifEmpty { null },
                address = address.ifEmpty { null }
            ),
            items = lines.map { line ->
                BillReceiptRenderer.Draft.Item(
                    name = line.name,
                    quantity = line.qty.toDouble(),
                    rate = line.price,
                    cgstRate = line.cgstRate,
                    sgstRate = line.sgstRate,
                    vatRate = line.vatRate,
                    discountAmount = lineDiscountForBill(line),
                    hsn = catalog.firstOrNull { it.id.toLongOrNull() == line.productId }?.hsn
                )
            },
            discount = discountAmt(),
            roundOff = roundOffAmt(),
            netAmount = total(),
            paymentModes = listOf(method.name)
        )
    }

    // ---- Complete ----------------------------------------------------------

    private fun complete() {
        if (lines.isEmpty()) { toast("The bill is empty"); return }

        val result = generateBill()
        if (result == null) {
            toast("Failed to generate bill")
            return
        }

        // The sale is committed, so the receipt goes out without waiting to be asked:
        // the operator hands over paper while the dialog is still up.
        printBill(result.receiptNo)

        DialogUtils.showConfirm(
            context = requireContext(),
            title = "Checkout complete",
            message = "Bill No: ${result.billNumber}",
            positiveText = "Start new sale",
            negativeText = "Reprint",
            iconRes = R.drawable.ic_check,
            onConfirm = {
                // No counter to bump: the next screen reads the number back from the
                // bills table, which the sale just added to.
                CheckoutSession.lines = mutableListOf()
                CheckoutSession.startFreshSale = true
                requireActivity().supportFragmentManager.popBackStack()
            },
            onCancel = { printBill(result.receiptNo) }
        )
    }

    // ---- Printing ----------------------------------------------------------

    /**
     * Renders the completed bill off-screen and sends it to the thermal printer.
     *
     * Rendered from the bill tables rather than from the summary on this screen, so
     * the slip carries the store header, bill number, GSTIN, amount in words and
     * footer - and is identical to what the bill screen would reprint later.
     */
    private fun printBill(receiptNo: Long) {
        if (receiptNo <= 0) return

        // The BILL slot in md_printer is the source of truth (its paper width scales
        // the print); fall back to the legacy saved config only if it is unset.
        val config = ThermalPrinter.configForPurpose(requireContext(), "BILL")
            ?: ThermalPrinter.savedConfig(requireContext())
        if (config == null) {
            // No printer set up yet - ask for it, then print once it is saved.
            PrinterSetup.show(requireContext()) { saved -> sendToPrinter(receiptNo, saved) }
            return
        }
        sendToPrinter(receiptNo, config)
    }

    private fun sendToPrinter(receiptNo: Long, config: ThermalPrinter.Config) {
        val capture = BillReceiptRenderer(requireContext()).renderToBitmap(receiptNo, config.paperDots)
        if (capture == null) {
            toast("Could not render the receipt")
            return
        }
        // Bill Settings' "Two Copy" toggle - sent as two separate jobs off the one
        // rendered bitmap, not two renders.
        val copies = if (BillSettingsDao(requireContext()).load().twoCopyBill) 2 else 1
        ThermalPrinter.printCopies(requireContext(), capture, config, copies) { result ->
            // The sale is already saved, so a printer problem is reported and never
            // blocks the till: the bill can always be reprinted from Recent Bills.
            if (!isAdded) return@printCopies
            when (result) {
                is ThermalPrinter.Result.Success -> {
                    toast("Printed")
                    BillReceiptRenderer.recordPrint(requireContext(), receiptNo)
                }
                is ThermalPrinter.Result.Sent -> {
                    toast("Sent to printer")
                    BillReceiptRenderer.recordPrint(requireContext(), receiptNo)
                }
                is ThermalPrinter.Result.Failure -> toast("Print failed: ${result.message}")
            }
        }
    }

    /** Persists the current sale to td_bills / td_bill_items / td_payments. */
    private fun generateBill(): BillDao.Result? {
        val dao = BillDao(requireContext())

        // Each line is taxed at its own product's rates. Under item-wise discount,
        // each line carries its own pre-configured discount, expressed as an
        // amount off its pre-tax base so the DAO's existing pre-tax pipeline
        // reproduces the same sale price GstCalculator.priceItem works out on
        // screen - see GstCalculator.itemDiscountAgainstRawBase. Otherwise, it
        // carries its share of the whole-bill discount, but only when that
        // discount is pre-tax; applied post-tax, the line is taxed in full and the
        // discount comes off the bill's total instead.
        val items = lines.map { line ->
            BillDao.Item(
                productId = line.productId,
                name = line.name,
                quantity = line.qty.toDouble(),
                rate = line.price,
                cgstRate = line.cgstRate,
                sgstRate = line.sgstRate,
                vatRate = line.vatRate,
                discountAmount = lineDiscountForBill(line)
            )
        }

        val billType = when (method) {
            Method.CASH -> "CASH"
            Method.CREDIT -> "CREDIT"
            Method.CARD -> "CARD"
            Method.ONLINE -> "ONLINE"
        }
        val paymentMode = when (method) {
            Method.CASH -> "CASH"
            Method.CREDIT -> "CREDIT"
            Method.CARD -> "CARD"
            Method.ONLINE -> "ONLINE"
        }

        val grandTotal = total()
        val (amountPaid, change) = when (method) {
            Method.CASH -> {
                val tendered = if (cashReceptionEnabled()) {
                    id<TextInputEditText>(R.id.etCash).text?.toString()?.toDoubleOrNull() ?: grandTotal
                } else {
                    grandTotal
                }
                tendered to (tendered - grandTotal).coerceAtLeast(0.0)
            }
            Method.CREDIT -> {
                val paid = id<TextInputEditText>(R.id.etCredit).text?.toString()?.toDoubleOrNull() ?: 0.0
                paid to 0.0
            }
            else -> grandTotal to 0.0
        }

        // Resolve customer: credit dialog details take precedence, else the sale's customer.
        val custName = creditCustomerName.ifEmpty { CheckoutSession.customerName ?: "" }
        val custPhone = creditCustomerPhone.ifEmpty { CheckoutSession.customerPhone ?: "" }
        // Resolve against the phone actually printed on the bill so an edited number
        // cannot attach the sale to the previously selected customer. The id captured
        // when the customer was picked in billing is the fallback, which also covers
        // a customer whose phone was since changed in the master.
        val custId = dao.findCustomerIdByPhone(custPhone.ifEmpty { null })
            ?: CheckoutSession.customerId.takeIf { custPhone == CheckoutSession.customerPhone }

        // Split by what each side actually came to - the two rates can differ.
        val cgstTotal = cgstAmt()
        val sgstTotal = sgstAmt()

        val newBill = BillDao.NewBill(
            billType = billType,
            customerId = custId,
            items = items,
            payment = BillDao.Payment(
                mode = paymentMode,
                amountPaid = amountPaid,
                changeAmount = change,
                custName = custName.ifEmpty { null },
                custPhone = custPhone.ifEmpty { null },
                custGstin = creditCustomerGstin.ifEmpty { null },
                custId = custId
            ),
            totalPrice = subtotal(),
            discountAmount = discountAmt(),
            discountPercentage = discountPctForDisplay(),
            discountIsPercent = discountMode == GstCalculator.DiscountMode.PERCENT,
            cgstAmount = cgstTotal,
            sgstAmount = sgstTotal,
            vatAmount = vatAmt(),
            netAmount = grandTotal,
            // net_amount is the rounded figure the customer paid; the adjustment is
            // stored beside it so the receipt can reconcile it to the taxed value.
            roundOffAmount = roundOffAmt()
        )

        return dao.createBill(newBill)
    }

    // ---- Hold / Resume held orders ------------------------------------------

    private fun onHold() {
        if (lines.isEmpty()) { toast("Cart is empty"); return }
        // Only one bill can be held at a time - replace existing if present
        CheckoutSession.heldOrders.clear()
        val custData = CheckoutSession.customerId?.let {
            mapOf<String, Any?>("id" to it, "name" to CheckoutSession.customerName, "phone" to CheckoutSession.customerPhone)
        }
        CheckoutSession.heldOrders.add(
            CheckoutSession.HeldBill(
                "Sale #1", lines.map { it.copy() },
                CheckoutSession.discountMode, CheckoutSession.discountValue, false,
                CheckoutSession.customerName, CheckoutSession.customerPhone, custData
            )
        )
        lines.clear()
        renderItems()
        refreshTotals()
        updateHeldButton()
        toast("Sale put on hold")
        // Refresh the billing page on the way back, so the held sale is cleared there
        // too - same flow as holding from the billing screen.
        CheckoutSession.startFreshSale = true
        parentFragmentManager.popBackStack()
    }

    private fun showHeldDialog() {
        if (CheckoutSession.heldOrders.isEmpty()) { toast("No sales on hold"); return }

        if (CheckoutSession.heldOrders.size == 1) {
            showHeldBillDetails(0)
        } else {
            val labels = CheckoutSession.heldOrders.mapIndexed { index, h ->
                "${h.label} · ${h.lines.sumOf { it.qty }} items · ${money(h.lines.sumOf { it.price * it.qty })}"
            }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Held orders")
                .setSingleChoiceItems(labels, -1) { dialog, which ->
                    dialog.dismiss()
                    showHeldBillDetails(which)
                }
                .setNegativeButton("Close", null)
                .create()
                .also { it.setCanceledOnTouchOutside(false); it.show() }
        }
    }

    private fun showHeldBillDetails(index: Int) {
        val heldBill = CheckoutSession.heldOrders[index]
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)

        val billDetails = StringBuilder().apply {
            append("${heldBill.label}\n\n")
            append("ITEMS:\n")
            heldBill.lines.forEach { line ->
                append("${line.name}\n")
                append("  Qty: ${line.qty} × ${money(line.price)} = ${money(line.price * line.qty)}\n")
            }
            val subtotal = heldBill.lines.sumOf { it.price * it.qty }
            val discAmt = GstCalculator.discountAmount(subtotal, heldBill.discountMode, heldBill.discountValue)
            append("\nSubtotal: ${money(subtotal)}\n")
            if (discAmt > 0.0) {
                val label = if (heldBill.discountMode == GstCalculator.DiscountMode.PERCENT)
                    "${heldBill.discountValue}%" else money(heldBill.discountValue)
                append("Discount ($label): -${money(discAmt)}\n")
            }
            val lineTaxes = heldBill.lines.map { lineTax(it, subtotal, discAmt) }
            val taxable = lineTaxes.sumOf { it.taxable }
            val tax = lineTaxes.sumOf { it.cgst + it.sgst + it.vat }
            val itemwiseExtra = lineTaxes.sumOf { it.discount }
            append("${taxLabelText()}: ${money(tax)}\n")
            val total = if (discountPreTax) {
                (taxable + tax - itemwiseExtra).coerceAtLeast(0.0)
            } else {
                (taxable + tax - discAmt - itemwiseExtra).coerceAtLeast(0.0)
            }
            append("\nTOTAL: ${money(total)}")
        }.toString()

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_common, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val tvMessage = view.findViewById<TextView>(R.id.tvDialogMessage)
        val btnPositive = view.findViewById<MaterialButton>(R.id.btnDialogPositive)
        val btnNegative = view.findViewById<MaterialButton>(R.id.btnDialogNegative)
        val ivIcon = view.findViewById<View>(R.id.ivDialogIcon)

        tvTitle.text = "Held Bill"
        tvMessage.text = billDetails

        btnPositive.text = "Restore Held Bill"
        btnNegative.text = "OK"
        btnPositive.backgroundTintList = ColorStateList.valueOf(accent)
        btnNegative.setTextColor(accent)
        btnNegative.strokeColor = ColorStateList.valueOf(accent)
        ivIcon.visibility = View.GONE

        btnPositive.setOnClickListener {
            resumeHeld(index)
            dialog.dismiss()
        }

        btnNegative.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        val window = dialog.window
        window?.setLayout(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        window?.setGravity(android.view.Gravity.CENTER)
    }

    private fun resumeHeld(index: Int) {
        val restoredBill = CheckoutSession.heldOrders.removeAt(index)
        // Pass the restored bill back to billing fragment
        CheckoutSession.restoredBill = restoredBill
        updateHeldButton()
        requireActivity().supportFragmentManager.popBackStack()
    }

    private fun updateHeldButton() {
        id<MaterialButton>(R.id.btnHeld).text = "Held (${CheckoutSession.heldOrders.size})"
    }

    // ---- Credit Customer Information ----------------------------------------

    private fun showCreditCustomerDialog() {
        val ctx = requireContext()
        val inflater = LayoutInflater.from(ctx)
        val view = inflater.inflate(R.layout.dialog_form, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx).setView(view).create().also {
            it.setCanceledOnTouchOutside(false)
        }
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val accent = ThemeManager.getThemeColor(ctx)
        val grid = view.findViewById<GridLayout>(R.id.glFields)
        val btnPositive = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnNegative = view.findViewById<MaterialButton>(R.id.btnFormNegative)

        view.findViewById<TextView>(R.id.tvFormTitle).text = "Credit Sale - Customer Details"
        btnPositive.text = "Save"
        btnNegative.text = "Cancel"

        val density = ctx.resources.displayMetrics.density
        val margin = (8 * density).toInt()
        val inputs = mutableListOf<TextInputEditText>()

        // Phone field (first)
        var tilPhone = inflater.inflate(R.layout.item_form_field, null, false) as TextInputLayout
        tilPhone.hint = "Phone Number"
        tilPhone.layoutParams = GridLayout.LayoutParams().apply {
            rowSpec = GridLayout.spec(0)
            columnSpec = GridLayout.spec(0, 2, 1f)
            width = 0
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setMargins(margin, margin / 2, margin, margin / 2)
        }
        val etPhone = tilPhone.findViewById<TextInputEditText>(R.id.etField)
        etPhone.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        etPhone.filters = arrayOf(android.text.InputFilter.LengthFilter(10))
        etPhone.setText(creditCustomerPhone)
        grid.addView(tilPhone)
        inputs.add(etPhone)

        // Name field
        var tilName = inflater.inflate(R.layout.item_form_field, null, false) as TextInputLayout
        tilName.hint = "Customer Name"
        tilName.layoutParams = GridLayout.LayoutParams().apply {
            rowSpec = GridLayout.spec(1)
            columnSpec = GridLayout.spec(0, 1, 1f)
            width = 0
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setMargins(margin, margin / 2, margin, margin / 2)
        }
        val etName = tilName.findViewById<TextInputEditText>(R.id.etField)
        etName.setText(creditCustomerName)
        grid.addView(tilName)
        inputs.add(etName)

        // Address field
        var tilAddress = inflater.inflate(R.layout.item_form_field, null, false) as TextInputLayout
        tilAddress.hint = "Address"
        tilAddress.layoutParams = GridLayout.LayoutParams().apply {
            rowSpec = GridLayout.spec(2)
            columnSpec = GridLayout.spec(0, 2, 1f)
            width = 0
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setMargins(margin, margin / 2, margin, margin / 2)
        }
        val etAddress = tilAddress.findViewById<TextInputEditText>(R.id.etField)
        etAddress.setText(creditCustomerAddress)
        etAddress.minLines = 3
        etAddress.maxLines = 5
        etAddress.isSingleLine = false
        grid.addView(tilAddress)
        inputs.add(etAddress)

        // GSTIN field
        var tilGstin = inflater.inflate(R.layout.item_form_field, null, false) as TextInputLayout
        tilGstin.hint = "GSTIN"
        tilGstin.layoutParams = GridLayout.LayoutParams().apply {
            rowSpec = GridLayout.spec(3)
            columnSpec = GridLayout.spec(0, 2, 1f)
            width = 0
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setMargins(margin, margin / 2, margin, margin / 2)
        }
        val etGstin = tilGstin.findViewById<TextInputEditText>(R.id.etField)
        etGstin.setText(creditCustomerGstin)
        grid.addView(tilGstin)
        inputs.add(etGstin)

        // Phone autocomplete with suggestions
        val customerDao = com.example.synergic_pos_offline.database.CustomerDao(ctx)
        val suggestionsContainer = view.findViewById<LinearLayout>(R.id.llSuggestions)

        etPhone.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                if (query.length >= 3 && query.all { it.isDigit() }) {
                    val allCustomers = customerDao.getAll()
                    val suggestions = allCustomers.filter { it.phone.startsWith(query) }

                    if (suggestions.isNotEmpty()) {
                        suggestionsContainer.removeAllViews()
                        suggestionsContainer.visibility = View.VISIBLE

                        suggestions.take(5).forEach { customer ->
                            val suggestionView = android.widget.TextView(ctx).apply {
                                text = "${customer.name} - ${customer.phone}"
                                textSize = 12f
                                setTextColor(android.graphics.Color.parseColor("#333333"))
                                setPadding(16, 12, 16, 12)
                                setBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                                setOnClickListener {
                                    etPhone.setText(customer.phone)
                                    etName.setText(customer.name)
                                    etAddress.setText(customer.address)
                                    etGstin.setText(customer.gstin)
                                    suggestionsContainer.visibility = View.GONE
                                }
                            }
                            suggestionsContainer.addView(suggestionView)
                        }
                    } else {
                        suggestionsContainer.visibility = View.GONE
                    }
                } else {
                    suggestionsContainer.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        ThemeManager.applyTheme(grid)
        btnPositive.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        btnNegative.setTextColor(accent)
        btnNegative.strokeColor = android.content.res.ColorStateList.valueOf(accent)

        btnPositive.setOnClickListener {
            val phone = inputs[0].text?.toString()?.trim() ?: ""
            if (phone.isEmpty() || phone.length != 10 || !phone.all { it.isDigit() }) {
                toast("Phone number must be exactly 10 digits")
                return@setOnClickListener
            }

            creditCustomerPhone = phone
            creditCustomerName = inputs[1].text?.toString()?.trim() ?: ""
            creditCustomerAddress = inputs[2].text?.toString()?.trim() ?: ""
            creditCustomerGstin = inputs[3].text?.toString()?.trim() ?: ""

            if (creditCustomerName.isBlank()) {
                toast("Customer name is required for a credit bill")
                return@setOnClickListener
            }

            saveCreditCustomerDetails()
            updateHeaderWithCustomer()
            dialog.dismiss()
            toast("Customer details saved")
        }

        btnNegative.setOnClickListener { dialog.dismiss() }

        dialog.show()
        val window = dialog.window
        window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setGravity(android.view.Gravity.CENTER)
    }

    private fun updateHeaderWithCustomer() {
        id<TextView>(R.id.tvCustName).text = if (creditCustomerName.isNotEmpty()) creditCustomerName else "Guest"
        id<TextView>(R.id.tvCustSub).text = if (creditCustomerPhone.isNotEmpty()) creditCustomerPhone else "Walk-in"
        id<TextView>(R.id.tvCustInitials).text = (if (creditCustomerName.isNotEmpty()) creditCustomerName else "Guest")
            .split(" ")
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .take(2)
            .joinToString("")
    }

    // ---- Helpers -----------------------------------------------------------

    private fun titleFor(m: Method) = when (m) {
        Method.CASH -> "Cash"
        Method.CREDIT -> "Credit"
        Method.CARD -> "Card"
        Method.ONLINE -> "Online"
    }

    private fun styleFilled(btn: MaterialButton) {
        btn.backgroundTintList = ColorStateList.valueOf(accent)
        btn.setTextColor(Color.WHITE)
        btn.strokeWidth = 0
        btn.iconTint = ColorStateList.valueOf(Color.WHITE)
        btn.cornerRadius = (resources.displayMetrics.density * 12).toInt()
    }

    /** Restores an outlined button's white fill + accent border/text/icon. */
    private fun styleOutlined(btn: MaterialButton) {
        btn.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        btn.setTextColor(accent)
        btn.strokeColor = ColorStateList.valueOf(accent)
        btn.strokeWidth = (resources.displayMetrics.density * 2f).toInt()
        btn.iconTint = ColorStateList.valueOf(accent)
        btn.rippleColor = ColorStateList.valueOf(accent).withAlpha(30)
        btn.cornerRadius = (resources.displayMetrics.density * 12).toInt()
    }

    private fun money(v: Double) = "₹" + String.format("%.2f", BillRounding.toPaise(v))
    private fun fmtPlain(v: Double) = String.format("%.2f", v)

    private fun <T : View> id(resId: Int): T = root.findViewById(resId)

    private fun toast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun watcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { onChange(s?.toString()?.trim().orEmpty()) }
        override fun afterTextChanged(s: Editable?) {}
    }
}
