package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.BillDao
import com.example.synergic_pos_offline.database.CategoryDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.GstCalculator
import com.example.synergic_pos_offline.utils.ProductEntryDialog
import com.example.synergic_pos_offline.utils.ImageUtils
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.SettingsCache
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Longest edge decoded for a product tile photo. Larger than a list thumbnail
 * because the tile crops the image across the full card width.
 */
private const val PHOTO_PX = 320

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

    /**
     * [cgst] and [sgst] are the per-product rates read from md_product_rates. They
     * are held separately rather than split from a single GST figure - the master
     * allows them to differ, so halving a combined rate would misreport both.
     */
    private data class Product(
        val id: String, val name: String, val sku: String,
        val category: String, val categoryId: Long, val price: Double, val stock: String = "ok",
        val hsn: String = "0000", val cgst: Double = 0.0, val sgst: Double = 0.0,
        val unit: String = "pcs",
        /** Every sellable rate (only populated in Multiple item-rate mode). */
        val rates: List<ProductEntryDialog.Rate> = emptyList()
    ) {
        /** Combined rate, for display only. */
        val gst: Double get() = cgst + sgst
    }

    private data class CartLine(val product: Product, var qty: Int)
    private fun CartLine.toSessionLine() = CheckoutSession.Line(
        product.name, product.sku, product.price, qty,
        product.id.toLongOrNull(), product.cgst, product.sgst
    )

    /**
     * Rebuilds a cart line from a held one. The catalogue is consulted first so the
     * restored line keeps its category, HSN and unit; a line held from the checkout
     * screen may name a product that is no longer listed, so what the held bill
     * itself recorded is used as the fallback.
     */
    private fun CheckoutSession.Line.toCartLine(): CartLine {
        val fromMenu = menu.firstOrNull { it.id == productId?.toString() }
            ?: menu.firstOrNull { it.sku.isNotEmpty() && it.sku == sku }
        val product = fromMenu?.copy(price = price) ?: Product(
            id = productId?.toString() ?: "",
            name = name, sku = sku, category = "", categoryId = 0L,
            price = price, cgst = cgstRate, sgst = sgstRate
        )
        return CartLine(product, qty)
    }

    private fun CheckoutSession.HeldBill.toCartLines(): List<CartLine> = lines.map { it.toCartLine() }

    private data class CategoryItem(val id: Long, val name: String)

    private val categories = mutableListOf("All")
    private val categoryItems = mutableListOf<CategoryItem>()
    private val menu = mutableListOf<Product>()

    /** Product photos, decoded once per catalogue load and keyed by product id. */
    private val photoCache = mutableMapOf<String, android.graphics.Bitmap>()

    // ---- State -------------------------------------------------------------

    private var activeCategory = "All"
    private var activeCategoryId: Long? = null
    private var query = ""
    private var discountPercent = 0
    private var couponApplied = false
    private var customerName: String? = null
    private var customerPhone: String? = null
    private var currentCustomerData: Map<String, Any?>? = null
    private var lastAddedId: String? = null
    private val cart = mutableListOf<CartLine>()
    /**
     * Held sales live on [CheckoutSession] rather than in this fragment, so the
     * billing and checkout screens are looking at one list. A local copy here would
     * only be seen by this screen, and would not survive the fragment being recreated.
     */
    private val heldOrders: MutableList<CheckoutSession.HeldBill> get() = CheckoutSession.heldOrders

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
    private lateinit var tvOrderNo: TextView
    private lateinit var tvCashierName: TextView
    private lateinit var btnHeld: MaterialButton
    private lateinit var btnCharge: MaterialButton
    private lateinit var btnAddCustomer: MaterialButton
    private lateinit var llCustomerInfo: View
    private lateinit var tvCustName: TextView
    private lateinit var tvCustSub: TextView
    private lateinit var tvCouponMsg: TextView
    private lateinit var btnCustomerInfo: ImageButton

    // Auto-prompts for the customer once, the first time this screen is shown after
    // arriving from "Sale". The same fragment instance is reused when checkout pops
    // back here, so this flag keeps the dialog from reopening on that return.
    private var promptedForCustomer = false

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
        tvOrderNo = view.findViewById(R.id.tvOrderNo)
        tvCashierName = view.findViewById(R.id.tvCashierName)
        btnHeld = view.findViewById(R.id.btnHeld)
        btnCharge = view.findViewById(R.id.btnCharge)
        btnAddCustomer = view.findViewById(R.id.btnAddCustomer)
        llCustomerInfo = view.findViewById(R.id.llCustomerInfo)
        tvCustName = view.findViewById(R.id.tvCustName)
        tvCustSub = view.findViewById(R.id.tvCustSub)
        tvCouponMsg = view.findViewById(R.id.tvCouponMsg)
        btnCustomerInfo = view.findViewById(R.id.btnCustomerInfo)

        // Brand cell shows the registered store name, not a hardcoded placeholder.
        storeName(ctx)?.let { view.findViewById<TextView>(R.id.tvBrandName).text = it.uppercase() }

        // Set cashier name from logged-in user
        tvCashierName.text = SessionManager.currentUser?.userId ?: "Guest"

        // Customer info button click listener
        btnCustomerInfo.setOnClickListener {
            if (currentCustomerData != null) {
                showEditCustomerDialog(ctx, currentCustomerData!!)
            }
        }

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

        // Re-apply the current customer rather than clearing it: the view is recreated
        // when checkout pops back, and the sale must survive that unless the operator
        // chose "Start new sale" (which resets via startNewSale()).
        setCustomer(customerName, customerPhone, currentCustomerData)
        loadCategoriesAndProducts()
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

        clockRunnable = object : Runnable {
            override fun run() {
                tvClock.text = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                clockHandler.postDelayed(this, 30_000)
            }
        }
        clockRunnable.run()
    }

    override fun onResume() {
        super.onResume()

        // A sale just completed and the operator asked for another one.
        if (CheckoutSession.startFreshSale) {
            CheckoutSession.startFreshSale = false
            startNewSale()
            return
        }

        // Check if there's a restored bill from checkout
        if (CheckoutSession.restoredBill != null) {
            val restoredBill = CheckoutSession.restoredBill!!
            CheckoutSession.restoredBill = null

            // Restore the bill to cart. Going through the catalogue keeps each line's
            // GST rates - rebuilding a bare Product here would silently zero them.
            cart.clear()
            cart.addAll(restoredBill.toCartLines())

            discountPercent = restoredBill.discount
            couponApplied = restoredBill.coupon
            setCustomer(restoredBill.customerName, restoredBill.customerPhone, restoredBill.customerData)
            cartAdapter.notifyDataSetChanged()
            updateTotals()
            toast("Bill restored")
        }

        // First arrival from "Sale": prompt to add a customer. Guarded so it opens
        // only on entry and never when checkout returns to this screen (a completed
        // sale returns via the startFreshSale path above, which already returns).
        if (!promptedForCustomer) {
            promptedForCustomer = true
            showCustomerDialog()
        }

        updateHeldButton()
        updateOrderNo()
    }

    /**
     * Shows the number the next completed sale will carry, in the top header and on
     * the sale panel. Re-read on resume so it moves on after a bill is saved, rather
     * than repeating the one just printed.
     */
    private fun updateOrderNo() {
        val next = runCatching { BillDao(requireContext()).nextBillNumber() }.getOrDefault("")
        tvOrderNo.text = next
        updateLastBill()
    }

    /**
     * Shows the last bill's id in the header when the "Last Bill Status" general
     * setting is on. Read from the local settings cache, not the DB.
     */
    private fun updateLastBill() {
        val v = view ?: return
        val cell = v.findViewById<View>(R.id.cellLastBill)
        val divider = v.findViewById<View>(R.id.vLastBillDivider)
        val on = SettingsCache.value(requireContext(), "G", "Last Bill Status") == "1"
        if (!on) {
            cell.visibility = View.GONE
            divider.visibility = View.GONE
            return
        }
        val last = runCatching { BillDao(requireContext()).lastBillNumber() }.getOrNull()
        v.findViewById<TextView>(R.id.tvHeaderLastBill).text = last ?: "--"
        cell.visibility = View.VISIBLE
        divider.visibility = View.VISIBLE
        // Tap the cell to open that bill's receipt (with a print option).
        cell.setOnClickListener { openLastBill() }
    }

    /** The registered store's name from md_registration, or null if unavailable. */
    private fun storeName(ctx: android.content.Context): String? {
        return runCatching {
            DatabaseHelper.getInstance(ctx).readableDatabase.query(
                DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_name"),
                null, null, null, null, "store_id ASC", "1"
            ).use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }

    /** Opens the last bill's receipt ([BillFragment]), which offers a print option. */
    private fun openLastBill() {
        val receiptNo = runCatching { BillDao(requireContext()).lastReceiptNo() }.getOrNull()
        if (receiptNo == null) { toast("No bills yet"); return }
        val billNo = (view?.findViewById<TextView>(R.id.tvHeaderLastBill)?.text?.toString()).orEmpty()
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_container,
                BillFragment.newInstance(billNo, "", "", "", "", receiptNo)
            )
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clockHandler.removeCallbacks(clockRunnable)
    }

    // ---- Filtering / cart --------------------------------------------------

    private fun loadCategoriesAndProducts() {
        val categoryDao = CategoryDao(requireContext())
        val dbCategories = categoryDao.getAll()

        categories.clear()
        categories.add("All")
        categoryItems.clear()

        for (cat in dbCategories) {
            categories.add(cat.name)
            categoryItems.add(CategoryItem(cat.id, cat.name))
        }

        categoryAdapter.notifyDataSetChanged()

        // Load products from hardcoded list but map to database categories
        // In a real scenario, this would query md_products from database
        // For now, maintaining the existing product list structure
        loadProductsFromDatabase()
    }

    private fun loadProductsFromDatabase() {
        menu.clear()
        val helper = DatabaseHelper.getInstance(requireContext())
        val db = helper.readableDatabase
        // Multiple item-rate mode: the product popup offers a rate dropdown.
        val multipleRates = SettingsCache.value(requireContext(), "G", "Item Rate") == "M"

        // Query products with their rates
        photoCache.clear()
        db.query(
            "md_products",
            arrayOf("id", "product_name", "bar_code", "hsn_code", "category_id",
                "product_image"),
            null, null, null, null, "product_name ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val productId = cursor.getLong(0).toString()
                val productName = cursor.getString(1) ?: ""
                val barcode = cursor.getString(2) ?: ""
                val hsn = cursor.getString(3) ?: "0000"
                val categoryId = cursor.getLong(4)

                // Decoded once here rather than on every bind: the grid rebinds on
                // each filter keystroke, and decoding a JPEG per tile would stutter.
                if (!cursor.isNull(5)) {
                    cursor.getBlob(5)
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { ImageUtils.decodeThumb(it, PHOTO_PX) }
                        ?.let { photoCache[productId] = it }
                }

                // Get the category name
                val categoryName = categoryItems.find { it.id == categoryId }?.name ?: ""

                // Query the product's default rate row (rate + its own GST split).
                db.query(
                    "md_product_rates",
                    arrayOf("rate", "cgst_rate", "sgst_rate"),
                    "product_id = ?",
                    arrayOf(productId),
                    null, null, "\"default\" DESC, id ASC", "1"
                ).use { rateCursor ->
                    var price = 0.0
                    var cgst = 0.0
                    var sgst = 0.0
                    if (rateCursor.moveToFirst()) {
                        price = if (rateCursor.isNull(0)) 0.0 else rateCursor.getDouble(0)
                        cgst = if (rateCursor.isNull(1)) 0.0 else rateCursor.getDouble(1)
                        sgst = if (rateCursor.isNull(2)) 0.0 else rateCursor.getDouble(2)
                    }

                    // In Multiple mode, gather every rate for the popup's dropdown.
                    val rates = if (multipleRates) loadRates(db, productId) else emptyList()

                    // Create product with database values
                    val product = Product(
                        id = productId,
                        name = productName,
                        sku = barcode,
                        category = categoryName,
                        categoryId = categoryId,
                        price = price,
                        hsn = hsn,
                        cgst = cgst,
                        sgst = sgst,
                        rates = rates
                    )
                    menu.add(product)
                }
            }
        }
    }

    /** Every rate row for a product (default first), for the popup's rate dropdown. */
    private fun loadRates(
        db: android.database.sqlite.SQLiteDatabase, productId: String
    ): List<ProductEntryDialog.Rate> {
        val out = mutableListOf<ProductEntryDialog.Rate>()
        db.query(
            "md_product_rates",
            arrayOf("rate_name", "rate", "cgst_rate", "sgst_rate"),
            "product_id = ?", arrayOf(productId),
            null, null, "\"default\" DESC, id ASC"
        ).use { c ->
            var i = 1
            while (c.moveToNext()) {
                out.add(
                    ProductEntryDialog.Rate(
                        name = c.getString(0)?.takeIf { it.isNotBlank() } ?: "Rate ${i}",
                        rate = if (c.isNull(1)) 0.0 else c.getDouble(1),
                        cgst = if (c.isNull(2)) 0.0 else c.getDouble(2),
                        sgst = if (c.isNull(3)) 0.0 else c.getDouble(3)
                    )
                )
                i++
            }
        }
        return out
    }

    private fun applyFilter() {
        shownProducts.clear()
        shownProducts.addAll(menu.filter { p ->
            (activeCategory == "All" || p.categoryId == activeCategoryId) &&
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

        // "Quantity Status" ON: a new item opens with quantity 0 and the cursor on
        // the quantity field so the operator must enter it. OFF: defaults to 1.
        val quantityStatusOn = SettingsCache.value(requireContext(), "G", "Quantity Status") == "1"
        val startQty = when {
            editing -> cart[editIndex].qty
            quantityStatusOn -> 0
            else -> 1
        }

        // Manual Rate off (App Settings): the rate field is read-only.
        val manualRateOn = SettingsCache.value(requireContext(), "A", "Manual Rate") == "1"

        ProductEntryDialog.show(
            context = requireContext(),
            inflater = layoutInflater,
            product = p.toDialogProduct(),
            startRate = if (editing) cart[editIndex].product.price else p.price,
            startQty = startQty,
            confirmLabel = if (editing) "Update" else "Add to cart",
            focusQty = !editing && quantityStatusOn,
            focusRate = !editing && manualRateOn,
            rateEditable = manualRateOn
        ) { qty, rate ->
            if (editing) updateCartLine(editIndex, qty, rate) else addToCart(p, qty, rate)
        }
    }

    private fun Product.toDialogProduct() = ProductEntryDialog.Product(
        id = id, name = name, sku = sku, category = category,
        price = price, hsn = hsn, unit = unit, cgst = cgst, sgst = sgst, rates = rates
    )

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

    private fun setCustomer(name: String?, phone: String? = null, customerData: Map<String, Any?>? = null) {
        customerName = name
        customerPhone = phone
        currentCustomerData = customerData
        if (name == null && phone == null) {
            btnAddCustomer.visibility = View.VISIBLE
            llCustomerInfo.visibility = View.GONE
            currentCustomerData = null
        } else {
            btnAddCustomer.visibility = View.GONE
            llCustomerInfo.visibility = View.VISIBLE
            tvCustName.text = name ?: "Customer"
            tvCustSub.text = phone ?: "No phone"
            // Show info button only if we have actual customer data (not default "Customer")
            btnCustomerInfo.visibility = if (customerData != null) View.VISIBLE else View.GONE
        }
    }

    private fun showCustomerDialog() {
        DialogUtils.showForm(
            context = requireContext(),
            title = "Add Customer",
            fields = listOf(
                DialogUtils.FormField("Phone Number", customerPhone ?: "", inputType = "phone", maxLength = 10)
            ),
            positiveText = "Add",
            showNegative = false,
            mandatoryFields = listOf(0),
            onSave = { values ->
                val phone = values[0].trim()
                if (phone.isNotEmpty() && phone.length == 10) {
                    val ctx = requireContext()
                    var customerName = "Guest"
                    var customerData: Map<String, Any?>? = null

                    try {
                        val helper = DatabaseHelper.getInstance(ctx)
                        val db = helper.readableDatabase

                        db.query(
                            "md_customers",
                            arrayOf("id", "customer_name", "phone_number", "customer_address", "gstin", "dob", "dom", "credit_enabled", "credit_limit", "balance_amount"),
                            "phone_number = ?",
                            arrayOf(phone),
                            null, null, null
                        ).use { cursor ->
                            if (cursor.moveToFirst()) {
                                customerName = cursor.getString(1) ?: "Customer"
                                customerData = mapOf(
                                    "id" to cursor.getLong(0),
                                    "name" to customerName,
                                    "phone" to (cursor.getString(2) ?: ""),
                                    "address" to (cursor.getString(3) ?: ""),
                                    "gstin" to (cursor.getString(4) ?: ""),
                                    "dob" to (cursor.getString(5) ?: ""),
                                    "dom" to (cursor.getString(6) ?: ""),
                                    "credit_enabled" to (cursor.getInt(7) != 0),
                                    "credit_limit" to cursor.getDouble(8),
                                    "balance" to cursor.getDouble(9)
                                )

                                // Attach the found customer to the sale directly, no
                                // intermediate confirmation card.
                                setCustomer(customerName.ifEmpty { null }, phone, customerData)
                            } else {
                                // Customer not found - insert new customer into database
                                try {
                                    val values = android.content.ContentValues().apply {
                                        put("phone_number", phone)
                                        put("customer_name", "")
                                        put("customer_address", "")
                                        put("gstin", "")
                                        put("dob", "")
                                        put("dom", "")
                                        put("credit_enabled", 0)
                                        put("credit_limit", 0.0)
                                        put("balance_amount", 0.0)
                                        put("created_by", SessionManager.currentUser?.userId ?: "System")
                                        put("created_at", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
                                    }
                                    val result = db.insert("md_customers", null, values)
                                    if (result > 0) {
                                        // Attach the newly-created customer to the sale
                                        // directly, no intermediate confirmation card.
                                        setCustomer(
                                            null, phone,
                                            mapOf(
                                                "id" to result, "name" to "", "phone" to phone,
                                                "address" to "", "gstin" to "", "dob" to "", "dom" to "",
                                                "credit_enabled" to false,
                                                "credit_limit" to 0.0, "balance" to 0.0
                                            )
                                        )
                                        toast("New customer saved against $phone")
                                    } else {
                                        toast("Could not create the customer")
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("PosBillingFragment", "Customer lookup failed", e)
                                    toast("Could not create the customer")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        setCustomer(null, phone, null)
                    }
                }
            }
        )
    }

    private fun showCustomerInfoPopover(ctx: android.content.Context, customer: Map<String, Any?>) {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_common, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val accent = ThemeManager.getThemeColor(ctx)
        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val tvMessage = view.findViewById<TextView>(R.id.tvDialogMessage)
        val btnNegative = view.findViewById<MaterialButton>(R.id.btnDialogNegative)
        val ivIcon = view.findViewById<ImageView>(R.id.ivDialogIcon)

        tvTitle.text = customer["name"].toString()

        val infoText = """
            Phone: ${customer["phone"]}
            Address: ${customer["address"]}
            GSTIN: ${customer["gstin"]}
            DOB: ${customer["dob"]}
            DOM: ${customer["dom"]}
            Credit Enabled: ${if (customer["credit_enabled"] == true) "Yes" else "No"}
            Credit Limit: ₹${customer["credit_limit"]}
            Balance: ₹${customer["balance"]}
        """.trimIndent()

        tvMessage.text = infoText
        btnNegative.text = "Close"
        btnNegative.setTextColor(accent)
        btnNegative.strokeColor = android.content.res.ColorStateList.valueOf(accent)
        view.findViewById<MaterialButton>(R.id.btnDialogPositive).visibility = View.GONE
        ivIcon.visibility = View.GONE

        btnNegative.setOnClickListener { dialog.dismiss() }

        dialog.show()
        val window = dialog.window
        window?.setLayout(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        window?.setGravity(android.view.Gravity.CENTER)
    }

    /**
     * Opens an editable customer form pre-filled with the attached customer, and
     * writes changes back to md_customers. The updated details also refresh the sale.
     */
    private fun showEditCustomerDialog(ctx: android.content.Context, customer: Map<String, Any?>) {
        val id = customer["id"] as? Long
        if (id == null) { toast("No customer to edit"); return }

        DialogUtils.showForm(
            context = ctx,
            title = "Edit Customer",
            fields = listOf(
                DialogUtils.FormField("Customer Name", customer["name"]?.toString().orEmpty()),
                DialogUtils.FormField("Phone Number", customer["phone"]?.toString().orEmpty(), inputType = "phone", maxLength = 10),
                DialogUtils.FormField("Address", customer["address"]?.toString().orEmpty(), isTextArea = true, spanColumns = 2),
                DialogUtils.FormField("GSTIN", customer["gstin"]?.toString().orEmpty(), maxLength = 15, spanColumns = 2)
            ),
            positiveText = "Update",
            mandatoryFields = listOf(1),
            onSave = { values ->
                val name = values[0].trim()
                val phone = values[1].trim()
                val address = values[2].trim()
                val gstin = values[3].trim()

                if (phone.length != 10) {
                    toast("Enter a valid 10-digit phone number")
                } else {
                    val updated = runCatching {
                        val db = DatabaseHelper.getInstance(ctx).writableDatabase
                        val cv = android.content.ContentValues().apply {
                            put("customer_name", name)
                            put("phone_number", phone)
                            put("customer_address", address)
                            put("gstin", gstin)
                            put("modified_by", SessionManager.currentUser?.userId)
                            put("modified_at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                        }
                        db.update("md_customers", cv, "id=?", arrayOf(id.toString()))
                    }.getOrDefault(0)

                    if (updated > 0) {
                        val newData = customer.toMutableMap().apply {
                            this["name"] = name
                            this["phone"] = phone
                            this["address"] = address
                            this["gstin"] = gstin
                        }
                        currentCustomerData = newData
                        setCustomer(name.ifEmpty { null }, phone, newData)
                        toast("Customer updated")
                    } else {
                        toast("Could not update the customer")
                    }
                }
            }
        )
    }

    /** On-screen calculator. Just for calculations, does not add items to cart. */
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
        styleOutlined(btnClose, accent)

        btnClose.setOnClickListener { dialog.dismiss() }
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
        // Only one bill can be held at a time - replace existing if present
        heldOrders.clear()
        heldOrders.add(
            CheckoutSession.HeldBill(
                "Sale #1", cart.map { it.toSessionLine() }, discountPercent, couponApplied,
                customerName, customerPhone, currentCustomerData
            )
        )
        // Fully refresh the sale page for the next customer (clears cart, resets
        // filters, reloads the catalogue) - same reset as starting a new sale.
        startNewSale()
        toast("Sale put on hold")
    }

    private fun showHeldDialog() {
        if (heldOrders.isEmpty()) { toast("No sales on hold"); return }

        if (heldOrders.size == 1) {
            // Only one held bill - show details directly
            showHeldBillDetails(0)
        } else {
            // Multiple held bills - show as list
            val labels = heldOrders.mapIndexed { index, h ->
                "${h.label} · ${h.lines.sumOf { it.qty }} items · " +
                    money(totalOf(h.toCartLines(), h.discount, h.coupon))
            }.toTypedArray()

            AlertDialog.Builder(requireContext())
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
        val heldBill = heldOrders[index]
        val heldLines = heldBill.toCartLines()
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)

        // Build bill details text
        val gross = heldLines.sumOf { it.product.price * it.qty }
        val discountAmt = gross * heldBill.discount / 100.0
        val billDetails = StringBuilder().apply {
            append("${heldBill.label}\n\n")
            append("ITEMS:\n")
            heldLines.forEach { line ->
                append("${line.product.name}\n")
                append("  Qty: ${line.qty} × ${money(line.product.price)} = ${money(line.product.price * line.qty)}\n")
            }
            append("\nSubtotal: ${money(gross)}\n")
            if (heldBill.discount > 0) {
                append("Discount (${heldBill.discount}%): -${money(discountAmt)}\n")
            }
            append("Tax: ${money(taxOf(heldLines, heldBill.discount))}\n")
            append("\nTOTAL: ${money(totalOf(heldLines, heldBill.discount, heldBill.coupon))}")
        }.toString()

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_common, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val tvMessage = view.findViewById<TextView>(R.id.tvDialogMessage)
        val btnPositive = view.findViewById<MaterialButton>(R.id.btnDialogPositive)
        val btnNegative = view.findViewById<MaterialButton>(R.id.btnDialogNegative)
        val ivIcon = view.findViewById<ImageView>(R.id.ivDialogIcon)

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
        val h = heldOrders.removeAt(index)
        cart.clear()
        cart.addAll(h.toCartLines())
        discountPercent = h.discount
        couponApplied = h.coupon
        setCustomer(h.customerName, h.customerPhone, h.customerData)
        cartAdapter.notifyDataSetChanged()
        updateHeldButton()
        updateTotals()
    }

    private fun updateHeldButton() { btnHeld.text = "Held (${heldOrders.size})" }

    private fun onCheckout() {
        if (cart.isEmpty()) { toast("Cart is empty"); return }

        // Every bill is raised against a customer. The phone is what identifies one,
        // and is present whether the record was matched or created on the spot, so a
        // blank here means nothing was attached. Open the lookup rather than just
        // refusing, so the block can be cleared without hunting for the button.
        if (customerPhone.isNullOrBlank()) {
            toast("Add a customer to continue")
            showCustomerDialog()
            return
        }

        // Hand the current sale to the checkout screen.
        CheckoutSession.lines = cart.map {
            CheckoutSession.Line(
                it.product.name, it.product.sku, it.product.price, it.qty,
                it.product.id.toLongOrNull(), it.product.cgst, it.product.sgst
            )
        }.toMutableList()
        CheckoutSession.customerName = customerName
        CheckoutSession.customerPhone = customerPhone
        CheckoutSession.customerId = currentCustomerData?.get("id") as? Long
        // heldOrders needs no copying: both screens read the one list on the session.
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, PosCheckoutFragment())
            .addToBackStack(null)
            .commit()
    }

    /**
     * Resets the screen for the next customer after a completed sale.
     *
     * Beyond emptying the cart, this reloads the catalogue and its photos and puts
     * the browsing state back to "All". The fragment survives while checkout sits on
     * top of it, so [query] and [activeCategory] would otherwise still be holding
     * the last sale's filter while the search box - rebuilt with the view - looks
     * empty, leaving most of the grid mysteriously missing.
     *
     * Held sales are deliberately untouched: they live on [CheckoutSession] and a
     * bill parked earlier is still parked.
     */
    private fun startNewSale() {
        clearSale()

        query = ""
        view?.findViewById<TextInputEditText>(R.id.etSearch)?.setText("")
        activeCategory = "All"
        activeCategoryId = null

        // Re-read the masters so anything edited mid-sale shows up, photos included.
        loadCategoriesAndProducts()

        applyFilter()
        updateHeldButton()
        updateOrderNo()
        view?.findViewById<RecyclerView>(R.id.rvProducts)?.scrollToPosition(0)
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
    private fun taxAmt(): Double = taxOf(cart, totalPct())

    /** Taxed value before rounding; what the round-off line is measured against. */
    private fun taxedTotal(): Double =
        (subtotal() - discountAmt()).coerceAtLeast(0.0) + taxAmt()

    private fun roundOffAmt(): Double = BillRounding.roundOff(taxedTotal())

    /** Rounded to whole rupees, so this screen quotes what checkout will charge. */
    private fun computeTotal(): Double = BillRounding.payable(taxedTotal())

    /**
     * GST across the cart, charged at each product's own CGST+SGST rate rather than
     * one blanket rate. The discount is a whole-bill percentage, so it is applied to
     * each line before that line is taxed - tax follows what is actually charged.
     */
    private fun taxOf(lines: List<CartLine>, discountPct: Int): Double =
        lines.sumOf { line ->
            val taxable = GstCalculator.taxableValue(line.product.price, line.qty, discountPct)
            GstCalculator.taxAmount(taxable, line.product.cgst) +
                GstCalculator.taxAmount(taxable, line.product.sgst)
        }

    /** Rounded total of an arbitrary set of lines, used for held-bill summaries. */
    private fun totalOf(lines: List<CartLine>, disc: Int, coupon: Boolean): Double {
        val sub = lines.sumOf { it.product.price * it.qty }
        val pct = min(100, disc + if (coupon) 10 else 0)
        val afterDisc = (sub - sub * pct / 100.0).coerceAtLeast(0.0)
        return BillRounding.payable(afterDisc + taxOf(lines, pct))
    }

    private fun updateTotals() {
        tvCartEmpty.visibility = if (cart.isEmpty()) View.VISIBLE else View.GONE
        
        val totalQty = cart.sumOf { it.qty }
        tvItemCount.text = "$totalQty item${if (totalQty != 1) "s" else ""}"

        tvSubtotal.text = money(subtotal())
        tvDiscountLabel.text = "Discount (${totalPct()}%)"
        tvDiscountAmt.text = "- ${money(discountAmt())}"
        tvTax.text = money(taxAmt())

        val roundOff = roundOffAmt()
        view?.findViewById<View>(R.id.rowBillingRoundOff)?.visibility =
            if (kotlin.math.abs(roundOff) > 0.001) {
                view?.findViewById<TextView>(R.id.tvBillingRoundOff)?.text =
                    (if (roundOff > 0) "+ " else "- ") + money(kotlin.math.abs(roundOff))
                View.VISIBLE
            } else View.GONE

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
                activeCategoryId = if (cat == "All") null else categoryItems.find { it.name == cat }?.id
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
            val photo: android.widget.ImageView = view.findViewById(R.id.ivProductPhoto)
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

            // Views are recycled, so a product without a photo must clear the tile
            // rather than inherit the previous one's.
            val photo = photoCache[p.id]
            if (photo != null) {
                holder.photo.setImageBitmap(photo)
                holder.photo.visibility = View.VISIBLE
            } else {
                holder.photo.setImageDrawable(null)
                holder.photo.visibility = View.GONE
            }

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
