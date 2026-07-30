package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.TableDao
import com.example.synergic_pos_offline.database.TaxSettingsDao
import com.example.synergic_pos_offline.utils.GstCalculator
import com.example.synergic_pos_offline.utils.ProductEntryDialog
import com.example.synergic_pos_offline.utils.SettingsCache
import com.example.synergic_pos_offline.utils.ThemeManager

/**
 * Restaurant "Sale" screen — an Orders workspace (order list + live order detail
 * + summary). Opened in place of the grocery POS billing when Mode = Restaurant.
 *
 * This is the design pass: the layout is populated with representative
 * placeholder data. The data and actions are wired in a follow-up.
 */
class RestaurantOrdersFragment : Fragment(), TitledScreen {

    override val screenTitle = "Sale"

    // One line in an order's cart.
    private data class CartItem(val product: ProductEntryDialog.Product, var qty: Int, var rate: Double)

    private data class OrderCard(
        val id: String, val type: String, val section: String, val phone: String,
        val time: String, var amount: String, val cashier: String,
        val status: String, var selected: Boolean,
        // Each order keeps its own cart, so switching tables shows its own items.
        val items: MutableList<CartItem> = mutableListOf()
    )

    // Dynamic order list — starts empty and grows as orders are created.
    private val orders = mutableListOf<OrderCard>()

    /** The cart of the currently selected order, or null when none is selected. */
    private fun currentCart(): MutableList<CartItem>? = orders.firstOrNull { it.selected }?.items

    // Tax configuration, resolved the same way the grocery billing screen does.
    private val taxSettings by lazy { TaxSettingsDao(requireContext()).load() }
    private val discountPreTax by lazy { taxSettings.discountPosition == TaxSettingsDao.DiscountPosition.PRE_TAX }
    private val itemwiseDiscountActive by lazy {
        taxSettings.discountEnabled && taxSettings.discountType == TaxSettingsDao.DiscountType.ITEM_WISE
    }
    private val taxRegime by lazy { GstCalculator.regimeFor(taxSettings.gstEnabled, taxSettings.vatEnabled) }
    private val taxInclusive by lazy {
        when (taxRegime) {
            GstCalculator.TaxRegime.GST -> taxSettings.gstMode == TaxSettingsDao.GstMode.INCLUSIVE
            GstCalculator.TaxRegime.VAT -> taxSettings.vatMode == TaxSettingsDao.GstMode.INCLUSIVE
            GstCalculator.TaxRegime.NONE -> false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_restaurant_orders, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val accent = ThemeManager.getThemeColor(requireContext())

        populateOrders(view, accent)
        clearDetail(view)

        // The order-type segment fills the selected side (handled by a state list).
        view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.segOrderType)
            .check(R.id.btnDineIn)

        // New Order → table/customer modal.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNewOrder).setOnClickListener {
            showNewOrderDialog()
        }

        // Add Item → product-grid modal (only when a table/order is active).
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAddItem).setOnClickListener {
            if (orders.none { it.selected }) {
                toast("Create or select a table order first")
            } else {
                showAddItemDialog()
            }
        }

        // Bill & Pay → restaurant checkout.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBillPay).setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, RestaurantCheckoutFragment())
                .addToBackStack(null)
                .commit()
        }

        // MainActivity re-themes the whole tree on resume (by button name), which
        // would clobber our button styling — re-apply ours after that pass.
        view.post { restyle(view, accent) }
    }

    override fun onResume() {
        super.onResume()
        view?.let { v -> v.post { restyle(v, ThemeManager.getThemeColor(requireContext())) } }
    }

    /** Called by MainActivity when the palette colour changes — recolour instantly. */
    fun onThemeChanged() {
        val v = view ?: return
        val accent = ThemeManager.getThemeColor(requireContext())
        v.post { recolorAll(v, accent) }
    }

    /** Re-applies every accent that isn't handled by ThemeManager (cards, tabs, statuses). */
    private fun recolorAll(v: View, accent: Int) {
        populateOrders(v, accent)   // re-renders cards with the new accent + selection
        restyle(v, accent)
    }

    private fun restyle(view: View, accent: Int) {
        applyAccents(view, accent)
        styleSeg(view, accent)
    }

    /**
     * The segment recolors by *checked state* via a ColorStateList, so toggling
     * Dine In / Take Away updates automatically and both sides stay consistent.
     */
    private fun styleSeg(view: View, accent: Int) {
        val white = android.graphics.Color.WHITE
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)
        )
        val bg = ColorStateList(states, intArrayOf(accent, white))
        val text = ColorStateList(states, intArrayOf(white, accent))
        listOf(R.id.btnDineIn, R.id.btnTakeAway).forEach { id ->
            view.findViewById<com.google.android.material.button.MaterialButton>(id).apply {
                backgroundTintList = bg
                setTextColor(text)
                strokeColor = ColorStateList.valueOf(accent)
            }
        }
    }

    private fun populateOrders(root: View, accent: Int) {
        val list = root.findViewById<LinearLayout>(R.id.llOrderList)
        val inflater = LayoutInflater.from(requireContext())
        val soft = ColorUtils.setAlphaComponent(accent, 0x14)   // ~8% accent tint
        list.removeAllViews()

        // Empty state: no orders yet.
        val emptyView = root.findViewById<TextView>(R.id.tvNoOrders)
        emptyView?.visibility = if (orders.isEmpty()) View.VISIBLE else View.GONE

        orders.forEach { o ->
            val card = inflater.inflate(R.layout.item_order_card, list, false)
                    as com.google.android.material.card.MaterialCardView
            card.findViewById<TextView>(R.id.tvOrderId).apply { text = o.id; setTextColor(accent) }
            card.findViewById<TextView>(R.id.tvOrderType).text = o.type
            card.findViewById<TextView>(R.id.tvOrderGuests).text = o.section.ifBlank { "—" }
            card.findViewById<TextView>(R.id.tvOrderTime).apply { text = o.time; setTextColor(accent) }
            card.findViewById<TextView>(R.id.tvOrderCashier).text = o.cashier
            card.findViewById<TextView>(R.id.tvOrderAmount).text = o.amount
            card.findViewById<TextView>(R.id.tvOrderStatus).apply {
                text = o.status
                setTextColor(accent)
                backgroundTintList = ColorStateList.valueOf(soft)
            }
            if (o.selected) {
                card.setCardBackgroundColor(soft)
                card.strokeColor = accent
                card.strokeWidth = (resources.displayMetrics.density * 1.5f).toInt()
            } else {
                card.setCardBackgroundColor(android.graphics.Color.WHITE)
                card.strokeWidth = 0
            }
            card.setOnClickListener { selectOrder(o) }
            list.addView(card)
        }
    }

    /** Marks [order] active, repaints the list, and loads its own cart into the detail panel. */
    private fun selectOrder(order: OrderCard) {
        orders.forEach { it.selected = (it === order) }
        val root = view ?: return
        populateOrders(root, ThemeManager.getThemeColor(requireContext()))
        showOrderDetail(order)
        renderCart()   // show this table's own items + totals
    }

    /** Updates the detail-panel header for the given order. */
    private fun showOrderDetail(order: OrderCard) {
        val root = view ?: return
        val accent = ThemeManager.getThemeColor(requireContext())
        root.findViewById<TextView>(R.id.tvDetailTable).apply { text = order.id; setTextColor(accent) }
        root.findViewById<TextView>(R.id.tvDetailCustomer).text = order.phone.ifBlank { "Walk-in" }
        root.findViewById<TextView>(R.id.tvDetailGuests).text =
            if (order.section.isNotBlank()) "${order.section}  ·  ${order.type}" else order.type
    }

    /** Neutral detail panel when no order is selected: empty cart + zeroed totals. */
    private fun clearDetail(root: View) {
        root.findViewById<TextView>(R.id.tvDetailTable).text = "—"
        root.findViewById<TextView>(R.id.tvDetailCustomer).text = "Walk-in"
        root.findViewById<TextView>(R.id.tvDetailGuests).text = "—"
        root.findViewById<LinearLayout>(R.id.llOrderItems).removeAllViews()
        val zero = "₹ ${money(0.0)}"
        root.findViewById<TextView>(R.id.tvSubtotal).text = zero
        root.findViewById<TextView>(R.id.tvService).text = zero
        root.findViewById<TextView>(R.id.tvCgst).text = zero
        root.findViewById<TextView>(R.id.tvSgst).text = zero
        root.findViewById<TextView>(R.id.tvOrderTotal).text = zero
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBillPay).text =
            "Bill & Pay  ( $zero )"
    }

    /** Accent the filled buttons, headers and the active tab (avoids ThemeManager's name rules). */
    private fun applyAccents(root: View, accent: Int) {
        val white = android.graphics.Color.WHITE
        val strokePx = (resources.displayMetrics.density * 1.5f).toInt()
        fun filled(id: Int) = root.findViewById<com.google.android.material.button.MaterialButton>(id).apply {
            backgroundTintList = ColorStateList.valueOf(accent); setTextColor(white)
            iconTint = ColorStateList.valueOf(white); strokeWidth = 0
        }
        fun outlined(id: Int) = root.findViewById<com.google.android.material.button.MaterialButton>(id).apply {
            // Reset the background too — ThemeManager may have filled it green already.
            backgroundTintList = ColorStateList.valueOf(white); setTextColor(accent)
            strokeColor = ColorStateList.valueOf(accent); strokeWidth = strokePx
            iconTint = ColorStateList.valueOf(accent)
        }
        filled(R.id.btnNewOrder); filled(R.id.btnAddItem); filled(R.id.btnBillPay)
        outlined(R.id.btnRefreshOrders); outlined(R.id.btnHold); outlined(R.id.btnPrintKot)
        outlined(R.id.btnTransfer); outlined(R.id.btnMerge); outlined(R.id.btnSplit)
        outlined(R.id.btnCancelOrder); outlined(R.id.btnBillPrint)

        // Segment toggle colours.
        listOf(R.id.btnDineIn, R.id.btnTakeAway).forEach {
            root.findViewById<com.google.android.material.button.MaterialButton>(it).strokeColor =
                ColorStateList.valueOf(accent)
        }

        // Active tab + count badge + detail accents.
        root.findViewById<TextView>(R.id.tabActive).setTextColor(accent)
        root.findViewById<TextView>(R.id.badgeActive).backgroundTintList = ColorStateList.valueOf(accent)
        root.findViewById<TextView>(R.id.tvDetailTable).setTextColor(accent)
        root.findViewById<TextView>(R.id.tvOrderTotal).setTextColor(accent)
        root.findViewById<TextView>(R.id.tvDetailCustomer).apply {
            setTextColor(accent)
            backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x14))
        }
    }

    // ---- New Order: table + customer modal ---------------------------------

    private fun showNewOrderDialog() {
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_new_order, null)
        val dialog = AlertDialog.Builder(ctx).setView(v).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val etTable = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etTableNo)
        val etSection = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSection)
        val etWaiter = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etWaiter)
        val etPhone = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPhone)
        val btnSave = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFormPositive)
        val btnCancel = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFormNegative)

        // Entering a table code auto-fills its section + assigned waiter.
        etTable.addTextChangedListener {
            val code = it?.toString()?.trim().orEmpty()
            val info = if (code.isEmpty()) null else TableDao(ctx).lookupByCode(code)
            etSection.setText(info?.sectionName.orEmpty())
            etWaiter.setText(info?.waiterName ?: if (info != null) "—" else "")
        }

        ThemeManager.applyTheme(v)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent)
        btnSave.setTextColor(Color.WHITE)
        btnCancel.setTextColor(accent)
        btnCancel.strokeColor = ColorStateList.valueOf(accent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val table = etTable.text?.toString()?.trim().orEmpty()
            if (table.isEmpty()) { etTable.error = "Enter a table no"; return@setOnClickListener }
            // Don't open a second order for a table that already has an active one.
            if (orders.any { it.id.equals(table, ignoreCase = true) }) {
                etTable.error = "Table $table already has an active order"
                return@setOnClickListener
            }
            val section = etSection.text?.toString()?.trim().orEmpty()
            val phone = etPhone.text?.toString()?.trim().orEmpty()
            dialog.dismiss()
            openNewOrder(table, section, phone)
            toast("Order created for table $table")
        }

        dialog.show()
    }

    /** Adds a new order to the list, selects it, and starts it with a fresh empty cart. */
    private fun openNewOrder(table: String, section: String, phone: String) {
        val root = view ?: return
        val type = if (root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTakeAway).isChecked)
            "Take Away" else "Dine In"
        val now = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
        val cashier = com.example.synergic_pos_offline.utils.SessionManager.currentUser?.userId ?: "—"

        orders.forEach { it.selected = false }
        val order = OrderCard(
            id = table, type = type, section = section, phone = phone, time = now,
            amount = "₹ ${money(0.0)}", cashier = cashier, status = "In Progress", selected = true
        )
        orders.add(0, order)                                  // newest on top
        populateOrders(root, ThemeManager.getThemeColor(requireContext()))
        showOrderDetail(order)
        renderCart()   // this order's (empty) cart + zeroed totals
    }

    /** A grid entry: the popup product plus its restaurant attributes (food type + spice). */
    private data class GridProduct(
        val product: ProductEntryDialog.Product, val foodType: String, val spice: String
    )

    /** Loads the current store's products (rate + tax split + category + food/spice), for the grid. */
    private fun loadProductsFromDb(): List<GridProduct> {
        val db = com.example.synergic_pos_offline.database.DatabaseHelper.getInstance(requireContext()).readableDatabase
        val store = currentStoreId(db)
        val cats = com.example.synergic_pos_offline.database.CategoryDao(requireContext())
            .getAll().associate { it.id to it.name }
        val multipleRates = SettingsCache.value(requireContext(), "G", "Item Rate") == "M"
        val out = mutableListOf<GridProduct>()
        db.query(
            "md_products",
            arrayOf("id", "product_name", "bar_code", "hsn_code", "category_id", "food_type", "spice_level"),
            (if (store != null) "store_id = ?" else null),
            store?.let { arrayOf(it.toString()) },
            null, null, "product_name ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0).toString()
                val name = c.getString(1).orEmpty()
                val sku = c.getString(2).orEmpty()
                val hsn = c.getString(3)?.takeIf { it.isNotBlank() } ?: "0000"
                val catName = cats[c.getLong(4)].orEmpty()
                val foodType = c.getString(5).orEmpty()
                val spice = c.getString(6).orEmpty()
                var price = 0.0; var cgst = 0.0; var sgst = 0.0; var vat = 0.0
                var disc = 0.0; var discType: String? = null
                db.query(
                    "md_product_rates",
                    arrayOf("rate", "cgst_rate", "sgst_rate", "vat_rate", "discount", "discount_type"),
                    "product_id = ?", arrayOf(id), null, null, "\"default\" DESC, id ASC", "1"
                ).use { r ->
                    if (r.moveToFirst()) {
                        price = if (r.isNull(0)) 0.0 else r.getDouble(0)
                        cgst = if (r.isNull(1)) 0.0 else r.getDouble(1)
                        sgst = if (r.isNull(2)) 0.0 else r.getDouble(2)
                        vat = if (r.isNull(3)) 0.0 else r.getDouble(3)
                        disc = if (r.isNull(4)) 0.0 else r.getDouble(4)
                        discType = r.getString(5)
                    }
                }
                val rates = if (multipleRates) loadRates(db, id) else emptyList()
                out.add(
                    GridProduct(
                        ProductEntryDialog.Product(
                            id = id, name = name, sku = sku, category = catName, price = price,
                            hsn = hsn, unit = "", cgst = cgst, sgst = sgst, vat = vat,
                            discValue = disc, discType = discType, rates = rates
                        ),
                        foodType = foodType, spice = spice
                    )
                )
            }
        }
        return out
    }

    /** Every rate row for a product (default first), for the popup's rate dropdown. */
    private fun loadRates(db: android.database.sqlite.SQLiteDatabase, productId: String): List<ProductEntryDialog.Rate> {
        val out = mutableListOf<ProductEntryDialog.Rate>()
        db.query(
            "md_product_rates",
            arrayOf("rate_name", "rate", "cgst_rate", "sgst_rate", "vat_rate", "discount", "discount_type"),
            "product_id = ?", arrayOf(productId), null, null, "\"default\" DESC, id ASC"
        ).use { c ->
            var i = 1
            while (c.moveToNext()) {
                out.add(
                    ProductEntryDialog.Rate(
                        name = c.getString(0)?.takeIf { it.isNotBlank() } ?: "Rate ${i}",
                        rate = if (c.isNull(1)) 0.0 else c.getDouble(1),
                        cgst = if (c.isNull(2)) 0.0 else c.getDouble(2),
                        sgst = if (c.isNull(3)) 0.0 else c.getDouble(3),
                        vat = if (c.isNull(4)) 0.0 else c.getDouble(4),
                        discValue = if (c.isNull(5)) 0.0 else c.getDouble(5),
                        discType = c.getString(6)
                    )
                )
                i++
            }
        }
        return out
    }

    private fun currentStoreId(db: android.database.sqlite.SQLiteDatabase): Long? {
        com.example.synergic_pos_offline.utils.SessionManager.currentUser?.storeId
            ?.takeIf { it != 0 }?.let { return it.toLong() }
        db.query(
            com.example.synergic_pos_offline.database.DatabaseHelper.Tables.MD_REGISTRATION,
            arrayOf("store_id"), null, null, null, null, "store_id ASC", "1"
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) }
        return null
    }

    // ---- Add Item: product-grid modal --------------------------------------

    private fun showAddItemDialog() {
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_item, null)
        val dialog = AlertDialog.Builder(ctx).setView(v).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val etSearch = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSearch)
        val llCats = v.findViewById<LinearLayout>(R.id.llCategories)
        val rv = v.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvProducts)

        // Live data from md_products / md_category (store-scoped).
        val products = loadProductsFromDb()
        val catNames = listOf("All") + products.map { it.product.category }.filter { it.isNotBlank() }.distinct()

        var selectedCat = "All"
        var query = ""

        val adapter = ProductAdapter(accent) { showProductEntry(it) }
        rv.layoutManager = androidx.recyclerview.widget.GridLayoutManager(ctx, 4)
        rv.adapter = adapter

        fun refresh() {
            val q = query.trim().lowercase()
            adapter.submit(products.filter {
                (selectedCat == "All" || it.product.category == selectedCat) &&
                    (q.isEmpty() || it.product.name.lowercase().contains(q) || it.product.sku.contains(q))
            })
        }

        // Category tabs — the selected one gets an accent underline.
        val tabViews = linkedMapOf<String, TextView>()
        catNames.forEach { c ->
            val tv = TextView(ctx).apply {
                text = c
                textSize = 15f
                setPadding(dp(10), dp(10), dp(10), dp(12))
                setOnClickListener { selectedCat = c; styleCats(tabViews, selectedCat, accent); refresh() }
            }
            tabViews[c] = tv
            llCats.addView(tv)
        }
        styleCats(tabViews, selectedCat, accent)

        etSearch.addTextChangedListener { query = it?.toString().orEmpty(); refresh() }
        v.findViewById<ImageButton>(R.id.btnCloseAddItem).setOnClickListener { dialog.dismiss() }

        ThemeManager.applyTheme(v)
        refresh()
        dialog.show()
        // AlertDialog caps its width by default; widen it so the grid has room.
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /** Selected category shows an accent underline; the rest are muted. */
    private fun styleCats(tabs: Map<String, TextView>, selected: String, accent: Int) {
        tabs.forEach { (name, tv) ->
            val on = name == selected
            tv.setTextColor(if (on) accent else 0xFF9AA0A6.toInt())
            tv.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            tv.background = if (on) underline(accent) else null
        }
    }

    /** A thin accent line drawn along the bottom edge, for the active tab. */
    private fun underline(accent: Int): android.graphics.drawable.Drawable {
        val line = android.graphics.drawable.GradientDrawable().apply { setColor(accent) }
        return android.graphics.drawable.LayerDrawable(arrayOf(line)).apply {
            setLayerInset(0, 0, dp(38), 0, 0)  // push the fill down so only a 2dp strip shows
        }
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private fun initials(name: String): String =
        name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

    private inner class ProductAdapter(
        private val accent: Int,
        private val onPick: (ProductEntryDialog.Product) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<ProductAdapter.VH>() {

        private val items = mutableListOf<GridProduct>()
        // Header colours: a spread of accent shades so tiles read as a set.
        private val factors = listOf(0.0f, 0.2f, 0.4f, -0.18f, 0.55f, -0.34f)

        fun submit(list: List<GridProduct>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        inner class VH(v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_product_tile, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val gp = items[position]
            val p = gp.product
            val f = factors[position % factors.size]
            val shade = if (f >= 0) ColorUtils.blendARGB(accent, Color.BLACK, f)
                        else ColorUtils.blendARGB(accent, Color.WHITE, -f)
            holder.itemView.findViewById<TextView>(R.id.tvTileInitials).apply {
                text = initials(p.name); setBackgroundColor(shade)
            }
            holder.itemView.findViewById<TextView>(R.id.tvTileName).text = p.name
            holder.itemView.findViewById<TextView>(R.id.tvTilePrice).apply {
                text = "₹ ${money(p.price)}"; setTextColor(accent)
            }
            holder.itemView.findViewById<TextView>(R.id.tvTileSku).text = p.sku
            bindFoodType(holder.itemView.findViewById(R.id.ivFoodType), gp.foodType)
            bindSpice(holder.itemView.findViewById(R.id.llSpice), gp.spice)
            holder.itemView.setOnClickListener { onPick(p) }
        }

        override fun getItemCount() = items.size
    }

    /** Veg / Non-Veg / Egg marker (hidden when the product has no food type set). */
    private fun bindFoodType(iv: android.widget.ImageView, foodType: String) {
        val res = when (foodType.lowercase()) {
            "veg" -> R.drawable.ic_food_veg
            "non-veg", "nonveg", "non veg" -> R.drawable.ic_food_nonveg
            "egg" -> R.drawable.ic_food_egg
            else -> 0
        }
        if (res == 0) iv.visibility = View.GONE
        else { iv.visibility = View.VISIBLE; iv.setImageResource(res) }
    }

    /** Spice level as 1–3 chili icons (Mild / Medium / Hot). */
    private fun bindSpice(container: LinearLayout, spice: String) {
        container.removeAllViews()
        val count = when (spice.lowercase()) { "mild" -> 1; "medium" -> 2; "hot" -> 3; else -> 0 }
        val size = dp(13)
        repeat(count) { i ->
            val iv = android.widget.ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).also { it.marginStart = if (i == 0) 0 else dp(1) }
                setImageResource(R.drawable.ic_chili)
            }
            container.addView(iv)
        }
    }

    // ---- Item detail popup + cart ------------------------------------------

    /** Opens the shared grocery product popup; on confirm the item joins the cart. */
    private fun showProductEntry(p: ProductEntryDialog.Product) {
        ProductEntryDialog.show(
            context = requireContext(),
            inflater = layoutInflater,
            product = p,
            startRate = p.price,
            startQty = 1,
            confirmLabel = "Add to cart",
            taxRegime = taxRegime,
            taxInclusive = taxInclusive,
            itemwiseDiscountActive = itemwiseDiscountActive,
            discountPreTax = discountPreTax
        ) { qty, rate -> addToCart(p, qty, rate) }
    }

    private fun addToCart(p: ProductEntryDialog.Product, qty: Int, rate: Double) {
        val cart = currentCart() ?: run { toast("Select a table order first"); return }
        val existing = cart.firstOrNull { it.product.id == p.id && it.rate == rate }
        if (existing != null) existing.qty += qty else cart.add(CartItem(p, qty, rate))
        renderCart()
    }

    /** Rebuilds the order-item rows from the SELECTED order's cart and recomputes totals. */
    private fun renderCart() {
        val root = view ?: return
        val container = root.findViewById<LinearLayout>(R.id.llOrderItems)
        val inflater = LayoutInflater.from(requireContext())
        container.removeAllViews()
        val cart = currentCart() ?: emptyList<CartItem>()
        cart.forEachIndexed { index, item ->
            val row = inflater.inflate(R.layout.item_order_line, container, false)
            row.findViewById<TextView>(R.id.tvLineName).text = item.product.name
            row.findViewById<TextView>(R.id.tvLineQty).text = item.qty.toString()
            row.findViewById<TextView>(R.id.tvLineRate).text = money(item.rate)
            row.findViewById<TextView>(R.id.tvLineAmount).text = money(item.qty * item.rate)
            row.findViewById<TextView>(R.id.tvLineNote).text = "—"
            row.findViewById<ImageButton>(R.id.btnPlus).setOnClickListener {
                item.qty++; renderCart()
            }
            row.findViewById<ImageButton>(R.id.btnMinus).setOnClickListener {
                if (item.qty > 1) item.qty-- else currentCart()?.removeAt(index)
                renderCart()
            }
            row.findViewById<ImageButton>(R.id.btnRemoveLine).setOnClickListener {
                currentCart()?.removeAt(index); renderCart()
            }
            container.addView(row)
            ThemeManager.applyTheme(row)
        }
        updateTotals()
    }

    private fun updateTotals() {
        val root = view ?: return
        val subtotal = (currentCart() ?: emptyList()).sumOf { it.qty * it.rate }
        val service = subtotal * 0.05
        val taxable = subtotal + service
        val cgst = taxable * 0.025
        val sgst = taxable * 0.025
        val total = subtotal + service + cgst + sgst
        root.findViewById<TextView>(R.id.tvSubtotal).text = "₹ ${money(subtotal)}"
        root.findViewById<TextView>(R.id.tvService).text = "₹ ${money(service)}"
        root.findViewById<TextView>(R.id.tvCgst).text = "₹ ${money(cgst)}"
        root.findViewById<TextView>(R.id.tvSgst).text = "₹ ${money(sgst)}"
        root.findViewById<TextView>(R.id.tvOrderTotal).text = "₹ ${money(total)}"
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBillPay).text =
            "Bill & Pay  ( ₹ ${money(total)} )"
        // Reflect the running total on the active order card.
        orders.firstOrNull { it.selected }?.let { it.amount = "₹ ${money(total)}" }
        populateOrders(root, ThemeManager.getThemeColor(requireContext()))
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun money(v: Double): String =
        String.format(java.util.Locale.US, "%,.2f", v)
}
