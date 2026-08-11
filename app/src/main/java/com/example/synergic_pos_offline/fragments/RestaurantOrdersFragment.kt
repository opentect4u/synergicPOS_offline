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

    // One line in an order's cart (backed by td_running_order_items).
    private data class CartItem(
        val productId: Long, val name: String, var qty: Double, var rate: Double,
        var dbItemId: Long = 0, var kotQty: Double = 0.0,
        val cgstRate: Double = 0.0, val sgstRate: Double = 0.0
    ) {
        /** Quantity not yet sent to the kitchen. */
        val pending: Double get() = (qty - kotQty).coerceAtLeast(0.0)
    }

    private data class OrderCard(
        val dbId: Long, var id: String, val type: String, val section: String, val phone: String,
        val time: String, var amount: String, val cashier: String,
        var status: String, var selected: Boolean, var note: String = "",
        // Each order keeps its own cart, so switching tables shows its own items.
        val items: MutableList<CartItem> = mutableListOf()
    ) {
        val completed: Boolean get() = status.equals("COMPLETED", ignoreCase = true)
    }

    // Running orders — loaded from / persisted to the database (survive restarts).
    private val orders = mutableListOf<OrderCard>()
    private val roDao by lazy { com.example.synergic_pos_offline.database.RunningOrderDao(requireContext()) }
    private val stockDao by lazy { com.example.synergic_pos_offline.database.StockDao(requireContext()) }

    /** Whether stock is tracked, as of the last catalogue read. Gates the ceiling below. */
    private var stockTrackingOn = false

    /**
     * The catalogue behind the Add Item grid, kept on the fragment so the stock
     * ceiling can be checked from the order rows too - a table restored from the
     * database has quantities to step up before that dialog has ever been opened.
     */
    private var allProducts: List<GridProduct> = emptyList()
    private val tableDao by lazy { com.example.synergic_pos_offline.database.TableDao(requireContext()) }
    private val subTableDao by lazy { com.example.synergic_pos_offline.database.SubTableDao(requireContext()) }
    private var suppressNoteWatcher = false   // guards programmatic note-field updates

    private fun currentOrder(): OrderCard? = orders.firstOrNull { it.selected }
    private fun currentCart(): MutableList<CartItem>? = currentOrder()?.items

    /** Reloads the running orders (and their items) from the database into [orders]. */
    private fun loadRunningOrders() {
        orders.clear()
        roDao.allRunning().forEach { ro ->
            val card = OrderCard(
                dbId = ro.id, id = ro.tableCode, type = ro.orderType, section = ro.section,
                phone = ro.phone, time = ro.time, amount = "₹ ${money(0.0)}",
                cashier = ro.cashier, status = ro.status, selected = false, note = ro.note
            )
            // qty 0 lines are removed items awaiting a cancellation KOT — hide from the cart.
            roDao.itemsFor(ro.id).filter { it.qty > 0.0 }.forEach { ri ->
                card.items.add(CartItem(ri.productId, ri.name, ri.qty, ri.rate, ri.id, ri.kotQty, ri.cgstRate, ri.sgstRate))
            }
            card.amount = "₹ ${money(computeBill(card.items, serviceRateFor(card.section)).total)}"
            orders.add(card)
        }
    }

    /** Reloads one order's items from the database (after a DB mutation). */
    private fun reloadItems(order: OrderCard) {
        order.items.clear()
        roDao.itemsFor(order.dbId).filter { it.qty > 0.0 }.forEach { ri ->
            order.items.add(CartItem(ri.productId, ri.name, ri.qty, ri.rate, ri.id, ri.kotQty, ri.cgstRate, ri.sgstRate))
        }
    }

    private val sectionDao by lazy { com.example.synergic_pos_offline.database.SectionDao(requireContext()) }

    /** A bill breakdown computed from per-product GST plus the section's service charge. */
    private data class BillBreakdown(
        val subtotal: Double, val service: Double, val cgst: Double, val sgst: Double, val total: Double
    )

    /** Flat service-charge amount (₹) for a section, from the Section master. */
    private fun serviceRateFor(sectionName: String): Double = sectionDao.serviceChargeForName(sectionName)

    /**
     * Bills each line by its own CGST/SGST rate — honouring the store Tax Settings
     * (GST on/off, inclusive vs exclusive) via the same [BillPricing] the saved bill
     * uses — and adds the section's service charge as a flat rupee amount. For an
     * inclusive regime the tax is already within the rate, so it isn't added again.
     */
    private fun computeBill(items: List<CartItem>, serviceChargeAmt: Double): BillBreakdown {
        var subtotal = 0.0; var cgst = 0.0; var sgst = 0.0
        items.forEach {
            val p = com.example.synergic_pos_offline.utils.BillPricing.price(
                rate = it.rate, quantity = it.qty,
                cgstRate = it.cgstRate, sgstRate = it.sgstRate, vatRate = 0.0,
                discountAmount = 0.0, regime = taxRegime, inclusive = taxInclusive, discountPreTax = discountPreTax
            )
            subtotal += it.qty * it.rate     // gross (as listed) — what the Subtotal line shows
            cgst += p.cgst
            sgst += p.sgst
        }
        // Flat section service charge, applied only to a non-empty order.
        val service = if (subtotal > 0.0) serviceChargeAmt else 0.0
        // Inclusive: tax is already inside the gross subtotal, so don't add it again.
        val total = if (taxInclusive) subtotal + service else subtotal + service + cgst + sgst
        return BillBreakdown(subtotal, service, cgst, sgst, total)
    }

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

        // Read up front, not only when Add Item opens: an order restored from the
        // database can have its quantities stepped up before that dialog is ever
        // used, and the stock ceiling has to be in place by then.
        loadProductsFromDb()

        loadRunningOrders()          // restore open tables from the database
        populateOrders(view, accent)
        clearDetail(view)

        // The order-type segment fills the selected side (handled by a state list).
        view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.segOrderType)
            .check(R.id.btnDineIn)

        // Take Away needs no table — tapping it opens a take-away order: if one is
        // already active, just select it (no duplicate token); otherwise start a new one.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTakeAway).setOnClickListener {
            view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.segOrderType)
                .check(R.id.btnTakeAway)
            val existing = orders.firstOrNull { it.type.equals("Take Away", ignoreCase = true) && !it.completed }
            if (existing != null) {
                selectOrder(existing)
            } else {
                openNewOrder(nextTakeAwayCode(), section = "", phone = "", type = "Take Away")
                toast("Take-away order started — add items, then Bill & Pay")
            }
        }
        // Dine In switches the segment and selects a dine-in table if one is active.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDineIn).setOnClickListener {
            view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.segOrderType)
                .check(R.id.btnDineIn)
            val dineIn = orders.firstOrNull { it.type.equals("Dine In", ignoreCase = true) && !it.completed }
                ?: orders.firstOrNull { it.type.equals("Dine In", ignoreCase = true) }
            if (dineIn != null) selectOrder(dineIn)
        }

        // Order note: persist per-order as it's typed (guarded against programmatic sets).
        view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etOrderNote)
            .addTextChangedListener {
                if (suppressNoteWatcher) return@addTextChangedListener
                val o = currentOrder() ?: return@addTextChangedListener
                if (o.completed) return@addTextChangedListener   // billed order is locked
                o.note = it?.toString().orEmpty()
                roDao.setNote(o.dbId, o.note)
            }

        // New Order → dine-in table/customer modal (select the Dine In segment).
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNewOrder).setOnClickListener {
            view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.segOrderType)
                .check(R.id.btnDineIn)
            showNewOrderDialog()
        }

        // Add Item → product-grid modal (only when a table/order is active and not billed).
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAddItem).setOnClickListener {
            val order = currentOrder()
            when {
                order == null -> toast("Create or select a table order first")
                order.completed -> toast("Table already billed — cannot add items")
                else -> showAddItemDialog()
            }
        }

        // Print KOT → resolve the kitchen printer, then cut a ticket for the new items.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPrintKot).setOnClickListener {
            val order = currentOrder() ?: return@setOnClickListener toast("Select a table order first")
            if (order.type.equals("Take Away", ignoreCase = true))
                return@setOnClickListener toast("Not available for Take Away — KOT prints on payment")
            if (order.completed) return@setOnClickListener toast("Table already billed")
            if (!roDao.hasPendingKot(order.dbId)) {
                toast("No new or cancelled items to send to kitchen"); return@setOnClickListener
            }
            resolveKotPrinterThenPrint(order)
        }

        // Transfer → move this order to another available table in the same section.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTransfer).setOnClickListener {
            val order = currentOrder() ?: return@setOnClickListener toast("Select a table order first")
            if (order.type.equals("Take Away", ignoreCase = true))
                return@setOnClickListener toast("Not available for Take Away")
            if (order.completed) return@setOnClickListener toast("Table already billed — cannot transfer")
            showTransferDialog(order)
        }

        // Merge → open the popup and add the active tables (same section) to combine.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMerge).setOnClickListener {
            showMergeDialog()
        }

        // Split → break the selected table into sub-tables (101 A, 101 B, …).
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSplit).setOnClickListener {
            val order = currentOrder() ?: return@setOnClickListener toast("Select a table order first")
            if (order.type.equals("Take Away", ignoreCase = true))
                return@setOnClickListener toast("Not available for Take Away")
            if (order.completed) return@setOnClickListener toast("Table already billed — cannot split")
            if (order.id.contains(" ")) return@setOnClickListener toast("This is already a split sub-table")
            showSplitDialog(order)
        }

        // Cancel Order → clear the selected active table (removes the order + items).
        // Only allowed before any KOT is sent, or once all sent items are cancelled.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelOrder).setOnClickListener {
            val order = currentOrder() ?: return@setOnClickListener toast("Select an order first")
            if (roDao.hasSentActiveItems(order.dbId)) {
                return@setOnClickListener toast("Can't cancel — items already sent to kitchen. Remove them (and Print KOT to cancel) first.")
            }
            val label = if (order.type.equals("Take Away", ignoreCase = true))
                order.id.replace("TA-", "Take Away Token #") else "Table ${order.id}"
            com.example.synergic_pos_offline.utils.DialogUtils.showConfirm(
                requireContext(),
                title = "Clear this order?",
                message = "Remove $label and all its items? This can't be undone.",
                positiveText = "Clear",
                destructive = true
            ) { clearActiveOrder(order) }
        }

        // Bill & Print → print the bill on the default BILL printer (or choose one), then lock the table.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBillPrint).setOnClickListener {
            val order = currentOrder() ?: return@setOnClickListener toast("Select a table order first")
            when {
                order.items.isEmpty() -> toast("Add items before printing the bill")
                order.completed -> toast("Table already billed")
                else -> resolveBillPrinterThenPrint(order)
            }
        }

        // When checkout confirms payment, settle the table: close it (in DB too),
        // then print the receipt with a preview — the same as the grocery bill flow.
        parentFragmentManager.setFragmentResultListener(
            RestaurantCheckoutFragment.RESULT_PAID, viewLifecycleOwner
        ) { _, bundle ->
            val paidTable = bundle.getString(RestaurantCheckoutFragment.ARG_TABLE)
            // Resolve the order first — settlePaidOrder removes it from the list, so a
            // second lookup afterwards would find nothing and the bill would never save
            // or print.
            val order = orders.firstOrNull { it.id == paidTable } ?: return@setFragmentResultListener
            // What was served has left the shelf. Done here rather than at bill save
            // because Restaurant checkout does not write a bill - settling the order is
            // the only moment the sale is known to be complete.
            if (stockTrackingOn) {
                stockDao.recordSale(
                    reference = "Table ${order.id}",
                    lines = order.items.map {
                        com.example.synergic_pos_offline.database.StockDao.SaleLine(
                            it.productId.toInt(), it.qty.toDouble()
                        )
                    }
                )
            }
            val payMethod = bundle.getString(RestaurantCheckoutFragment.ARG_PAY_METHOD).orEmpty()
            val tendered = bundle.getDouble(RestaurantCheckoutFragment.ARG_TENDERED, 0.0)
            // Persists the bill, closes & frees the table(s), refreshes the list, and
            // prints the paid receipt (with the payment mode).
            settlePaidOrder(order, payMethod, tendered)
            // Grid product counts have moved after the sale.
            loadProductsFromDb()
        }

        // Bill & Pay → restaurant checkout with the selected order's items.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBillPay).setOnClickListener {
            val order = orders.firstOrNull { it.selected }
            when {
                order == null -> toast("Select a table order first")
                order.items.isEmpty() -> toast("Add items before billing")
                else -> {
                    val names = ArrayList(order.items.map { it.name })
                    val qtys = order.items.map { it.qty }.toDoubleArray()
                    val rates = order.items.map { it.rate }.toDoubleArray()
                    val cgsts = order.items.map { it.cgstRate }.toDoubleArray()
                    val sgsts = order.items.map { it.sgstRate }.toDoubleArray()
                    requireActivity().supportFragmentManager.beginTransaction()
                        .replace(
                            R.id.fragment_container,
                            RestaurantCheckoutFragment.newInstance(
                                order.id, order.phone.ifBlank { "Walk-in" }, names, qtys, rates,
                                cgsts, sgsts, serviceRateFor(order.section),
                                gstEnabled = taxSettings.gstEnabled, inclusive = taxInclusive
                            )
                        )
                        .addToBackStack(null)
                        .commit()
                }
            }
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

        // Active-orders count badge.
        root.findViewById<TextView>(R.id.tabActive).setTextColor(accent)
        root.findViewById<TextView>(R.id.badgeActive).apply {
            text = orders.size.toString(); backgroundTintList = ColorStateList.valueOf(accent)
        }

        // Empty state: no orders yet.
        val emptyView = root.findViewById<TextView>(R.id.tvNoOrders)
        emptyView?.visibility = if (orders.isEmpty()) View.VISIBLE else View.GONE

        orders.forEach { o ->
            val card = inflater.inflate(R.layout.item_order_card, list, false)
                    as com.google.android.material.card.MaterialCardView
            val takeAway = o.type.equals("Take Away", ignoreCase = true)
            card.findViewById<TextView>(R.id.tvOrderId).apply {
                text = if (takeAway) o.id.replace("TA-", "Token #") else o.id; setTextColor(accent)
            }
            card.findViewById<TextView>(R.id.tvOrderType).text = o.type
            card.findViewById<TextView>(R.id.tvOrderGuests).text =
                if (takeAway) "—" else o.section.ifBlank { "—" }
            card.findViewById<TextView>(R.id.tvOrderTime).apply { text = o.time; setTextColor(accent) }
            card.findViewById<TextView>(R.id.tvOrderCashier).text = o.cashier
            card.findViewById<TextView>(R.id.tvOrderAmount).text = o.amount
            card.findViewById<TextView>(R.id.tvOrderStatus).apply {
                // An empty split sub-table reads as Available until it gets items.
                text = when {
                    o.completed -> "Completed • Billed"
                    o.id.contains(" ") && o.items.isEmpty() -> "Available"
                    else -> "In Progress"
                }
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
        // Reflect the selected order's type on the top segment (programmatic check
        // doesn't fire the Take Away click listener, so it won't open a new order).
        root.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.segOrderType).check(
            if (order.type.equals("Take Away", ignoreCase = true)) R.id.btnTakeAway else R.id.btnDineIn
        )
        populateOrders(root, ThemeManager.getThemeColor(requireContext()))
        showOrderDetail(order)
        renderCart()   // show this table's own items + totals
    }

    /** Updates the detail-panel header for the given order. */
    private fun showOrderDetail(order: OrderCard) {
        val root = view ?: return
        val accent = ThemeManager.getThemeColor(requireContext())
        val takeAway = order.type.equals("Take Away", ignoreCase = true)
        // Take Away has no table — show it as a take-away token, not "Table: …".
        root.findViewById<TextView>(R.id.tvDetailTableLabel).visibility = if (takeAway) View.GONE else View.VISIBLE
        root.findViewById<TextView>(R.id.tvDetailTable).apply {
            text = if (takeAway) "Take Away" else order.id; setTextColor(accent)
        }
        root.findViewById<TextView>(R.id.tvDetailCustomer).text = order.phone.ifBlank { "Walk-in" }
        root.findViewById<TextView>(R.id.tvDetailGuests).text =
            if (takeAway) order.id.replace("TA-", "Token #")
            else if (order.section.isNotBlank()) "${order.section}  ·  ${order.type}" else order.type
        root.findViewById<TextView>(R.id.tvDetailOrderTime).text =
            "Order Time: ${order.time.ifBlank { "—" }}"
        setNoteField(root, order.note)
        // Take Away has no table to KOT/transfer/merge; disable those actions.
        setDineInActionsEnabled(root, !order.type.equals("Take Away", ignoreCase = true))
    }

    /** Enables/disables the dine-in-only actions (Print KOT, Transfer, Merge, Split). */
    private fun setDineInActionsEnabled(root: View, enabled: Boolean) {
        listOf(R.id.btnPrintKot, R.id.btnTransfer, R.id.btnMerge, R.id.btnSplit).forEach { id ->
            root.findViewById<com.google.android.material.button.MaterialButton>(id).apply {
                isEnabled = enabled; alpha = if (enabled) 1f else 0.4f
            }
        }
    }

    /** Sets the order-note field without triggering the persist watcher. */
    private fun setNoteField(root: View, note: String) {
        suppressNoteWatcher = true
        root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etOrderNote).setText(note)
        suppressNoteWatcher = false
    }

    /** Neutral detail panel when no order is selected: empty cart + zeroed totals. */
    private fun clearDetail(root: View) {
        root.findViewById<TextView>(R.id.tvDetailTableLabel).visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.tvDetailTable).text = "—"
        root.findViewById<TextView>(R.id.tvDetailCustomer).text = "Walk-in"
        root.findViewById<TextView>(R.id.tvDetailGuests).text = "—"
        root.findViewById<TextView>(R.id.tvDetailOrderTime).text = "Order Time: —"
        setDineInActionsEnabled(root, true)   // neutral state: actions available again
        setNoteField(root, "")
        root.findViewById<LinearLayout>(R.id.llOrderItems).removeAllViews()
        val zero = "₹ ${money(0.0)}"
        root.findViewById<TextView>(R.id.tvSubtotal).text = zero
        root.findViewById<TextView>(R.id.tvService).text = zero
        root.findViewById<TextView>(R.id.tvCgst).text = zero
        root.findViewById<TextView>(R.id.tvSgst).text = zero
        root.findViewById<TextView>(R.id.tvOrderTotal).text = zero
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBillPay).text =
            "Checkout  ( $zero )"
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
        outlined(R.id.btnRefreshOrders); outlined(R.id.btnPrintKot)
        outlined(R.id.btnTransfer); outlined(R.id.btnMerge); outlined(R.id.btnSplit)
        outlined(R.id.btnCancelOrder); outlined(R.id.btnBillPrint)

        // Segment toggle colours.
        listOf(R.id.btnDineIn, R.id.btnTakeAway).forEach {
            root.findViewById<com.google.android.material.button.MaterialButton>(it).strokeColor =
                ColorStateList.valueOf(accent)
        }

        // Active-orders tab + count badge + detail accents.
        root.findViewById<TextView>(R.id.tabActive).setTextColor(accent)
        root.findViewById<TextView>(R.id.badgeActive).backgroundTintList = ColorStateList.valueOf(accent)
        root.findViewById<TextView>(R.id.tvDetailTable).setTextColor(accent)
        root.findViewById<TextView>(R.id.tvOrderTotal).setTextColor(accent)
        root.findViewById<TextView>(R.id.tvDetailCustomer).apply {
            setTextColor(accent)
            backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x14))
        }
    }

    // ---- New Order: table + customer modal (dine-in) -----------------------

    private fun showNewOrderDialog() {
        val ctx = com.example.synergic_pos_offline.utils.FixedFontScale.wrap(requireContext())
        val accent = ThemeManager.getThemeColor(ctx)
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_new_order, null)
        com.example.synergic_pos_offline.utils.InputLimits.applyDefaults(v)
        val dialog = AlertDialog.Builder(ctx).setView(v).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT); setGravity(android.view.Gravity.CENTER) }

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
            val phone = etPhone.text?.toString()?.trim().orEmpty()
            val table = etTable.text?.toString()?.trim().orEmpty()
            if (table.isEmpty()) { etTable.error = "Enter a table no"; return@setOnClickListener }
            // Don't open a second order for a table that already has an active one.
            if (orders.any { it.id.equals(table, ignoreCase = true) }) {
                etTable.error = "Table $table already has an active order"
                return@setOnClickListener
            }
            // Nor for a table that isn't free (occupied/billing/merged into another order).
            val status = TableDao(ctx).statusOf(table)
            if (status != null && !status.equals("Available", ignoreCase = true)) {
                etTable.error = "Table $table is $status"
                return@setOnClickListener
            }
            val section = etSection.text?.toString()?.trim().orEmpty()
            dialog.dismiss()
            openNewOrder(table, section, phone, type = "Dine In")
            toast("Order created for table $table")
        }

        dialog.show()
    }

    /** Next unused take-away token (TA-1, TA-2, …) among the active orders. */
    private fun nextTakeAwayCode(): String {
        val active = orders.map { it.id }.toSet()
        var n = 1
        while (active.contains("TA-$n")) n++
        return "TA-$n"
    }

    /** Persists a new running order, selects it, and starts it with a fresh empty cart. */
    private fun openNewOrder(table: String, section: String, phone: String, type: String) {
        val root = view ?: return
        val now = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
        val cashier = com.example.synergic_pos_offline.utils.SessionManager.currentUser?.userId ?: "—"

        val dbId = roDao.createOrder(table, section, null, type, phone, cashier)
        if (dbId == -1L) { toast("Could not create order"); return }
        if (!type.equals("Take Away", ignoreCase = true))
            tableDao.setStatusByCode(table, "Occupied")   // dine-in table now has a live order

        orders.forEach { it.selected = false }
        val order = OrderCard(
            dbId = dbId, id = table, type = type, section = section, phone = phone, time = now,
            amount = "₹ ${money(0.0)}", cashier = cashier, status = "RUNNING", selected = true
        )
        orders.add(0, order)                                  // newest on top
        populateOrders(root, ThemeManager.getThemeColor(requireContext()))
        showOrderDetail(order)
        renderCart()   // this order's (empty) cart + zeroed totals
    }

    // ---- Table Transfer ----------------------------------------------------

    /**
     * Transfer popup: From is the selected table (read-only); To is a dropdown of
     * AVAILABLE tables in the SAME section. Validation: a target must be picked, be
     * in the same section, and be currently available.
     */
    private fun showTransferDialog(order: OrderCard) {
        val ctx = com.example.synergic_pos_offline.utils.FixedFontScale.wrap(requireContext())
        val accent = ThemeManager.getThemeColor(ctx)

        // Valid targets: available tables in the same section, minus any that already
        // hold an active order in memory (defensive, in case a status drifted).
        val activeCodes = orders.map { it.id.lowercase() }.toSet()
        val targets = tableDao.availableTablesSameSection(order.section, order.id)
            .filter { it.lowercase() !in activeCodes }
        if (targets.isEmpty()) {
            toast("No available table in ${order.section.ifBlank { "this section" }}"); return
        }

        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_transfer_table, null)
        val dialog = AlertDialog.Builder(ctx).setView(v).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT); setGravity(android.view.Gravity.CENTER) }

        val etFrom = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etFromTable)
        val etSection = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etTransferSection)
        val actTo = v.findViewById<android.widget.AutoCompleteTextView>(R.id.actToTable)
        val btnSave = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFormPositive)
        val btnCancel = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFormNegative)

        etFrom.setText(order.id)
        etSection.setText(order.section.ifBlank { "—" })
        actTo.setAdapter(android.widget.ArrayAdapter(ctx, android.R.layout.simple_list_item_1, targets))
        actTo.setOnClickListener { actTo.showDropDown() }

        ThemeManager.applyTheme(v)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent); btnSave.setTextColor(Color.WHITE)
        btnCancel.setTextColor(accent); btnCancel.strokeColor = ColorStateList.valueOf(accent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val to = actTo.text?.toString()?.trim().orEmpty()
            when {
                to.isEmpty() -> actTo.error = "Select a table"
                to.equals(order.id, ignoreCase = true) -> actTo.error = "Choose a different table"
                !targets.contains(to) -> actTo.error = "Not an available table in this section"
                else -> { dialog.dismiss(); performTransfer(order, to) }
            }
        }
        dialog.show()
    }

    /** Applies the transfer: move the order, free the old table, occupy the new one. */
    private fun performTransfer(order: OrderCard, to: String) {
        val from = order.id
        roDao.transferTable(order.dbId, to)
        tableDao.setStatusByCode(from, "Available")   // old table freed
        tableDao.setStatusByCode(to, "Occupied")      // new table taken
        order.id = to
        val root = view ?: return
        populateOrders(root, ThemeManager.getThemeColor(requireContext()))
        showOrderDetail(order)                         // refresh the detail header
        toast("Order moved from table $from to $to")
    }

    // ---- Table Merge -------------------------------------------------------

    /**
     * Merge popup — opens without any table pre-selected. You add active (running,
     * not billed) tables from a dropdown; the FIRST one added is kept and the rest
     * merge into it. Once a table is added, the section locks and the dropdown only
     * offers same-section tables. Needs at least two tables.
     */
    private fun showMergeDialog() {
        val ctx = com.example.synergic_pos_offline.utils.FixedFontScale.wrap(requireContext())
        val accent = ThemeManager.getThemeColor(ctx)

        // Every active DINE-IN table (running, not billed) is a candidate at first.
        // Take Away orders have no table to merge.
        val activeTables = orders.filter { !it.completed && !it.type.equals("Take Away", ignoreCase = true) }
        if (activeTables.size < 2) { toast("Need at least two active dine-in tables to merge"); return }

        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_merge_table, null)
        val dialog = AlertDialog.Builder(ctx).setView(v).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT); setGravity(android.view.Gravity.CENTER) }

        val etSection = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etMergeSection)
        val actWith = v.findViewById<android.widget.AutoCompleteTextView>(R.id.actMergeWith)
        val btnAdd = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAddMergeTable)
        val llTables = v.findViewById<LinearLayout>(R.id.llMergeTables)
        val tvEmpty = v.findViewById<TextView>(R.id.tvMergeEmpty)
        val btnSave = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFormPositive)
        val btnCancel = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFormNegative)

        val added = mutableListOf<String>()   // tables queued to merge (first = kept)
        fun sectionOf(code: String) = orders.firstOrNull { it.id == code }?.section.orEmpty()

        // Candidates: before any add — all active tables; after — same section as the first.
        fun candidates(): List<String> {
            val base = if (added.isEmpty()) activeTables
            else activeTables.filter { it.section.equals(sectionOf(added.first()), ignoreCase = true) }
            return base.map { it.id }.filter { it !in added }
        }
        fun refreshDropdown() {
            actWith.setAdapter(android.widget.ArrayAdapter(ctx, android.R.layout.simple_list_item_1, candidates()))
            actWith.setText("", false)
        }
        fun renderAdded() {
            llTables.removeAllViews()
            tvEmpty.visibility = if (added.isEmpty()) View.VISIBLE else View.GONE
            etSection.setText(if (added.isEmpty()) "" else sectionOf(added.first()).ifBlank { "—" })
            added.forEachIndexed { index, code ->
                val row = LayoutInflater.from(ctx).inflate(R.layout.item_merge_table, llTables, false)
                row.findViewById<TextView>(R.id.tvMergeTableName).text =
                    if (index == 0) "Table $code  (Kept)" else "Table $code"
                val count = orders.firstOrNull { it.id == code }?.items?.size ?: 0
                row.findViewById<TextView>(R.id.tvMergeTableInfo).text = "$count item${if (count == 1) "" else "s"}"
                row.findViewById<android.widget.ImageView>(R.id.btnRemoveMergeTable).setOnClickListener {
                    added.remove(code); renderAdded(); refreshDropdown()
                }
                llTables.addView(row)
            }
        }

        actWith.setOnClickListener { actWith.showDropDown() }
        btnAdd.setOnClickListener {
            val pick = actWith.text?.toString()?.trim().orEmpty()
            when {
                pick.isEmpty() -> actWith.error = "Select a table"
                pick !in candidates() -> actWith.error = "Not an active table in this section"
                else -> { added.add(pick); renderAdded(); refreshDropdown() }
            }
        }

        ThemeManager.applyTheme(v)
        btnAdd.setTextColor(accent); btnAdd.strokeColor = ColorStateList.valueOf(accent); btnAdd.iconTint = ColorStateList.valueOf(accent)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent); btnSave.setTextColor(Color.WHITE)
        btnCancel.setTextColor(accent); btnCancel.strokeColor = ColorStateList.valueOf(accent)

        refreshDropdown()
        renderAdded()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            if (added.size < 2) { toast("Add at least two tables to merge"); return@setOnClickListener }
            dialog.dismiss()
            performMerge(added.first(), added.drop(1))
        }
        dialog.show()
    }

    /** Applies the merge: fold each source table's items into the kept table. The
     *  merged tables stay Occupied (part of the merge) and are freed only when the
     *  kept order is settled. */
    private fun performMerge(keepCode: String, sourceCodes: List<String>) {
        val target = orders.firstOrNull { it.id == keepCode } ?: return
        sourceCodes.forEach { code ->
            val source = orders.firstOrNull { it.id == code } ?: return@forEach
            roDao.mergeOrders(target.dbId, source.dbId)   // records the merged table + keeps it Occupied
            orders.removeAll { it.id == source.id }        // its own order card is gone (shares the kept bill)
        }
        reloadItems(target)                                    // pull the combined items
        orders.forEach { it.selected = it.id == target.id }    // focus the kept table
        val root = view ?: return
        populateOrders(root, ThemeManager.getThemeColor(requireContext()))
        showOrderDetail(target)
        renderCart()                                           // combined items + totals
        toast("${sourceCodes.size} table${if (sourceCodes.size == 1) "" else "s"} merged into ${target.id}")
    }

    // ---- Table Split -------------------------------------------------------

    /**
     * Split popup: choose how many parts (2–4); shows the sub-tables that will be
     * created (e.g. 101 A, 101 B). On Split the first part keeps this order's items;
     * the rest start empty. All parts share the parent table until each is settled.
     */
    private fun showSplitDialog(order: OrderCard) {
        val ctx = com.example.synergic_pos_offline.utils.FixedFontScale.wrap(requireContext())
        val accent = ThemeManager.getThemeColor(ctx)
        val counts = listOf("2", "3", "4")

        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_split_table, null)
        val dialog = AlertDialog.Builder(ctx).setView(v).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT); setGravity(android.view.Gravity.CENTER) }

        val etTable = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSplitTable)
        val actCount = v.findViewById<android.widget.AutoCompleteTextView>(R.id.actSplitCount)
        val tvPreview = v.findViewById<TextView>(R.id.tvSplitPreview)
        val btnSave = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFormPositive)
        val btnCancel = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFormNegative)

        etTable.setText(order.id)
        fun preview(count: Int) {
            tvPreview.text = (0 until count).joinToString(",  ") { "${order.id} ${('A' + it)}" }
        }
        actCount.setAdapter(android.widget.ArrayAdapter(ctx, android.R.layout.simple_list_item_1, counts))
        actCount.setText("2", false)
        preview(2)
        actCount.setOnClickListener { actCount.showDropDown() }
        actCount.setOnItemClickListener { _, _, pos, _ -> preview(counts[pos].toInt()) }

        ThemeManager.applyTheme(v)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent); btnSave.setTextColor(Color.WHITE)
        btnCancel.setTextColor(accent); btnCancel.strokeColor = ColorStateList.valueOf(accent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val count = actCount.text?.toString()?.trim()?.toIntOrNull() ?: 2
            dialog.dismiss()
            performSplit(order, count.coerceIn(2, 4))
        }
        dialog.show()
    }

    /** Applies the split: first part keeps the order/items, the rest are new empties. */
    private fun performSplit(order: OrderCard, count: Int) {
        val parent = order.id
        val waiterId = roDao.findByTable(parent)?.waiterId
        val cashier = com.example.synergic_pos_offline.utils.SessionManager.currentUser?.userId ?: "—"

        // Part A keeps the existing order (and its items) — occupied.
        val firstCode = subTableDao.create(parent, "A", status = "Occupied")
        roDao.transferTable(order.dbId, firstCode)
        // Parts B, C, D start empty → Available until items are added.
        for (i in 1 until count) {
            val code = subTableDao.create(parent, ('A' + i).toString(), status = "Available")
            roDao.createOrder(code, order.section, waiterId, order.type, order.phone, cashier)
        }
        tableDao.setStatusByCode(parent, "Occupied")   // parent stays occupied by its parts

        loadRunningOrders()
        val root = view ?: return
        populateOrders(root, ThemeManager.getThemeColor(requireContext()))
        orders.firstOrNull { it.id == firstCode }?.let { selectOrder(it) } ?: clearDetail(root)
        toast("Table $parent split into $count")
    }

    /**
     * The split is finished once every remaining part is empty (no items) or gone:
     * tear down all its parts, free the parent table and drop its sub-table records.
     * While at least one part still has items, the split stays and empty parts remain
     * as Available slots to re-order.
     */
    private fun freeParentIfSplitDone(code: String) {
        if (!code.contains(" ")) return
        val parent = code.substringBeforeLast(" ").trim()
        val parts = orders.filter { it.id.startsWith("$parent ") }
        if (parts.all { it.items.isEmpty() }) {
            parts.forEach { roDao.close(it.dbId) }
            orders.removeAll { it.id.startsWith("$parent ") }
            tableDao.setStatusByCode(parent, "Available")
            subTableDao.clearForParent(parent)
        }
    }

    /** A grid entry: the popup product plus its restaurant attributes (food type + spice). */
    private data class GridProduct(
        val product: ProductEntryDialog.Product, val foodType: String, val spice: String,
        /** The scanned code, kept beside the product now that its SKU is its own id
         *  - both are searched on, so a scan into the search box still finds it. */
        val barcode: String = "",
        /** Preparation time from the master (e.g. "15" / "15 min"); shown on the tile. */
        val prepTime: String = "",
        /** Product image bytes from the master, or null; shown on the tile when present. */
        val image: ByteArray? = null
    )

    /** Loads the current store's products (rate + tax split + category + food/spice), for the grid. */
    private fun loadProductsFromDb(): List<GridProduct> {
        val db = com.example.synergic_pos_offline.database.DatabaseHelper.getInstance(requireContext()).readableDatabase
        val store = currentStoreId(db)
        val cats = com.example.synergic_pos_offline.database.CategoryDao(requireContext())
            .getAll().associate { it.id to it.name }
        val multipleRates = SettingsCache.value(requireContext(), "G", "Item Rate") == "M"

        // Read once for the whole grid, and only while stock is tracked - with the
        // flag off this screen never asks the stock tables anything, exactly as the
        // grocery sale screen does not.
        stockTrackingOn = com.example.synergic_pos_offline.database.GeneralSettingsDao
            .isStockEnabled(requireContext())
        val levels = if (stockTrackingOn) {
            com.example.synergic_pos_offline.database.StockDao(requireContext())
                .levels(store?.toInt() ?: 0)
        } else emptyMap()

        val out = mutableListOf<GridProduct>()
        db.query(
            "md_products",
            arrayOf("id", "product_name", "bar_code", "hsn_code", "category_id", "food_type", "spice_level", "availability", "prep_time", "product_image"),
            (if (store != null) "store_id = ?" else null),
            store?.let { arrayOf(it.toString()) },
            null, null, "product_name ASC"
        ).use { c ->
            while (c.moveToNext()) {
                // Only sellable items reach the Add Item grid: a product explicitly set
                // Unavailable in the master is hidden. Unset (blank) counts as available.
                if (c.getString(7)?.equals("Unavailable", ignoreCase = true) == true) continue
                val id = c.getLong(0).toString()
                val name = c.getString(1).orEmpty()
                // The SKU is the product's own id; the barcode is searched on too.
                val sku = id
                val barcode = c.getString(2).orEmpty()
                val hsn = c.getString(3)?.takeIf { it.isNotBlank() } ?: "0000"
                val catName = cats[c.getLong(4)].orEmpty()
                val foodType = c.getString(5).orEmpty()
                val spice = c.getString(6).orEmpty()
                val prepTime = c.getString(8).orEmpty()
                val image = if (c.isNull(9)) null else c.getBlob(9)
                var price = 0.0; var cgst = 0.0; var sgst = 0.0; var vat = 0.0
                var disc = 0.0; var discType: String? = null; var unitId: Long? = null
                db.query(
                    "md_product_rates",
                    arrayOf("rate", "cgst_rate", "sgst_rate", "vat_rate", "discount", "discount_type", "unit_id"),
                    "product_id = ?", arrayOf(id), null, null, "\"default\" DESC, id ASC", "1"
                ).use { r ->
                    if (r.moveToFirst()) {
                        price = if (r.isNull(0)) 0.0 else r.getDouble(0)
                        cgst = if (r.isNull(1)) 0.0 else r.getDouble(1)
                        sgst = if (r.isNull(2)) 0.0 else r.getDouble(2)
                        vat = if (r.isNull(3)) 0.0 else r.getDouble(3)
                        disc = if (r.isNull(4)) 0.0 else r.getDouble(4)
                        discType = r.getString(5)
                        unitId = if (r.isNull(6)) null else r.getLong(6)
                    }
                }
                val (unitSymbol, allowFraction) = unitInfo(db, unitId)
                val rates = if (multipleRates) loadRates(db, id) else emptyList()
                val level = if (stockTrackingOn) levels[c.getLong(0)] else null
                out.add(
                    GridProduct(
                        ProductEntryDialog.Product(
                            id = id, name = name, sku = sku, category = catName, price = price,
                            hsn = hsn, unit = unitSymbol, allowFraction = allowFraction, 
                            cgst = cgst, sgst = sgst, vat = vat,
                            discValue = disc, discType = discType, rates = rates,
                            stock = com.example.synergic_pos_offline.utils.StockBadge.stateOf(level),
                            stockQty = level?.quantity ?: 0.0
                        ),
                        foodType = foodType, spice = spice, barcode = barcode,
                        prepTime = prepTime, image = image
                    )
                )
            }
        }
        allProducts = out
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

    /** A unit's symbol and whether it allows fractional quantities (fraction_flag). */
    private fun unitInfo(db: android.database.sqlite.SQLiteDatabase, unitId: Long?): Pair<String, Boolean> {
        if (unitId == null) return "" to false
        db.query("md_units", arrayOf("unit_symbol", "fraction_flag"),
            "id = ?", arrayOf(unitId.toString()), null, null, null, "1").use { c ->
            if (c.moveToFirst()) return (c.getString(0).orEmpty() to (c.getInt(1) == 1))
        }
        return "" to false
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
        val ctx = com.example.synergic_pos_offline.utils.FixedFontScale.wrap(requireContext())
        val accent = ThemeManager.getThemeColor(ctx)
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_item, null)
        val dialog = AlertDialog.Builder(ctx).setView(v).create()
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT); setGravity(android.view.Gravity.CENTER) }

        val etSearch = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSearch)
        val llCats = v.findViewById<LinearLayout>(R.id.llCategories)
        val rv = v.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvProducts)

        // Live data from md_products / md_category (store-scoped).
        val products = loadProductsFromDb()
        val catNames = listOf("All") + products.map { it.product.category }.filter { it.isNotBlank() }.distinct()

        var selectedCat = "All"
        var query = ""

        // Picking a product opens the qty dialog; once it's added, clear the search
        // box so the next item can be searched from a clean slate. Clearing the text
        // fires the watcher below, which resets the list back to z full menu.
        //
        // Direct Add to Cart (App Settings): skip the popup and add one of the tapped
        // item at its default rate straight to the cart; each tap adds one more.
        val directAdd = com.example.synergic_pos_offline.utils.SettingsCache
            .value(ctx, "A", "Direct Add to Cart") == "1"
        val adapter = ProductAdapter(accent) { picked ->
            if (directAdd) {
                val before = currentOrder()?.items?.sumOf { it.qty } ?: 0.0
                addToCart(picked, 1.0, picked.price)
                // Only announce when the tap actually added (order selected, in stock),
                // and show the running count: "1 item added", "2 items added", …
                val after = currentOrder()?.items?.sumOf { it.qty } ?: 0.0
                if (after > before) toast(itemsAddedMessage(after))
                etSearch.setText("")
            } else {
                showProductEntry(picked) { etSearch.setText("") }
            }
        }
        rv.layoutManager = androidx.recyclerview.widget.GridLayoutManager(ctx, 4)
        rv.adapter = adapter

        fun refresh() {
            val q = query.trim().lowercase()
            adapter.submit(products.filter {
                (selectedCat == "All" || it.product.category == selectedCat) &&
                    (q.isEmpty() || it.product.name.lowercase().contains(q) || it.product.sku.contains(q) || it.barcode.contains(q))
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
            // Show the product's own image on the tile when the master has one; else the
            // coloured initials tile underneath shows through.
            holder.itemView.findViewById<android.widget.ImageView>(R.id.ivTileImage).apply {
                val bmp = gp.image?.let {
                    runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
                }
                if (bmp != null) { setImageBitmap(bmp); visibility = View.VISIBLE }
                else { setImageDrawable(null); visibility = View.GONE }
            }
            holder.itemView.findViewById<TextView>(R.id.tvTileName).text = p.name
            holder.itemView.findViewById<TextView>(R.id.tvTilePrice).apply {
                text = "₹ ${money(p.price)}"; setTextColor(accent)
            }
            holder.itemView.findViewById<TextView>(R.id.tvTileSku).text = p.sku
            bindFoodType(holder.itemView.findViewById(R.id.ivFoodType), gp.foodType)
            bindSpice(holder.itemView.findViewById(R.id.llSpice), gp.spice)
            bindPrepTime(
                holder.itemView.findViewById(R.id.llPrepTime),
                holder.itemView.findViewById(R.id.tvTilePrepTime),
                gp.prepTime
            )
            com.example.synergic_pos_offline.utils.StockBadge.apply(
                holder.itemView.findViewById(R.id.tvTileStock), p.stock, p.stockQty
            )
            holder.itemView.alpha =
                if (p.stock == com.example.synergic_pos_offline.utils.StockBadge.OUT) 0.5f else 1f
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

    /**
     * Preparation time on the tile (clock icon + minutes). Hidden when the product has
     * no prep time set. A bare number is shown as "N min"; text already carrying a unit
     * (e.g. "15 min") is shown as entered.
     */
    private fun bindPrepTime(container: LinearLayout, label: TextView, prepTime: String) {
        val value = prepTime.trim()
        if (value.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        label.text = if (value.all { it.isDigit() }) "$value min" else value
        container.visibility = View.VISIBLE
    }

    // ---- Item detail popup + cart ------------------------------------------

    /** Opens the shared grocery product popup; on confirm the item joins the cart. */
    private fun showProductEntry(p: ProductEntryDialog.Product, onAdded: (() -> Unit)? = null) {
        // "Enter Quantity" general setting: on lets the quantity be typed in the popup,
        // off fixes it to 1 (the default add). Off when the setting was never saved.
        val qtyEditable = com.example.synergic_pos_offline.utils.SettingsCache
            .value(requireContext(), "G", "Quantity Status") == "1"
        ProductEntryDialog.show(
            context = requireContext(),
            inflater = layoutInflater,
            product = p,
            startRate = p.price,
            startQty = 1.0,
            confirmLabel = "Add to cart",
            qtyEditable = qtyEditable,
            taxRegime = taxRegime,
            taxInclusive = taxInclusive,
            itemwiseDiscountActive = itemwiseDiscountActive,
            discountPreTax = discountPreTax
        ) { qty, rate ->
            addToCart(p, qty, rate)
            onAdded?.invoke()
        }
    }

    /**
     * Whether putting [wantedQty] of [productId] on this order would sell stock that
     * is not there, counting what the order already holds of it.
     *
     * Scoped to the order being served, matching how the grocery cart is checked.
     * Stock is only taken off the shelf when an order is paid, so two tables can
     * still each be promised the last one - covering that would mean reserving
     * stock the moment it is ordered, which is a different thing from a ceiling.
     */
    private fun exceedsStock(productId: String, wantedQty: Double, ignoreItemId: Long = 0L): Boolean {
        if (!stockTrackingOn) return false
        val gp = allProducts.firstOrNull { it.product.id == productId } ?: return false
        val onOrder = currentOrder()?.items.orEmpty()
            .filter { it.dbItemId != ignoreItemId && it.productId.toString() == productId }
            .sumOf { it.qty }
        if (onOrder + wantedQty <= gp.product.stockQty + 0.0001) return false

        val remaining = (gp.product.stockQty - onOrder).coerceAtLeast(0.0)
        toast(
            if (remaining <= 0.0) "${gp.product.name}: no stock left to add"
            else "${gp.product.name}: only ${com.example.synergic_pos_offline.database.StockDao.trim(remaining)} left in stock"
        )
        return true
    }

    private fun addToCart(p: ProductEntryDialog.Product, qty: Double, rate: Double) {
        val order = currentOrder() ?: run { toast("Select a table order first"); return }
        if (exceedsStock(p.id, qty)) return
        roDao.addItem(order.dbId, p.id.toLongOrNull() ?: 0L, p.name, qty, rate, p.cgst, p.sgst)
        // A split sub-table with its first item is now Occupied.
        if (order.id.contains(" ")) subTableDao.setStatus(order.id, "Occupied")
        reloadItems(order)
        renderCart()
    }

    /** "N item(s) added" for the running Direct-Add-to-Cart toast; [total] is the
     *  order's total quantity, shown whole when it has no fraction. */
    private fun itemsAddedMessage(total: Double): String {
        val display = if (total % 1.0 == 0.0) total.toInt().toString() else total.toString()
        return "$display ${if (total == 1.0) "item" else "items"} added"
    }

    /** Rebuilds the order-item rows from the SELECTED order's cart and recomputes totals. */
    private fun renderCart() {
        val root = view ?: return
        val container = root.findViewById<LinearLayout>(R.id.llOrderItems)
        val inflater = LayoutInflater.from(requireContext())
        container.removeAllViews()
        val accent = ThemeManager.getThemeColor(requireContext())
        val order = currentOrder()
        val locked = order?.completed == true      // billed → read-only
        val cart = order?.items ?: emptyList<CartItem>()
        cart.forEach { item ->
            val row = inflater.inflate(R.layout.item_order_line, container, false)
            row.findViewById<TextView>(R.id.tvLineName).text = item.name
            row.findViewById<TextView>(R.id.tvLineQty).text = qtyText(item.qty)
            row.findViewById<TextView>(R.id.tvLineRate).text = money(item.rate)
            row.findViewById<TextView>(R.id.tvLineAmount).text = money(item.qty * item.rate)
            // KOT status: any quantity not yet sent shows NEW ×n (accent); else ✓ Sent.
            row.findViewById<TextView>(R.id.tvLineNote).apply {
                if (item.pending > 0.0) { text = "NEW ×${qtyText(item.pending)}"; setTextColor(accent) }
                else { text = "✓ Sent"; setTextColor(0xFF9AA0A6.toInt()) }
            }
            val btnPlus = row.findViewById<ImageButton>(R.id.btnPlus)
            val btnMinus = row.findViewById<ImageButton>(R.id.btnMinus)
            val btnRemove = row.findViewById<ImageButton>(R.id.btnRemoveLine)
            if (locked) {
                // Billed order — hide the editing controls entirely.
                btnPlus.visibility = View.GONE
                btnMinus.visibility = View.GONE
                btnRemove.visibility = View.GONE
            } else {
                btnPlus.setOnClickListener {
                    // Only a step up can outrun the shelf; stepping down never can.
                    if (exceedsStock(item.productId.toString(), item.qty + 1.0, item.dbItemId)) {
                        return@setOnClickListener
                    }
                    roDao.setItemQty(item.dbItemId, item.qty + 1.0); order?.let { reloadItems(it) }; renderCart()
                }
                btnMinus.setOnClickListener {
                    roDao.setItemQty(item.dbItemId, (item.qty - 1.0).coerceAtLeast(0.0))
                    order?.let { reloadItems(it) }; renderCart()
                }
                btnRemove.setOnClickListener {
                    roDao.removeItem(item.dbItemId); order?.let { reloadItems(it) }; renderCart()
                }
            }
            container.addView(row)
            ThemeManager.applyTheme(row)
        }
        updateTotals()
    }

    /**
     * Picks the kitchen (KOT) printer from the Operating Printer master (print_flag = 'K'):
     * a default one prints straight away; otherwise the operator chooses a counter.
     */
    private fun resolveKotPrinterThenPrint(order: OrderCard) {
        val kotPrinters = com.example.synergic_pos_offline.database.OperatingPrinterDao(requireContext())
            .getAll().filter { it.printFlag.equals("K", ignoreCase = true) }
        val default = kotPrinters.firstOrNull { it.isDefault }
        when {
            kotPrinters.isEmpty() ->
                toast("No KOT printer set up — add one in Settings ▸ Operating Printer")
            default != null -> doPrintKot(order, default)
            else -> showPrinterChooser(kotPrinters, "Select kitchen counter") { doPrintKot(order, it) }
        }
    }

    /**
     * Picks the bill (BILL) printer from the Operating Printer master (print_flag = 'B'):
     * a default one prints straight away; otherwise the operator chooses one.
     */
    private fun resolveBillPrinterThenPrint(order: OrderCard) {
        val billPrinters = com.example.synergic_pos_offline.database.OperatingPrinterDao(requireContext())
            .getAll().filter { it.printFlag.equals("B", ignoreCase = true) }
        val default = billPrinters.firstOrNull { it.isDefault }
        when {
            billPrinters.isEmpty() ->
                toast("No bill printer set up — add one in Settings ▸ Operating Printer")
            default != null -> doPrintBill(order, default)
            else -> showPrinterChooser(billPrinters, "Select bill printer") { doPrintBill(order, it) }
        }
    }

    /**
     * Builds the grocery-format receipt for an order and prints it on [printer].
     * Uses [BillReceiptRenderer] so the font size and layout are identical to the
     * grocery bill; the section service charge is folded in as a line so it's part
     * of the printed total. [billNumber] labels the slip; [payment] shows the mode.
     */
    private fun printGroceryStyleBill(
        order: OrderCard,
        printer: com.example.synergic_pos_offline.database.OperatingPrinterDao.OperatingPrinter,
        billNumber: String, payment: String, tendered: Double = 0.0
    ) {
        val config = com.example.synergic_pos_offline.utils.ThermalPrinter.configFor(printer)
            ?: run { toast("Bill printer '${printer.printerName}' is not fully configured"); return }
        val draft = buildBillDraft(order, billNumber, payment, tendered)
        val bmp = com.example.synergic_pos_offline.utils.BillReceiptRenderer(requireContext())
            .renderDraftToBitmap(draft, config.paperDots)
            ?: run { toast("Could not render the bill"); return }
        com.example.synergic_pos_offline.utils.ThermalPrinter.print(requireContext(), bmp, config) { result ->
            toast(when (result) {
                is com.example.synergic_pos_offline.utils.ThermalPrinter.Result.Success -> "Bill printed at ${printer.printerName}"
                is com.example.synergic_pos_offline.utils.ThermalPrinter.Result.Sent -> "Bill sent to ${printer.printerName}"
                is com.example.synergic_pos_offline.utils.ThermalPrinter.Result.Failure -> "Bill print failed: ${result.message}"
            })
            bmp.recycle()
        }
    }

    /** Maps an order to a grocery-renderer Draft (per-item GST + a service-charge line). */
    private fun buildBillDraft(
        order: OrderCard, billNumber: String, payment: String, tendered: Double = 0.0
    ): com.example.synergic_pos_offline.utils.BillReceiptRenderer.Draft {
        val b = computeBill(order.items, serviceRateFor(order.section))
        val items = order.items.map {
            com.example.synergic_pos_offline.utils.BillReceiptRenderer.Draft.Item(
                name = it.name, quantity = it.qty, rate = it.rate,
                cgstRate = it.cgstRate, sgstRate = it.sgstRate
            )
        }
        val tableLabel = if (order.type.equals("Take Away", ignoreCase = true))
            "Take Away ${order.id.replace("TA-", "Token #")}" else "Table ${order.id}"
        val now = java.text.SimpleDateFormat("dd-MM-yyyy hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
        return com.example.synergic_pos_offline.utils.BillReceiptRenderer.Draft(
            billNumber = billNumber,
            dateTime = now,
            cashier = order.cashier,
            customer = com.example.synergic_pos_offline.utils.BillReceiptRenderer.Draft.Customer(
                name = tableLabel, phone = order.phone.takeIf { it.isNotBlank() }
            ),
            items = items,
            discount = 0.0, roundOff = 0.0, netAmount = b.total,
            paymentModes = if (payment.isNotBlank()) listOf(payment.uppercase(java.util.Locale.US)) else emptyList(),
            serviceCharge = b.service,   // shown as its own totals line, not an item
            returnAmount = (tendered - b.total).coerceAtLeast(0.0)   // cash to hand back
        )
    }

    /** Prints the (provisional) bill in grocery format, then completes the table. */
    private fun doPrintBill(
        order: OrderCard,
        printer: com.example.synergic_pos_offline.database.OperatingPrinterDao.OperatingPrinter
    ) {
        val nextNo = com.example.synergic_pos_offline.database.BillDao(requireContext()).nextBillNumber()
        // This is the provisional table bill, printed before payment - so it carries no
        // payment mode. The mode is only known and printed on the paid receipt, after
        // Checkout -> Confirm (see settlePaidOrder -> printGroceryStyleBill with payMethod).
        printGroceryStyleBill(order, printer, billNumber = nextNo, payment = "")
        completeTable(order)   // billed → locked (stays until paid)
    }

    /**
     * Persists a paid restaurant order as a completed sale across td_bills /
     * td_bill_items / td_payments — the same store the grocery bill writes to, plus
     * the restaurant columns (table_number, order_type, service_charge_amount). Each
     * line carries its OWN CGST/SGST rate so tax is charged per product; the section's
     * service charge is booked as an "other charge" so the net reconciles.
     */
    private fun persistBill(
        order: OrderCard, payMethod: String, tendered: Double = 0.0
    ): com.example.synergic_pos_offline.database.BillDao.Result? {
        val billDao = com.example.synergic_pos_offline.database.BillDao(requireContext())
        val b = computeBill(order.items, serviceRateFor(order.section))

        val billType = when (payMethod.lowercase(java.util.Locale.US)) {
            "card" -> "CARD"; "online" -> "ONLINE"; else -> "CASH"
        }
        // Cash handed over more than the bill → book the change and record what was
        // actually tendered; otherwise the payment settles exactly the total.
        val change = (tendered - b.total).coerceAtLeast(0.0)
        val amountPaid = if (tendered > b.total) tendered else b.total
        val custId = billDao.findCustomerIdByPhone(order.phone.takeIf { it.isNotBlank() })
        val waiterId = roDao.findByTable(order.id)?.waiterId

        val result = runCatching {
            billDao.createBill(
                com.example.synergic_pos_offline.database.BillDao.NewBill(
                    billType = billType,
                    customerId = custId,
                    items = order.items.map {
                        com.example.synergic_pos_offline.database.BillDao.Item(
                            productId = it.productId.takeIf { id -> id > 0 },
                            name = it.name,
                            quantity = it.qty,
                            rate = it.rate,
                            cgstRate = it.cgstRate,
                            sgstRate = it.sgstRate
                        )
                    },
                    payment = com.example.synergic_pos_offline.database.BillDao.Payment(
                        mode = billType, amountPaid = amountPaid, changeAmount = change,
                        custPhone = order.phone.takeIf { it.isNotBlank() }, custId = custId
                    ),
                    totalPrice = b.subtotal,
                    discountAmount = 0.0,
                    discountPercentage = 0.0,
                    cgstAmount = b.cgst,
                    sgstAmount = b.sgst,
                    netAmount = b.total,
                    otherChargesAmount = b.service,   // so net reconciles with stored components
                    waiterId = waiterId,
                    tableNumber = order.id,
                    orderType = order.type,
                    serviceChargeAmount = b.service
                )
            )
        }.getOrNull()

        if (result == null) toast("Warning: bill could not be saved to history")
        return result
    }

    /**
     * Clears (cancels) an active order without payment: deletes the running order and
     * its items, closes its KOT, and frees its table(s). No bill is recorded.
     */
    private fun clearActiveOrder(order: OrderCard) {
        val root = view ?: return
        // Split sub-table: empty it but keep it as an Available part to re-order.
        if (order.id.contains(" ")) {
            roDao.clearItems(order.dbId)
            order.items.clear()
            subTableDao.setStatus(order.id, "Available")
            freeParentIfSplitDone(order.id)              // if every part is now empty, tear the split down
            if (orders.any { it.id == order.id }) {      // still there → kept as an available part
                populateOrders(root, ThemeManager.getThemeColor(requireContext()))
                renderCart()
                toast("Sub-table ${order.id} cleared — available to re-order")
            } else {                                     // whole split collapsed
                populateOrders(root, ThemeManager.getThemeColor(requireContext()))
                clearDetail(root)
                toast("Table cleared")
            }
            return
        }
        val mergedTables = roDao.mergedTablesOf(order.dbId)
        roDao.close(order.dbId)                          // delete order + items, close KOT
        if (!order.type.equals("Take Away", ignoreCase = true))
            tableDao.setStatusByCode(order.id, "Available")   // free the dine-in table
        mergedTables.forEach { tableDao.setStatusByCode(it, "Available") }
        orders.removeAll { it.id == order.id }
        populateOrders(root, ThemeManager.getThemeColor(requireContext()))
        clearDetail(root)
        toast("Order cleared")
    }

    /**
     * Cuts and prints the KOT for a take-away order at payment time (its items never
     * went to the kitchen during the order). Uses the default KOT printer (or the
     * first one) — no chooser, so payment fires both prints back-to-back.
     */
    private fun printTakeAwayKot(order: OrderCard) {
        val batch = roDao.printKot(order.dbId, order.id, null, order.section, order.note) ?: return
        val kotPrinters = com.example.synergic_pos_offline.database.OperatingPrinterDao(requireContext())
            .getAll().filter { it.printFlag.equals("K", ignoreCase = true) }
        val printer = kotPrinters.firstOrNull { it.isDefault } ?: kotPrinters.firstOrNull()
        if (printer == null) { toast("Paid — no KOT printer set up to send the kitchen ticket"); return }
        com.example.synergic_pos_offline.utils.KotPrinter.print(requireContext(), batch, printer) { msg -> toast(msg) }
    }

    /**
     * Payment confirmed (Bill & Pay ▸ Confirm): settle the order — save the bill,
     * close & remove it, then print the paid receipt. For TAKE AWAY only, the KOT is
     * also cut and printed here, so Confirm fires two prints (KOT + paid bill).
     * Dine-in prints only the receipt (its KOT was sent during the order).
     */
    private fun settlePaidOrder(order: OrderCard, payMethod: String, tendered: Double = 0.0) {
        // Take Away sends no KOT during the order — cut & print it now, before the
        // order is closed, so payment prints both the KOT and the paid bill together.
        if (order.type.equals("Take Away", ignoreCase = true)) printTakeAwayKot(order)
        val saved = persistBill(order, payMethod, tendered)  // save to td_bills / td_bill_items / td_payments
        val mergedTables = roDao.mergedTablesOf(order.dbId)
        roDao.close(order.dbId)                          // payment done → remove from temp table
        tableDao.setStatusByCode(order.id, "Available")  // table freed for the next guest
        if (order.id.contains(" ")) subTableDao.setStatus(order.id, "Available")  // split part settled → freed
        mergedTables.forEach { tableDao.setStatusByCode(it, "Available") }   // merged tables freed too
        orders.removeAll { it.id == order.id }
        freeParentIfSplitDone(order.id)                  // free the parent once all parts are done
        view?.let { root ->
            populateOrders(root, ThemeManager.getThemeColor(requireContext()))
            clearDetail(root)
        }
        // Then the receipt: resolve the BILL printer and print like the grocery flow.
        val billNo = saved?.billNumber
            ?: com.example.synergic_pos_offline.database.BillDao(requireContext()).lastBillNumber().orEmpty()
        // Prints the paid receipt, re-resolving the bill printer each time so the
        // Reprint button on the completion popup works exactly the same way.
        val printPaidReceipt: () -> Unit = {
            val billPrinters = com.example.synergic_pos_offline.database.OperatingPrinterDao(requireContext())
                .getAll().filter { it.printFlag.equals("B", ignoreCase = true) }
            val default = billPrinters.firstOrNull { it.isDefault }
            when {
                billPrinters.isEmpty() ->
                    toast("Paid — no bill printer set up to print the receipt")
                default != null -> printGroceryStyleBill(order, default, billNo, payMethod, tendered)
                else -> showPrinterChooser(billPrinters, "Select bill printer") {
                    printGroceryStyleBill(order, it, billNo, payMethod, tendered)
                }
            }
        }
        printPaidReceipt()
        // Like the grocery checkout: a completion popup offering Reprint / Start New Sale.
        showPaidCompletionDialog(billNo, printPaidReceipt)
    }

    /**
     * Post-payment popup mirroring the grocery checkout: confirms the sale with its
     * bill number and offers Reprint or Start New Sale. Start New Sale just closes it —
     * the table is already settled and the Orders screen is ready for the next order.
     */
    private fun showPaidCompletionDialog(billNo: String, reprint: () -> Unit) {
        // Built as a custom dialog (not showConfirm) on purpose: showConfirm routes both
        // the negative button AND a back-press/escape through onCancel, which made
        // dismissing the popup fire the reprint. Here only the explicit Reprint button
        // reprints; Start New Sale or dismissing (back / escape) just closes it.
        val (dialog, view) = com.example.synergic_pos_offline.utils.DialogUtils.buildCustom(
            requireContext(), R.layout.dialog_common
        )
        val accent = ThemeManager.getThemeColor(requireContext())
        view.findViewById<android.widget.ImageView>(R.id.ivDialogIcon).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = android.content.res.ColorStateList.valueOf(accent)
            visibility = View.VISIBLE
        }
        view.findViewById<android.widget.TextView>(R.id.tvDialogTitle).text = "Payment complete"
        view.findViewById<android.widget.TextView>(R.id.tvDialogMessage).text =
            if (billNo.isNotBlank()) "Bill No: $billNo" else "The bill has been settled."
        val btnStartNew = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogPositive)
        val btnReprint = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogNegative)
        btnStartNew.text = "Start New Sale"
        btnReprint.text = "Reprint"
        ThemeManager.styleDialogButtons(btnStartNew, btnReprint, accent)
        btnStartNew.setOnClickListener { dialog.dismiss() }
        btnReprint.setOnClickListener { dialog.dismiss(); reprint() }
        dialog.show()
    }

    /**
     * Bill & Print result: mark the table COMPLETED (billed). It stays in the
     * temporary running table (so it can still be paid), but is locked — no more
     * items / qty / KOT changes. It's only removed after payment (Bill & Pay).
     */
    private fun completeTable(order: OrderCard) {
        val mergedBefore = roDao.mergedTablesOf(order.dbId)
        roDao.markCompleted(order.dbId)
        tableDao.setStatusByCode(order.id, "Billing")   // billed → awaiting payment
        mergedBefore.forEach { tableDao.setStatusByCode(it, "Billing") }   // merged tables too
        order.status = "COMPLETED"
        val root = view ?: return
        populateOrders(root, ThemeManager.getThemeColor(requireContext()))
        renderCart()   // re-render locked (steppers hidden)
        toast("Table ${order.id} billed — locked. Use Bill & Pay to settle.")
    }

    /** A picker of printers when no default is set for the flag (KOT counters / bill printers). */
    private fun showPrinterChooser(
        printers: List<com.example.synergic_pos_offline.database.OperatingPrinterDao.OperatingPrinter>,
        title: String,
        onPick: (com.example.synergic_pos_offline.database.OperatingPrinterDao.OperatingPrinter) -> Unit
    ) {
        val labels = printers.map { p ->
            val extra = p.printerLabel.ifBlank { "" }
            if (extra.isBlank()) p.printerName else "${p.printerName}  ($extra)"
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setItems(labels) { _, which -> onPick(printers[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Cuts the KOT batch, refreshes the cart, and shows the ticket for [printer]. */
    private fun doPrintKot(
        order: OrderCard,
        printer: com.example.synergic_pos_offline.database.OperatingPrinterDao.OperatingPrinter
    ) {
        val note = view?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etOrderNote)
            ?.text?.toString()?.trim().orEmpty()
        // Cut the KOT (marks items sent) and send it straight to the printer.
        val batch = roDao.printKot(order.dbId, order.id, null, order.section, note) ?: run {
            toast("No new items to send to kitchen"); return
        }
        reloadItems(order)
        renderCart()
        com.example.synergic_pos_offline.utils.KotPrinter.print(requireContext(), batch, printer) { msg -> toast(msg) }
    }

    private fun updateTotals() {
        val root = view ?: return
        val order = currentOrder()
        val b = computeBill(order?.items ?: emptyList(), serviceRateFor(order?.section.orEmpty()))
        root.findViewById<TextView>(R.id.tvSubtotal).text = "₹ ${money(b.subtotal)}"
        root.findViewById<TextView>(R.id.tvService).text = "₹ ${money(b.service)}"
        root.findViewById<TextView>(R.id.tvCgst).text = "₹ ${money(b.cgst)}"
        root.findViewById<TextView>(R.id.tvSgst).text = "₹ ${money(b.sgst)}"
        root.findViewById<TextView>(R.id.tvOrderTotal).text = "₹ ${money(b.total)}"
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBillPay).text =
            "Checkout  ( ₹ ${money(b.total)} )"
        // Reflect the running total on the active order card.
        order?.let { it.amount = "₹ ${money(b.total)}" }
        populateOrders(root, ThemeManager.getThemeColor(requireContext()))
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun money(v: Double): String =
        String.format(java.util.Locale.US, "%,.2f", v)

    /** Whole quantities show without decimals; fractional ones keep up to 3 places. */
    private fun qtyText(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
}
