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
        val category: String, val price: Double, val stock: String = "ok"
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
    )

    // ---- State -------------------------------------------------------------

    private var activeCategory = "All"
    private var query = ""
    private var discountPercent = 0
    private var couponApplied = false
    private var customerName: String? = null
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
        val btnEnterPrice = view.findViewById<MaterialButton>(R.id.btnEnterPrice)
        val btnCustomer = view.findViewById<MaterialButton>(R.id.btnCustomer)
        val btnApplyCoupon = view.findViewById<MaterialButton>(R.id.btnApplyCoupon)
        val btnHold = view.findViewById<MaterialButton>(R.id.btnHold)
        btnEnterPrice.setOnClickListener { showPriceDialog() }
        btnCustomer.setOnClickListener { showCustomerDialog() }
        btnAddCustomer.setOnClickListener { showCustomerDialog() }
        view.findViewById<ImageButton>(R.id.btnRemoveCust).setOnClickListener { setCustomer(null) }
        btnApplyCoupon.setOnClickListener {
            applyCoupon(view.findViewById<TextInputEditText>(R.id.etCoupon).text?.toString().orEmpty())
        }
        btnHeld.setOnClickListener { showHeldDialog() }
        btnHold.setOnClickListener { onHold() }
        btnCharge.setOnClickListener { onCheckout() }

        setCustomer(null)
        updateHeldButton()
        applyFilter()
        updateTotals()

        // Theme everything, THEN restore each button's intended look
        ThemeManager.applyTheme(view)
        
        listOf(btnHeld, btnEnterPrice, btnCustomer, btnApplyCoupon, btnHold).forEach { styleOutlined(it, accent) }
        
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

    private fun addToCart(p: Product) {
        if (p.stock == "out") { toast("${p.name} is out of stock"); return }
        val line = cart.firstOrNull { it.product.id == p.id }
        if (line != null) line.qty++ else cart.add(CartLine(p, 1))
        cartAdapter.notifyDataSetChanged()
        updateTotals()
    }

    private fun changeQty(pos: Int, delta: Int) {
        if (pos !in cart.indices) return
        val line = cart[pos]
        line.qty += delta
        if (line.qty <= 0) cart.removeAt(pos)
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

    private fun setCustomer(name: String?) {
        customerName = name
        if (name == null) {
            btnAddCustomer.visibility = View.VISIBLE
            llCustomerInfo.visibility = View.GONE
        } else {
            btnAddCustomer.visibility = View.GONE
            llCustomerInfo.visibility = View.VISIBLE
            tvCustName.text = name
            tvCustSub.text = "340 pts"
        }
    }

    private fun showCustomerDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Phone or loyalty ID"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Loyalty customer")
            .setView(padded(input))
            .setPositiveButton("Look up") { _, _ ->
                if (input.text.toString().isNotBlank()) setCustomer("Alex Rivera")
            }
            .setNegativeButton("Cancel", null)
            .create()
            .also { it.setCanceledOnTouchOutside(false); it.show() }
    }

    private fun showPriceDialog() {
        val input = EditText(requireContext()).apply {
            hint = "0.00"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Enter price")
            .setView(padded(input))
            .setPositiveButton("Add to cart") { _, _ ->
                val price = input.text.toString().toDoubleOrNull()
                if (price != null && price > 0) {
                    cart.add(CartLine(Product("M${System.currentTimeMillis()}", "Manual Item", "----", "All", price), 1))
                    cartAdapter.notifyDataSetChanged()
                    updateTotals()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .also { it.setCanceledOnTouchOutside(false); it.show() }
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
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, PosCheckoutFragment())
            .addToBackStack(null)
            .commit()
    }

    private fun clearSale() {
        cart.clear()
        discountPercent = 0
        couponApplied = false
        tvCouponMsg.visibility = View.GONE
        setCustomer(null)
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
                "low" -> { holder.stock.visibility = View.VISIBLE; holder.stock.text = "Low stock" }
                "out" -> { holder.stock.visibility = View.VISIBLE; holder.stock.text = "Out of stock" }
                else -> holder.stock.visibility = View.GONE
            }
            holder.itemView.alpha = if (p.stock == "out") 0.5f else 1f
            holder.itemView.setOnClickListener { addToCart(p) }
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
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_pos_cart_line, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val line = cart[position]
            holder.name.text = line.product.name
            holder.each.text = "${money(line.product.price)} each"
            holder.qty.text = line.qty.toString()
            holder.total.text = money(line.product.price * line.qty)
            holder.minus.setOnClickListener { changeQty(holder.adapterPosition, -1) }
            holder.plus.setOnClickListener { changeQty(holder.adapterPosition, +1) }
            holder.remove.setOnClickListener { removeLine(holder.adapterPosition) }
        }

        override fun getItemCount() = cart.size
    }
}
