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
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-process hand-off of the current sale from billing to checkout. */
object CheckoutSession {
    data class Line(val name: String, val sku: String, var price: Double, var qty: Int)

    var lines: MutableList<Line> = mutableListOf()
    var customerName: String? = null
    var customerPhone: String? = null
    var orderNo: Int = 1042
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
    private data class Held(val label: String, val lines: List<CheckoutSession.Line>)

    // Working copy of the sale (edits here don't touch billing).
    private val lines = CheckoutSession.lines.map { it.copy() }.toMutableList()
    private val heldOrders = mutableListOf<Held>()

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
        id<TextView>(R.id.tvOrder).text = "#${CheckoutSession.orderNo}"
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

        // Accent bars
        id<View>(R.id.barLeftTotal).setBackgroundColor(accent)
        id<View>(R.id.barAmountDue).setBackgroundColor(accent)

        // Mode toggle
        id<MaterialButton>(R.id.btnModeEdit).setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        id<MaterialButton>(R.id.btnModeReceipt).setOnClickListener { setMode(false) }

        // Add line
        id<MaterialButton>(R.id.btnAddLine).setOnClickListener { addLine() }

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

    private fun addLine() {
        val name = id<TextInputEditText>(R.id.etAddName).text?.toString()?.trim().orEmpty()
        val price = id<TextInputEditText>(R.id.etAddPrice).text?.toString()?.toDoubleOrNull()
        if (name.isEmpty() || price == null || price <= 0) { toast("Enter item name and price"); return }
        lines.add(CheckoutSession.Line(name, "MAN", price, 1))
        id<TextInputEditText>(R.id.etAddName).setText("")
        id<TextInputEditText>(R.id.etAddPrice).setText("")
        renderItems(); refreshTotals()
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
            showCreditCustomerDialog()
        }
    }


    private fun applyTileStyles() {
        listOf(
            R.id.btnCash to Method.CASH, R.id.btnCredit to Method.CREDIT,
            R.id.btnCard to Method.CARD, R.id.btnOnline to Method.ONLINE
        ).forEach { (bId, value) ->
            val b = id<MaterialButton>(bId)
            if (value == method) {
                styleFilled(b)
            } else {
                styleOutlined(b)
            }
        }
    }

    // ---- Totals ------------------------------------------------------------

    private fun totalPct() = 0
    private fun subtotal() = lines.sumOf { it.price * it.qty }
    private fun discountAmt() = subtotal() * totalPct() / 100.0
    private fun taxAmt() = (subtotal() - discountAmt()).coerceAtLeast(0.0) * 0.05
    private fun total() = (subtotal() - discountAmt()).coerceAtLeast(0.0) + taxAmt()

    private fun refreshTotals() {
        val disc = "Discount (${totalPct()}%)"
        id<TextView>(R.id.tvSubtotal).text = money(subtotal())
        id<TextView>(R.id.tvDiscountLabel).text = disc
        id<TextView>(R.id.tvDiscountAmt).text = "- ${money(discountAmt())}"
        id<TextView>(R.id.tvTax).text = money(taxAmt())
        id<TextView>(R.id.tvLeftTotal).text = money(total())
        id<TextView>(R.id.tvAmountDue).text = money(total())

        // Update item count
        val itemCount = lines.sumOf { it.qty }
        id<TextView>(R.id.tvItemCount).text = "Items: $itemCount"

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

    // ---- Complete ----------------------------------------------------------

    private fun complete() {
        if (lines.isEmpty()) { toast("The bill is empty"); return }
        val method = titleFor(method)
        val change = if (this.method == Method.CASH) {
            val t = id<TextInputEditText>(R.id.etCash).text?.toString()?.toDoubleOrNull() ?: 0.0
            " · Change ${money((t - total()).coerceAtLeast(0.0))}"
        } else ""

        DialogUtils.showConfirm(
            context = requireContext(),
            title = "Checkout complete",
            message = "Bill No: #${CheckoutSession.orderNo}",
            positiveText = "Start new sale",
            negativeText = "Reprint",
            iconRes = R.drawable.ic_check,
            onConfirm = {
                CheckoutSession.orderNo++
                // Signal to the billing fragment to clear its state for a new sale
                parentFragmentManager.setFragmentResult("request_new_sale", Bundle())
                requireActivity().supportFragmentManager.popBackStack()
            },
            onCancel = {
                toast("Reprinting bill #${CheckoutSession.orderNo}...")
            }
        )
    }

    // ---- Hold / Resume held orders ------------------------------------------

    private fun onHold() {
        if (lines.isEmpty()) { toast("Cart is empty"); return }
        heldOrders.add(Held("Sale #${heldOrders.size + 1}", lines.map { it.copy() }))
        lines.clear()
        renderItems()
        refreshTotals()
        updateHeldButton()
        toast("Sale put on hold")
    }

    private fun showHeldDialog() {
        if (heldOrders.isEmpty()) { toast("No sales on hold"); return }
        val labels = heldOrders.map { h ->
            "${h.label} · ${h.lines.sumOf { it.qty }} items · ${money(h.lines.sumOf { it.price * it.qty })}"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Held orders")
            .setItems(labels) { _, which -> resumeHeld(which) }
            .setNegativeButton("Cancel", null)
            .create()
            .also { it.setCanceledOnTouchOutside(false); it.show() }
    }

    private fun resumeHeld(index: Int) {
        val h = heldOrders.removeAt(index)
        lines.clear()
        lines.addAll(h.lines.map { it.copy() })
        renderItems()
        refreshTotals()
        updateHeldButton()
        toast("Sale resumed")
    }

    private fun updateHeldButton() {
        id<MaterialButton>(R.id.btnHeld).text = "Held (${heldOrders.size})"
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
        btn.setBackgroundColor(accent)
        btn.setTextColor(Color.WHITE)
        btn.strokeWidth = 0
        btn.iconTint = ColorStateList.valueOf(Color.WHITE)
        btn.cornerRadius = (resources.displayMetrics.density * 12).toInt()
    }

    /** Restores an outlined button's white fill + accent border/text/icon. */
    private fun styleOutlined(btn: MaterialButton) {
        btn.setBackgroundColor(Color.WHITE)
        btn.setTextColor(accent)
        btn.setStrokeColorResource(android.R.color.transparent)
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
