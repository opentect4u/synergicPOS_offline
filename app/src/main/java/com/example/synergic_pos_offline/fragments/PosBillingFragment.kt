package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Point-of-sale billing terminal, faithfully modelled on the shared design:
 * modular header, product region (search + Enter Price / Customer, category
 * tab strip, product grid, shortcut hints) and a live order ticket (customer,
 * cart, coupon/discount, totals, Hold / Charge). Responsive: the product grid
 * re-flows its column count and the cart panel width adapts to the screen.
 */
class PosBillingFragment : Fragment(), TitledScreen {

    override val screenTitle = "Sale"

    // ---- Catalog (from the shared design) ----------------------------------

    private data class Product(
        val id: String, val name: String, val sku: String,
        val category: String, val price: Double, val stock: String = "ok",
        val hsn: String = "0000", val gst: Double = 0.0, val unit: String = "pcs"
    ) {
        val cgst: Double get() = gst / 2.0
        val sgst: Double get() = gst / 2.0
    }

    /** HSN code, GST% and selling unit by category (used to enrich the catalog). */
    private data class TaxInfo(val hsn: String, val gst: Double, val unit: String)

    private val taxByCategory = mapOf(
        "Produce" to TaxInfo("0708", 0.0, "kg"),
        "Dairy" to TaxInfo("0401", 5.0, "pcs"),
        "Bakery" to TaxInfo("1905", 5.0, "pcs"),
        "Frozen" to TaxInfo("2106", 18.0, "pcs"),
        "Beverages" to TaxInfo("2202", 18.0, "pcs"),
        "Snacks" to TaxInfo("2106", 12.0, "pcs"),
        "Household" to TaxInfo("3402", 18.0, "pcs")
    )

    private data class CartLine(val product: Product, var qty: Int)
    private data class Held(val label: String, val lines: List<CartLine>, val discount: Int, val coupon: Boolean)

    private val categories = listOf(
        "All", "Produce", "Dairy", "Bakery", "Frozen", "Beverages", "Snacks", "Household"
    )

    private val menu = listOf(
        Product("p1", "Bananas (lb)", "4011", "Produce", 0.59),
        Product("p2", "Roma Tomatoes (lb)", "4087", "Produce", 1.29),
        Product("p3", "Avocado", "4225", "Produce", 1.25, "low"),
        Product("p4", "Red Onion (lb)", "4082", "Produce", 0.99),
        Product("p5", "Whole Milk 1 Gal", "1101", "Dairy", 3.79),
        Product("p6", "Large Eggs, Dozen", "1102", "Dairy", 2.99),
        Product("p7", "Cheddar Block 8oz", "1140", "Dairy", 4.49),
        Product("p8", "Salted Butter", "1155", "Dairy", 3.99, "out"),
        Product("p9", "White Bread Loaf", "2001", "Bakery", 2.49),
        Product("p10", "Bagels 6pk", "2015", "Bakery", 3.29),
        Product("p11", "Croissants 4pk", "2022", "Bakery", 4.99, "low"),
        Product("p12", "Pepperoni Pizza", "3010", "Frozen", 5.49),
        Product("p13", "Vanilla Ice Cream", "3044", "Frozen", 4.29),
        Product("p14", "Mixed Vegetables", "3067", "Frozen", 2.19),
        Product("p15", "Orange Juice 64oz", "5002", "Beverages", 3.49),
        Product("p16", "Cola 12pk Cans", "5019", "Beverages", 5.99),
        Product("p17", "Sparkling Water", "5033", "Beverages", 1.19, "low"),
        Product("p18", "Potato Chips", "6004", "Snacks", 3.29),
        Product("p19", "Pretzel Twists", "6011", "Snacks", 2.79),
        Product("p20", "Trail Mix", "6028", "Snacks", 4.49, "low"),
        Product("p21", "Paper Towels 6pk", "7003", "Household", 6.99),
        Product("p22", "Dish Soap", "7014", "Household", 2.49),
        Product("p23", "Trash Bags 30ct", "7029", "Household", 5.49, "out")
    ).map { p ->
        // Enrich each catalog entry with its category's HSN / GST / unit.
        val t = taxByCategory[p.category]
        if (t != null) p.copy(hsn = t.hsn, gst = t.gst, unit = t.unit) else p
    }

    // ---- State -------------------------------------------------------------

    private var activeCategory = "All"
    private var query = ""
    private var discountPercent = 0
    private var couponApplied = false
    private var customerName: String? = null
    private var customerPhone: String? = null
    private var lastAddedId: String? = null
    private val cart = mutableListOf<CartLine>()
    private val heldOrders = mutableListOf<Held>()

    private val shownProducts = mutableListOf<Product>()
    private lateinit var productAdapter: ProductAdapter
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var cartAdapter: CartAdapter

    private val clockHandler = Handler(Looper.getMainLooper())
    private lateinit var clockRunnable: Runnable

    // Views
    private lateinit var tvCartEmpty: TextView
    private lateinit var tvSubtotal: TextView
    private lateinit var tvDiscountLabel: TextView
    private lateinit var tvDiscountAmt: TextView
    private lateinit var tvTax: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvItemCount: TextView
    private lateinit var tvNoProducts: TextView
    private lateinit var tvClock: TextView
    private lateinit var btnHeld: MaterialButton
    private lateinit var btnCharge: MaterialButton
    private lateinit var btnAddCustomer: MaterialButton
    private lateinit var llCustomerInfo: View
    private lateinit var tvCustName: TextView
    private lateinit var tvCustSub: TextView
    private lateinit var tvCouponMsg: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_pos_billing, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)
        val density = resources.displayMetrics.density

        tvCartEmpty = view.findViewById(R.id.tvCartEmpty)
        tvSubtotal = view.findViewById(R.id.tvSubtotal)
        tvDiscountLabel = view.findViewById(R.id.tvDiscountLabel)
        tvDiscountAmt = view.findViewById(R.id.tvDiscountAmt)
        tvTax = view.findViewById(R.id.tvTax)
        tvTotal = view.findViewById(R.id.tvTotal)
        tvItemCount = view.findViewById(R.id.tvItemCount)
        tvNoProducts = view.findViewById(R.id.tvNoProducts)
        tvClock = view.findViewById(R.id.tvClock)
        btnHeld = view.findViewById(R.id.btnHeld)
        btnCharge = view.findViewById(R.id.btnCharge)
        btnAddCustomer = view.findViewById(R.id.btnAddCustomer)
        llCustomerInfo = view.findViewById(R.id.llCustomerInfo)
        tvCustName = view.findViewById(R.id.tvCustName)
        tvCustSub = view.findViewById(R.id.tvCustSub)
        tvCouponMsg = view.findViewById(R.id.tvCouponMsg)

        // Categories (underline tabs)
        val rvCategories = view.findViewById<RecyclerView>(R.id.rvCategories)
        rvCategories.layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
        categoryAdapter = CategoryAdapter()
        rvCategories.adapter = categoryAdapter

        // Products (responsive grid)
        val rvProducts = view.findViewById<RecyclerView>(R.id.rvProducts)
        val glm = GridLayoutManager(ctx, 2)
        rvProducts.layoutManager = glm
        productAdapter = ProductAdapter()
        rvProducts.adapter = productAdapter
        rvProducts.post {
            val span = max(1, (rvProducts.width / (168 * density)).toInt())
            glm.spanCount = span
        }

        // Cart
        val rvCart = view.findViewById<RecyclerView>(R.id.rvCart)
        rvCart.layoutManager = LinearLayoutManager(ctx)
        cartAdapter = CartAdapter()
        rvCart.adapter = cartAdapter

        val btnJumpTop = view.findViewById<MaterialButton>(R.id.btnJumpTop)
        btnJumpTop.setOnClickListener { rvCart.smoothScrollToPosition(0) }
        
        rvCart.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val firstVisible = (recyclerView.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                btnJumpTop.visibility = if (firstVisible > 0) View.VISIBLE else View.GONE
            }
        })

        // Responsive cart width. Tablets (the target: 10" / 15") get the wide
        // order ticket from the design; phones shrink it so it never dominates.
        val cartPanel = view.findViewById<View>(R.id.cartPanel)
        val screenDp = resources.displayMetrics.widthPixels / density
        val cartDp = when {
            screenDp >= 1100f -> 440f   // 15" tablet
            screenDp >= 820f -> 400f    // 10" tablet (landscape)
            screenDp >= 600f -> 360f    // small tablet / large phone landscape
            else -> min(320f, screenDp * 0.46f)   // phones
        }
        cartPanel.layoutParams = cartPanel.layoutParams.apply { width = (cartDp * density).toInt() }

        // Search
        view.findViewById<TextInputEditText>(R.id.etSearch).addTextChangedListener(simpleWatcher {
            query = it; applyFilter()
        })
        // Discount
        view.findViewById<TextInputEditText>(R.id.etDiscount).addTextChangedListener(simpleWatcher {
            discountPercent = (it.toIntOrNull() ?: 0).coerceIn(0, 100); updateTotals()
        })

        // Buttons — actions
        val btnCalculator = view.findViewById<MaterialButton>(R.id.btnCalculator)
        val btnCustomer = view.findViewById<MaterialButton>(R.id.btnCustomer)
        val btnHold = view.findViewById<MaterialButton>(R.id.btnHold)
        btnCalculator.setOnClickListener { showCalculatorDialog() }
        btnCustomer.setOnClickListener { showCustomerDialog() }
        btnAddCustomer.setOnClickListener { showCustomerDialog() }
        view.findViewById<ImageButton>(R.id.btnRemoveCust).setOnClickListener { setCustomer(null, null) }
        btnHeld.setOnClickListener { showHeldDialog() }
        btnHold.setOnClickListener { onHold() }
        btnCharge.setOnClickListener { onCheckout() }

        setCustomer(null, null)
        updateHeldButton()
        applyFilter()
        updateTotals()

        // Theme everything, THEN restore each button's intended look
        ThemeManager.applyTheme(view)
        
        listOf(btnHeld, btnCalculator, btnCustomer, btnHold).forEach { styleOutlined(it, accent) }
        
        // "+ Add loyalty customer" is a borderless text button.
        btnAddCustomer.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        btnAddCustomer.setTextColor(accent)
        
        // Checkout button: Solid theme color
        btnCharge.backgroundTintList = ColorStateList.valueOf(accent)
        btnCharge.setTextColor(Color.WHITE)
        btnCharge.strokeWidth = 0

        // Listen for "New Sale" request from Checkout screen
        parentFragmentManager.setFragmentResultListener("request_new_sale", viewLifecycleOwner) { _, _ ->
            clearSale()
        }

        clockRunnable = object : Runnable {
            override fun run() {
                tvClock.text = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                clockHandler.postDelayed(this, 30_000)
            }
        }
        clockRunnable.run()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clockHandler.removeCallbacks(clockRunnable)
    }

    // ---- Filtering / cart --------------------------------------------------

    private fun applyFilter() {
        shownProducts.clear()
        shownProducts.addAll(menu.filter { p ->
            (activeCategory == "All" || p.category == activeCategory) &&
                (query.isEmpty() || p.name.contains(query, true) || p.sku.contains(query))
        })
        productAdapter.notifyDataSetChanged()
        tvNoProducts.visibility = if (shownProducts.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Adds [qty] units of [p] at [rate]. Merges with an existing line only when
     *  the same product is already in the cart at the same rate. */
    private fun addToCart(p: Product, qty: Int, rate: Double) {
        if (p.stock == "out") { toast("${p.name} is out of stock"); return }
        val priced = if (rate == p.price) p else p.copy(price = rate)
        
        // Find existing line at the SAME rate
        val existingIndex = cart.indexOfFirst { it.product.id == p.id && it.product.price == rate }
        
        if (existingIndex != -1) {
            val line = cart.removeAt(existingIndex)
            line.qty += qty
            cart.add(0, line)
        } else {
            cart.add(0, CartLine(priced, qty))
        }
        
        lastAddedId = p.id
        cartAdapter.notifyDataSetChanged()
        
        // Scroll to top to show the most recent item
        view?.findViewById<RecyclerView>(R.id.rvCart)?.scrollToPosition(0)

        updateTotals()
    }

    /**
     * Popup showing the product's details (HSN / GST / CGST / SGST) with editable
     * rate and quantity. When [editIndex] points at a cart line, the dialog edits
     * that line in place; otherwise it adds a new line.
     */
    private fun showProductDialog(p: Product, editIndex: Int = -1) {
        if (editIndex < 0 && p.stock == "out") { toast("${p.name} is out of stock"); return }
        val editing = editIndex in cart.indices
        val accent = ThemeManager.getThemeColor(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_product_entry, null)

        fun pct(v: Double) = (if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()) + "%"

        view.findViewById<TextView>(R.id.tvDialogTitle).text = p.name
        view.findViewById<TextView>(R.id.tvDialogCategory).text = p.category
        view.findViewById<TextView>(R.id.tvDetSku).text = p.sku
        view.findViewById<TextView>(R.id.tvDetHsn).text = p.hsn
        view.findViewById<TextView>(R.id.tvDetUnit).text = p.unit
        view.findViewById<TextView>(R.id.tvDetMrp).text = money(p.price)
        view.findViewById<TextView>(R.id.tvDetGst).text = pct(p.gst)
        view.findViewById<TextView>(R.id.tvDetCgst).text = pct(p.cgst)
        view.findViewById<TextView>(R.id.tvDetSgst).text = pct(p.sgst)
        view.findViewById<TextView>(R.id.tvCgstLabel).text = "CGST (${pct(p.cgst)})"
        view.findViewById<TextView>(R.id.tvSgstLabel).text = "SGST (${pct(p.sgst)})"

        val etRate = view.findViewById<TextInputEditText>(R.id.etRate)
        val etQty = view.findViewById<TextInputEditText>(R.id.etQty)
        val tvTaxable = view.findViewById<TextView>(R.id.tvTaxable)
        val tvCgstAmt = view.findViewById<TextView>(R.id.tvCgstAmt)
        val tvSgstAmt = view.findViewById<TextView>(R.id.tvSgstAmt)
        val tvAmount = view.findViewById<TextView>(R.id.tvLineAmount)
        val startRate = if (editing) cart[editIndex].product.price else p.price
        val startQty = if (editing) cart[editIndex].qty else 1
        etRate.setText(String.format("%.2f", startRate))
        etQty.setText(startQty.toString())

        fun refreshAmount() {
            val rate = etRate.text?.toString()?.toDoubleOrNull() ?: 0.0
            val qty = etQty.text?.toString()?.toIntOrNull() ?: 0
            val taxable = rate * qty
            val cgst = taxable * p.cgst / 100.0
            val sgst = taxable * p.sgst / 100.0
            tvTaxable.text = money(taxable)
            tvCgstAmt.text = money(cgst)
            tvSgstAmt.text = money(sgst)
            tvAmount.text = money(taxable + cgst + sgst)
        }
        etRate.addTextChangedListener(simpleWatcher { refreshAmount() })
        etQty.addTextChangedListener(simpleWatcher { refreshAmount() })
        refreshAmount()

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .create()
        dialog.setCanceledOnTouchOutside(false)

        val btnCancel = view.findViewById<MaterialButton>(R.id.btnDialogCancel)
        val btnAdd = view.findViewById<MaterialButton>(R.id.btnDialogAdd)
        btnAdd.text = if (editing) "Update" else "Add to cart"
        styleOutlined(btnCancel, accent)
        btnAdd.backgroundTintList = ColorStateList.valueOf(accent)
        btnAdd.setTextColor(Color.WHITE)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnAdd.setOnClickListener {
            val rate = etRate.text?.toString()?.toDoubleOrNull()
            val qty = etQty.text?.toString()?.toIntOrNull()
            when {
                rate == null || rate <= 0 -> toast("Enter a valid rate")
                qty == null || qty <= 0 -> toast("Enter a valid quantity")
                editing -> { updateCartLine(editIndex, qty, rate); dialog.dismiss() }
                else -> { addToCart(p, qty, rate); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    /** Replaces a cart line's rate and quantity (from the edit dialog). */
    private fun updateCartLine(index: Int, qty: Int, rate: Double) {
        if (index !in cart.indices) return
        val base = cart[index].product
        val priced = if (rate == base.price) base else base.copy(price = rate)
        cart[index] = CartLine(priced, qty)
        cartAdapter.notifyDataSetChanged()
        updateTotals()
    }

    private fun changeQty(pos: Int, delta: Int) {
        if (pos !in cart.indices) return
        val line = cart.removeAt(pos)
        line.qty += delta
        if (line.qty > 0) {
            cart.add(0, line)
            lastAddedId = line.product.id
            view?.findViewById<RecyclerView>(R.id.rvCart)?.scrollToPosition(0)
        }
        cartAdapter.notifyDataSetChanged()
        updateTotals()
    }

    private fun removeLine(pos: Int) {
        if (pos !in cart.indices) return
        cart.removeAt(pos)
        cartAdapter.notifyDataSetChanged()
        updateTotals()
    }

    // ---- Customer / coupon / price dialogs ---------------------------------

    private fun setCustomer(name: String?, phone: String? = null) {
        customerName = name
        customerPhone = phone
        if (name == null && phone == null) {
            btnAddCustomer.visibility = View.VISIBLE
            llCustomerInfo.visibility = View.GONE
        } else {
            btnAddCustomer.visibility = View.GONE
            llCustomerInfo.visibility = View.VISIBLE
            tvCustName.text = name ?: "Guest"
            tvCustSub.text = phone ?: "No phone"
        }
    }

    private fun showCustomerDialog() {
        DialogUtils.showForm(
            context = requireContext(),
            title = "Add Customer",
            fields = listOf(DialogUtils.FormField("Customer Phone No", customerPhone ?: "")),
            positiveText = "Look up",
            onSave = { values ->
                val phone = values[0]
                if (phone.isNotBlank()) {
                    // Mock: If phone is entered, identifying as Alex Rivera
                    setCustomer("Alex Rivera", phone)
                }
            }
        )
    }

    /** On-screen calculator. The computed value can be added to the cart as a
     *  manual-priced item. */
    private fun showCalculatorDialog() {
        val accent = ThemeManager.getThemeColor(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_calculator, null)
        val tvDisplay = view.findViewById<TextView>(R.id.tvDisplay)
        val tvExpr = view.findViewById<TextView>(R.id.tvExpr)
        val grid = view.findViewById<android.widget.GridLayout>(R.id.gridKeys)
        val density = resources.displayMetrics.density

        var expr = ""            // working expression, using display symbols
        var justEvaluated = false

        fun render() { tvDisplay.text = if (expr.isEmpty()) "0" else expr }

        fun press(key: String) {
            when (key) {
                "C" -> { expr = ""; tvExpr.text = "" }
                "⌫" -> if (expr.isNotEmpty()) expr = expr.dropLast(1)
                "=" -> {
                    val result = evalExpression(expr)
                    if (result != null) {
                        tvExpr.text = "$expr ="
                        expr = trimNumber(result)
                        justEvaluated = true
                    } else toast("Invalid expression")
                }
                "+", "−", "×", "÷" -> {
                    if (expr.isEmpty()) return
                    justEvaluated = false
                    // Replace a trailing operator instead of stacking them.
                    expr = if (expr.last() in "+−×÷") expr.dropLast(1) + key else expr + key
                }
                "%" -> if (expr.isNotEmpty()) { expr += "%"; justEvaluated = false }
                else -> {   // digits, "00", "."
                    if (justEvaluated && key != ".") { expr = ""; justEvaluated = false }
                    expr += key
                }
            }
            render()
        }

        val keys = listOf(
            "C", "⌫", "%", "÷",
            "7", "8", "9", "×",
            "4", "5", "6", "−",
            "1", "2", "3", "+",
            "0", "00", ".", "="
        )
        grid.post {
            val cell = (grid.width - (3 * 8 * density).toInt()) / 4
            keys.forEach { key ->
                val b = MaterialButton(requireContext(), null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = key
                    textSize = 18f
                    insetTop = 0; insetBottom = 0; minHeight = 0
                    cornerRadius = (10 * density).toInt()
                    val emphasise = key in listOf("÷", "×", "−", "+", "=")
                    if (key == "=") {
                        backgroundTintList = ColorStateList.valueOf(accent); setTextColor(Color.WHITE)
                    } else {
                        backgroundTintList = ColorStateList.valueOf(Color.WHITE)
                        setTextColor(if (emphasise) accent else ContextCompat.getColor(context, R.color.text_main))
                        strokeColor = ColorStateList.valueOf(accent)
                        strokeWidth = (density * 1f).toInt()
                    }
                    setOnClickListener { press(key) }
                }
                val lp = android.widget.GridLayout.LayoutParams().apply {
                    width = cell
                    height = (54 * density).toInt()
                    setMargins((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                }
                grid.addView(b, lp)
            }
        }
        render()

        val dialog = AlertDialog.Builder(requireContext()).setView(view).create()
        dialog.setCanceledOnTouchOutside(false)

        val btnClose = view.findViewById<MaterialButton>(R.id.btnCalcClose)
        val btnUse = view.findViewById<MaterialButton>(R.id.btnCalcUse)
        styleOutlined(btnClose, accent)
        btnUse.backgroundTintList = ColorStateList.valueOf(accent)
        btnUse.setTextColor(Color.WHITE)

        btnClose.setOnClickListener { dialog.dismiss() }
        btnUse.setOnClickListener {
            val value = evalExpression(expr) ?: expr.toDoubleOrNull()
            if (value != null && value > 0) {
                cart.add(CartLine(Product("M${System.currentTimeMillis()}", "Manual Item", "----", "All", value), 1))
                cartAdapter.notifyDataSetChanged()
                updateTotals()
                dialog.dismiss()
            } else toast("Enter a valid amount")
        }
        dialog.show()
    }

    /** Formats a result without a trailing ".0" for whole numbers. */
    private fun trimNumber(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else String.format("%.4f", v).trimEnd('0').trimEnd('.')

    /** Evaluates a flat +−×÷% expression (display symbols). Returns null if invalid. */
    private fun evalExpression(raw: String): Double? {
        if (raw.isBlank()) return null
        val normalized = raw.replace('×', '*').replace('÷', '/').replace('−', '-')
        // Tokenize into numbers and operators; '%' turns the preceding number into /100.
        val tokens = mutableListOf<String>()
        val num = StringBuilder()
        for (c in normalized) {
            when (c) {
                in '0'..'9', '.' -> num.append(c)
                '%' -> { if (num.isEmpty()) return null; num.append("*0.01_pct") }
                '+', '-', '*', '/' -> {
                    if (num.isEmpty()) {
                        // allow a leading unary minus
                        if (c == '-' && tokens.isEmpty()) { num.append('-'); continue }
                        return null
                    }
                    tokens.add(resolvePct(num.toString()) ?: return null); num.clear()
                    tokens.add(c.toString())
                }
                else -> return null
            }
        }
        if (num.isEmpty()) return null
        tokens.add(resolvePct(num.toString()) ?: return null)

        // Two-pass: * and / first, then + and -.
        val pass1 = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t == "*" || t == "/") {
                val a = pass1.removeAt(pass1.size - 1).toDouble()
                val b = tokens[i + 1].toDouble()
                pass1.add((if (t == "*") a * b else if (b == 0.0) return null else a / b).toString())
                i += 2
            } else { pass1.add(t); i++ }
        }
        var acc = pass1[0].toDouble()
        var j = 1
        while (j < pass1.size) {
            val op = pass1[j]; val b = pass1[j + 1].toDouble()
            acc = if (op == "+") acc + b else acc - b
            j += 2
        }
        return acc
    }

    private fun resolvePct(token: String): String? {
        if (!token.contains("*0.01_pct")) return token.toDoubleOrNull()?.toString()
        val base = token.replace("*0.01_pct", "").toDoubleOrNull() ?: return null
        return (base * 0.01).toString()
    }

    private fun applyCoupon(code: String) {
        when {
            code.trim().uppercase() == "SAVE10" -> {
                couponApplied = true
                tvCouponMsg.visibility = View.VISIBLE
                tvCouponMsg.text = "SAVE10 applied — 10% off"
            }
            code.isBlank() -> tvCouponMsg.visibility = View.GONE
            else -> {
                couponApplied = false
                tvCouponMsg.visibility = View.VISIBLE
                tvCouponMsg.text = "Invalid code"
            }
        }
        updateTotals()
    }

    // ---- Hold / charge -----------------------------------------------------

    private fun onHold() {
        if (cart.isEmpty()) { toast("Cart is empty"); return }
        heldOrders.add(
            Held("Sale #${heldOrders.size + 1}", cart.map { CartLine(it.product, it.qty) }, discountPercent, couponApplied)
        )
        clearSale()
        updateHeldButton()
        toast("Sale put on hold")
    }

    private fun showHeldDialog() {
        if (heldOrders.isEmpty()) { toast("No sales on hold"); return }
        val labels = heldOrders.map { h ->
            "${h.label} · ${h.lines.sumOf { it.qty }} items · ${money(totalOf(h.lines, h.discount, h.coupon))}"
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Held orders")
            .setItems(labels) { _, which -> resumeHeld(which) }
            .setNegativeButton("Close", null)
            .create()
            .also { it.setCanceledOnTouchOutside(false); it.show() }
    }

    private fun resumeHeld(index: Int) {
        val h = heldOrders.removeAt(index)
        cart.clear()
        cart.addAll(h.lines.map { CartLine(it.product, it.qty) })
        discountPercent = h.discount
        couponApplied = h.coupon
        cartAdapter.notifyDataSetChanged()
        updateHeldButton()
        updateTotals()
    }

    private fun updateHeldButton() { btnHeld.text = "Held (${heldOrders.size})" }

    private fun onCheckout() {
        if (cart.isEmpty()) { toast("Cart is empty"); return }
        // Hand the current sale to the checkout screen.
        CheckoutSession.lines = cart.map {
            CheckoutSession.Line(it.product.name, it.product.sku, it.product.price, it.qty)
        }.toMutableList()
        CheckoutSession.customerName = customerName
        CheckoutSession.customerPhone = customerPhone
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, PosCheckoutFragment())
            .addToBackStack(null)
            .commit()
    }

    private fun clearSale() {
        cart.clear()
        discountPercent = 0
        couponApplied = false
        lastAddedId = null
        tvCouponMsg.visibility = View.GONE
        setCustomer(null, null)
        cartAdapter.notifyDataSetChanged()
        updateTotals()
    }

    // ---- Totals ------------------------------------------------------------

    private fun totalPct() = min(100, discountPercent + if (couponApplied) 10 else 0)
    private fun subtotal(): Double = cart.sumOf { it.product.price * it.qty }
    private fun discountAmt(): Double = subtotal() * totalPct() / 100.0
    private fun taxAmt(): Double = (subtotal() - discountAmt()).coerceAtLeast(0.0) * 0.05
    private fun computeTotal(): Double = (subtotal() - discountAmt()).coerceAtLeast(0.0) + taxAmt()

    private fun totalOf(lines: List<CartLine>, disc: Int, coupon: Boolean): Double {
        val sub = lines.sumOf { it.product.price * it.qty }
        val pct = min(100, disc + if (coupon) 10 else 0)
        val afterDisc = (sub - sub * pct / 100.0).coerceAtLeast(0.0)
        return afterDisc + afterDisc * 0.05
    }

    private fun updateTotals() {
        tvCartEmpty.visibility = if (cart.isEmpty()) View.VISIBLE else View.GONE
        
        val totalQty = cart.sumOf { it.qty }
        tvItemCount.text = "$totalQty item${if (totalQty != 1) "s" else ""}"

        tvSubtotal.text = money(subtotal())
        tvDiscountLabel.text = "Discount (${totalPct()}%)"
        tvDiscountAmt.text = "- ${money(discountAmt())}"
        tvTax.text = money(taxAmt())
        tvTotal.text = money(computeTotal())
        btnCharge.text = "Checkout ${money(computeTotal())}"
    }

    private fun money(v: Double): String = "₹" + String.format("%.2f", v)

    private fun toast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun padded(v: View): View {
        val p = (20 * resources.displayMetrics.density).toInt()
        return android.widget.FrameLayout(requireContext()).apply { setPadding(p, p / 2, p, 0); addView(v) }
    }

    /** Restores an outlined button's transparent fill + accent border/text/icon. */
    private fun styleOutlined(btn: MaterialButton, accent: Int) {
        // White background, theme-coloured text + border.
        btn.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        btn.setTextColor(accent)
        btn.strokeColor = ColorStateList.valueOf(accent)
        btn.strokeWidth = (resources.displayMetrics.density * 1.2f).toInt()
        btn.iconTint = ColorStateList.valueOf(accent)
    }

    private fun simpleWatcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { onChange(s?.toString()?.trim().orEmpty()) }
        override fun afterTextChanged(s: Editable?) {}
    }

    // ---- Adapters ----------------------------------------------------------

    private inner class CategoryAdapter : RecyclerView.Adapter<CategoryAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tv: TextView = view.findViewById(R.id.tvCategory)
            val underline: View = view.findViewById(R.id.vUnderline)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_pos_category, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val cat = categories[position]
            val accent = ThemeManager.getThemeColor(holder.itemView.context)
            val selected = cat == activeCategory
            holder.tv.text = cat
            holder.tv.setTextColor(if (selected) accent else Color.parseColor("#8A8A8A"))
            holder.underline.setBackgroundColor(if (selected) accent else Color.TRANSPARENT)
            holder.itemView.setOnClickListener {
                activeCategory = cat
                notifyDataSetChanged()
                applyFilter()
            }
        }

        override fun getItemCount() = categories.size
    }

    private inner class ProductAdapter : RecyclerView.Adapter<ProductAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tvName)
            val price: TextView = view.findViewById(R.id.tvPrice)
            val sku: TextView = view.findViewById(R.id.tvSku)
            val stock: TextView = view.findViewById(R.id.tvStock)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_pos_product, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = shownProducts[position]
            holder.name.text = p.name
            holder.price.text = money(p.price)
            holder.sku.text = p.sku
            
            when (p.stock) {
                "low" -> {
                    holder.stock.visibility = View.VISIBLE
                    holder.stock.text = "Low stock"
                    val shape = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 8 * holder.itemView.resources.displayMetrics.density
                        setColor(Color.parseColor("#F9AB00")) // Amber
                    }
                    holder.stock.background = shape
                    holder.stock.setTextColor(Color.WHITE)
                }
                "out" -> {
                    holder.stock.visibility = View.VISIBLE
                    holder.stock.text = "Out of stock"
                    val shape = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 8 * holder.itemView.resources.displayMetrics.density
                        setColor(Color.parseColor("#D93025")) // Red
                    }
                    holder.stock.background = shape
                    holder.stock.setTextColor(Color.WHITE)
                }
                else -> holder.stock.visibility = View.GONE
            }
            holder.itemView.alpha = if (p.stock == "out") 0.5f else 1f
            holder.itemView.setOnClickListener { showProductDialog(p) }
        }

        override fun getItemCount() = shownProducts.size
    }

    private inner class CartAdapter : RecyclerView.Adapter<CartAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tvLineName)
            val each: TextView = view.findViewById(R.id.tvLineEach)
            val qty: TextView = view.findViewById(R.id.tvQty)
            val total: TextView = view.findViewById(R.id.tvLineTotal)
            val minus: ImageButton = view.findViewById(R.id.btnMinus)
            val plus: ImageButton = view.findViewById(R.id.btnPlus)
            val remove: ImageButton = view.findViewById(R.id.btnRemoveLine)
            val marker: View = view.findViewById(R.id.vNewMarker)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_pos_cart_line, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val line = cart[position]
            val accent = ThemeManager.getThemeColor(holder.itemView.context)
            
            holder.name.text = line.product.name
            holder.each.text = "${money(line.product.price)} each"
            holder.qty.text = line.qty.toString()
            holder.total.text = money(line.product.price * line.qty)
            
            // Show marker for the most recently added/updated item
            if (line.product.id == lastAddedId) {
                holder.marker.visibility = View.VISIBLE
                holder.marker.setBackgroundColor(accent)
            } else {
                holder.marker.visibility = View.INVISIBLE
            }

            holder.minus.setOnClickListener { changeQty(holder.adapterPosition, -1) }
            holder.plus.setOnClickListener { changeQty(holder.adapterPosition, +1) }
            holder.remove.setOnClickListener { removeLine(holder.adapterPosition) }
            holder.itemView.setOnClickListener {
                val pos = holder.adapterPosition
                if (pos in cart.indices) showProductDialog(cart[pos].product, pos)
            }
        }

        override fun getItemCount() = cart.size
    }
}
