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
        val productId: Long, val name: String, var qty: Int, var rate: Double,
        var dbItemId: Long = 0, var kotQty: Double = 0.0
    ) {
        /** Quantity not yet sent to the kitchen. */
        val pending: Int get() = (qty - kotQty).toInt().coerceAtLeast(0)
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
    private val tableDao by lazy { com.example.synergic_pos_offline.database.TableDao(requireContext()) }
    private var suppressNoteWatcher = false   // guards programmatic note-field updates

    private fun currentOrder(): OrderCard? = orders.firstOrNull { it.selected }
    private fun currentCart(): MutableList<CartItem>? = currentOrder()?.items

    /** Reloads the running orders (and their items) from the database into [orders]. */
    private fun loadRunningOrders() {
        orders.clear()
        roDao.allRunning().forEach { ro ->
            val card = OrderCard(
                dbId = ro.id, id = ro.tableCode, type = ro.orderType, section = ro.section,
                phone = ro.phone, time = ro.time, amount = "₹ ${money(grandTotal(ro.total))}",
                cashier = ro.cashier, status = ro.status, selected = false, note = ro.note
            )
            // qty 0 lines are removed items awaiting a cancellation KOT — hide from the cart.
            roDao.itemsFor(ro.id).filter { it.qty > 0.0 }.forEach { ri ->
                card.items.add(CartItem(ri.productId, ri.name, ri.qty.toInt(), ri.rate, ri.id, ri.kotQty))
            }
            orders.add(card)
        }
    }

    /** Reloads one order's items from the database (after a DB mutation). */
    private fun reloadItems(order: OrderCard) {
        order.items.clear()
        roDao.itemsFor(order.dbId).filter { it.qty > 0.0 }.forEach { ri ->
            order.items.add(CartItem(ri.productId, ri.name, ri.qty.toInt(), ri.rate, ri.id, ri.kotQty))
        }
    }

    /** Subtotal → grand total (service 5% + CGST 2.5% + SGST 2.5%). */
    private fun grandTotal(subtotal: Double): Double {
        val service = subtotal * 0.05
        val taxable = subtotal + service
        return subtotal + service + taxable * 0.025 + taxable * 0.025
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

        // Cancel Order → clear the selected active table (removes the order + items).
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelOrder).setOnClickListener {
            val order = currentOrder() ?: return@setOnClickListener toast("Select an order first")
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
            val payMethod = bundle.getString(RestaurantCheckoutFragment.ARG_PAY_METHOD).orEmpty()
            val order = orders.firstOrNull { it.id == paidTable } ?: return@setFragmentResultListener
            settlePaidOrder(order, payMethod)
        }

        // Bill & Pay → restaurant checkout with the selected order's items.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBillPay).setOnClickListener {
            val order = orders.firstOrNull { it.selected }
            when {
                order == null -> toast("Select a table order first")
                order.items.isEmpty() -> toast("Add items before billing")
                else -> {
                    val names = ArrayList(order.items.map { it.name })
                    val qtys = order.items.map { it.qty }.toIntArray()
                    val rates = order.items.map { it.rate }.toDoubleArray()
                    requireActivity().supportFragmentManager.beginTransaction()
                        .replace(
                            R.id.fragment_container,
                            RestaurantCheckoutFragment.newInstance(
                                order.id, order.phone.ifBlank { "Walk-in" }, names, qtys, rates
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
                text = if (o.completed) "Completed • Billed" else "In Progress"
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

    // ---- New Order: table + customer modal (dine-in) -----------------------

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
        val ctx = requireContext()
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
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

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
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)

        // Every active DINE-IN table (running, not billed) is a candidate at first.
        // Take Away orders have no table to merge.
        val activeTables = orders.filter { !it.completed && !it.type.equals("Take Away", ignoreCase = true) }
        if (activeTables.size < 2) { toast("Need at least two active dine-in tables to merge"); return }

        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_merge_table, null)
        val dialog = AlertDialog.Builder(ctx).setView(v).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

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

    /** A grid entry: the popup product plus its restaurant attributes (food type + spice). */
    private data class GridProduct(
        val product: ProductEntryDialog.Product, val foodType: String, val spice: String,
        /** The scanned code, kept beside the product now that its SKU is its own id
         *  - both are searched on, so a scan into the search box still finds it. */
        val barcode: String = ""
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
                // The SKU is the product's own id; the barcode is searched on too.
                val sku = id
                val barcode = c.getString(2).orEmpty()
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
                        foodType = foodType, spice = spice, barcode = barcode
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
        val order = currentOrder() ?: run { toast("Select a table order first"); return }
        roDao.addItem(order.dbId, p.id.toLongOrNull() ?: 0L, p.name, qty.toDouble(), rate)
        reloadItems(order)
        renderCart()
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
            row.findViewById<TextView>(R.id.tvLineQty).text = item.qty.toString()
            row.findViewById<TextView>(R.id.tvLineRate).text = money(item.rate)
            row.findViewById<TextView>(R.id.tvLineAmount).text = money(item.qty * item.rate)
            // KOT status: any quantity not yet sent shows NEW ×n (accent); else ✓ Sent.
            row.findViewById<TextView>(R.id.tvLineNote).apply {
                if (item.pending > 0) { text = "NEW ×${item.pending}"; setTextColor(accent) }
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
                    roDao.setItemQty(item.dbItemId, (item.qty + 1).toDouble()); order?.let { reloadItems(it) }; renderCart()
                }
                btnMinus.setOnClickListener {
                    roDao.setItemQty(item.dbItemId, (item.qty - 1).toDouble()); order?.let { reloadItems(it) }; renderCart()
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

    /** Builds a bill ticket from an order's items (service charge + flat GST). */
    private fun buildBillTicket(
        order: OrderCard, payment: String
    ): com.example.synergic_pos_offline.utils.RestaurantBillPrinter.BillTicket {
        val subtotal = order.items.sumOf { it.qty * it.rate }
        val service = subtotal * 0.05
        val taxable = subtotal + service
        val cgst = taxable * 0.025
        val sgst = taxable * 0.025
        val total = subtotal + service + cgst + sgst
        return com.example.synergic_pos_offline.utils.RestaurantBillPrinter.BillTicket(
            table = order.id,
            customer = order.phone.ifBlank { "Walk-in" },
            cashier = order.cashier,
            time = order.time,
            items = order.items.map {
                com.example.synergic_pos_offline.utils.RestaurantBillPrinter.Line(it.name, it.qty, it.rate, it.qty * it.rate)
            },
            subtotal = subtotal, service = service, cgst = cgst, sgst = sgst, total = total,
            note = order.note, payment = payment
        )
    }

    /** Previews the rendered bill, then prints it and completes the table on Print. */
    private fun doPrintBill(
        order: OrderCard,
        printer: com.example.synergic_pos_offline.database.OperatingPrinterDao.OperatingPrinter
    ) {
        val ticket = buildBillTicket(order, payment = "")
        com.example.synergic_pos_offline.utils.RestaurantBillPrinter.print(requireContext(), ticket, printer) { msg -> toast(msg) }
        completeTable(order)   // billed → locked (stays until paid)
    }

    /**
     * Persists a paid restaurant order as a completed sale across td_bills /
     * td_bill_items / td_payments — the same store the grocery bill writes to, plus
     * the restaurant columns (table_number, order_type, service_charge_amount). The
     * service charge is booked as an "other charge" so the net reconciles, and lines
     * carry no per-item GST (the bill uses a flat service + GST model).
     */
    private fun persistBill(order: OrderCard, payMethod: String) {
        val billDao = com.example.synergic_pos_offline.database.BillDao(requireContext())
        val subtotal = order.items.sumOf { it.qty * it.rate }
        val service = subtotal * 0.05
        val taxable = subtotal + service
        val cgst = taxable * 0.025
        val sgst = taxable * 0.025
        val total = subtotal + service + cgst + sgst

        val billType = when (payMethod.lowercase(java.util.Locale.US)) {
            "card" -> "CARD"; "online" -> "ONLINE"; else -> "CASH"
        }
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
                            quantity = it.qty.toDouble(),
                            rate = it.rate
                        )
                    },
                    payment = com.example.synergic_pos_offline.database.BillDao.Payment(
                        mode = billType, amountPaid = total, changeAmount = 0.0,
                        custPhone = order.phone.takeIf { it.isNotBlank() }, custId = custId
                    ),
                    totalPrice = subtotal,
                    discountAmount = 0.0,
                    discountPercentage = 0.0,
                    cgstAmount = cgst,
                    sgstAmount = sgst,
                    netAmount = total,
                    otherChargesAmount = service,   // so net reconciles with stored components
                    waiterId = waiterId,
                    tableNumber = order.id,
                    orderType = order.type,
                    serviceChargeAmount = service
                )
            )
        }.getOrNull()

        if (result == null) toast("Warning: bill could not be saved to history")
    }

    /**
     * Clears (cancels) an active order without payment: deletes the running order and
     * its items, closes its KOT, and frees its table(s). No bill is recorded.
     */
    private fun clearActiveOrder(order: OrderCard) {
        val mergedTables = roDao.mergedTablesOf(order.dbId)
        roDao.close(order.dbId)                          // delete order + items, close KOT
        if (!order.type.equals("Take Away", ignoreCase = true))
            tableDao.setStatusByCode(order.id, "Available")
        mergedTables.forEach { tableDao.setStatusByCode(it, "Available") }
        orders.removeAll { it.id == order.id }
        val root = view ?: return
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
    private fun settlePaidOrder(order: OrderCard, payMethod: String) {
        // Take Away sends no KOT during the order — cut & print it now, before the
        // order is closed, so payment prints both the KOT and the paid bill together.
        if (order.type.equals("Take Away", ignoreCase = true)) printTakeAwayKot(order)
        persistBill(order, payMethod)                   // save to td_bills / td_bill_items / td_payments
        val mergedTables = roDao.mergedTablesOf(order.dbId)
        roDao.close(order.dbId)                          // payment done → remove from temp table
        tableDao.setStatusByCode(order.id, "Available")  // table freed for the next guest
        mergedTables.forEach { tableDao.setStatusByCode(it, "Available") }   // merged tables freed too
        orders.removeAll { it.id == order.id }
        view?.let { root ->
            populateOrders(root, ThemeManager.getThemeColor(requireContext()))
            clearDetail(root)
        }
        // Then the receipt: resolve the BILL printer and preview like the grocery flow.
        val billPrinters = com.example.synergic_pos_offline.database.OperatingPrinterDao(requireContext())
            .getAll().filter { it.printFlag.equals("B", ignoreCase = true) }
        val default = billPrinters.firstOrNull { it.isDefault }
        when {
            billPrinters.isEmpty() ->
                toast("Paid — no bill printer set up to print the receipt")
            default != null -> previewPaidReceipt(order, default, payMethod)
            else -> showPrinterChooser(billPrinters, "Select bill printer") {
                previewPaidReceipt(order, it, payMethod)
            }
        }
    }

    /** Prints the paid receipt directly (table already settled). */
    private fun previewPaidReceipt(
        order: OrderCard,
        printer: com.example.synergic_pos_offline.database.OperatingPrinterDao.OperatingPrinter,
        payMethod: String
    ) {
        val ticket = buildBillTicket(order, payment = payMethod)
        com.example.synergic_pos_offline.utils.RestaurantBillPrinter.print(requireContext(), ticket, printer) { msg -> toast(msg) }
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
            "Checkout  ( ₹ ${money(total)} )"
        // Reflect the running total on the active order card.
        orders.firstOrNull { it.selected }?.let { it.amount = "₹ ${money(total)}" }
        populateOrders(root, ThemeManager.getThemeColor(requireContext()))
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun money(v: Double): String =
        String.format(java.util.Locale.US, "%,.2f", v)
}
