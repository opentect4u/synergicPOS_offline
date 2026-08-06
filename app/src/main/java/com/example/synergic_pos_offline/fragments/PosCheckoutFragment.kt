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
import com.example.synergic_pos_offline.utils.InputLimits
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.GstCalculator
import com.example.synergic_pos_offline.utils.PrinterSetup
import com.example.synergic_pos_offline.utils.ReceiptContext
import com.example.synergic_pos_offline.utils.ProductEntryDialog
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.ThemeManager
import com.example.synergic_pos_offline.utils.ThermalPrinter
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How many item lines the "restore held bill?" confirmation previews. The card it
 * is drawn in does not scroll, so a long bill is summarised rather than listed in
 * full and pushing the buttons off the screen.
 */
private const val HELD_PREVIEW_LINES = 8

/** In-process hand-off of the current sale from billing to checkout. */
object CheckoutSession {
    data class Line(
        val name: String, val sku: String, var price: Double, var qty: Double,
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
        /** The bill number the sale was carrying when it was parked - what the
         *  operator picks it out by. See [holdLabel]. */
        val label: String, val lines: List<Line>,
        val discountMode: GstCalculator.DiscountMode = GstCalculator.DiscountMode.PERCENT,
        val discountValue: Double = 0.0,
        val coupon: Boolean,
        val customerName: String? = null,
        val customerPhone: String? = null,
        val customerData: Map<String, Any?>? = null,
        /** When it was parked, shown in the picker so two holds taken against the
         *  same bill number can still be told apart. */
        val heldAt: Long = System.currentTimeMillis()
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

    /**
     * Every sale currently parked, in the order it was parked. There is no cap:
     * a counter can hold as many bills as it needs to and pick any of them back up.
     */
    var heldOrders: MutableList<HeldBill> = mutableListOf()
    var restoredBill: HeldBill? = null

    /**
     * The label a bill held right now should carry.
     *
     * [billNo] is the number the sale would have taken had it been charged. Held
     * bills are never saved, so the counter does not move and a second hold reports
     * the same number as the first - the suffix is what keeps two rows of the picker
     * from reading identically.
     */
    fun holdLabel(billNo: String): String {
        val base = if (billNo.isBlank()) "Bill" else "Bill $billNo"
        if (heldOrders.none { it.label == base }) return base
        var n = 2
        while (heldOrders.any { it.label == "$base ($n)" }) n++
        return "$base ($n)"
    }

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

    /**
     * Whether a sale captures the customer at all - General Settings' "Customer
     * Info". Off, nothing on this screen collects or shows one, and the sale is
     * written with a null `customer_id`.
     *
     * A credit sale is the exception throughout: it is collected later, so it has
     * to be attributable, and it asks for the customer and prints them regardless.
     */
    private val capturesCustomer: Boolean by lazy {
        com.example.synergic_pos_offline.database.GeneralSettingsDao(requireContext()).load().customerInfo
    }

    /** True when this sale carries a customer at all - see [capturesCustomer]. */
    private fun customerApplies(): Boolean = capturesCustomer || method == Method.CREDIT

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
        applyCustomerVisibility()

        // Accent bars
        id<View>(R.id.barLeftTotal).setBackgroundColor(accent)
        id<View>(R.id.barAmountDue).setBackgroundColor(accent)

        // Mode toggle
        id<MaterialButton>(R.id.btnModeEdit).setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        id<MaterialButton>(R.id.btnModeReceipt).setOnClickListener { setMode(false) }

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
            row.findViewById<TextView>(R.id.tvQty).text = "Qty: ${qtyText(line.qty)}"
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
     *
     * A customer already on file must also have credit switched on: the flag is what
     * says this customer is allowed to owe the store money, so a sale cannot be put
     * on the account of someone who has never been given credit terms.
     */
    private fun ensureCreditCustomer() {
        if (blockCreditIfIneligible()) return

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

        showCreditCustomerDialog()
    }

    /**
     * Fills in whatever is already known about the sale's customer, from the master
     * first and the sale itself second.
     *
     * Called from the dialog rather than from one route into it, so it opens with the
     * same details however it was reached - including from the credit-not-enabled
     * message, which stops before any of the flow below it runs. A customer with no
     * master record still arrives with at least the phone number the sale was rung up
     * against, rather than an empty form.
     */
    private fun prefillCreditCustomer() {
        val onFile = currentCustomer()
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
    }

    /**
     * The gate a sale has to pass before it can go on a customer's account: they must
     * have credit switched on, and enough of their limit left to carry what is
     * payable. Whichever way it falls short, the operator is told and sent to the
     * customer's details to fix it.
     *
     * Checked both when credit is chosen and again when the bill is completed - the
     * cart can be edited in between, and a sale that has grown past the limit since
     * must not slip through on a mode picked when it still fitted.
     *
     * A customer with no master record has no limit to test, so nothing is blocked
     * here; the details dialog captures them first.
     *
     * @return true when the sale was blocked, and the caller should stop.
     */
    private fun blockCreditIfIneligible(): Boolean {
        val onFile = currentCustomer() ?: return false
        if (!onFile.creditEnabled) {
            showCreditDisabledDialog(onFile.name.takeIf { it.isNotBlank() } ?: onFile.phone)
            return true
        }
        val payable = total()
        if (onFile.creditLimit < payable - 0.005) {
            showCreditLimitDialog(onFile.creditLimit, payable)
            return true
        }
        return false
    }

    /**
     * Stops a credit sale the customer's remaining limit cannot carry, and says by
     * how much it falls short. Confirming opens their details, where the limit can be
     * raised; the sale goes through once it covers what is payable.
     */
    private fun showCreditLimitDialog(limit: Double, payable: Double) {
        DialogUtils.showConfirm(
            context = requireContext(),
            title = "Credit limit too low",
            message = "This sale comes to ${money(payable)}, but only ${money(limit)} of credit " +
                "is left on the account. Raise the credit limit to at least ${money(payable)} " +
                "to bill it to their account.",
            positiveText = "Open details",
            negativeText = "Cancel",
            onConfirm = { showCreditCustomerDialog() }
        )
        setMethod(Method.CASH)
    }

    /**
     * Stops a credit sale to a customer whose credit flag is off, and says why.
     *
     * Confirming opens their details, where the same Credit switch the customer
     * master carries can be turned on and saved; the sale can then be put on credit
     * on the next attempt. Either way the payment mode falls back to cash, so a bill
     * cannot be completed on credit the customer does not have.
     */
    private fun showCreditDisabledDialog(who: String) {
        DialogUtils.showConfirm(
            context = requireContext(),
            title = "Credit not enabled",
            message = "Credit is switched off for $who. Turn Credit on in the customer's " +
                "details before billing this sale to their account.",
            positiveText = "Open details",
            negativeText = "Cancel",
            onConfirm = { showCreditCustomerDialog() }
        )
        setMethod(Method.CASH)
    }

    /**
     * Writes the details captured for a credit sale to the customer master, so the
     * same questions are not asked again on the next visit.
     *
     * Matched on the phone number typed in the dialog, which is the number the bill
     * goes out under. A number not already on file is inserted rather than left to
     * live only on this one bill: a credit sale has to hang off a customer record for
     * the balance it runs up to be traceable at all.
     *
     * The dialog opens prefilled from this same record, so saving what is on screen
     * writes back only what the operator actually changed.
     */
    private fun saveCreditCustomer(details: CustomerDao.Customer) {
        val dao = CustomerDao(requireContext())
        runCatching {
            val onFile = dao.findByPhone(details.phone)
            if (onFile == null) dao.insert(details.copy(id = 0L)) else dao.update(onFile.id, details.copy(id = onFile.id))
        }.onFailure { android.util.Log.e("PosCheckoutFragment", "Could not save customer details", it) }
    }

    /** Opens a calendar seeded with [current] ("yyyy-MM-dd") and returns the pick. */
    private fun pickDate(current: String?, onPicked: (String) -> Unit) {
        val cal = java.util.Calendar.getInstance()
        current?.takeIf { it.isNotBlank() }?.let { s ->
            runCatching {
                val p = s.split("-")
                cal.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt())
            }
        }
        android.app.DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                onPicked(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day))
            },
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH),
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        ).show()
    }

    /** A whole number without a needless ".0" - what the master shows in its inputs. */
    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

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
        applyCustomerVisibility()
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
        id<TextView>(R.id.tvPayItemCount).text = "Items: ${qtyText(itemCount)}"

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
        // Standard font scale, like the print - see [ReceiptContext]. The rest of
        // this screen still honours the device's text size; only the slip is pinned.
        LayoutInflater.from(ReceiptContext.standardFontScale(requireContext()))
            // The till's Print Template layout, so the preview is the slip that will
            // print rather than a Standard stand-in for it.
            .inflate(BillReceiptRenderer.layoutFor(requireContext()), host, false).also { bill ->
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
        val onFile = currentCustomer().takeIf { customerApplies() }
        val name = creditCustomerName.ifEmpty { onFile?.name ?: CheckoutSession.customerName.orEmpty() }
        val phone = creditCustomerPhone.ifEmpty { onFile?.phone ?: CheckoutSession.customerPhone.orEmpty() }
        val gstin = creditCustomerGstin.ifEmpty { onFile?.gstin.orEmpty() }
        val address = creditCustomerAddress.ifEmpty { onFile?.address.orEmpty() }
        // What the customer will owe once this sale is booked: what is already on
        // their account plus whatever this bill leaves unpaid - the same sum
        // [BillDao.recordBalanceDue] will write, so the preview and the printed
        // slip quote one figure. Only a customer on the master has an account to
        // add it to, and only a credit sale adds anything to it.
        val outstanding = onFile?.takeIf { method == Method.CREDIT }?.let { customer ->
            val paidNow = id<TextInputEditText>(R.id.etCredit).text?.toString()?.toDoubleOrNull() ?: 0.0
            BillRounding.toPaise(customer.balance + (total() - paidNow).coerceAtLeast(0.0))
        }
        return BillReceiptRenderer.Draft(
            billNumber = BillDao(requireContext()).nextBillNumber(),
            dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
            cashier = SessionManager.currentUser?.userId?.uppercase() ?: "---",
            customer = BillReceiptRenderer.Draft.Customer(
                name = name.ifEmpty { null },
                phone = phone.ifEmpty { null },
                gstin = gstin.ifEmpty { null },
                address = address.ifEmpty { null },
                outstanding = outstanding
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
                    hsn = catalog.firstOrNull { it.id.toLongOrNull() == line.productId }?.hsn,
                    unit = catalog.firstOrNull { it.id.toLongOrNull() == line.productId }?.unit
                )
            },
            discount = discountAmt(),
            roundOff = roundOffAmt(),
            netAmount = total(),
            paymentModes = listOf(method.name),
            returnAmount = if (method == Method.CASH && cashReceptionEnabled()) {
                val tendered = id<TextInputEditText>(R.id.etCash).text?.toString()?.toDoubleOrNull() ?: total()
                (tendered - total()).coerceAtLeast(0.0)
            } else 0.0
        )
    }

    // ---- Complete ----------------------------------------------------------

    private fun complete() {
        if (lines.isEmpty()) { toast("The bill is empty"); return }
        // Re-checked here, not just when credit was chosen: the cart may have grown
        // past the customer's limit since.
        if (method == Method.CREDIT && blockCreditIfIneligible()) return

        val result = generateBill()
        if (result == null) {
            toast("Failed to generate bill")
            return
        }

        // The sale is committed, so start a fresh sale right now - not only when
        // "Start new sale" is tapped. However the operator leaves this screen next
        // (that button, "Reprint" then back, or the system back button), the cart
        // that was just billed must not carry over to the billing screen.
        CheckoutSession.lines = mutableListOf()
        CheckoutSession.startFreshSale = true

        // The receipt goes out without waiting to be asked: the operator hands over
        // paper while the dialog is still up.
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
        // This can run from the printer-setup dialog's callback, which may fire after
        // the operator has left checkout. Use the current context and bail if the
        // screen is gone, rather than crashing on requireContext(). The sale is saved,
        // so it can still be reprinted from Recent Bills.
        val ctx = context ?: return
        val capture = BillReceiptRenderer(ctx).renderToBitmap(receiptNo, config.paperDots)
        if (capture == null) {
            toast("Could not render the receipt")
            return
        }
        // Bill Settings' "Two Copy" toggle - sent as two separate jobs off the one
        // rendered bitmap, not two renders.
        val copies = if (BillSettingsDao(ctx).load().twoCopyBill) 2 else 1
        ThermalPrinter.printCopies(ctx, capture, config, copies) { result ->
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

        // Resolve customer: credit dialog details take precedence, else the sale's
        // customer. With capture off, a non-credit sale has none at all - the fields
        // stay empty and customer_id below resolves to null.
        val custName = if (customerApplies()) creditCustomerName.ifEmpty { CheckoutSession.customerName ?: "" } else ""
        val custPhone = if (customerApplies()) creditCustomerPhone.ifEmpty { CheckoutSession.customerPhone ?: "" } else ""
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
        // Appended, never replaced: any number of sales can sit on hold at once, the
        // same as on the billing screen - both work the one list on the session.
        val custData = CheckoutSession.customerId?.let {
            mapOf<String, Any?>("id" to it, "name" to CheckoutSession.customerName, "phone" to CheckoutSession.customerPhone)
        }
        val label = CheckoutSession.holdLabel(id<TextView>(R.id.tvOrder).text?.toString().orEmpty())
        CheckoutSession.heldOrders.add(
            CheckoutSession.HeldBill(
                label, lines.map { it.copy() },
                CheckoutSession.discountMode, CheckoutSession.discountValue, false,
                CheckoutSession.customerName, CheckoutSession.customerPhone, custData
            )
        )
        lines.clear()
        renderItems()
        refreshTotals()
        updateHeldButton()
        toast("$label put on hold")
        // Refresh the billing page on the way back, so the held sale is cleared there
        // too - same flow as holding from the billing screen.
        CheckoutSession.startFreshSale = true
        parentFragmentManager.popBackStack()
    }

    /** The parked sales, listed by bill number; picking one offers to restore it. */
    private fun showHeldDialog() {
        if (CheckoutSession.heldOrders.isEmpty()) { toast("No sales on hold"); return }

        val items = CheckoutSession.heldOrders.map { h ->
            val details = listOfNotNull(
                "${qtyText(h.lines.sumOf { it.qty })} items",
                h.customerName?.takeIf { it.isNotBlank() },
                "held ${heldTime(h.heldAt)}"
            ).joinToString(" · ")
            DialogUtils.ListItem(
                title = h.label,
                subtitle = details,
                trailing = money(heldTotal(h))
            )
        }

        DialogUtils.showList(
            requireContext(),
            title = "Held Bills",
            items = items,
            subtitle = "Tap a bill to restore it"
        ) { index -> confirmRestoreHeld(index) }
    }

    /** Asks before a held bill replaces the sale currently being charged. */
    private fun confirmRestoreHeld(index: Int) {
        val heldBill = CheckoutSession.heldOrders.getOrNull(index) ?: return

        val subtotal = heldBill.lines.sumOf { it.price * it.qty }
        val discAmt = GstCalculator.discountAmount(subtotal, heldBill.discountMode, heldBill.discountValue)
        val tax = heldBill.lines.map { lineTax(it, subtotal, discAmt) }.sumOf { it.cgst + it.sgst + it.vat }
        val message = StringBuilder().apply {
            // Capped, because the card cannot scroll: a fifty-line bill would push
            // the buttons off the screen.
            heldBill.lines.take(HELD_PREVIEW_LINES).forEach { line ->
                append("${line.name}  ×${line.qty}   ${money(line.price * line.qty)}\n")
            }
            if (heldBill.lines.size > HELD_PREVIEW_LINES) {
                append("…and ${heldBill.lines.size - HELD_PREVIEW_LINES} more\n")
            }
            append("\nSubtotal: ${money(subtotal)}\n")
            if (discAmt > 0.0) append("Discount: -${money(discAmt)}\n")
            append("${taxLabelText()}: ${money(tax)}\n")
            append("Total: ${money(heldTotal(heldBill))}\n")
            append(
                if (lines.isEmpty()) "\nRestore this bill into the cart?"
                else "\nRestore this bill? The sale being charged will be replaced."
            )
        }.toString()

        DialogUtils.showConfirm(
            requireContext(),
            title = "Restore ${heldBill.label}?",
            message = message,
            positiveText = "Restore",
            negativeText = "Cancel",
            onCancel = { showHeldDialog() }
        ) { resumeHeld(index) }
    }

    /** What a held bill would be charged, taxed the way this screen taxes the live one. */
    private fun heldTotal(held: CheckoutSession.HeldBill): Double {
        val subtotal = held.lines.sumOf { it.price * it.qty }
        val discAmt = GstCalculator.discountAmount(subtotal, held.discountMode, held.discountValue)
        val lineTaxes = held.lines.map { lineTax(it, subtotal, discAmt) }
        val taxable = lineTaxes.sumOf { it.taxable }
        val tax = lineTaxes.sumOf { it.cgst + it.sgst + it.vat }
        val itemwiseExtra = lineTaxes.sumOf { it.discount }
        return if (discountPreTax) {
            (taxable + tax - itemwiseExtra).coerceAtLeast(0.0)
        } else {
            (taxable + tax - discAmt - itemwiseExtra).coerceAtLeast(0.0)
        }
    }

    /** Clock time a bill was parked at, for the held-bills picker. */
    private fun heldTime(at: Long): String =
        SimpleDateFormat("hh:mm a", Locale.US).format(Date(at))

    private fun resumeHeld(index: Int) {
        val restoredBill = CheckoutSession.heldOrders.getOrNull(index) ?: return
        CheckoutSession.heldOrders.removeAt(index)
        // Handed back to the billing screen, which is where a cart is edited - this
        // screen only ever charges what it was given.
        CheckoutSession.restoredBill = restoredBill
        updateHeldButton()
        requireActivity().supportFragmentManager.popBackStack()
    }

    private fun updateHeldButton() {
        id<MaterialButton>(R.id.btnHeld).text = "Held (${CheckoutSession.heldOrders.size})"
    }

    // ---- Credit Customer Information ----------------------------------------

    private fun showCreditCustomerDialog() {
        prefillCreditCustomer()
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

        // Whatever the master already holds for this customer, so the form opens as
        // the record stands and a field left alone saves back unchanged.
        val onFile = currentCustomer()

        /** Adds one outlined input at [row], spanning [span] of the grid's 2 columns. */
        fun field(hint: String, row: Int, col: Int, span: Int, value: String): TextInputLayout {
            val til = inflater.inflate(R.layout.item_form_field, null, false) as TextInputLayout
            til.hint = hint
            til.layoutParams = GridLayout.LayoutParams().apply {
                rowSpec = GridLayout.spec(row)
                columnSpec = GridLayout.spec(col, span, 1f)
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(margin, margin / 2, margin, margin / 2)
            }
            grid.addView(til)
            til.findViewById<TextInputEditText>(R.id.etField).setText(value)
            return til
        }

        fun editText(til: TextInputLayout) = til.findViewById<TextInputEditText>(R.id.etField)

        // The same fields the customer master keeps, in the same order, so a credit
        // sale can capture the whole record rather than the four details the bill
        // itself needs and leave the rest to be filled in later.
        val etPhone = editText(field("Phone Number", 0, 0, 2, creditCustomerPhone)).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(10))
        }
        val etName = editText(field("Customer Name", 1, 0, 2, creditCustomerName))
            .apply { InputLimits.cap(this, InputLimits.TEXT) }
        val etAddress = editText(field("Address", 2, 0, 2, creditCustomerAddress)).apply {
            minLines = 3
            maxLines = 5
            isSingleLine = false
            InputLimits.cap(this, InputLimits.TEXT_AREA)
        }
        val etGstin = editText(field("GSTIN", 3, 0, 2, creditCustomerGstin))
            .apply { InputLimits.cap(this, InputLimits.GSTIN) }

        // Dates are picked from a calendar, never typed, so they can only ever be
        // stored in the yyyy-MM-dd the master expects.
        val etBirthday = editText(field("Birthday", 4, 0, 1, onFile?.birthday.orEmpty())).apply {
            isFocusable = false
            setOnClickListener { pickDate(text?.toString()) { setText(it) } }
        }
        val etAnniversary = editText(field("Anniversary", 4, 1, 1, onFile?.anniversary.orEmpty())).apply {
            isFocusable = false
            setOnClickListener { pickDate(text?.toString()) { setText(it) } }
        }

        // Credit switch, laid out as its own full-width row.
        val creditRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = GridLayout.LayoutParams().apply {
                rowSpec = GridLayout.spec(5)
                columnSpec = GridLayout.spec(0, 2, 1f)
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(margin, margin / 2, margin, margin / 2)
            }
        }
        creditRow.addView(TextView(ctx).apply {
            text = "Credit"
            textSize = 16f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_main))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val swCredit = SwitchMaterial(ctx).apply {
            isChecked = onFile?.creditEnabled ?: true
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        creditRow.addView(swCredit)
        grid.addView(creditRow)

        val tilLimit = field("Credit Limit", 6, 0, 1, onFile?.let { trimNumber(it.creditLimit) }.orEmpty())
        val tilBalance = field("Balance Amount", 6, 1, 1, onFile?.let { trimNumber(it.balance) }.orEmpty())
        editText(tilLimit).inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        editText(tilBalance).inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        InputLimits.cap(editText(tilLimit), InputLimits.NUMBER)
        InputLimits.cap(editText(tilBalance), InputLimits.NUMBER)

        // Credit gates the figures that only exist because of it, exactly as the
        // customer master does.
        fun applyCreditState(enabled: Boolean) {
            tilLimit.isEnabled = enabled
            tilBalance.isEnabled = enabled
        }
        applyCreditState(swCredit.isChecked)
        swCredit.setOnCheckedChangeListener { _, checked -> applyCreditState(checked) }

        // Phone autocomplete with suggestions
        val customerDao = CustomerDao(ctx)
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
                                    // Picking a suggestion fills the whole record, not
                                    // just the four details the bill needs.
                                    etPhone.setText(customer.phone)
                                    etName.setText(customer.name)
                                    etAddress.setText(customer.address)
                                    etGstin.setText(customer.gstin)
                                    etBirthday.setText(customer.birthday)
                                    etAnniversary.setText(customer.anniversary)
                                    swCredit.isChecked = customer.creditEnabled
                                    editText(tilLimit).setText(trimNumber(customer.creditLimit))
                                    editText(tilBalance).setText(trimNumber(customer.balance))
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
        ThemeManager.styleDialogButtons(btnPositive, btnNegative)

        btnPositive.setOnClickListener {
            val phone = etPhone.text?.toString()?.trim().orEmpty()
            if (phone.length != 10 || !phone.all { it.isDigit() }) {
                toast("Phone number must be exactly 10 digits")
                return@setOnClickListener
            }
            val name = etName.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                toast("Customer name is required for a credit bill")
                return@setOnClickListener
            }

            val credit = swCredit.isChecked
            val limit = editText(tilLimit).text?.toString()?.toDoubleOrNull() ?: 0.0
            // A balance already run up is not settled by switching credit off, so it
            // is carried over rather than zeroed - as in the master.
            val balance = if (credit) {
                editText(tilBalance).text?.toString()?.toDoubleOrNull() ?: 0.0
            } else {
                onFile?.balance ?: 0.0
            }
            // The limit is what the customer is allowed to owe, so it cannot be set
            // below what they already do - that would put them over their limit the
            // moment it was saved.
            if (credit && limit < balance - 0.005) {
                toast("Credit limit cannot be less than the outstanding ${money(balance)}")
                return@setOnClickListener
            }

            creditCustomerPhone = phone
            creditCustomerName = name
            creditCustomerAddress = etAddress.text?.toString()?.trim().orEmpty()
            creditCustomerGstin = etGstin.text?.toString()?.trim().orEmpty()

            saveCreditCustomer(
                CustomerDao.Customer(
                    id = onFile?.id ?: 0L,
                    name = name,
                    address = creditCustomerAddress,
                    phone = phone,
                    gstin = creditCustomerGstin,
                    creditEnabled = credit,
                    creditLimit = if (credit) limit else 0.0,
                    balance = balance,
                    birthday = etBirthday.text?.toString()?.trim().orEmpty(),
                    anniversary = etAnniversary.text?.toString()?.trim().orEmpty()
                )
            )
            updateHeaderWithCustomer()
            dialog.dismiss()
            toast("Customer details saved")

            // Straight back through the gate on what was just saved. Clearing it puts
            // the sale back on credit - what the operator opened this dialog to do -
            // while a limit still short of the bill says so again, rather than leaving
            // them to find out at checkout.
            if (!blockCreditIfIneligible()) setMethod(Method.CREDIT)
        }

        btnNegative.setOnClickListener { dialog.dismiss() }

        dialog.show()
        val window = dialog.window
        window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setGravity(android.view.Gravity.CENTER)
    }

    /**
     * Hides the customer strip when this sale has no customer to speak of. Called
     * again on every payment-mode change, because switching to Credit gives the
     * sale a customer and switching away takes it back.
     */
    private fun applyCustomerVisibility() {
        val show = customerApplies()
        id<View>(R.id.llCustomerHeader).visibility = if (show) View.VISIBLE else View.GONE
        id<android.widget.ImageButton>(R.id.btnCustInfo).visibility =
            if (show) View.VISIBLE else View.GONE
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

    /** Whole quantities show without decimals; fractional ones keep up to 3 places. */
    private fun qtyText(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString()
        else String.format("%.3f", v).trimEnd('0').trimEnd('.')
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
