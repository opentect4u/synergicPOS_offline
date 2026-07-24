package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.BillDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.CustomerDao
import com.example.synergic_pos_offline.utils.CustomerCardDialog
import com.example.synergic_pos_offline.utils.BillReceiptRenderer
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.GstCalculator
import com.example.synergic_pos_offline.utils.PrinterSetup
import com.example.synergic_pos_offline.utils.ProductEntryDialog
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
        /** Per-product GST rates from md_product_rates, carried so checkout and the
         *  bill tax each line at the rate its product actually charges. */
        val cgstRate: Double = 0.0,
        val sgstRate: Double = 0.0
    )
    data class HeldBill(
        val label: String, val lines: List<Line>, val discount: Int, val coupon: Boolean,
        val customerName: String? = null,
        val customerPhone: String? = null,
        val customerData: Map<String, Any?>? = null
    )

    var lines: MutableList<Line> = mutableListOf()
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

    private var editMode = true
    private var method = Method.CASH
    private var accent = 0

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
        lines.forEach { line ->
            val row = layoutInflater.inflate(R.layout.item_checkout_line, ll, false)
            row.findViewById<TextView>(R.id.tvName).text = line.name
            row.findViewById<TextView>(R.id.tvQty).text = "Qty: ${line.qty}"
            row.findViewById<TextView>(R.id.tvPrice).text = money(line.price * line.qty)

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
                       c.category_name, r.rate, r.cgst_rate, r.sgst_rate
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
                            sgst = if (c.isNull(7)) 0.0 else c.getDouble(7)
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
            confirmLabel = "Add to bill"
        ) { qty, rate ->
            lines.add(
                CheckoutSession.Line(
                    name = product.name,
                    sku = product.sku,
                    price = rate,
                    qty = qty,
                    productId = product.id.toLongOrNull(),
                    cgstRate = product.cgst,
                    sgstRate = product.sgst
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

    private fun totalPct() = 0
    private fun subtotal() = lines.sumOf { it.price * it.qty }
    private fun discountAmt() = subtotal() * totalPct() / 100.0

    /** Taxable value of a line once the whole-bill discount is spread over it. */
    private fun taxableOf(line: CheckoutSession.Line) =
        GstCalculator.taxableValue(line.price, line.qty, totalPct())

    private fun cgstAmt() = lines.sumOf { GstCalculator.taxAmount(taxableOf(it), it.cgstRate) }
    private fun sgstAmt() = lines.sumOf { GstCalculator.taxAmount(taxableOf(it), it.sgstRate) }

    /** GST at each product's own rate, not one blanket rate across the bill. */
    private fun taxAmt() = cgstAmt() + sgstAmt()

    /** Taxed value of the bill, before it is rounded to whole rupees. */
    private fun taxedTotal() = (subtotal() - discountAmt()).coerceAtLeast(0.0) + taxAmt()

    private fun roundOffAmt() = BillRounding.roundOff(taxedTotal())

    /**
     * What the customer actually pays. Everything downstream - the amount due, the
     * cash validation, the figure written to the bill - works from this, so the
     * receipt, the payment record and the till all agree on one number.
     */
    private fun total() = BillRounding.payable(taxedTotal())

    private fun refreshTotals() {
        val disc = "Discount (${totalPct()}%)"
        id<TextView>(R.id.tvSubtotal).text = money(subtotal())
        id<TextView>(R.id.tvDiscountLabel).text = disc
        id<TextView>(R.id.tvDiscountAmt).text = "- ${money(discountAmt())}"
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

        // Cash change
        val tendered = id<TextInputEditText>(R.id.etCash).text?.toString()?.toDoubleOrNull() ?: 0.0
        id<TextView>(R.id.tvChange).text = money((tendered - total()).coerceAtLeast(0.0))

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

    private fun renderReceipt() {
        val ll = id<LinearLayout>(R.id.llReceiptItems)
        ll.removeAllViews()
        val ctx = requireContext()

        // Store identity from the registered store, not a hardcoded placeholder.
        renderStoreHeader(ctx)
        lines.forEach { line ->
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
            }
            val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(TextView(ctx).apply {
                text = line.name; textSize = 13f; typeface = Typeface.MONOSPACE
                setTextColor(ContextCompat.getColor(ctx, R.color.text_main))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(TextView(ctx).apply {
                text = money(line.price * line.qty); textSize = 13f; typeface = Typeface.MONOSPACE
                setTextColor(ContextCompat.getColor(ctx, R.color.text_main))
            })
            col.addView(top)
            col.addView(TextView(ctx).apply {
                text = "${line.qty} × ${money(line.price)}"; textSize = 11f; typeface = Typeface.MONOSPACE
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            })
            ll.addView(col)
        }
        id<TextView>(R.id.tvRcSubtotal).text = money(subtotal())
        id<TextView>(R.id.tvRcDiscountLabel).text = "Discount (${totalPct()}%)"
        id<TextView>(R.id.tvRcDiscount).text = "- ${money(discountAmt())}"
        id<TextView>(R.id.tvRcTax).text = money(taxAmt())
        id<TextView>(R.id.tvRcTotal).text = money(total())
    }

    /** Fills the receipt's store name + address/GSTIN/phone line from md_registration. */
    private fun renderStoreHeader(ctx: android.content.Context) {
        val db = DatabaseHelper.getInstance(ctx).readableDatabase
        db.query(
            DatabaseHelper.Tables.MD_REGISTRATION,
            arrayOf("store_name", "address", "phone_no", "store_gstin"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: "SYNERGIC POS"
                val address = c.getString(1)?.takeIf { it.isNotBlank() }
                val phone = c.getString(2)?.takeIf { it.isNotBlank() }
                val gstin = c.getString(3)?.takeIf { it.isNotBlank() }

                id<TextView>(R.id.tvRcStoreName).text = name.uppercase()

                // Build a single info line from whatever the store actually has.
                val parts = mutableListOf<String>()
                address?.let { parts.add(it) }
                gstin?.let { parts.add("GSTIN $it") }
                phone?.let { parts.add("Tel $it") }
                val info = id<TextView>(R.id.tvRcStoreInfo)
                if (parts.isEmpty()) {
                    info.visibility = View.GONE
                } else {
                    info.visibility = View.VISIBLE
                    info.text = parts.joinToString("\n")
                }
            }
        }
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
        ThermalPrinter.print(requireContext(), capture, config) { result ->
            // The sale is already saved, so a printer problem is reported and never
            // blocks the till: the bill can always be reprinted from Recent Bills.
            if (!isAdded) return@print
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

        // Each line is taxed at its own product's rates, and carries its share of the
        // whole-bill discount so the DAO taxes the same base the totals were built on.
        val items = lines.map { line ->
            val gross = line.price * line.qty
            BillDao.Item(
                productId = line.productId,
                name = line.name,
                quantity = line.qty.toDouble(),
                rate = line.price,
                cgstRate = line.cgstRate,
                sgstRate = line.sgstRate,
                discountAmount = gross * totalPct() / 100.0
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
                val tendered = id<TextInputEditText>(R.id.etCash).text?.toString()?.toDoubleOrNull() ?: grandTotal
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
            discountPercentage = totalPct().toDouble(),
            cgstAmount = cgstTotal,
            sgstAmount = sgstTotal,
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
                "Sale #1", lines.map { it.copy() }, 0, false,
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
            append("\nSubtotal: ${money(subtotal)}\n")
            if (heldBill.discount > 0) {
                append("Discount (${heldBill.discount}%): -${money(subtotal * heldBill.discount / 100)}\n")
            }
            append("Tax (5%): ${money(subtotal * (100 - heldBill.discount) / 100 * 0.05)}\n")
            val total = subtotal * (100 - heldBill.discount) / 100 + subtotal * (100 - heldBill.discount) / 100 * 0.05
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

    private fun money(v: Double) = "₹" + String.format("%.2f", v)
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
