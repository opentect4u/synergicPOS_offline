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

    /**
     * Whether the note + tax breakdown are pulled up. Folded away to start with, so a
     * long order scrolls in the whole panel rather than a letterbox above the totals -
     * the figures those rows carry are all in the Total and on Checkout anyway.
     */
    private var summaryExpanded = false

    /** Whether Transfer/Merge/Split apply to the selected order (dine-in only). */
    private var dineInActionsEnabled = true

    /**
     * App Settings' Direct Add to Cart, read at [onResume]. Reading it per tap meant
     * re-parsing the settings JSON in the middle of the one interaction that has to
     * feel instant.
     */
    private var directAddToCart = false

    private fun currentOrder(): OrderCard? = orders.firstOrNull { it.selected }
    private fun currentCart(): MutableList<CartItem>? = currentOrder()?.items

    /**
     * Whether [order] is the order on THIS table. Table codes restart at 1 in every
     * section, so a code on its own names one table per room - matching an order by
     * code alone picks up the AC room's table 1 while the operator is looking at the
     * non-AC room's. Section is part of a table's name everywhere it is compared.
     *
     * A blank [section] matches on code alone, for a store with no sections set up.
     */
    private fun sameTable(order: OrderCard, code: String, section: String): Boolean =
        order.id.equals(code, ignoreCase = true) &&
            (section.isBlank() || order.section.equals(section, ignoreCase = true))

    /** The active order on one table, or null when it is free. */
    private fun orderFor(code: String, section: String): OrderCard? =
        orders.firstOrNull { sameTable(it, code, section) }

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

    /**
     * Flat service-charge amount (₹) for a section, from the Section master.
     *
     * Held per section for as long as the screen is open: the totals are recomputed on
     * every tap of the menu, and a rate that changes about once a year does not need a
     * query behind each one.
     */
    private val serviceRates = mutableMapOf<String, Double>()
    private fun serviceRateFor(sectionName: String): Double =
        serviceRates.getOrPut(sectionName) { sectionDao.serviceChargeForName(sectionName) }

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
        val segOrderType =
            view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.segOrderType)
        // Choose Table is a dine-in action: a take-away order has no table to pick, so
        // the button stays disabled for as long as the segment sits on Take Away. Hung
        // off the segment rather than off the order, so it holds however the type was
        // set - the Take Away button, a take-away order selected from the list, or a
        // restored one.
        segOrderType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) setChooseTableEnabled(view, checkedId != R.id.btnTakeAway)
        }
        segOrderType.check(R.id.btnDineIn)

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

        // Choose Table → table-picker grid (sections as tabs, tables as cards).
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnChooseTable).setOnClickListener {
            showChooseTableDialog()
        }
        setChooseTableEnabled(view, segOrderType.checkedButtonId != R.id.btnTakeAway)

        // More → the whole order in a roomy popup, since this panel is narrow.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMoreItems)
            .setOnClickListener { showOrderItemsDialog() }

        // The note + tax rows fold away and pull back up.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnToggleSummary)
            .setOnClickListener { setSummaryExpanded(view, !summaryExpanded) }
        setSummaryExpanded(view, summaryExpanded, animate = false)

        // The active-order list slides in over the menu and back off it.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnToggleOrders)
            .setOnClickListener { setOrdersPanelOpen(!ordersPanelOpen) }
        view.findViewById<View>(R.id.vOrdersScrim).setOnClickListener { setOrdersPanelOpen(false) }
        // Starts closed - the menu covers the page until the list is asked for. Posted
        // so the panel has been measured and its own width is what it slides by.
        view.findViewById<View>(R.id.panelOrders).post {
            if (isAdded) setOrdersPanelOpen(ordersPanelOpen, animate = false)
        }

        // The menu sits on the page itself - see setupProductSection - so there is no
        // Add Item button to open a grid in a popup.
        setupProductSection(view)

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

        // Table Actions → Transfer, Merge, Split and Cancel Order, behind one control.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTableActions)
            .setOnClickListener { showTableActionsMenu(it) }

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
            // The running order's own id, not its table code: two sections can both
            // have a table 1, and settling one must not settle the other's bill.
            val paidId = bundle.getLong(RestaurantCheckoutFragment.ARG_ORDER_ID, -1L)
            // Resolve the order first — settlePaidOrder removes it from the list, so a
            // second lookup afterwards would find nothing and the bill would never save
            // or print.
            val order = orders.firstOrNull { it.dbId == paidId } ?: return@setFragmentResultListener
            // What was served has left the shelf. Done here rather than at bill save
            // because Restaurant checkout does not write a bill - settling the order is
            // the only moment the sale is known to be complete.
            if (stockTrackingOn) {
                stockDao.recordSale(
                    reference = tableLabel(order),
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
                                order.dbId, order.id, order.section,
                                order.phone.ifBlank { "Walk-in" }, names, qtys, rates,
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

        // Opening the sale screen asks which table first: that is the decision every
        // restaurant order starts with, so the picker comes up rather than waiting to
        // be asked for. Only on a fresh open - a rotation keeps whatever was on screen.
        if (savedInstanceState == null) {
            view.post { if (isAdded) showChooseTableDialog() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-read once here rather than on every tap: the only way this changes is a
        // trip to App Settings, which comes back through onResume.
        directAddToCart = com.example.synergic_pos_offline.utils.SettingsCache
            .value(requireContext(), "A", "Direct Add to Cart") == "1"
        view?.let { v -> v.post { restyle(v, ThemeManager.getThemeColor(requireContext())) } }
        // The menu is on the page now, so it has to be current whenever the page is:
        // a product edited, or stock moved by a settled bill, shows on the way back.
        reloadProductsAndRefresh()
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

        // Active-orders count badge, and the same count on the button that slides the
        // list in - with the list closed that button is the only place it shows.
        root.findViewById<TextView>(R.id.tabActive).setTextColor(accent)
        root.findViewById<TextView>(R.id.badgeActive).apply {
            text = orders.size.toString(); backgroundTintList = ColorStateList.valueOf(accent)
        }
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnToggleOrders)?.text =
            if (orders.isEmpty()) "Active Orders" else "Active Orders (${orders.size})"

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
            card.tag = o.dbId          // so its total can be patched without a rebuild
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
        // The table has been chosen, so the list slides back off and hands the page
        // to the menu - which is what the operator wants next.
        setOrdersPanelOpen(false)
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

    /**
     * Folds the note and the tax breakdown away, or pulls them back up to the full
     * height the panel used to have. The items list above is the view that flexes, so
     * whatever this releases goes straight to it.
     */
    private fun setSummaryExpanded(root: View, expanded: Boolean, animate: Boolean = true) {
        summaryExpanded = expanded
        val detail = root.findViewById<LinearLayout>(R.id.llSummaryDetail)
        if (animate) {
            // Lays the change out in one pass with the items list growing into it,
            // rather than the panel jumping.
            android.transition.TransitionManager.beginDelayedTransition(
                detail.parent as ViewGroup,
                android.transition.AutoTransition().apply { duration = 160 }
            )
        }
        detail.visibility = if (expanded) View.VISIBLE else View.GONE
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnToggleSummary).apply {
            text = if (expanded) "Hide note & tax details" else "Note & tax details"
            setIconResource(if (expanded) R.drawable.ic_expand_more else R.drawable.ic_expand_less)
        }
    }

    /**
     * The table's occasional actions, on one menu rather than four more buttons in a
     * row that already carries the order's own. Each item is greyed the same way its
     * button was, so what cannot be done still shows itself rather than disappearing.
     */
    private fun showTableActionsMenu(anchor: View) {
        val menu = android.widget.PopupMenu(requireContext(), anchor)
        menu.menu.add(0, MENU_TRANSFER, 0, "Transfer").isEnabled = dineInActionsEnabled
        menu.menu.add(0, MENU_MERGE, 1, "Merge").isEnabled = dineInActionsEnabled
        menu.menu.add(0, MENU_SPLIT, 2, "Split").isEnabled = dineInActionsEnabled
        // Lettered in red: it throws the order away, and it is the one item on here
        // that cannot be undone.
        menu.menu.add(0, MENU_CANCEL, 3, android.text.SpannableString("Cancel Order").apply {
            setSpan(
                android.text.style.ForegroundColorSpan(0xFFD93025.toInt()),
                0, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        })
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_TRANSFER -> onTransfer()
                MENU_MERGE -> showMergeDialog()
                MENU_SPLIT -> onSplit()
                MENU_CANCEL -> onCancelOrder()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        menu.show()
    }

    /** Transfer: move this order to another available table in the same section. */
    private fun onTransfer() {
        val order = currentOrder() ?: return toast("Select a table order first")
        if (order.type.equals("Take Away", ignoreCase = true))
            return toast("Not available for Take Away")
        if (order.completed) return toast("Table already billed — cannot transfer")
        showTransferDialog(order)
    }

    /** Split: break the selected table into sub-tables (101 A, 101 B, …). */
    private fun onSplit() {
        val order = currentOrder() ?: return toast("Select a table order first")
        if (order.type.equals("Take Away", ignoreCase = true))
            return toast("Not available for Take Away")
        if (order.completed) return toast("Table already billed — cannot split")
        if (order.id.contains(" ")) return toast("This is already a split sub-table")
        showSplitDialog(order)
    }

    /**
     * Cancel Order: clear the selected active table (removes the order + items). Only
     * allowed before any KOT is sent, or once all sent items are cancelled.
     */
    private fun onCancelOrder() {
        val order = currentOrder() ?: return toast("Select an order first")
        if (roDao.hasSentActiveItems(order.dbId)) {
            return toast("Can't cancel — items already sent to kitchen. Remove them (and Print KOT to cancel) first.")
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

    /** Choose Table, greyed out the same way the other dine-in-only actions are. */
    private fun setChooseTableEnabled(root: View, enabled: Boolean) {
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnChooseTable).apply {
            isEnabled = enabled; alpha = if (enabled) 1f else 0.4f
        }
    }

    /**
     * Enables/disables the dine-in-only actions: Print KOT, which is a button, and
     * Transfer/Merge/Split, which are items on the Table Actions menu and so are held
     * as a flag until that menu is built.
     */
    private fun setDineInActionsEnabled(root: View, enabled: Boolean) {
        dineInActionsEnabled = enabled
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPrintKot).apply {
            isEnabled = enabled; alpha = if (enabled) 1f else 0.4f
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
        // A plain text control: label and chevron only, no pill behind them.
        // ThemeManager fills every MaterialButton it walks, so the fill it puts on
        // has to be taken back off here.
        fun textOnly(id: Int) = root.findViewById<com.google.android.material.button.MaterialButton>(id).apply {
            backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            setTextColor(accent); iconTint = ColorStateList.valueOf(accent); strokeWidth = 0
            rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x1A))
        }
        filled(R.id.btnNewOrder); filled(R.id.btnBillPay)
        textOnly(R.id.btnToggleSummary)
        outlined(R.id.btnRefreshOrders); outlined(R.id.btnPrintKot); outlined(R.id.btnChooseTable)
        outlined(R.id.btnToggleOrders)
        outlined(R.id.btnTableActions); outlined(R.id.btnBillPrint)

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

        // Entering a table code fills in its section and assigned waiter. A code the
        // master holds in more than one section cannot be resolved from the number
        // alone, so the section is left for the operator to say which room they mean -
        // by tapping the field, or when they Save.
        var sectionChoices = emptyList<String>()
        fun applySection(section: String) {
            etSection.setText(section)
            val info = if (section.isBlank()) null
            else TableDao(ctx).lookupByCode(etTable.text?.toString()?.trim().orEmpty(), section)
            etWaiter.setText(info?.waiterName ?: if (info != null) "—" else "")
        }
        fun chooseSection(onPicked: (String) -> Unit) {
            AlertDialog.Builder(ctx)
                .setTitle("Which section?")
                .setItems(sectionChoices.toTypedArray()) { _, which ->
                    applySection(sectionChoices[which]); onPicked(sectionChoices[which])
                }
                .show()
        }
        etSection.setOnClickListener { if (sectionChoices.size > 1) chooseSection {} }
        etTable.addTextChangedListener {
            val code = it?.toString()?.trim().orEmpty()
            sectionChoices = if (code.isEmpty()) emptyList() else TableDao(ctx).sectionsForCode(code)
            // One section - filled in for them; several - blank until one is picked.
            applySection(sectionChoices.singleOrNull().orEmpty())
        }

        ThemeManager.applyTheme(v)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent)
        btnSave.setTextColor(Color.WHITE)
        btnCancel.setTextColor(accent)
        btnCancel.strokeColor = ColorStateList.valueOf(accent)

        btnCancel.setOnClickListener { dialog.dismiss() }

        // Save, once the room is known. Split out so an ambiguous code can ask which
        // section first and come back here with the answer.
        fun save(table: String, section: String) {
            val phone = etPhone.text?.toString()?.trim().orEmpty()
            // Don't open a second order for a table that already has an active one.
            if (orderFor(table, section) != null) {
                etTable.error = "Table $table in $section already has an active order"
                return
            }
            // Nor for a table that isn't free (occupied/billing/merged into another order).
            val status = TableDao(ctx).statusOf(table, section)
            if (status != null && !status.equals("Available", ignoreCase = true)) {
                etTable.error = "Table $table in $section is $status"
                return
            }
            dialog.dismiss()
            openNewOrder(table, section, phone, type = "Dine In")
            toast("Order created for table $table in $section")
        }

        btnSave.setOnClickListener {
            val table = etTable.text?.toString()?.trim().orEmpty()
            if (table.isEmpty()) { etTable.error = "Enter a table no"; return@setOnClickListener }
            val section = etSection.text?.toString()?.trim().orEmpty()
            when {
                section.isNotEmpty() -> save(table, section)
                // The code names a table in several rooms: ask which, then carry on.
                sectionChoices.size > 1 -> chooseSection { picked -> save(table, picked) }
                else -> etTable.error = "No such table — pick a table that has a section"
            }
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
            updateTableStatus(table, section, "Occupied")   // dine-in table now has a live order

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
        val activeCodes = orders.filter { it.section.equals(order.section, ignoreCase = true) }
            .map { it.id.lowercase() }.toSet()
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
        // Both tables are in the order's own section — a transfer only ever offers
        // same-section targets.
        updateTableStatus(from, order.section, "Available")   // old table freed
        updateTableStatus(to, order.section, "Occupied")      // new table taken
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

        // Queued orders, not queued table codes: two sections can both have a table 1
        // and the dropdown may well offer both, so each entry has to say which order
        // it stands for.
        val added = mutableListOf<OrderCard>()   // first = kept

        // A table reads as "1 (AC)" here, since the number alone no longer identifies
        // it once a second section has the same number.
        fun label(o: OrderCard) = if (o.section.isBlank()) o.id else "${o.id} (${o.section})"

        // Candidates: before any add — all active tables; after — same section as the first.
        fun candidates(): List<OrderCard> {
            val base = if (added.isEmpty()) activeTables
            else activeTables.filter { it.section.equals(added.first().section, ignoreCase = true) }
            return base.filter { cand -> added.none { it.dbId == cand.dbId } }
        }
        fun refreshDropdown() {
            actWith.setAdapter(
                android.widget.ArrayAdapter(
                    ctx, android.R.layout.simple_list_item_1, candidates().map { label(it) }
                )
            )
            actWith.setText("", false)
        }
        fun renderAdded() {
            llTables.removeAllViews()
            tvEmpty.visibility = if (added.isEmpty()) View.VISIBLE else View.GONE
            etSection.setText(if (added.isEmpty()) "" else added.first().section.ifBlank { "—" })
            added.forEachIndexed { index, o ->
                val row = LayoutInflater.from(ctx).inflate(R.layout.item_merge_table, llTables, false)
                row.findViewById<TextView>(R.id.tvMergeTableName).text =
                    if (index == 0) "Table ${label(o)}  (Kept)" else "Table ${label(o)}"
                val count = o.items.size
                row.findViewById<TextView>(R.id.tvMergeTableInfo).text = "$count item${if (count == 1) "" else "s"}"
                row.findViewById<android.widget.ImageView>(R.id.btnRemoveMergeTable).setOnClickListener {
                    added.removeAll { it.dbId == o.dbId }; renderAdded(); refreshDropdown()
                }
                llTables.addView(row)
            }
        }

        actWith.setOnClickListener { actWith.showDropDown() }
        btnAdd.setOnClickListener {
            val pick = actWith.text?.toString()?.trim().orEmpty()
            val picked = candidates().firstOrNull { label(it) == pick }
            when {
                pick.isEmpty() -> actWith.error = "Select a table"
                picked == null -> actWith.error = "Not an active table in this section"
                else -> { added.add(picked); renderAdded(); refreshDropdown() }
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
    private fun performMerge(target: OrderCard, sources: List<OrderCard>) {
        sources.forEach { source ->
            roDao.mergeOrders(target.dbId, source.dbId)   // records the merged table + keeps it Occupied
            orders.removeAll { it.dbId == source.dbId }   // its own order card is gone (shares the kept bill)
        }
        reloadItems(target)                                       // pull the combined items
        orders.forEach { it.selected = it.dbId == target.dbId }   // focus the kept table
        val root = view ?: return
        populateOrders(root, ThemeManager.getThemeColor(requireContext()))
        showOrderDetail(target)
        renderCart()                                           // combined items + totals
        toast("${sources.size} table${if (sources.size == 1) "" else "s"} merged into ${target.id}")
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
        val section = order.section
        val waiterId = roDao.findByTable(parent, section)?.waiterId
        val cashier = com.example.synergic_pos_offline.utils.SessionManager.currentUser?.userId ?: "—"

        // Part A keeps the existing order (and its items) — occupied.
        val firstCode = subTableDao.create(parent, section, "A", status = "Occupied")
        roDao.transferTable(order.dbId, firstCode)
        // Parts B, C, D start empty → Available until items are added.
        for (i in 1 until count) {
            val code = subTableDao.create(parent, section, ('A' + i).toString(), status = "Available")
            roDao.createOrder(code, section, waiterId, order.type, order.phone, cashier)
        }
        updateTableStatus(parent, section, "Occupied")   // parent stays occupied by its parts

        loadRunningOrders()
        val root = view ?: return
        populateOrders(root, ThemeManager.getThemeColor(requireContext()))
        orderFor(firstCode, section)?.let { selectOrder(it) } ?: clearDetail(root)
        toast("Table $parent split into $count")
    }

    /**
     * The split is finished once every remaining part is empty (no items) or gone:
     * tear down all its parts, free the parent table and drop its sub-table records.
     * While at least one part still has items, the split stays and empty parts remain
     * as Available slots to re-order.
     */
    private fun freeParentIfSplitDone(code: String, section: String) {
        if (!code.contains(" ")) return
        val parent = code.substringBeforeLast(" ").trim()
        // Only this section's parts: another section's table 1 can be split too, and
        // its parts carry the very same sub-codes.
        val parts = orders.filter {
            it.id.startsWith("$parent ") &&
                (section.isBlank() || it.section.equals(section, ignoreCase = true))
        }
        if (parts.all { it.items.isEmpty() }) {
            val partIds = parts.map { it.dbId }.toSet()
            parts.forEach { roDao.close(it.dbId) }
            orders.removeAll { it.dbId in partIds }
            updateTableStatus(parent, section, "Available")
            subTableDao.clearForParent(parent, section)
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

    // ---- Products: the menu, on the page itself -----------------------------

    /** Redraws the product grid for the current search text and category. */
    private var refreshProducts: (() -> Unit)? = null

    // ---- The slide-over order list ------------------------------------------

    /** Whether the active-order list is currently slid in over the menu. */
    private var ordersPanelOpen = false

    /** How long the list takes to slide in or out. */
    private val SLIDE_MS_VALUE = 220L

    /**
     * Slides the active-order list in over the page, or off it.
     *
     * Closed is the resting state: the list sits one panel-width to the left of the
     * screen and the menu has the whole page. Open brings it back to 0 with a dimmed
     * backdrop behind it. [animate] is false for the first pass, where there is nothing
     * to animate from.
     */
    private fun setOrdersPanelOpen(open: Boolean, animate: Boolean = true) {
        val root = view ?: return
        val panel = root.findViewById<View>(R.id.panelOrders) ?: return
        val scrim = root.findViewById<View>(R.id.vOrdersScrim) ?: return
        ordersPanelOpen = open

        // Before the first layout the panel has no width yet; its declared 320dp is
        // the same distance, so it stands in rather than leaving the panel on screen.
        val width = (if (panel.width > 0) panel.width else dp(320)).toFloat()
        val target = if (open) 0f else -width

        if (open) {
            scrim.alpha = 0f
            scrim.visibility = View.VISIBLE
        }
        if (animate) {
            panel.animate().translationX(target).setDuration(SLIDE_MS_VALUE)
                .withEndAction { if (!open) scrim.visibility = View.GONE }
                .start()
            scrim.animate().alpha(if (open) 1f else 0f).setDuration(SLIDE_MS_VALUE).start()
        } else {
            panel.translationX = target
            scrim.alpha = if (open) 1f else 0f
            scrim.visibility = if (open) View.VISIBLE else View.GONE
        }
    }

    /**
     * The menu section that sits beside the order: a search box, the categories as
     * tabs, and the products as a grid. Tapping a tile puts that item on the selected
     * table's order - the same add the Add Item popup used to do, without the popup.
     *
     * The catalogue is read once here rather than per tap; [reloadProductsAndRefresh]
     * re-reads it when something that changes it (a settled bill moving stock) happens.
     */
    private fun setupProductSection(root: View) {
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)

        val etSearch = root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etProductSearch)
        val llCats = root.findViewById<LinearLayout>(R.id.llProductCategories)
        val rv = root.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvProductGrid)

        var selectedCat = "All"
        var query = ""

        val adapter = ProductAdapter(accent) { picked -> onProductPicked(picked) { etSearch.setText("") } }
        // Seven to a row, the same as the grocery sale screen's shelf, so the menu shows
        // as much of itself as it can at once and the same card comes out the same size
        // in both trades.
        rv.layoutManager = androidx.recyclerview.widget.GridLayoutManager(ctx, 7)
        rv.adapter = adapter

        // Category tabs, rebuilt whenever the catalogue is re-read so a newly added
        // category cannot be missing from the tabs until the screen is reopened.
        fun rebuildTabs() {
            val catNames = listOf("All") +
                allProducts.map { it.product.category }.filter { it.isNotBlank() }.distinct()
            if (catNames.none { it == selectedCat }) selectedCat = "All"
            llCats.removeAllViews()
            val tabViews = linkedMapOf<String, TextView>()
            catNames.forEach { c ->
                val tv = TextView(ctx).apply {
                    text = c
                    textSize = 15f
                    setPadding(dp(10), dp(10), dp(10), dp(12))
                    setOnClickListener {
                        selectedCat = c
                        styleCats(tabViews, selectedCat, accent)
                        refreshProducts?.invoke()
                    }
                }
                tabViews[c] = tv
                llCats.addView(tv)
            }
            styleCats(tabViews, selectedCat, accent)
        }

        refreshProducts = {
            val q = query.trim().lowercase()
            adapter.submit(allProducts.filter {
                (selectedCat == "All" || it.product.category == selectedCat) &&
                    (q.isEmpty() || it.product.name.lowercase().contains(q) ||
                        it.product.sku.contains(q) || it.barcode.contains(q))
            })
        }

        etSearch.addTextChangedListener { query = it?.toString().orEmpty(); refreshProducts?.invoke() }

        loadProductsFromDb()   // fills allProducts
        rebuildTabs()
        refreshProducts?.invoke()
    }

    /**
     * A product tapped on the grid. Refuses politely when there is no order to put it
     * on, or the table is already billed; otherwise honours App Settings' Direct Add to
     * Cart - straight in at its default rate, or through the quantity popup.
     */
    private fun onProductPicked(picked: ProductEntryDialog.Product, onAdded: () -> Unit) {
        val order = currentOrder()
        when {
            order == null -> { toast("Create or select a table order first"); return }
            order.completed -> { toast("Table already billed — cannot add items"); return }
        }
        if (directAddToCart) {
            val before = currentOrder()?.items?.sumOf { it.qty } ?: 0.0
            addToCart(picked, 1.0, picked.price)
            val after = currentOrder()?.items?.sumOf { it.qty } ?: 0.0
            if (after > before) toast(itemsAddedMessage(after))
            onAdded()
        } else {
            showProductEntry(picked) { onAdded() }
        }
    }

    /** Re-reads the catalogue (stock levels move as orders settle) and redraws the grid. */
    private fun reloadProductsAndRefresh() {
        if (view == null) return
        loadProductsFromDb()
        refreshProducts?.invoke()
    }

    // ---- Choose Table: table-grid modal ------------------------------------

    private data class TableTile(
        val code: String,
        val section: String,
        val status: String,
        val capacity: Int = 0,
        val waiter: String = ""
    )

    /**
     * The table picker: the same grid modal as Add Item, but tables as cards and their
     * sections as the category tabs. Tapping a table selects its order if it already has
     * one, or starts a new dine-in order on it when it is free.
     */
    private fun showChooseTableDialog() {
        val ctx = com.example.synergic_pos_offline.utils.FixedFontScale.wrap(requireContext())
        val accent = ThemeManager.getThemeColor(ctx)
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_choose_table, null)
        val dialog = AlertDialog.Builder(ctx).setView(v).create()
        // Full screen, always: the floor plan is the whole screen for as long as it is
        // open, whatever the device's size and however few tables are on it.
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(android.view.Gravity.CENTER)
        }

        val etSearch = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etTableSearch)
        val llCats = v.findViewById<LinearLayout>(R.id.llTableSections)
        val rv = v.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvTables)
        val emptyNote = v.findViewById<TextView>(R.id.tvNoTables)

        val tables = loadTables()
        val catNames = listOf("All") + loadSectionNames()
        var selectedCat = "All"
        var query = ""

        val adapter = TableAdapter(accent) { t ->
            dialog.dismiss()
            val existing = orderFor(t.code, t.section)
            // Named with its room in every message: the number on its own belongs to
            // one table per section.
            val named = if (t.section.isBlank()) t.code else "${t.code} (${t.section})"
            if (existing != null) {
                selectOrder(existing)
                toast("Table $named selected")
            } else {
                // Only a free table can start a new order; an occupied/billing one is
                // busy on an order this table picker cannot reach.
                val status = TableDao(requireContext()).statusOf(t.code, t.section)
                if (!status.isNullOrBlank() && !status.equals("Available", ignoreCase = true)) {
                    toast("Table $named is $status")
                } else {
                    openNewOrder(t.code, t.section, "", "Dine In")
                    toast("Order created for table $named")
                }
            }
        }
        // Eight across: a floor is read as a plan, and eight to a row fits a whole
        // section on screen without scrolling - which is what makes it look like the
        // room rather than a list of tables.
        rv.layoutManager = androidx.recyclerview.widget.GridLayoutManager(ctx, 8)
        rv.adapter = adapter

        val counts = v.findViewById<LinearLayout>(R.id.llTableCounts)

        fun refresh() {
            val q = query.trim().lowercase()
            val shown = tables.filter {
                (selectedCat == "All" || it.section == selectedCat) &&
                    (q.isEmpty() || it.code.lowercase().contains(q) || it.section.lowercase().contains(q))
            }
            adapter.submit(shown, showSection = selectedCat == "All")
            emptyNote.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            rv.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
            fillTableCounts(counts, shown)
        }

        // Sections read as chips across the top - the categories of this grid.
        val tabViews = linkedMapOf<String, TextView>()
        catNames.distinct().forEach { c ->
            val tv = TextView(ctx).apply {
                text = c
                textSize = 16f
                setPadding(dp(16), dp(9), dp(16), dp(9))
                setOnClickListener {
                    selectedCat = c
                    styleSectionChips(tabViews, selectedCat, accent)
                    refresh()
                }
            }
            (tv.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(-2, -2)).let {
                it.marginEnd = dp(8); tv.layoutParams = it
            }
            tabViews[c] = tv
            llCats.addView(tv)
        }
        styleSectionChips(tabViews, selectedCat, accent)

        etSearch.addTextChangedListener { query = it?.toString().orEmpty(); refresh() }
        v.findViewById<ImageButton>(R.id.btnCloseChooseTable).setOnClickListener { dialog.dismiss() }

        ThemeManager.applyTheme(v)
        // After the theme pass, so it cannot repaint the chips out from under us.
        styleSectionChips(tabViews, selectedCat, accent)
        refresh()
        dialog.show()
        // Re-applied after show, which is where the window would otherwise fall back to
        // the theme's own sizing.
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        stretchToWindow(v)
    }

    /**
     * A full-screen window is not enough on its own: AlertDialog drops the root's own
     * height when it takes the view, and nests it in panels that are each only as tall
     * as what they hold - which is why the card stopped halfway down the screen. Walks
     * the chain from the card up to the window's content frame making every step as
     * tall as it can be, so the dialog fills the display however little is on it.
     */
    private fun stretchToWindow(root: View) {
        var view: View? = root
        while (view != null) {
            view.layoutParams?.let { lp ->
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                if (lp is LinearLayout.LayoutParams) lp.weight = 1f
                view!!.layoutParams = lp
            }
            if (view.id == android.R.id.content) return
            view = view.parent as? View
        }
    }

    /**
     * The section chips: the chosen one filled in the accent, the rest outlined.
     *
     * Chips rather than the underlined tabs the product grid uses - a floor is a place
     * and reads as a button you press, and it keeps the two grids visibly different.
     */
    private fun styleSectionChips(tabs: Map<String, TextView>, selected: String, accent: Int) {
        tabs.forEach { (name, tv) ->
            val on = name == selected
            tv.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                if (on) setColor(accent)
                else { setColor(Color.WHITE); setStroke(dp(1), 0xFFDDE1E6.toInt()) }
            }
            tv.setTextColor(if (on) Color.WHITE else 0xFF6B7280.toInt())
            tv.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    /**
     * The colour a table's status is drawn in: the card's border and label, and the
     * tint of its icon. [fill] is the same colour as the card's wash behind it.
     *
     * 'Billing' is the app's name for a table that has been billed and is waiting to
     * pay, which reads as Bill Pending on the floor plan.
     */
    private data class StatusLook(val label: String, val color: Int, val fill: Int)

    /**
     * Where [tableCode]'s order stands with the kitchen, or null when it has no order
     * (or an empty one) - there is nothing to have sent, so the card shows no line.
     *
     * Read from the orders already loaded on this screen rather than the KOT tables:
     * `pending` is the quantity on a line that has not gone to the kitchen yet, which
     * is the same figure Print KOT acts on, so the card and that button agree.
     */
    private fun kotLookOf(tableCode: String, section: String): StatusLook? {
        val order = orderFor(tableCode, section) ?: return null
        if (order.items.isEmpty()) return null
        return if (order.items.any { it.pending > 0.0 })
            StatusLook("KOT Pending", 0xFF7C3AED.toInt(), 0xFFF3E8FF.toInt())
        else StatusLook("KOT Sent", 0xFF0D9488.toInt(), 0xFFE6F6F4.toInt())
    }

    /**
     * The tally across the top of the picker: the total, then one box per status.
     *
     * Counted from the tables actually on screen, so picking a section re-counts for
     * that section - the strip answers "is anything free *here*", which is the question
     * being asked while looking at one room. A status nobody is in is left out rather
     * than shown as a zero, so the strip stays short enough to read.
     */
    private fun fillTableCounts(strip: LinearLayout, shown: List<TableTile>) {
        strip.removeAllViews()
        if (shown.isEmpty()) { strip.visibility = View.GONE; return }
        strip.visibility = View.VISIBLE

        val boxes = mutableListOf<Triple<String, Int, Int>>()   // label, count, colour
        boxes.add(Triple("Total Tables", shown.size, 0xFF334155.toInt()))
        // In the order the legend lists them, so the two read the same way round.
        listOf("Available", "Occupied", "Reserved", "Bill Pending").forEach { label ->
            val n = shown.count { lookOf(it.status).label == label }
            if (n > 0) boxes.add(Triple(label, n, lookOf(statusFor(label)).color))
        }

        boxes.forEach { (label, count, colour) ->
            val box = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.marginEnd = dp(8) }
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(ColorUtils.setAlphaComponent(colour, 0x14))
                    setStroke(dp(1), ColorUtils.setAlphaComponent(colour, 0x4D))
                }
            }
            box.addView(TextView(requireContext()).apply {
                text = count.toString()
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(colour)
            })
            box.addView(TextView(requireContext()).apply {
                text = label
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(resources.getColor(R.color.text_secondary, null))
            })
            strip.addView(box)
        }
    }

    /** The stored status behind a label the strip shows - the inverse of [lookOf]. */
    private fun statusFor(label: String): String =
        if (label == "Bill Pending") "Billing" else label

    private fun lookOf(status: String): StatusLook = when (status.trim().lowercase()) {
        "", "available" -> StatusLook("Available", 0xFF16A34A.toInt(), 0xFFE7F8EE.toInt())
        "occupied" -> StatusLook("Occupied", 0xFFDC2626.toInt(), 0xFFFDECEC.toInt())
        // Written by older builds when a KOT was sent. The table was in use then and is
        // in use now, so it reads as occupied rather than as anything of its own.
        "kot printed" -> StatusLook("Occupied", 0xFFDC2626.toInt(), 0xFFFDECEC.toInt())
        "reserved" -> StatusLook("Reserved", 0xFFF59E0B.toInt(), 0xFFFEF6E0.toInt())
        "billing" -> StatusLook("Bill Pending", 0xFF2563EB.toInt(), 0xFFE7F0FE.toInt())
        "cleaning" -> StatusLook("Cleaning", 0xFF0891B2.toInt(), 0xFFE6F6FA.toInt())
        "blocked" -> StatusLook("Blocked", 0xFF6B7280.toInt(), 0xFFF1F3F5.toInt())
        // Never fall back to Available: a status this does not know is still not proof
        // the table is free, and showing a taken table as free is the costly mistake.
        else -> StatusLook(status, 0xFF6B7280.toInt(), 0xFFF1F3F5.toInt())
    }

    /** Every active section name for the Choose Table tabs. */
    private fun loadSectionNames(): List<String> {
        val db = com.example.synergic_pos_offline.database.DatabaseHelper.getInstance(requireContext()).readableDatabase
        val store = currentStoreId(db)
        val out = mutableListOf<String>()
        val where = if (store != null) "WHERE store_id = ? AND is_active = 1" else "WHERE is_active = 1"
        val args = store?.let { arrayOf(it.toString()) }
        db.rawQuery("SELECT section_name FROM md_section $where ORDER BY section_name", args).use { c ->
            while (c.moveToNext()) {
                c.getString(0)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        }
        return out
    }

    /** Every table with its section and current status, for the Choose Table grid. */
    private fun loadTables(): List<TableTile> {
        val db = com.example.synergic_pos_offline.database.DatabaseHelper.getInstance(requireContext()).readableDatabase
        val store = currentStoreId(db)
        val out = mutableListOf<TableTile>()
        val where = if (store != null) "WHERE t.store_id = ?" else ""
        val args = store?.let { arrayOf(it.toString()) }
        db.rawQuery(
            "SELECT t.table_code, COALESCE(s.section_name,'') AS section, COALESCE(t.table_status,'Available'), " +
                "COALESCE(t.seating_capacity, 0), COALESCE(w.waiter_name, '') " +
                "FROM ${com.example.synergic_pos_offline.database.DatabaseHelper.Tables.MD_TABLE} t " +
                "LEFT JOIN ${com.example.synergic_pos_offline.database.DatabaseHelper.Tables.MD_SECTION} s ON s.id = t.section_id " +
                "LEFT JOIN ${com.example.synergic_pos_offline.database.DatabaseHelper.Tables.MD_WAITERS} w ON w.id = t.waiter_id " +
                "$where ORDER BY s.section_name COLLATE NOCASE, CAST(t.table_code AS INTEGER), t.table_code",
            args
        ).use { c ->
            while (c.moveToNext()) {
                val code = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                out.add(
                    TableTile(
                        code = code,
                        section = c.getString(1).orEmpty(),
                        status = c.getString(2).orEmpty(),
                        capacity = c.getInt(3),
                        waiter = c.getString(4).orEmpty()
                    )
                )
            }
        }
        return out
    }

    private inner class TableAdapter(
        private val accent: Int,
        private val onPick: (TableTile) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<TableAdapter.VH>() {
        private val items = mutableListOf<TableTile>()
        /** Set while the grid is showing more than one room, so a repeated table
         *  number can still be told apart. */
        private var showSection = false
        fun submit(list: List<TableTile>, showSection: Boolean) {
            items.clear(); items.addAll(list)
            this.showSection = showSection
            notifyDataSetChanged()
        }
        inner class VH(v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_table_tile, parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = items[position]
            // One colour drives the whole card - wash, border, icon and label - so the
            // floor reads by colour alone; the legend under the grid keys them.
            val look = lookOf(t.status)

            (holder.itemView as com.google.android.material.card.MaterialCardView).apply {
                setCardBackgroundColor(look.fill)
                strokeColor = look.color
            }
            // The number alone, set large - the card is already labelled "table" by
            // being on this grid. A coded one (a take-away token, a split part like
            // "5 A") is already a name, so it stands as it is, a size down to fit.
            holder.itemView.findViewById<TextView>(R.id.tvTableCode).apply {
                val numbered = t.code.all { it.isDigit() }
                text = t.code
                textSize = if (numbered) 23f else 17f
                setTextColor(look.color)
            }
            // Which room, and how many seats. The room shows while the grid is on All:
            // table numbers restart in every section, so two cards can both say "1" and
            // the line under the number is what tells them apart.
            holder.itemView.findViewById<TextView>(R.id.tvTableSeats).apply {
                val bits = mutableListOf<String>()
                if (showSection && t.section.isNotBlank()) bits.add(t.section)
                if (t.capacity > 0) bits.add("${t.capacity} seats")
                text = bits.joinToString("  ·  ")
                visibility = if (bits.isEmpty()) View.GONE else View.VISIBLE
            }
            holder.itemView.findViewById<android.widget.ImageView>(R.id.ivTableIcon)
                .imageTintList = ColorStateList.valueOf(look.color)
            holder.itemView.findViewById<TextView>(R.id.tvTableStatus).apply {
                text = look.label
                backgroundTintList = ColorStateList.valueOf(look.color)
            }
            // KOT badge: only for a table that actually has an order to send. Filled
            // like the status pill rather than coloured text - small coloured type on a
            // tinted card washes out, and this is the line a waiter checks at a glance.
            holder.itemView.findViewById<TextView>(R.id.tvTableKot).apply {
                val kot = kotLookOf(t.code, t.section)
                if (kot == null) visibility = View.GONE
                else {
                    text = kot.label
                    backgroundTintList = ColorStateList.valueOf(kot.color)
                    visibility = View.VISIBLE
                }
            }
            holder.itemView.setOnClickListener { onPick(t) }
        }
        override fun getItemCount() = items.size
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

        fun submit(list: List<GridProduct>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        inner class VH(v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v)

        // The grocery sale screen's own tile, so the menu here and the shelf there are
        // one design rather than two that drift apart. What is particular to a
        // restaurant - the veg marker, the spice, the prep time - is laid on the photo,
        // where a grocery tile carries nothing and shows the same card it always did.
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pos_product, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val gp = items[position]
            val p = gp.product
            holder.itemView.findViewById<TextView>(R.id.tvName).text = p.name
            holder.itemView.findViewById<TextView>(R.id.tvPrice).text = "₹ ${money(p.price)}"
            holder.itemView.findViewById<TextView>(R.id.tvSku).text = p.sku

            // Recycled tiles: a dish with no photo has to clear the one before it rather
            // than inherit it - the "no photo" caption behind shows through instead.
            holder.itemView.findViewById<android.widget.ImageView>(R.id.ivProductPhoto).apply {
                val bmp = gp.image?.let {
                    runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
                }
                if (bmp != null) { setImageBitmap(bmp); visibility = View.VISIBLE }
                else { setImageDrawable(null); visibility = View.GONE }
            }

            bindFoodType(holder.itemView.findViewById(R.id.ivFoodType), gp.foodType)
            bindSpice(holder.itemView.findViewById(R.id.llSpice), gp.spice)
            holder.itemView.findViewById<TextView>(R.id.tvPrepBadge).apply {
                val value = gp.prepTime.trim()
                if (value.isEmpty()) visibility = View.GONE
                else {
                    text = if (value.all { it.isDigit() }) "$value min" else value
                    visibility = View.VISIBLE
                }
            }

            com.example.synergic_pos_offline.utils.StockBadge.apply(
                holder.itemView.findViewById(R.id.tvStock), p.stock, p.stockQty
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
        // The badge is a chip on the photo now, so an unspiced dish has to take it off
        // the tile rather than leave an empty white square sitting there.
        container.visibility = if (count > 0) View.VISIBLE else View.GONE
        // Sized for the seven-across tile - three of these and their chip have to sit
        // in a corner of a photo barely 100dp wide.
        val size = dp(9)
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
        val wasEmpty = order.items.isEmpty()
        roDao.addItem(order.dbId, p.id.toLongOrNull() ?: 0L, p.name, qty, rate, p.cgst, p.sgst)
        // A split sub-table with its first item is now Occupied. Only the first: the
        // table cannot become more occupied than it already is, and this was a write
        // to the table master behind every single tap.
        if (wasEmpty && !order.type.equals("Take Away", ignoreCase = true)) {
            updateTableStatus(order.id, order.section, "Occupied")
        }
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
        val accent = ThemeManager.getThemeColor(requireContext())
        val order = currentOrder()
        val locked = order?.completed == true      // billed → read-only
        val cart = order?.items ?: emptyList<CartItem>()

        // Rows are re-bound in place rather than thrown away and inflated again. Every
        // tap on the menu redraws this list, and inflating each line afresh - each one
        // a card, five text views and three buttons, themed - is what put a wait
        // between the tap and the quantity moving. Adding one more of something
        // already on the order now costs a handful of setText calls.
        while (container.childCount > cart.size) {
            container.removeViewAt(container.childCount - 1)
        }
        cart.forEachIndexed { index, item ->
            val row = container.getChildAt(index) ?: newOrderRow(
                inflater, container, R.layout.item_order_line_compact
            ).also { container.addView(it) }
            bindOrderRow(row, item, locked, accent) { renderCart() }
        }
        updateTotals()
    }

    /**
     * One cart line, built the same way wherever it is shown.
     *
     * [layoutRes] is the narrow row for the order panel or the wide one for the More
     * popup - both carry the same ids, so this fills either. [onChanged] is called
     * after a quantity or a removal has been written, for the caller to redraw itself.
     */
    private fun orderRow(
        inflater: LayoutInflater,
        parent: LinearLayout,
        item: CartItem,
        layoutRes: Int,
        locked: Boolean,
        accent: Int,
        onChanged: () -> Unit
    ): View = newOrderRow(inflater, parent, layoutRes)
        .also { bindOrderRow(it, item, locked, accent, onChanged) }

    /**
     * An empty row of [layoutRes], themed once. A row's colours come from the theme
     * and its numbers from the item, so the theme pass belongs here - with the
     * inflate, which happens once per line - and not in the bind, which happens on
     * every tap.
     */
    private fun newOrderRow(inflater: LayoutInflater, parent: LinearLayout, layoutRes: Int): View =
        inflater.inflate(layoutRes, parent, false).also { ThemeManager.applyTheme(it) }

    /** Fills a row (new or reused) with one cart line and wires its steppers. */
    private fun bindOrderRow(
        row: View,
        item: CartItem,
        locked: Boolean,
        accent: Int,
        onChanged: () -> Unit
    ) {
        val order = currentOrder()
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
        // Set both ways round, not just the hiding one: this row may have been bound
        // to a billed order a moment ago and is being reused for a live one.
        val editing = if (locked) View.GONE else View.VISIBLE
        btnPlus.visibility = editing
        btnMinus.visibility = editing
        btnRemove.visibility = editing
        if (!locked) {
            btnPlus.setOnClickListener {
                // Only a step up can outrun the shelf; stepping down never can.
                if (exceedsStock(item.productId.toString(), item.qty + 1.0, item.dbItemId)) {
                    return@setOnClickListener
                }
                roDao.setItemQty(item.dbItemId, item.qty + 1.0); order?.let { reloadItems(it) }; onChanged()
            }
            btnMinus.setOnClickListener {
                roDao.setItemQty(item.dbItemId, (item.qty - 1.0).coerceAtLeast(0.0))
                order?.let { reloadItems(it) }; onChanged()
            }
            btnRemove.setOnClickListener {
                roDao.removeItem(item.dbItemId); order?.let { reloadItems(it) }; onChanged()
            }
        }
    }

    /**
     * The whole order in a popup, opened from "More" beside ORDER ITEMS.
     *
     * The panel on the page is narrow by design - the menu takes the width - so this
     * is the same order in a proper table, with the same steppers. Editing here redraws
     * both this and the panel behind it, so the two never disagree.
     */
    private fun showOrderItemsDialog() {
        val order = currentOrder() ?: run { toast("Select a table order first"); return }
        val (dialog, v) = com.example.synergic_pos_offline.utils.DialogUtils
            .buildCustom(requireContext(), R.layout.dialog_order_items)
        val accent = ThemeManager.getThemeColor(requireContext())
        val inflater = LayoutInflater.from(requireContext())
        val container = v.findViewById<LinearLayout>(R.id.llDialogOrderItems)
        val empty = v.findViewById<TextView>(R.id.tvOrderItemsEmpty)

        val label = if (order.type.equals("Take Away", ignoreCase = true))
            "Take Away ${order.id}" else "Table ${order.id}"
        v.findViewById<TextView>(R.id.tvOrderItemsTitle).text = "Order Items — $label"

        fun fill() {
            val current = currentOrder()
            val items = current?.items.orEmpty()
            val locked = current?.completed == true
            container.removeAllViews()
            items.forEach { item ->
                container.addView(
                    orderRow(inflater, container, item, R.layout.item_order_line, locked, accent) {
                        renderCart()   // keep the panel behind in step
                        // Re-fill after the change; the dialog closes if nothing is left
                        // to show, since an empty order has nothing to correct.
                        if (currentOrder()?.items.isNullOrEmpty()) dialog.dismiss() else fillAgain()
                    }
                )
            }
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            v.findViewById<TextView>(R.id.tvOrderItemsCount).text =
                "${items.size} item(s)  ·  ${qtyText(items.sumOf { it.qty })} qty"
            v.findViewById<TextView>(R.id.tvOrderItemsTotal).text =
                "₹ ${money(items.sumOf { it.qty * it.rate })}"
        }
        // Held so a row's callback can call back into the fill above it.
        refillOrderItemsDialog = { fill() }
        fill()

        v.findViewById<ImageButton>(R.id.btnOrderItemsClose).setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { refillOrderItemsDialog = null }
        dialog.show()
    }

    /** Redraws the open More popup, or does nothing when it is closed. */
    private var refillOrderItemsDialog: (() -> Unit)? = null

    private fun fillAgain() { refillOrderItemsDialog?.invoke() }

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

    /**
     * The customer's total outstanding balance (md_customers.balance_amount) matched by
     * [phone], or null when there is no matching customer / nothing owed - for the
     * OUTSTANDING line printed with the totals on the restaurant bill.
     */
    private fun customerOutstanding(phone: String): Double? {
        if (phone.isBlank()) return null
        val db = com.example.synergic_pos_offline.database.DatabaseHelper
            .getInstance(requireContext()).readableDatabase
        return db.query(
            "md_customers", arrayOf("balance_amount"), "phone_number = ?",
            arrayOf(phone), null, null, null, "1"
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0).takeIf { it > 0.005 } else null }
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
                name = tableLabel, phone = order.phone.takeIf { it.isNotBlank() },
                outstanding = customerOutstanding(order.phone)
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
        val waiterId = roDao.findByTable(order.id, order.section)?.waiterId

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
                    tableSection = order.section,
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
            updateTableStatus(order.id, order.section, "Available")
            // if every part is now empty, tear the split down
            freeParentIfSplitDone(order.id, order.section)
            if (orders.any { it.dbId == order.dbId }) {  // still there → kept as an available part
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
            updateTableStatus(order.id, order.section, "Available")   // free the dine-in table
        // Merged tables are same-section by construction, so they free with it.
        mergedTables.forEach { updateTableStatus(it, order.section, "Available") }
        orders.removeAll { it.dbId == order.dbId }
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
        updateTableStatus(order.id, order.section, "Available")  // table freed for the next guest
        // merged tables freed too (same section as the kept one)
        mergedTables.forEach { updateTableStatus(it, order.section, "Available") }
        orders.removeAll { it.dbId == order.dbId }
        // free the parent once all parts are done
        freeParentIfSplitDone(order.id, order.section)
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
        // No completion popup: the order is already settled and the Orders (sale) screen
        // was refreshed above, so just confirm with a toast.
        toast(if (billNo.isNotBlank()) "Bill No: $billNo — payment complete" else "Payment complete")
    }

    /**
     * Bill & Print result: mark the table COMPLETED (billed). It stays in the
     * temporary running table (so it can still be paid), but is locked — no more
     * items / qty / KOT changes. It's only removed after payment (Bill & Pay).
     */
    private fun completeTable(order: OrderCard) {
        val mergedBefore = roDao.mergedTablesOf(order.dbId)
        roDao.markCompleted(order.dbId)
        updateTableStatus(order.id, order.section, "Billing")   // billed → awaiting payment
        // merged tables too (same section as the kept one)
        mergedBefore.forEach { updateTableStatus(it, order.section, "Billing") }
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

        // Sending a KOT does not change what the table IS: it is still occupied, and
        // stays that way until the bill is paid. It used to be set to "KOT Printed",
        // which is not one of the statuses the table master allows
        // ('Available','Occupied','Reserved','Cleaning','Billing','Blocked') - so the
        // value either failed to save or read back as unknown, and an unknown status
        // showed the table as free while guests were sitting at it. Where the order
        // stands with the kitchen is carried by the KOT badge instead, off the items'
        // own pending quantity.
        if (!order.type.equals("Take Away", ignoreCase = true)) {
            updateTableStatus(order.id, order.section, "Occupied")
            roDao.mergedTablesOf(order.dbId)
                .forEach { updateTableStatus(it, order.section, "Occupied") }
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
        // Reflect the running total on the active order card. Only that one figure
        // moves as items go on, so the card is patched where it stands - rebuilding
        // the whole list meant inflating a card per open table on every tap.
        order?.let {
            it.amount = "₹ ${money(b.total)}"
            if (!updateOrderCardAmount(root, it)) {
                populateOrders(root, ThemeManager.getThemeColor(requireContext()))
            }
        }
    }

    /**
     * Sets a table's live status. Sub-tables ("1 A") live in their own master, and a
     * plain table in the table master; both are addressed by code AND section, since
     * the code alone repeats in every section.
     */
    /**
     * Writes [order]'s running total onto its card in the active-orders list, in place.
     * False when the card is not there to patch (the list has not been built, or this
     * order is not on it yet), which is the caller's cue to rebuild the list properly.
     */
    private fun updateOrderCardAmount(root: View, order: OrderCard): Boolean {
        val list = root.findViewById<LinearLayout>(R.id.llOrderList) ?: return false
        for (i in 0 until list.childCount) {
            val card = list.getChildAt(i)
            if (card.tag == order.dbId) {
                card.findViewById<TextView>(R.id.tvOrderAmount).text = order.amount
                return true
            }
        }
        return false
    }

    private fun updateTableStatus(code: String, section: String, status: String) {
        if (code.contains(" ")) subTableDao.setStatus(code, section, status)
        else tableDao.setStatusByCode(code, section, status)
    }

    /**
     * How a table is named outside this screen - on a stock movement, a bill, a
     * receipt. Carries the section, because "Table 1" alone does not say which one
     * of them it was once a second section has a table 1 too.
     */
    private fun tableLabel(order: OrderCard): String = when {
        order.type.equals("Take Away", ignoreCase = true) ->
            order.id.replace("TA-", "Take Away Token #")
        order.section.isBlank() -> "Table ${order.id}"
        else -> "Table ${order.id} (${order.section})"
    }

    private companion object {
        const val MENU_TRANSFER = 1
        const val MENU_MERGE = 2
        const val MENU_SPLIT = 3
        const val MENU_CANCEL = 4
    }

    /**
     * One toast at a time. Tapping five dishes in a row should say what the fifth one
     * did, now - not queue five two-second messages to be read out one after another
     * long after the taps are over.
     */
    private var liveToast: android.widget.Toast? = null
    private fun toast(msg: String) {
        liveToast?.cancel()
        liveToast = android.widget.Toast
            .makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT)
            .also { it.show() }
    }

    private fun money(v: Double): String =
        String.format(java.util.Locale.US, "%,.2f", v)

    /** Whole quantities show without decimals; fractional ones keep up to 3 places. */
    private fun qtyText(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
}
