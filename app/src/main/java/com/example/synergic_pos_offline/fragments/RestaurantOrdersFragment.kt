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
import com.example.synergic_pos_offline.utils.AppLanguage
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.CartDensity
import com.example.synergic_pos_offline.utils.GstCalculator
import com.example.synergic_pos_offline.utils.ProductEntryDialog
import com.example.synergic_pos_offline.utils.ProductName
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
        val cgstRate: Double = 0.0, val sgstRate: Double = 0.0,
        /**
         * The product's VAT rate, off the master.
         *
         * A restaurant cart used to carry GST alone, so a VAT-rated dish arrived at
         * the bill rated at nothing and was charged nothing - see BillPricing, which
         * charges whatever rates the line actually has.
         */
        val vatRate: Double = 0.0,
        /**
         * The product's own discount, snapshotted when the line was added - Tax
         * Settings' item-wise discount. Snapshotted for the same reason the rates
         * beside it are: a table sits open across a price change, and the bill has to
         * be the one that was sold.
         */
        val discValue: Double = 0.0,
        /** "A" for a flat amount, otherwise a percentage; null when there is none. */
        val discType: String? = null
    ) {
        /** Quantity not yet sent to the kitchen. */
        val pending: Double get() = (qty - kotQty).coerceAtLeast(0.0)
    }

    private data class OrderCard(
        // phone is a var: a take-away's customer is attached as the order is started,
        // and an empty token that gets reused takes the next customer's number.
        val dbId: Long, var id: String, val type: String, val section: String, var phone: String,
        val time: String, var amount: String, val cashier: String,
        var status: String, var selected: Boolean, var note: String = "",
        /**
         * Tables folded into this bill by a merge. A var because a merge adds to it
         * while the screen is open, and the name shown for the table has to follow.
         */
        var merged: List<String> = emptyList(),
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
     * the figures those rows carry are all in the Total and on Settlement anyway.
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

    /**
     * App Settings' Table Shift / Table Merge / Table Split, read alongside
     * [directAddToCart] at [onResume].
     *
     * A shop that has switched one of these off does not do it at all - one room, one
     * bill per table - so the menu item stays greyed rather than working anyway. Read
     * here for the same reason as above: the only way they change is a trip to App
     * Settings, which comes back through onResume.
     */
    private var tableShiftOn = false
    private var tableMergeOn = false
    private var tableSplitOn = false

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

    /**
     * The order a merged-away table is now billed on, or null when it is not merged.
     *
     * Merge leaves no order against the table itself - the items moved - and records
     * the code on the kept order's merged_tables instead, which is where this looks.
     */
    private fun orderMergedInto(code: String): OrderCard? = orders.firstOrNull { o ->
        runCatching { roDao.mergedTablesOf(o.dbId) }.getOrDefault(emptyList())
            .any { it.equals(code, ignoreCase = true) }
    }

    /**
     * What this order's table is CALLED once tables have been merged into it.
     *
     * A merged bill is not table 1 any more, it is tables 1 and 3 A - and the guests
     * are sitting at both. Naming it after the kept table alone left the other one
     * with nothing to say it was part of anything, so a waiter reading either the
     * panel or the order list could not tell that the two were one bill.
     *
     * The kept table comes first, because that is the one the bill is filed under
     * everywhere else - the KOT, the printed slip, Bill History.
     */
    private fun tableDisplayName(order: OrderCard): String =
        if (order.merged.isEmpty()) order.id
        else (listOf(order.id) + order.merged).joinToString(" + ")

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
                cashier = ro.cashier, status = ro.status, selected = false, note = ro.note,
                merged = ro.mergedTables
            )
            // qty 0 lines are removed items awaiting a cancellation KOT — hide from the cart.
            roDao.itemsFor(ro.id).filter { it.qty > 0.0 }.forEach { ri ->
                card.items.add(CartItem(ri.productId, ri.name, ri.qty, ri.rate, ri.id, ri.kotQty, ri.cgstRate, ri.sgstRate, ri.vatRate, ri.discValue, ri.discType))
            }
            card.amount = "₹ ${money(payableTotal(computeBill(card.items, serviceRateFor(card.section), card.type).total))}"
            orders.add(card)
        }
    }

    /** Reloads one order's items from the database (after a DB mutation). */
    private fun reloadItems(order: OrderCard) {
        order.items.clear()
        roDao.itemsFor(order.dbId).filter { it.qty > 0.0 }.forEach { ri ->
            order.items.add(CartItem(ri.productId, ri.name, ri.qty, ri.rate, ri.id, ri.kotQty, ri.cgstRate, ri.sgstRate, ri.vatRate, ri.discValue, ri.discType))
        }
    }

    private val sectionDao by lazy { com.example.synergic_pos_offline.database.SectionDao(requireContext()) }

    /** A bill breakdown computed from per-product GST plus the section's service charge. */
    private data class BillBreakdown(
        val subtotal: Double, val service: Double, val cgst: Double, val sgst: Double, val total: Double,
        /** VAT, where a line carries a VAT rate - see CartMath. */
        val vat: Double = 0.0,
        /** What tax was charged on, once any pre-tax discount came off. */
        val taxable: Double = 0.0,
        /**
         * The discount taken off this order - the bill-wise figure typed on the cart
         * page, or the sum of the lines' own under item-wise discount. One number
         * whichever way it was arrived at, because that is what the slip prints and
         * what the bill stores.
         */
        val discount: Double = 0.0,
        /** How that discount was entered, for the record written to the bill. */
        val discountIsPercent: Boolean = true,
        val discountPercent: Double = 0.0,
        /**
         * The shop's own extra charges for this order - see the Extra Charges master.
         *
         * Held on the breakdown rather than worked out again wherever it is printed,
         * so the panel, the slip and the saved bill all quote one set of figures.
         */
        val charges: List<com.example.synergic_pos_offline.database.ChargeDao.Applied> = emptyList()
    ) {
        /** What [charges] adds to the order. */
        val chargesTotal: Double get() = charges.sumOf { it.amount }
    }

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
    private fun computeBill(items: List<CartItem>, serviceChargeAmt: Double, orderType: String? = null): BillBreakdown {
        val lines = items.map { it.toMathLine() }
        val subtotal = com.example.synergic_pos_offline.utils.CartMath.subtotal(lines)
        // Flat section service charge, applied only to a non-empty order.
        val service = if (subtotal > 0.0) serviceChargeAmt else 0.0
        // The shop's extra charges, on the ITEM LINES before tax - the same base and
        // the same rule the grocery till uses, so a 5% charge on 300 of food is 15
        // whichever screen rang it up. Worked out on the gross subtotal, not on the
        // service charge, which is itself an addition rather than something sold.
        //
        // Filtered by applicability against this order's TAKEAWAY/DINE_IN type - see
        // ChargeDao.Applicability. A charge set to "None" never comes back here.
        val chargeOrderType = if (orderType?.equals("Take Away", ignoreCase = true) == true) "TAKEAWAY" else "DINE_IN"
        val charges = runCatching {
            com.example.synergic_pos_offline.database.ChargeDao(requireContext()).amountsOn(subtotal, chargeOrderType)
        }.getOrDefault(emptyList())
        // THE WHOLE CALCULATION, in the one place that does it - see CartMath, which
        // is the grocery till's arithmetic restated so both modules bill alike.
        //
        // It replaces a sum that could only ever do one of the eight cases: it priced
        // every line with discountAmount = 0, then added tax unless the till was
        // inclusive. Nothing here knew about a discount at all, so item-wise
        // discounts configured on the products were ignored, a bill-wise discount had
        // nowhere to be entered, and the pre-tax/post-tax choice changed nothing.
        val totals = com.example.synergic_pos_offline.utils.CartMath.totals(
            lines = lines,
            cfg = cartConfig(),
            mode = discountMode,
            value = discountValue,
            service = service,
            charges = charges
        )
        return BillBreakdown(
            subtotal = totals.subtotal,
            service = totals.service,
            cgst = totals.cgst,
            sgst = totals.sgst,
            total = totals.total,
            vat = totals.vat,
            taxable = totals.taxable,
            // Under item-wise the figure shown is what the lines' own discounts came
            // to, which is the only discount there is; under bill-wise it is the one
            // that was typed.
            discount = if (itemwiseDiscountActive) itemwiseDiscountTotal(lines) else totals.discount,
            discountIsPercent = discountMode == com.example.synergic_pos_offline.utils.GstCalculator.DiscountMode.PERCENT,
            discountPercent = com.example.synergic_pos_offline.utils.CartMath
                .discountPercent(lines, cartConfig(), totals.discount),
            charges = charges
        )
    }

    /** This cart line, in the shape the calculation takes it. */
    private fun CartItem.toMathLine() = com.example.synergic_pos_offline.utils.CartMath.Line(
        qty = qty, rate = rate,
        cgstRate = cgstRate, sgstRate = sgstRate, vatRate = vatRate,
        discValue = discValue, discType = discType
    )

    /** Tax Settings as the calculation needs them. */
    private fun cartConfig() = com.example.synergic_pos_offline.utils.CartMath.Config(
        regime = taxRegime,
        inclusive = taxInclusive,
        discountPreTax = discountPreTax,
        itemwiseDiscount = itemwiseDiscountActive,
        billwiseDiscount = billwiseDiscountActive
    )

    /**
     * What the lines' own discounts came to, for the Discount line on the panel and
     * the slip. Read back as the gap between the full taxed line and what it actually
     * sells for, so it reports the same number under all four pre/post x incl/excl
     * rules rather than re-deriving each.
     */
    private fun itemwiseDiscountTotal(lines: List<com.example.synergic_pos_offline.utils.CartMath.Line>): Double {
        val cfg = cartConfig()
        val sub = com.example.synergic_pos_offline.utils.CartMath.subtotal(lines)
        val undiscounted = lines.sumOf { l ->
            val p = com.example.synergic_pos_offline.utils.CartMath.priceLine(
                l.copy(discValue = 0.0, discType = null), cfg, sub, 0.0
            )
            p.itemTotal
        }
        val actual = lines.sumOf {
            com.example.synergic_pos_offline.utils.CartMath.priceLine(it, cfg, sub, 0.0).itemTotal
        }
        return com.example.synergic_pos_offline.utils.BillRounding
            .toPaise((undiscounted - actual).coerceAtLeast(0.0))
    }

    /**
     * Bill Settings' Round Off switch - the one and only say in whether a restaurant
     * bill rounds. On, every payable total is rounded to the whole rupee regardless
     * of what is in the order; off, the exact taxed total stands. Read live rather
     * than cached, same as the grocery till, so a setting changed mid-shift takes
     * effect on the next bill without a restart.
     */
    private fun roundOffOn(): Boolean = runCatching {
        com.example.synergic_pos_offline.database.BillSettingsDao(requireContext()).load().roundOff
    }.getOrDefault(false)

    /** [total] as the customer actually pays - see [roundOffOn]. */
    private fun payableTotal(total: Double): Double =
        if (roundOffOn()) BillRounding.payable(total) else BillRounding.toPaise(total)

    /** The adjustment [payableTotal] applied to reach the rounded figure - 0 when off. */
    private fun roundOffAmount(total: Double): Double =
        if (roundOffOn()) BillRounding.roundOff(total) else 0.0

    // Tax configuration, resolved the same way the grocery billing screen does.
    private val taxSettings by lazy { TaxSettingsDao(requireContext()).load() }
    private val discountPreTax by lazy { taxSettings.discountPosition == TaxSettingsDao.DiscountPosition.PRE_TAX }
    private val itemwiseDiscountActive by lazy {
        taxSettings.discountEnabled && taxSettings.discountType == TaxSettingsDao.DiscountType.ITEM_WISE
    }

    /**
     * Whether the cart takes a whole-bill discount - Tax Settings' Discount on and set
     * to Bill wise. Mutually exclusive with [itemwiseDiscountActive]: a product's own
     * discount and a figure typed against the bill are two answers to one question.
     */
    private val billwiseDiscountActive by lazy {
        taxSettings.discountEnabled && taxSettings.discountType == TaxSettingsDao.DiscountType.BILL_WISE
    }

    /** How the typed discount is read - a percentage of the bill, or a flat amount. */
    private var discountMode = com.example.synergic_pos_offline.utils.GstCalculator.DiscountMode.PERCENT

    /**
     * The figure typed in the discount box.
     *
     * Held on the SCREEN rather than on the order, and cleared as orders are switched:
     * a discount is agreed with the person paying, at the till, at the moment of
     * settling. Carrying it silently onto the next table would apply one guest's
     * arrangement to another's bill.
     */
    private var discountValue = 0.0
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
        watchCartHeight(view)

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
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTakeAway)
            .setOnClickListener { openTakeAway() }
        // Dine In switches the segment and opens the floor plan.
        //
        // The two halves of this control now answer the same way. Take Away above
        // needs no table, so tapping it opens the order itself; Dine In DOES need a
        // table, so tapping it opens the picker to choose one - rather than quietly
        // selecting whichever dine-in order happened to be open, which is what it used
        // to do and which answered a question nobody had asked. A waiter reaching for
        // Dine In is starting to serve a table, and the first thing they need is the
        // floor.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDineIn).setOnClickListener {
            view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.segOrderType)
                .check(R.id.btnDineIn)
            showChooseTableDialog()
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

        // No New Order button any more - Choose Table below starts a dine-in order and
        // Take Away starts its own, which is both kinds. [showNewOrderDialog] is what
        // it opened and is kept for now, unreferenced, in case that modal is wanted
        // back on a control somewhere else.

        // Choose Table → table-picker grid (sections as tabs, tables as cards).
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnChooseTable).setOnClickListener {
            showChooseTableDialog()
        }
        setChooseTableEnabled(view, segOrderType.checkedButtonId != R.id.btnTakeAway)

        // More → the whole order in a roomy popup, since this panel is narrow.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMoreItems)
            .setOnClickListener { showOrderItemsDialog() }

        setUpDiscountBox(view)

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
            // No Take Away refusal here any more. It used to turn one away with "KOT
            // prints on payment", which was true when payment cut the ticket itself -
            // that was removed, and this refusal outlived it, leaving take-away with
            // an enabled button that answered every press by declining. A take-away
            // has food to cook and sends its ticket like any other order.
            val order = currentOrder() ?: return@setOnClickListener toast("Select an order first")
            if (order.completed) return@setOnClickListener toast("Order already billed")
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
                else -> withCustomerIfTakeAway(order) { resolveBillPrinterThenPrint(order) }
            }
        }

        // When checkout confirms payment, settle the table: save the bill and close it
        // (in the DB too). No receipt is printed here - see [settlePaidOrder].
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
            // NO STOCK IS MOVED HERE. It used to be, on the grounds that "Restaurant
            // checkout does not write a bill" - which stopped being true when
            // settlePaidOrder started persisting one. The result was every restaurant
            // sale deducted TWICE, once from here against the table label and once
            // from BillDao against the bill number, so selling one took two off the
            // shelf. The device's own movement rows showed the pair, a second apart,
            // for the same product and quantity.
            //
            // BillDao.createBill is the right place and the only place: it deducts
            // inside the bill's own transaction, so the sale and the stock it moved
            // land together or not at all - which this call, running before and
            // outside that transaction, could never promise.
            val payMethod = bundle.getString(RestaurantCheckoutFragment.ARG_PAY_METHOD).orEmpty()
            val tendered = bundle.getDouble(RestaurantCheckoutFragment.ARG_TENDERED, 0.0)
            // Persists the bill (with the payment mode), closes & frees the table(s)
            // and refreshes the list. Nothing is printed.
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
                // A TAKE-AWAY IS SETTLED WHERE IT WAS RUNG UP, not on a screen of its
                // own. The checkout page earns its place for a table: the bill is
                // itemised there because a table's bill is read, queried and argued
                // over before it is paid. A counter order has just been rung up in
                // front of the person paying for it, so listing it back to them is a
                // page to get past on the way to taking the money - and it costs the
                // counter the order it was working on and a trip back.
                order.type.equals("Take Away", ignoreCase = true) ->
                    withCustomerIfTakeAway(order) { showQuickPayment(order) }
                else -> {
                    val names = ArrayList(order.items.map { it.name })
                    val qtys = order.items.map { it.qty }.toDoubleArray()
                    val rates = order.items.map { it.rate }.toDoubleArray()
                    val cgsts = order.items.map { it.cgstRate }.toDoubleArray()
                    val sgsts = order.items.map { it.sgstRate }.toDoubleArray()
                    val vats = order.items.map { it.vatRate }.toDoubleArray()
                    // Off the catalogue, same as buildBillDraft() - a cart line does not
                    // carry its own HSN or unit, the product it was sold from does.
                    val hsns = ArrayList(order.items.map { line ->
                        allProducts.firstOrNull { it.product.id == line.productId.toString() }
                            ?.product?.hsn.orEmpty()
                    })
                    val units = ArrayList(order.items.map { line ->
                        allProducts.firstOrNull { it.product.id == line.productId.toString() }
                            ?.product?.unit.orEmpty()
                    })
                    // The shop's own extra charges - Parcel Charge among them - worked
                    // out and filtered by this order's type right here, the same call
                    // the fold-out panel and the printed bill both use, so Checkout
                    // shows and charges exactly what they do.
                    // The one calculation, read once: its charges AND its discount both
                    // travel to the checkout so that screen shows the same money.
                    val b = computeBill(order.items, serviceRateFor(order.section), order.type)
                    val charges = b.charges
                    requireActivity().supportFragmentManager.beginTransaction()
                        .replace(
                            R.id.fragment_container,
                            RestaurantCheckoutFragment.newInstance(
                                order.dbId, order.id, order.section,
                                order.phone.ifBlank { "Walk-in" }, names, qtys, rates,
                                cgsts, sgsts, serviceRateFor(order.section),
                                gstEnabled = taxSettings.gstEnabled, inclusive = taxInclusive,
                                hsns = hsns, vats = vats, vatEnabled = taxSettings.vatEnabled,
                                units = units,
                                chargeNames = ArrayList(charges.map { it.name }),
                                chargeAmounts = charges.map { it.amount }.toDoubleArray(),
                                chargeTypes = ArrayList(charges.map { it.type.name }),
                                // Tax Settings' discount, worked out ONCE here and
                                // carried across. The checkout used to price every
                                // line with no discount and a hard-coded post-tax
                                // flag, so a discounted table was quoted one total on
                                // the cart page and charged another at the till - and
                                // the pre-tax / post-tax choice did nothing on the
                                // screen the customer actually pays from.
                                discount = b.discount,
                                lineDiscounts = order.items.map { line ->
                                    com.example.synergic_pos_offline.utils.CartMath.lineDiscount(
                                        line.toMathLine(), cartConfig(),
                                        com.example.synergic_pos_offline.utils.CartMath
                                            .subtotal(order.items.map { it.toMathLine() }),
                                        b.discount
                                    )
                                }.toDoubleArray(),
                                discountPreTax = discountPreTax
                            )
                        )
                        .addToBackStack(null)
                        .commit()
                }
            }
        }

        // MainActivity re-themes the whole tree on resume (by button name), which
        // would clobber our button styling — re-apply ours after that pass.
        view.post { if (isAdded) restyle(view, accent) }

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
        directAddToCart = appSettingOn(com.example.synergic_pos_offline.database.AppSettingsDao.KEY_DIRECT_ADD_TO_CART)
        tableShiftOn = appSettingOn(com.example.synergic_pos_offline.database.AppSettingsDao.KEY_TABLE_SHIFT)
        tableMergeOn = appSettingOn(com.example.synergic_pos_offline.database.AppSettingsDao.KEY_TABLE_MERGE)
        tableSplitOn = appSettingOn(com.example.synergic_pos_offline.database.AppSettingsDao.KEY_TABLE_SPLIT)
        val accent = ThemeManager.getThemeColor(requireContext())
        view?.let { v -> v.post { if (isAdded) restyle(v, accent) } }
        // The menu is on the page now, so it has to be current whenever the page is:
        // a product edited, or stock moved by a settled bill, shows on the way back.
        reloadProductsAndRefresh()
    }

    /**
     * One App Settings toggle ('A'), out of the login cache. Named by the DAO's own
     * key so the toggle that writes it and the screen that obeys it cannot drift onto
     * two spellings of the same setting.
     */
    private fun appSettingOn(key: String): Boolean =
        SettingsCache.value(requireContext(), "A", key) == "1"

    /**
     * Whether this till collects customer details - General Settings ▸ Customer Info.
     *
     * Read on each use rather than cached on the fragment: it is a General Settings
     * trip away, and unlike the App Settings flags above it is asked once per order
     * rather than once per tap, so there is nothing to save by holding it.
     */
    private fun customerInfoOn(): Boolean =
        com.example.synergic_pos_offline.database.GeneralSettingsDao
            .isCustomerInfoEnabled(requireContext())

    override fun onDestroyView() {
        // A ListPopupWindow is a window, not a child of this view: left showing, it
        // would float over whatever replaces this screen.
        suggestions?.release()
        suggestions = null
        super.onDestroyView()
    }

    /** Called by MainActivity when the palette colour changes — recolour instantly. */
    fun onThemeChanged() {
        val v = view ?: return
        val accent = ThemeManager.getThemeColor(requireContext())
        v.post { if (isAdded) recolorAll(v, accent) }
    }

    /** Re-applies every accent that isn't handled by ThemeManager (cards, tabs, statuses). */
    private fun recolorAll(v: View, accent: Int) {
        populateOrders(v, accent)   // re-renders cards with the new accent + selection
        restyle(v, accent)
    }

    private fun restyle(view: View, accent: Int) {
        applyAccents(view, accent)
        styleSeg(view, accent)
        // The accent pass repaints these two by name, which is what a returning theme
        // pass does on every resume - so the lock is re-stated after it. Without this
        // a billed table came back from Settings or a rotation with both buttons lit
        // again, and the panel disagreed with the order.
        setBilledLock(view, billed = currentOrder()?.completed == true)
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

        // AN EMPTY SPLIT PART IS NOT AN ACTIVE ORDER. It is a free seat of a split
        // table - nothing has been ordered on it and nothing is owed for it - so it
        // belongs on the floor plan, where it now has a tile of its own, and not in a
        // list of orders being worked on.
        //
        // This is what was left showing after a part was settled: the paid part went,
        // and its untouched neighbour stayed behind reading "5 B - Available", an
        // entry in the active list for a table with no order on it. The parts must
        // survive settlement (closing them is what erased live tables), so the answer
        // is not to close them but to stop calling them active.
        //
        // The selected one is always shown. It is what the detail panel is pointed at,
        // and a list that leaves out the row you are working on reads as if the pick
        // had not registered.
        val shown = orders.filter { !it.id.contains(" ") || it.items.isNotEmpty() || it.selected }

        // Active-orders count badge, and the same count on the button that slides the
        // list in - with the list closed that button is the only place it shows.
        root.findViewById<TextView>(R.id.tabActive).setTextColor(accent)
        root.findViewById<TextView>(R.id.badgeActive).apply {
            text = shown.size.toString(); backgroundTintList = ColorStateList.valueOf(accent)
        }
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnToggleOrders)?.text =
            if (shown.isEmpty()) "Active Orders" else "Active Orders (${shown.size})"

        // Empty state: no orders yet.
        val emptyView = root.findViewById<TextView>(R.id.tvNoOrders)
        emptyView?.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE

        shown.forEach { o ->
            val card = inflater.inflate(R.layout.item_order_card, list, false)
                    as com.google.android.material.card.MaterialCardView
            val takeAway = o.type.equals("Take Away", ignoreCase = true)
            card.findViewById<TextView>(R.id.tvOrderId).apply {
                text = if (takeAway) o.id.replace("TA-", "Token #") else tableDisplayName(o)
                setTextColor(accent)
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
                card.strokeWidth = (card.resources.displayMetrics.density * 1.5f).toInt()
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
        clearDiscountEntry()
        val accent = ThemeManager.getThemeColor(requireContext())
        val takeAway = order.type.equals("Take Away", ignoreCase = true)
        // Take Away has no table — show it as a take-away token, not "Table: …".
        root.findViewById<TextView>(R.id.tvDetailTableLabel).visibility = if (takeAway) View.GONE else View.VISIBLE
        root.findViewById<TextView>(R.id.tvDetailTable).apply {
            text = if (takeAway) "Take Away" else tableDisplayName(order); setTextColor(accent)
        }
        root.findViewById<TextView>(R.id.tvDetailCustomer).text = order.phone.ifBlank { "Walk-in" }
        // TAKE AWAY HAS ONE BUTTON, NOT TWO. A table is billed and paid as two acts,
        // minutes apart: the bill goes out, the guests read it, and they settle when
        // they are ready - so Print Bill and Settlement are two controls because they
        // happen at two moments. At a counter they are one moment. The customer is
        // standing there paying, and the slip they are handed is the receipt for the
        // payment just taken, so asking the counter to press two buttons in a row is
        // asking it to split an act that is not divided.
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBillPrint)
            .visibility = if (takeAway) View.GONE else View.VISIBLE
        root.findViewById<TextView>(R.id.tvDetailGuests).text =
            if (takeAway) order.id.replace("TA-", "Token #")
            else if (order.section.isNotBlank()) "${order.section}  ·  ${order.type}" else order.type
        root.findViewById<TextView>(R.id.tvDetailOrderTime).text =
            "Order Time: ${order.time.ifBlank { "—" }}"
        setNoteField(root, order.note)
        // Take Away has no table to transfer, merge or split; those fold away. It does
        // have food to cook, so Print KOT stays available - see setDineInActionsEnabled.
        setDineInActionsEnabled(root, !order.type.equals("Take Away", ignoreCase = true))
        // After the type rule, so a billed order stays locked whatever type it is.
        setBilledLock(root, order.completed)
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
        // The handle carries the Total only while the fold is shut. Open, the full
        // Total row is showing a few lines below it, and the same number twice - one
        // above the other - reads as two figures to reconcile rather than one to read.
        root.findViewById<TextView>(R.id.tvTotalBar).visibility =
            if (expanded) View.GONE else View.VISIBLE
    }

    /**
     * The table's occasional actions, on one menu rather than four more buttons in a
     * row that already carries the order's own. Each item is greyed the same way its
     * button was, so what cannot be done still shows itself rather than disappearing.
     *
     * Two things can grey one: the order (Take Away has no table to do any of this
     * to) and App Settings, where a shop switches off the ones it does not work by.
     */
    private fun showTableActionsMenu(anchor: View) {
        val menu = android.widget.PopupMenu(requireContext(), anchor)
        menu.menu.add(0, MENU_TRANSFER, 0, "Transfer").isEnabled = dineInActionsEnabled && tableShiftOn
        menu.menu.add(0, MENU_MERGE, 1, "Merge").isEnabled = dineInActionsEnabled && tableMergeOn
        menu.menu.add(0, MENU_SPLIT, 2, "Split").isEnabled = dineInActionsEnabled && tableSplitOn
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
                MENU_MERGE -> onMerge()
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
        if (!tableShiftOn) return toast("Table Shift is switched off in App Settings")
        val order = currentOrder() ?: return toast("Select a table order first")
        if (order.type.equals("Take Away", ignoreCase = true))
            return toast("Not available for Take Away")
        if (order.completed) return toast("Table already billed — cannot transfer")
        showTransferDialog(order)
    }

    /**
     * Merge: fold other tables of this room into the selected table's bill.
     *
     * Guarded the way Transfer and Split are, and for the same reason - the dialog now
     * opens with the selected table already in it, so what can be merged FROM has to
     * be settled before it opens rather than discovered inside it.
     */
    private fun onMerge() {
        if (!tableMergeOn) return toast("Table Merge is switched off in App Settings")
        val order = currentOrder() ?: return toast("Select a table order first")
        if (order.type.equals("Take Away", ignoreCase = true))
            return toast("Not available for Take Away")
        if (order.completed) return toast("Table already billed — cannot merge")
        showMergeDialog()
    }

    /** Split: break the selected table into sub-tables (101 A, 101 B, …). */
    private fun onSplit() {
        if (!tableSplitOn) return toast("Table Split is switched off in App Settings")
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
        // An empty split part is not an order being thrown away - it is a seat of a
        // split nobody used, and cancelling it is how the operator gives that seat
        // back. Asking "remove all its items?" about a part with no items reads as a
        // warning about losing something, when the opposite is true.
        val emptyPart = order.id.contains(" ") && order.items.isEmpty()
        com.example.synergic_pos_offline.utils.DialogUtils.showConfirm(
            requireContext(),
            title = if (emptyPart) "Remove this part?" else "Clear this order?",
            message = if (emptyPart)
                "Give up $label? It has no items. The table goes back to whole once its last part is removed."
            else "Remove $label and all its items? This can't be undone.",
            positiveText = if (emptyPart) "Remove" else "Clear",
            destructive = true
        ) { clearActiveOrder(order) }
    }

    /**
     * Switches to Take Away and puts an order on screen: the one already running if
     * there is one, a new token if there is not.
     *
     * Reused by the Take Away button and by closing the table picker without choosing
     * a table - see [showChooseTableDialog]. One definition, so the two cannot drift
     * into starting a second token for a counter that already has one open.
     */
    private fun openTakeAway() {
        val root = view ?: return
        root.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.segOrderType)
            .check(R.id.btnTakeAway)
        // THE PROMPT ALWAYS SHOWS.
        //
        // It used to return here when any take-away was already running - "no duplicate
        // token" - which meant that once one counter order was open, Take Away silently
        // re-selected it and the customer was never asked for again. A counter serves
        // one customer after another, and each of them is their own order with their
        // own name on it.
        askTakeAwayCustomer { name, phone ->
            // An EMPTY take-away already open is reused rather than adding a second
            // token beside it. That is the case the old guard was really protecting
            // against: tapping Take Away twice, or skipping the prompt and coming back,
            // should not leave a trail of TA-1, TA-2, TA-3 with nothing on any of them.
            // One with items on it is a real order being served and is left alone.
            val reusable = orders.firstOrNull {
                it.type.equals("Take Away", ignoreCase = true) && !it.completed && it.items.isEmpty()
            }
            if (reusable != null) {
                reusable.phone = phone
                roDao.setPhone(reusable.dbId, phone)
                // RENUMBERED to whatever Token Numbering says now.
                //
                // An empty token that is reused kept whatever code it was cut with, so
                // a counter that changed the start number, the prefix or the reset
                // period saw the old token again and nothing appear to happen. The
                // number is worked out fresh here, with this order left out of the
                // count so it is not compared against itself and climbing on every
                // look. Unchanged settings therefore produce the same code and the
                // token sits still, which is the point of reusing it.
                val fresh = nextTakeAwayCode(excludeOrderId = reusable.dbId)
                if (!fresh.equals(reusable.id, ignoreCase = true)) {
                    roDao.transferTable(reusable.dbId, fresh)
                    reusable.id = fresh
                }
                selectOrder(reusable)
            } else {
                openNewOrder(nextTakeAwayCode(), section = "", phone = phone, type = "Take Away")
            }
            toast(
                if (name.isBlank()) "Take-away order started — add items, then Settlement"
                else "Take-away order for $name — add items, then Settlement"
            )
        }
    }

    /**
     * Asks who the take-away is for - the SAME prompt the grocery till uses.
     *
     * A dine-in order has a table to be known by: the floor calls out "table 7" and
     * everyone knows which order that is. A take-away has only a token number, so the
     * customer is part of the order rather than an extra on it, and is asked for
     * first.
     *
     * What it asks, and what it writes, is CustomerPrompt's business rather than this
     * screen's - one phone number, found or created in md_customers, never a
     * duplicate. This screen only decides WHEN to ask and what to do with the answer.
     * Written out twice the two would drift, and a shop's address book collecting two
     * records for one phone because two screens disagreed about what counts as the
     * same customer is a fault nobody notices until the list is unusable.
     *
     * Skip is the way past it: a counter with a queue must be able to take an order
     * without an interrogation, and a walk-in has no customer to record. Skipping
     * starts the order with nobody attached and writes nothing to the customer list.
     *
     * ASKED ONLY WHERE THE TILL COLLECTS CUSTOMERS AT ALL. With General Settings ▸
     * Customer Info off the shop keeps no customer list, so there is nothing for an
     * answer to be filed in and nothing later reads it - the prompt is skipped and the
     * order runs with nobody attached, exactly as pressing Skip would leave it.
     */
    private fun askTakeAwayCustomer(onDone: (name: String, phone: String) -> Unit) {
        if (!customerInfoOn()) return onDone("", "")
        com.example.synergic_pos_offline.utils.CustomerPrompt.showDetails(
            context = requireContext(),
            title = "Take Away — customer",
            positiveText = "Start Order",
            // Skip is offered. A counter with a queue has to be able to take an order
            // without an interrogation, and a walk-in paying cash for a coffee has no
            // customer to record - so there is a way past the form that does not
            // require knowing the back press does the same thing.
            showSkip = true,
            skipText = "Skip",
            // Skip and the back press mean the same thing: start the order with nobody
            // attached. Neither writes to the customer list.
            onCancel = { onDone("", "") },
            onPicked = { c -> onDone(c.name, c.phone) }
        )
    }

    /** Choose Table, greyed out the same way the other dine-in-only actions are. */
    private fun setChooseTableEnabled(root: View, enabled: Boolean) {
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnChooseTable).apply {
            isEnabled = enabled; alpha = if (enabled) 1f else 0.4f
        }
    }

    /**
     * Enables/disables the dine-in-only actions - Transfer, Merge and Split, which are
     * items on the Table Actions menu and so are held as a flag until that menu is
     * built.
     *
     * Also un-greys Print KOT unconditionally, for the reason in the body.
     */
    private fun setDineInActionsEnabled(root: View, enabled: Boolean) {
        dineInActionsEnabled = enabled
        // PRINT KOT IS NOT ONE OF THEM, and used to be.
        //
        // Transfer, Merge and Split all act on a TABLE, so a take-away order has
        // nothing for them to do. A KOT is not about the table at all - it is the
        // kitchen's copy of what to cook - and a take-away has food to cook like any
        // other order. Disabling it left take-away with no way to reach the kitchen:
        // the ticket used to be cut for it at payment instead, which arrived after the
        // food was paid for and has since been removed.
        //
        // Left enabled for every order type. Whether there is anything to send is a
        // separate question, and the button answers it when pressed - "No new items to
        // send to kitchen" - rather than being greyed on a rule about tables.
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPrintKot).apply {
            isEnabled = true; alpha = 1f
        }
    }

    /**
     * Greys Print KOT and Print Bill once the order has been billed.
     *
     * A billed order is closed to changes - completeTable locks it, and the cart's
     * steppers go with it - so neither button has anything left to do. Print KOT would
     * be a ticket for food already cooked, served and charged for; Print Bill would be
     * a second copy of a bill the customer is holding, cut from an order that can no
     * longer change. Both used to sit there fully lit and refuse on the tap, which
     * tells the operator only after they have tried.
     *
     * Settlement stays live. It is the one thing a billed order is still waiting for.
     *
     * MUST BE APPLIED AFTER [setDineInActionsEnabled], which lights Print KOT
     * unconditionally for every order type - a take-away has food to cook like any
     * other. That rule is about the TYPE of order; this one is about its STATE, and
     * the state has the last word.
     */
    private fun setBilledLock(root: View, billed: Boolean) {
        listOf(R.id.btnPrintKot, R.id.btnBillPrint).forEach { id ->
            root.findViewById<com.google.android.material.button.MaterialButton>(id)?.apply {
                isEnabled = !billed
                alpha = if (billed) 0.4f else 1f
            }
        }
        setSettlementEnabled(root, currentOrder())
    }

    /**
     * Settlement is only live once a DINE-IN order has been through the two steps that
     * come before it: the ticket to the kitchen, and the bill to the table.
     *
     * A table is settled last, not first. Money is taken after the food has been
     * ordered and after the guests have been given the bill and read it - so a
     * Settlement that is live on an untouched table is a button that can only be
     * pressed by mistake, and pressing it closes the table and writes a paid bill for
     * an order the kitchen never saw.
     *
     * THE KITCHEN COMES FIRST FOR BOTH. Nothing is settled before the ticket has gone
     * to the pass - taking money for food nobody has been told to cook is the one
     * order of events a counter cannot recover from.
     *
     * The printed bill is required for DINE-IN ONLY. A take-away's button IS the bill
     * - "Print & Settlement" prints and settles in one press (see printThenSettle) -
     * so requiring a printed bill first would require the button to have been pressed
     * before it can be pressed.
     *
     * Greyed rather than hidden: unlike a control that never applies, this one is
     * coming - it is the next thing that happens to this table - and a gap where it
     * sits would read as the screen having lost it.
     */
    private fun setSettlementEnabled(root: View, order: OrderCard?) {
        val takeAway = order?.type.equals("Take Away", ignoreCase = true)
        // Sent, not merely typed: kotQty is what actually went to the pass.
        val kotSent = order?.items?.any { it.kotQty > 0.0 } == true
        val ready = order != null && order.items.isNotEmpty() &&
            kotSent && (takeAway || order.completed)
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBillPay)?.apply {
            isEnabled = ready
            alpha = if (ready) 1f else 0.4f
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
        setBilledLock(root, billed = false)   // nothing selected → nothing locked
        setNoteField(root, "")
        root.findViewById<LinearLayout>(R.id.llOrderItems).removeAllViews()
        val zero = "₹ ${money(0.0)}"
        root.findViewById<TextView>(R.id.tvSubtotal).text = zero
        root.findViewById<TextView>(R.id.tvService).text = zero
        root.findViewById<TextView>(R.id.tvCgst).text = zero
        root.findViewById<TextView>(R.id.tvSgst).text = zero
        root.findViewById<TextView>(R.id.tvOrderTotal).text = zero
        root.findViewById<TextView>(R.id.tvTotalBar).text = zero
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBillPay).text =
            "Settlement  ( $zero )"
        // Back to the two-button layout: with nothing selected the panel shows what a
        // table order offers, which is what the next selection most often is.
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBillPrint)
            .visibility = View.VISIBLE
    }

    /**
     * What the settlement button says. A take-away's does both jobs, and says so - the
     * counter should not have to know that the button which takes the money also cuts
     * the slip.
     */
    private fun settlementLabel(order: OrderCard?, total: Double): String {
        val what = if (order?.type.equals("Take Away", ignoreCase = true)) "Print & Settlement"
        else "Settlement"
        return "$what  ( ₹ ${money(total)} )"
    }

    /** Accent the filled buttons, headers and the active tab (avoids ThemeManager's name rules). */
    private fun applyAccents(root: View, accent: Int) {
        val white = android.graphics.Color.WHITE
        val strokePx = (root.resources.displayMetrics.density * 1.5f).toInt()
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
        filled(R.id.btnBillPay)
        textOnly(R.id.btnToggleSummary)
        outlined(R.id.btnPrintKot); outlined(R.id.btnChooseTable)
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
        root.findViewById<TextView>(R.id.tvTotalBar).setTextColor(accent)
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
    /**
     * The code the next take-away order takes, under Bill Settings ▸ Token Numbering.
     *
     * It used to be the lowest "TA-n" no OPEN order was using, which meant a token was
     * handed back the moment its order was settled: take token 1, serve it, and the
     * next customer was token 1 again. Two people at one counter holding the same
     * number is the one thing a token has to prevent.
     *
     * [TokenNumberDao] now answers instead - counting settled orders as used, and
     * applying the shop's start number, reset period and prefix. Falls back to the old
     * scan only if that query fails outright, so a counter is never left unable to
     * start an order because a setting could not be read.
     */
    private fun nextTakeAwayCode(excludeOrderId: Long? = null): String =
        runCatching {
            com.example.synergic_pos_offline.database.TokenNumberDao(requireContext())
                .nextCode(excludeOrderId)
        }
            .getOrElse {
                val active = orders.map { it.id }.toSet()
                var n = 1
                while (active.contains("TA-$n")) n++
                "TA-$n"
            }

    /** Persists a new running order, selects it, and starts it with a fresh empty cart. */
    private fun openNewOrder(table: String, section: String, phone: String, type: String) {
        val root = view ?: return
        val now = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
        val cashier = com.example.synergic_pos_offline.utils.SessionManager.currentUser?.userId ?: "—"

        val dbId = roDao.createOrder(table, section, null, type, phone, cashier)
        if (dbId == -1L) { toast("Could not create order"); return }
        // THE TABLE IS NOT MARKED OCCUPIED HERE.
        //
        // Opening a table is not seating anyone at it. A waiter taps a table to see it,
        // to check it, or by mistake, and the order that gets created is an empty shell
        // waiting for a first item - so marking it Occupied on the tap turned the floor
        // plan red for tables with nobody at them, and left the next waiter unable to
        // take that table because the picker refuses one that is not Available.
        //
        // The status moves when the ORDER does: addToCart sets Occupied as the first
        // item goes on, which is the moment the table genuinely is. Tapping the table
        // again before then just re-selects this empty order rather than creating a
        // second one, so nothing is lost by waiting.

        orders.forEach { it.selected = false }
        val order = OrderCard(
            dbId = dbId, id = table, type = type, section = section, phone = phone, time = now,
            amount = "₹ ${money(0.0)}", cashier = cashier, status = "RUNNING", selected = true
        )
        orders.add(0, order)                                  // newest on top
        // The top segment shows what the NEW order is, the way [selectOrder] does for
        // one being re-opened. It was only done there, so an order created here left
        // the segment on whatever was last lit - a take-away token opening with Dine In
        // still selected, which reads as the wrong kind of order on the one control
        // that says which kind it is.
        //
        // Set AFTER the order exists rather than before, so nothing between here and
        // the screen settling can put it back. A programmatic check does not fire the
        // buttons' own click listeners, so this cannot re-enter and open a second order.
        root.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.segOrderType).check(
            if (type.equals("Take Away", ignoreCase = true)) R.id.btnTakeAway else R.id.btnDineIn
        )
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
        // The new table inherits what the old one WAS. An order transferred before its
        // KOT went out has taken nobody's table yet, and marking the target Occupied
        // would take one - the same thing adding an item used to do, in the one place
        // it was left. Sent already: the guests have moved and the new table is theirs.
        val sentAlready = order.items.any { it.kotQty > 0.0 }
        updateTableStatus(to, order.section, if (sentAlready) "Occupied" else "Available")
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
        if (!tableMergeOn) return toast("Table Merge is switched off in App Settings")
        val ctx = com.example.synergic_pos_offline.utils.FixedFontScale.wrap(requireContext())
        val accent = ThemeManager.getThemeColor(ctx)

        // Every active DINE-IN table (running, not billed) is a candidate at first.
        // Take Away orders have no table to merge.
        val activeTables = orders.filter { !it.completed && !it.type.equals("Take Away", ignoreCase = true) }
        // No "two active tables" check any more: a free table can be merged in now, so
        // one running order and an empty table beside it is a perfectly good merge -
        // and is the case this is most often wanted for.
        if (activeTables.isEmpty()) { toast("No active dine-in table to merge"); return }

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

        // Queued candidates, not queued table codes: two sections can both have a
        // table 1 and the dropdown may well offer both, so each entry has to say which
        // table in which room it stands for.
        val added = mutableListOf<MergeCandidate>()   // first = kept

        // A table reads as "1 (AC)" here, since the number alone no longer identifies
        // it once a second section has the same number.
        fun label(c: MergeCandidate) = if (c.section.isBlank()) c.code else "${c.code} (${c.section})"

        /**
         * What may be added, and it is not only tables that already have an order.
         *
         * FIRST PICK: an active order. It is the one that is kept, and a bill has to be
         * kept - an empty table has nothing for the others to fold into.
         *
         * AFTER THAT: everything else in the same room, free tables included. A party
         * grows and takes the empty table beside it, and that table has to be able to
         * join the bill; the dropdown offering only tables that had already ordered
         * meant the one case a merge is most often wanted for could not be done at all.
         * A free table brings no items - it just joins, and is held and released with
         * the bill it joined.
         *
         * Still one room. Merging across sections would put one bill on two floors.
         */
        fun candidates(): List<MergeCandidate> {
            if (added.isEmpty()) return activeTables.map { MergeCandidate(it.id, it.section, it) }
            val room = added.first().section
            val busy = activeTables
                .filter { it.section.equals(room, ignoreCase = true) }
                .map { MergeCandidate(it.id, it.section, it) }
            // Free tables of the same room, straight off the floor plan - which is
            // also where a split's parts come from, so "5 B" can join a bill like any
            // other table.
            val free = runCatching { loadTables() }.getOrDefault(emptyList())
                .filter {
                    it.section.equals(room, ignoreCase = true) &&
                        it.status.equals("Available", ignoreCase = true)
                }
                .map { MergeCandidate(it.code, it.section, null) }
            return (busy + free).filter { cand ->
                added.none { it.code.equals(cand.code, ignoreCase = true) && it.section.equals(cand.section, ignoreCase = true) }
            }
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
                val count = o.order?.items?.size ?: 0
                row.findViewById<TextView>(R.id.tvMergeTableInfo).text =
                    if (o.order == null) "Free — joins the bill"
                    else "$count item${if (count == 1) "" else "s"}"
                row.findViewById<android.widget.ImageView>(R.id.btnRemoveMergeTable).setOnClickListener {
                    added.removeAll {
                        it.code.equals(o.code, true) && it.section.equals(o.section, true)
                    }
                    renderAdded(); refreshDropdown()
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
                picked == null -> actWith.error = "Not a table in this section"
                else -> { added.add(picked); renderAdded(); refreshDropdown() }
            }
        }

        ThemeManager.applyTheme(v)
        btnAdd.setTextColor(accent); btnAdd.strokeColor = ColorStateList.valueOf(accent); btnAdd.iconTint = ColorStateList.valueOf(accent)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent); btnSave.setTextColor(Color.WHITE)
        btnCancel.setTextColor(accent); btnCancel.strokeColor = ColorStateList.valueOf(accent)

        // THE TABLE THE MERGE WAS STARTED FROM IS ALREADY IN THE LIST.
        //
        // Table Actions acts on the selected table, so choosing table 3 and pressing
        // Merge has already said which table this is about - asking for it again as
        // the first of two picks is asking a question that was just answered, and it
        // let the operator start from a different table than the one they had open.
        //
        // Seeded as the KEPT table, which is what starting from it means: its bill is
        // the one the others join. With the room settled by that first entry, the
        // dropdown below opens on the rest of that room - see candidates().
        //
        // Removable, like any other row: taking it out empties the list and the dialog
        // falls back to asking for a first table, which is what it did before.
        val startFrom = currentOrder()
        if (startFrom != null && !startFrom.completed &&
            !startFrom.type.equals("Take Away", ignoreCase = true)
        ) {
            added.add(MergeCandidate(startFrom.id, startFrom.section, startFrom))
        }

        refreshDropdown()
        renderAdded()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            if (added.size < 2) { toast("Add at least two tables to merge"); return@setOnClickListener }
            dialog.dismiss()
            val keep = added.first().order
            if (keep == null) { toast("The kept table must have an order"); return@setOnClickListener }
            performMerge(keep, added.drop(1))
        }
        dialog.show()
    }

    /** Applies the merge: fold each source table's items into the kept table. The
     *  merged tables stay Occupied (part of the merge) and are freed only when the
     *  kept order is settled. */
    private fun performMerge(target: OrderCard, sources: List<MergeCandidate>) {
        sources.forEach { source ->
            val order = source.order
            if (order != null) {
                roDao.mergeOrders(target.dbId, order.dbId)   // records the merged table + keeps it Occupied
                orders.removeAll { it.dbId == order.dbId }   // its own card is gone (shares the kept bill)
            } else {
                // A free table joining the party: nothing to move, so it is only
                // recorded against the kept bill and taken off the floor. It is
                // released when that bill settles, exactly like a table that did
                // bring items with it.
                roDao.attachTable(target.dbId, source.code)
                updateTableStatus(source.code, source.section, "Occupied")
            }
        }
        // The kept card learns what it now covers, so the panel and the order list
        // name the merged group without waiting for a reload.
        target.merged = runCatching { roDao.mergedTablesOf(target.dbId) }.getOrDefault(target.merged)
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
     * Split popup: choose how many parts (2–6); shows the sub-tables that will be
     * created (e.g. 101 A, 101 B). On Split the first part keeps this order's items;
     * the rest start empty. All parts share the parent table until each is settled.
     */
    private fun showSplitDialog(order: OrderCard) {
        val ctx = com.example.synergic_pos_offline.utils.FixedFontScale.wrap(requireContext())
        val accent = ThemeManager.getThemeColor(ctx)
        // Built from the same bounds the save clamps to, so the dropdown can never
        // offer a number that is then quietly reduced. See MAX_SPLIT_PARTS.
        val counts = (MIN_SPLIT_PARTS..MAX_SPLIT_PARTS).map { it.toString() }

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
            performSplit(order, count.coerceIn(MIN_SPLIT_PARTS, MAX_SPLIT_PARTS))
        }
        dialog.show()
    }

    /** Applies the split: first part keeps the order/items, the rest are new empties. */
    private fun performSplit(order: OrderCard, count: Int) {
        val parent = order.id
        val section = order.section
        val waiterId = roDao.findByTable(parent, section)?.waiterId
        val cashier = com.example.synergic_pos_offline.utils.SessionManager.currentUser?.userId ?: "—"

        // Part A keeps the existing order, so it is occupied if that order has been
        // SENT to the kitchen - the same rule as everywhere else. Items merely typed
        // in do not take a table, so a split made before the KOT goes out leaves part A
        // free like the rest, and it is taken when the ticket is cut.
        val sentAlready = order.items.any { it.kotQty > 0.0 }
        val firstCode = subTableDao.create(
            parent, section, "A",
            status = if (sentAlready) "Occupied" else "Available"
        )
        roDao.transferTable(order.dbId, firstCode)
        // The remaining parts (B onward, up to F) start empty → Available until items
        // are added.
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
     * A split is over once NOTHING of it is left - no part still has a running order.
     * Then, and only then, the parent table goes back to being one table: it is freed
     * and its sub-table records are dropped.
     *
     * The test used to be "every remaining part is empty", and that was wrong in a way
     * that lost live tables. An empty part is not a finished part - it is a seat that
     * has not ordered yet, which is exactly what every part except A is the moment a
     * table is split. So settling the parts that HAD ordered swept away the ones that
     * had not: split a table four ways, bill parts 1 and 2, and parts 3 and 4 were
     * closed underneath the guests still sitting at them, their orders deleted and
     * their sub-tables cleared. Reproduced against the device's own database - the
     * second settlement is the one that did it, because that is when the last part
     * holding items left the list and the untouched parts became "all empty".
     *
     * A part therefore leaves a split only when it is settled or when the operator
     * cancels it (see [clearActiveOrder]) - never as a side effect of what its
     * neighbours did. Dropping the last part is what ends the split.
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
        // A PART MERGED INTO ANOTHER TABLE IS STILL IN USE, even though it no longer
        // has an order of its own.
        //
        // Merge moves the items across and closes the source order, so 5 A vanishes
        // from this list the moment it is merged into table 10 - and if 5 B happened
        // to be empty, the split would be torn down underneath a part whose guests are
        // sitting there on table 10's bill. Table 5 would read Available while it was
        // being served, and the release recorded against table 10 (merged_tables)
        // would later have nothing left to release.
        //
        // So a parent is only given back once none of its parts is carried on any live
        // order either.
        val merged = mergedPartsInUse()
        val partMerged = merged.any { it.startsWith("$parent ", ignoreCase = true) }

        // THE SPLIT COLLAPSES ONCE NOTHING IS ON ANY PART.
        //
        // Either there are no parts left at all, or the ones left are empty - no
        // items, so nothing ordered, nothing owed, nothing waiting on the kitchen.
        // A table whose parts are all free is not really split any more, and leaving
        // 3 A and 3 B sitting on the floor plan means the next party of four cannot be
        // given table 3 without somebody first taking the split apart by hand.
        //
        // EMPTY, not merely Available. That distinction is what keeps the earlier
        // fault fixed: a part with items on it survives, whatever its status, so
        // billing two parts of a four-way split can never again close the two that
        // are still being served. What goes is only what is holding nothing.
        //
        // A part merged onto another bill is not empty in that sense either - its
        // items moved, and it is released when that bill settles.
        val allFree = parts.isNotEmpty() && parts.all { it.items.isEmpty() }
        if (!partMerged && (parts.isEmpty() || allFree)) {
            // Close the empty parts before dropping their sub-tables, or their orders
            // would be left pointing at tables that no longer exist.
            val partIds = parts.map { it.dbId }.toSet()
            parts.forEach { roDao.close(it.dbId) }
            orders.removeAll { it.dbId in partIds }
            updateTableStatus(parent, section, "Available")
            subTableDao.clearForParent(parent, section)
        }
    }

    /**
     * Every table code currently carried on some other order's bill through a merge.
     *
     * These are tables with no order of their own that are nonetheless occupied: their
     * items were moved onto the table they were merged with, and they are released when
     * THAT order is settled - see settlePaidOrder.
     */
    private fun mergedPartsInUse(): Set<String> =
        orders.flatMap { runCatching { roDao.mergedTablesOf(it.dbId) }.getOrDefault(emptyList()) }
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * One table queued in the merge dialog.
     *
     * [order] is null for a table that is free - it brings no items and only joins the
     * kept bill, which is why the merge cannot simply be a list of orders.
     */
    private data class MergeCandidate(val code: String, val section: String, val order: OrderCard?)

    /** A grid entry: the popup product plus its restaurant attributes (food type + spice). */
    /**
     * The search dropdown over the menu. Held on the fragment so it can be dismissed
     * when the screen goes away - a popup window outlives the view that anchored it.
     */
    private var suggestions: com.example.synergic_pos_offline.utils.SearchSuggestions? = null

    /**
     * One menu item as a suggestion row.
     *
     * The line under the name is what tells two similar dishes apart on a menu:
     * which course it belongs to and how long the kitchen needs for it - the second
     * being a restaurant's own question, and the reason this mapping is not shared
     * with the grocery screen's.
     */
    private fun suggestionOf(gp: GridProduct): com.example.synergic_pos_offline.utils.SearchSuggestions.Item {
        val language = AppLanguage.of(requireContext())
        return com.example.synergic_pos_offline.utils.SearchSuggestions.Item(
            id = gp.product.id,
            name = ProductName.inAppLanguage(language, gp.product.name),
            meta = listOfNotNull(
            gp.product.category.takeIf { it.isNotBlank() },
            gp.prepTime.takeIf { it.isNotBlank() }?.let { t -> if (t.contains("min", true)) t else "$t min" },
            gp.product.sku.takeIf { it.isNotBlank() }?.let { "#$it" }
            // HSN deliberately left out - SearchSuggestions.rank() matches against
            // meta too, so anything printed here is also searchable by it. Search is
            // name / serial no. / barcode only, the same as grocery - see [codes].
        ).joinToString("  ·  "),
        price = "₹ ${money(gp.product.price)}",
        codes = listOfNotNull(
            gp.product.sku.takeIf { it.isNotBlank() },
            gp.barcode.takeIf { it.isNotBlank() }
        ),
        barcode = gp.barcode,
        image = gp.image
        )
    }

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

        val productSort = com.example.synergic_pos_offline.database.GeneralSettingsDao
            .productSort(requireContext())
        val out = mutableListOf<GridProduct>()
        db.query(
            "md_products",
            arrayOf("id", "product_name", "bar_code", "hsn_code", "category_id", "food_type", "spice_level", "availability", "prep_time", "product_image"),
            (if (store != null) "store_id = ?" else null),
            store?.let { arrayOf(it.toString()) },
            null, null, productSort.orderBy
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
        db.query("md_units", arrayOf("unit_symbol", "fraction_flag", "unit_name"),
            "id = ?", arrayOf(unitId.toString()), null, null, null, "1").use { c ->
            // Resolved the way the printed bill resolves it, so the screen and the
            // slip never name the same unit differently.
            if (c.moveToFirst()) return (
                com.example.synergic_pos_offline.database.UnitDao
                    .shortNameOf(c.getString(0), c.getString(2)) to (c.getInt(1) == 1)
                )
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
        // Seven to a row AT LEAST, and more wherever the width allows - the same rule
        // as the grocery sale screen's shelf, so the menu shows as much of itself as it
        // can at once and the same card comes out the same size in both trades.
        com.example.synergic_pos_offline.utils.ProductGrid.attach(rv)
        rv.adapter = adapter
        // Only a page of the menu is drawn at a time; the rest arrives as the grid is
        // scrolled. See GridPager - the filtered list itself is whole, so searching and
        // the category tabs still see every dish.
        val pager = com.example.synergic_pos_offline.utils.GridPager<GridProduct>(rv) { page ->
            adapter.submit(page)
        }

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
            pager.set(allProducts.filter {
                (selectedCat == "All" || it.product.category == selectedCat) &&
                    // Name, SKU (serial number), and barcode only - no HSN.
                    (q.isEmpty() || it.product.name.lowercase().contains(q) ||
                        it.product.sku.contains(q) || it.barcode.contains(q))
            })
        }

        // Typing does two things at once: it narrows the menu behind, as it always
        // has, and it drops the best few matches out of the box itself. The grid is
        // still the answer; the list is the shortcut to the top of it, which matters
        // on a menu deep enough that a match can be three rows below the fold.
        suggestions = com.example.synergic_pos_offline.utils.SearchSuggestions(
            ctx, etSearch, accent
        ) { picked ->
            // Picking a suggestion does exactly what tapping its tile does - the same
            // Direct Add to Cart rule, the same quantity popup when it is off - so an
            // item cannot come onto the order by a different route than the grid's.
            allProducts.firstOrNull { it.product.id == picked.id }?.let { gp ->
                onProductPicked(gp.product) { etSearch.setText("") }
            }
        }

        // A scanned barcode that names one item goes straight onto the order, down the
        // same path a tapped tile takes - Direct Add to Cart and the quantity popup
        // both apply exactly as they do off the grid. Posted, because it fires from
        // inside the search box's own watcher and empties that box as its first act.
        suggestions?.onExactCode = { scanned ->
            allProducts.firstOrNull { it.product.id == scanned.id }?.let { gp ->
                etSearch.post { onProductPicked(gp.product) { etSearch.setText("") } }
            }
        }
        etSearch.addTextChangedListener {
            query = it?.toString().orEmpty()
            refreshProducts?.invoke()
            // Suggested from the WHOLE menu, not the open category: someone who types
            // a dish name has named the dish, and hiding it because a different course
            // is selected would be answering a question they did not ask.
            suggestions?.update(query, allProducts.map(::suggestionOf))
        }
        // A search that has been left behind must not float over the next screen.
        etSearch.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) suggestions?.dismiss() }
        // The keyboard's Search key, and the Enter a hardware scanner sends after a
        // barcode: the query is finished either way, so the keyboard goes and the menu
        // - filtered to what was asked for - is left uncovered.
        etSearch.setOnEditorActionListener { _, actionId, event ->
            val done = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER
            if (done) { suggestions?.dismiss(); suggestions?.hideKeyboard() }
            done
        }

        loadProductsFromDb()   // fills allProducts
        rebuildTabs()
        refreshProducts?.invoke()
    }

    /**
     * A product tapped on the grid. Refuses politely when there is no order to put it
     * on, or the table is already billed; otherwise honours App Settings' Direct Add to
     * Cart - straight in at its default rate, or through the quantity popup.
     *
     * NO FRACTION EXCEPTION. A fractional unit used to force the popup open even with
     * Direct Add on, so the one setting whose whole purpose is "do not stop and ask"
     * stopped and asked - on exactly the products a busy counter taps most.
     *
     * A weighed item goes on as 1 like anything else and is corrected by tapping its
     * line in the cart, where the quantity box opens for a fractional unit whatever
     * Enter Quantity says (see editCartLine). That is also the order the work happens
     * in: the dish is rung up, then it is weighed.
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
        // The rooms themselves, and nothing else: AC, Non AC, Terrace. There is no
        // All chip - a floor plan is read one room at a time, because that is how a
        // room is stood in, and "every table in the building at once" is a view of
        // the plan nobody serves from. It also cost the grid its shape: eight cards
        // to a row lays out AS the room only while the row belongs to one.
        val catNames = loadSectionNames().distinct()
        var selectedCat = catNames.firstOrNull().orEmpty()
        var query = ""

        // Closing the picker without choosing a table means the next order is not a
        // table order - so it becomes a take-away one rather than leaving the screen
        // on nothing. See the dismiss listener below for the one case it does not.
        var pickedATable = false

        val adapter = TableAdapter(accent) { t ->
            pickedATable = true
            dialog.dismiss()
            // A MERGED TABLE OPENS THE BILL IT WAS MERGED INTO.
            //
            // Merging moves the items onto the kept table and closes this one's order,
            // so the table still reads Occupied - its guests are being served - while
            // having no order of its own. Tapping it used to answer "Table 5 A is
            // Occupied" and stop there, which is true and useless: the one thing an
            // occupied table is tapped for is the bill it is on.
            val existing = orderFor(t.code, t.section) ?: orderMergedInto(t.code)
            // Named with its room in every message: the number on its own belongs to
            // one table per section.
            val named = if (t.section.isBlank()) t.code else "${t.code} (${t.section})"
            if (existing != null) {
                selectOrder(existing)
                toast(
                    if (existing.id.equals(t.code, ignoreCase = true)) "Table $named selected"
                    else "Table $named is merged into ${existing.id} — showing that bill"
                )
            } else {
                // Only a free table can start a new order; an occupied/billing one is
                // busy on an order this table picker cannot reach.
                //
                // A SPLIT PART reads its status off the tile. Its status lives in
                // md_subtable, not md_table, so asking TableDao about "4 B" comes back
                // with nothing - which read as "no status recorded", i.e. free, and let
                // a part that was already taken start a second order on itself.
                val status = if (t.code.contains(" ")) t.status
                else TableDao(requireContext()).statusOf(t.code, t.section)
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
            // A search reaches the whole floor, not the open room. With no All chip
            // left to fall back to, a search bounded by one section would come back
            // empty for a table the operator knows is there - just in the next room -
            // and there would be no way to widen it. So typing lifts the section, and
            // the cards name their room while it does; clearing it drops back to the
            // room whose chip is lit.
            val searching = q.isNotEmpty()
            val shown = tables.filter {
                (searching || catNames.isEmpty() || it.section == selectedCat) &&
                    (!searching || it.code.lowercase().contains(q) || it.section.lowercase().contains(q))
            }
            adapter.submit(shown, showSection = searching)
            emptyNote.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            rv.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
            fillTableCounts(counts, shown)
        }

        // Sections read as chips across the top - the categories of this grid. A store
        // with none set up gets no strip at all, rather than an empty band of padding
        // above the grid.
        v.findViewById<View>(R.id.svTableSections).visibility =
            if (catNames.isEmpty()) View.GONE else View.VISIBLE
        val tabViews = linkedMapOf<String, TextView>()
        catNames.forEach { c ->
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

        // Closed without picking a table -> Take Away. Always.
        //
        // The picker is the only way into a dine-in order, so closing it without
        // choosing is the operator saying this order has no table - which is what a
        // take-away is. It opens by itself on the way into this screen, when Dine In is
        // tapped, and again after a KOT goes out, so "close it" is the answer to the
        // same question every time and gets the same reply.
        //
        // Unconditional on purpose. It was briefly limited to the case where no order
        // was selected, so that opening the floor plan mid-service to look at it and
        // closing it again did not abandon the table being served. That is a real
        // trade, and it is settled the other way: one rule the counter can predict
        // beats a rule that depends on what happened to be selected. Nothing is lost
        // either way - the table keeps its order, and it is one tap away in the list.
        //
        // Covers every way out at once: the close button, the back press, a tap
        // outside.
        dialog.setOnDismissListener {
            if (!pickedATable && isAdded) openTakeAway()
        }

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
    private data class StatusLook(val label: String, val color: Int)

    /**
     * How much of [tableCode]'s order has already gone to the kitchen - the quantity,
     * summed across its lines, not the number of lines.
     *
     * Quantity is what a kitchen and a floor both count in: two of one dish is two
     * plates to cook and two to carry, and a card reading "1" for them would be
     * counting something nobody in the building counts.
     *
     * Read from `kotQty` - the part of a line that has been sent - which is the same
     * figure Print KOT acts on, so the count on a card and that button agree.
     *
     * This is all the floor plan says about the kitchen now: a number, and no colour.
     * The card's fill was refined by the same reading and is not any more - see the
     * note in the adapter's bind.
     */
    private fun kotSentQty(tableCode: String, section: String): Double =
        orderFor(tableCode, section)?.items?.sumOf { it.kotQty } ?: 0.0

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
        // Reserved is not among them, and is not in the legend either: no table can
        // be set to it any more (see TableFragment's status list).
        listOf("Available", "Occupied", "Bill Pending").forEach { label ->
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

    /**
     * The same colour taken down towards black, for the border of a card filled with
     * it. Blended rather than listed as a seventh palette entry per status: it is the
     * one colour that is only ever an edge, and a table of hand-picked shades is a
     * table to keep in step every time a status colour moves.
     */
    private fun darken(color: Int, factor: Float = 0.72f): Int =
        ColorUtils.blendARGB(color, Color.BLACK, 1f - factor)

    /** The same colour lifted towards white - the top of a card's gradient. */
    private fun lighten(color: Int, amount: Float = 0.13f): Int =
        ColorUtils.blendARGB(color, Color.WHITE, amount)

    /** The stored status behind a label the strip shows - the inverse of [lookOf]. */
    private fun statusFor(label: String): String =
        if (label == "Bill Pending") "Billing" else label

    private fun lookOf(status: String): StatusLook = when (status.trim().lowercase()) {
        "", "available" -> StatusLook("Available", 0xFF16A34A.toInt())
        "occupied" -> StatusLook("Occupied", 0xFFDC2626.toInt())
        // Written by older builds when a KOT was sent. The table was in use then and is
        // in use now, so it reads as occupied rather than as anything of its own.
        "kot printed" -> StatusLook("Occupied", 0xFFDC2626.toInt())
        // Kept for a table an older build left Reserved, though nothing sets it now
        // and neither the tally nor the legend carries it. Reading it back honestly
        // is still better than showing a table that was held as one that is free;
        // editing its section in the Table master settles it to Available.
        "reserved" -> StatusLook("Reserved", 0xFFF59E0B.toInt())
        "billing" -> StatusLook("Bill Pending", 0xFF2563EB.toInt())
        "cleaning" -> StatusLook("Cleaning", 0xFF0891B2.toInt())
        "blocked" -> StatusLook("Blocked", 0xFF6B7280.toInt())
        // Never fall back to Available: a status this does not know is still not proof
        // the table is free, and showing a taken table as free is the costly mistake.
        else -> StatusLook(status, 0xFF6B7280.toInt())
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

    /**
     * Every table with its section and current status, for the Choose Table grid -
     * with a SPLIT TABLE STANDING AS ITS PARTS rather than as itself.
     *
     * A split table is not one table any more. Its parts are what a waiter serves,
     * bills and settles, so they are what the floor plan has to offer: split table 4
     * three ways and the grid shows 4 A, 4 B and 4 C where the single card used to be,
     * each with its own colour for its own status. Before this the parts were not on
     * the plan at all - they existed only in the Active Orders list, so the one screen
     * a waiter picks a table from could not reach them, and table 4 sat there as a
     * single card whose colour spoke for parts that were doing different things.
     *
     * The parts take the parent's place in the order, so the plan still reads as the
     * room: they appear where table 4 was, lettered in turn, not appended at the end.
     */
    private fun loadTables(): List<TableTile> {
        val db = com.example.synergic_pos_offline.database.DatabaseHelper.getInstance(requireContext()).readableDatabase
        val store = currentStoreId(db)
        val out = mutableListOf<TableTile>()
        val where = if (store != null) "WHERE t.store_id = ?" else ""
        val args = store?.let { arrayOf(it.toString()) }
        val parts = loadSplitParts(db, store)
        db.rawQuery(
            "SELECT t.table_code, COALESCE(s.section_name,'') AS section, COALESCE(t.table_status,'Available'), " +
                "COALESCE(t.seating_capacity, 0), COALESCE(w.waiter_name, ''), t.id " +
                "FROM ${com.example.synergic_pos_offline.database.DatabaseHelper.Tables.MD_TABLE} t " +
                "LEFT JOIN ${com.example.synergic_pos_offline.database.DatabaseHelper.Tables.MD_SECTION} s ON s.id = t.section_id " +
                "LEFT JOIN ${com.example.synergic_pos_offline.database.DatabaseHelper.Tables.MD_WAITERS} w ON w.id = t.waiter_id " +
                "$where ORDER BY s.section_name COLLATE NOCASE, CAST(t.table_code AS INTEGER), t.table_code",
            args
        ).use { c ->
            while (c.moveToNext()) {
                val code = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                val section = c.getString(1).orEmpty()
                val waiter = c.getString(4).orEmpty()
                // Parts are keyed by the table's own row id where they have one, and
                // by the parent code where they do not - parts split before the id was
                // recorded carry no table_id. Same fallback SubTableDao scopes by.
                val mine = parts[c.getLong(5).toString()] ?: parts[code]
                if (mine.isNullOrEmpty()) {
                    out.add(
                        TableTile(
                            code = code, section = section,
                            status = c.getString(2).orEmpty(),
                            capacity = c.getInt(3), waiter = waiter
                        )
                    )
                } else {
                    // NO SEAT COUNT ON A PART. The parent's capacity is the whole
                    // table's; printing it on each of four parts would say the table
                    // seats four times what it does. How a split table's seats divide
                    // is not recorded, so the honest answer is to say nothing.
                    mine.sortedBy { it.second }.forEach { (subCode, _, status) ->
                        out.add(
                            TableTile(
                                code = subCode, section = section,
                                status = status, capacity = 0, waiter = waiter
                            )
                        )
                    }
                }
            }
        }
        return out
    }

    /**
     * Live split parts for the store, grouped by the parent they belong to. Each entry
     * is (sub_code, suffix, status).
     *
     * KEYED UNDER ONE KEY EACH, and that is the whole point of this function. A part
     * that knows its parent's table id is filed under that id ALONE; only a part with
     * no id recorded falls back to being filed under the parent's code.
     *
     * Filing every part under both keys is what made splitting Ac's table 1 appear to
     * split table 1 in every other room. Table codes repeat across rooms - Ac, No Ac
     * and Cabin each have a table 1 - so the code "1" is not a name for one table, and
     * a No Ac table 1 that found nothing under its own id fell through to the code and
     * picked up Ac's parts. The rooms then all showed 1 A and 1 B for a split that had
     * happened in one of them.
     */
    private fun loadSplitParts(
        db: android.database.sqlite.SQLiteDatabase, store: Long?
    ): Map<String, List<Triple<String, String, String>>> {
        val byKey = mutableMapOf<String, MutableList<Triple<String, String, String>>>()
        val where = if (store != null) "WHERE store_id = ?" else ""
        val args = store?.let { arrayOf(it.toString()) }
        db.rawQuery(
            "SELECT sub_code, suffix, COALESCE(table_status,'Available'), parent_code, table_id " +
                "FROM ${com.example.synergic_pos_offline.database.DatabaseHelper.Tables.MD_SUBTABLE} $where",
            args
        ).use { c ->
            while (c.moveToNext()) {
                val sub = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                val row = Triple(sub, c.getString(1).orEmpty(), c.getString(2).orEmpty())
                val key = if (!c.isNull(4)) c.getLong(4).toString()
                else c.getString(3)?.takeIf { it.isNotBlank() } ?: continue
                byKey.getOrPut(key) { mutableListOf() }.add(row)
            }
        }
        return byKey
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
            // One colour drives the whole card, and it is the TABLE'S OWN STATUS -
            // free, taken, waiting to pay. Nothing else.
            //
            // The fill used to be refined by where the order stood with the kitchen,
            // turning an occupied table purple while something was waiting to go and
            // teal once it had gone. That is a second thing to learn from the same
            // patch of colour, and it split "occupied" - the one state the floor plan
            // exists to show - across three shades that all mean the table is taken.
            // The kitchen's business is on the ticket and on the order panel; the
            // floor plan answers whether a table is free.
            val look = lookOf(t.status)

            // The card IS the status: filled with the colour outright, edged in a
            // darker cut of the same so it still has a shape against the white sheet
            // behind it. A pale wash of the colour read as decoration; a solid one
            // reads as the floor, from further away and at a glance.
            //
            // The edge is only a shade down (0.88) rather than the cut the border used
            // to take. At 1dp on a raised card it is there to stop the fill bleeding
            // into the sheet, not to draw a frame around it - a dark line that reads as
            // a frame turns a floor of cards into a floor of boxes.
            (holder.itemView as com.google.android.material.card.MaterialCardView).apply {
                setCardBackgroundColor(look.color)
                strokeColor = darken(look.color, 0.88f)
            }
            // The fill is laid on the body rather than the card, as a gradient: a
            // shade up at the top, a shade down at the foot. The range is deliberately
            // narrow - the card has to stay one colour to the legend, and a wide ramp
            // would make green-on-top read as a different key from green-at-the-foot -
            // but it is enough to give the card a body instead of a flat rectangle.
            //
            // The drawable carries the card's own corner radius as well as the colour.
            // The card would clip it to that shape anyway; matching it here means the
            // corners are right whether the clip happens or not.
            holder.itemView.findViewById<LinearLayout>(R.id.llTableBody).background =
                android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(lighten(look.color, 0.13f), darken(look.color, 0.90f))
                ).apply { cornerRadius = dp(16).toFloat() }
            // The number alone, set large - the card is already labelled "table" by
            // being on this grid. A coded one (a take-away token, a split part like
            // "5 A") is already a name, so it stands as it is, a size down to fit.
            holder.itemView.findViewById<TextView>(R.id.tvTableCode).apply {
                val numbered = t.code.all { it.isDigit() }
                text = t.code
                textSize = if (numbered) 30f else 20f
                setTextColor(Color.WHITE)
            }
            // Which room, and how many seats. The room shows while a search is crossing
            // rooms: table numbers restart in every section, so two cards can both say
            // "1" and the line under the number is what tells them apart.
            //
            // INVISIBLE when there is nothing to say, never GONE. A card that collapses
            // this line is shorter than the card beside it, and a row of eight that
            // steps up and down reads as if the steps meant something.
            holder.itemView.findViewById<TextView>(R.id.tvTableSeats).apply {
                val bits = mutableListOf<String>()
                if (showSection && t.section.isNotBlank()) bits.add(t.section)
                if (t.capacity > 0) bits.add("${t.capacity} seats")
                text = bits.joinToString("  ·  ")
                visibility = if (bits.isEmpty()) View.INVISIBLE else View.VISIBLE
                setTextColor(ColorUtils.setAlphaComponent(Color.WHITE, 0xB3))
            }
            // Tinted white and held back by the layout's own alpha - see the note on
            // it there: out at the corner it says "table" at a distance, and the
            // number in the body says which.
            holder.itemView.findViewById<android.widget.ImageView>(R.id.ivTableIcon)
                .imageTintList = ColorStateList.valueOf(Color.WHITE)
            // Nothing on the card spells the status out: [look] IS the card, kitchen
            // and all, and the legend under the grid keys it.
            //
            // What a fill cannot give is a number, so the count of what has gone to the
            // kitchen stays. Its digits take the card's own colour a shade down rather
            // than a fixed teal: on a white pill that reads against every fill, and it
            // ties the count to the card it is counting for.
            holder.itemView.findViewById<TextView>(R.id.tvKotCount).apply {
                val sent = kotSentQty(t.code, t.section)
                setTextColor(darken(look.color, 0.85f))
                if (sent <= 0.0) visibility = View.GONE
                else {
                    text = qtyText(sent)
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
            val language = AppLanguage.of(holder.itemView.context)
            holder.itemView.findViewById<TextView>(R.id.tvName).text = ProductName.inAppLanguage(language, p.name)
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
        // "Enter Quantity" (General Settings) decides whether the quantity can be
        // TYPED on this popup:
        //
        //   on  - the box is open, and opens on 1 with that 1 selected, so typing a
        //         figure replaces it and confirming without typing still adds one.
        //   off - the box is fixed at 1. The dish goes on as a single, and quantity is
        //         adjusted afterwards with the cart line's own steppers.
        //
        // NO FRACTION EXCEPTION HERE. Adding from the menu puts the dish on as a
        // single and nothing more; a fractional unit is weighed afterwards, by tapping
        // the line in the cart and typing the figure there - see editCartLine, which
        // does carry the exception.
        //
        // Splitting it that way keeps the menu grid quick. Ordering is mostly tapping
        // tiles, and a popup that stops for a decimal on every dish priced by weight
        // would slow the common case down for the sake of the uncommon one - the more
        // so with Enter Quantity deliberately switched off, which is a shop saying it
        // does not want to be asked.
        val quantityStatusOn = com.example.synergic_pos_offline.utils.SettingsCache
            .value(requireContext(), "G", "Quantity Status") == "1"
        val qtyEditable = quantityStatusOn
        // Manual Rate (App Settings) governs the rate box the same way it does in
        // grocery. It was not being passed at all, so the restaurant let a rate be
        // re-typed on every dish whatever the shop had set.
        val manualRateOn = com.example.synergic_pos_offline.utils.SettingsCache
            .value(requireContext(), "A", "Manual Rate") == "1"
        ProductEntryDialog.show(
            context = requireContext(),
            inflater = layoutInflater,
            product = p,
            startRate = p.price,
            // Always 1, never 0. Opening at zero makes an untouched confirm add
            // nothing, and the common case at a counter is one of something.
            startQty = 1.0,
            confirmLabel = "Add to cart",
            qtyEditable = qtyEditable,
            // Cursor in the quantity with the 1 selected, so the first key typed
            // replaces it rather than making 11.
            focusQty = qtyEditable,
            focusRate = manualRateOn,
            rateEditable = manualRateOn,
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
     * Reopens a cart line in the product popup so its quantity or rate can be changed.
     *
     * [pendingOnly] follows the list the tap came from. The order panel shows the round
     * that has NOT gone to the kitchen, and states that round's quantity - so the popup
     * opens on the same figure the row showed, and what comes back is added to what was
     * already sent rather than replacing it. The More popup lists whole lines, so there
     * it is the whole line that opens.
     *
     * Without that, tapping a line reading "1" on a table that had already been sent 2
     * would open on 3 and, on confirm, silently cancel two plates the kitchen was
     * cooking.
     */
    private fun editCartLine(item: CartItem, pendingOnly: Boolean) {
        val order = currentOrder() ?: return
        if (order.completed) return toast("Table already billed")
        // The popup is built from the MENU entry, which carries the photo, the unit,
        // the fraction rule and the tax rates it needs. A line whose dish has since
        // been removed from the menu has no such entry - the line stays on the order
        // and stays billable, it just cannot be reopened here.
        val p = allProducts.firstOrNull { it.product.id == item.productId.toString() }?.product
            ?: return toast("${item.name} is no longer on the menu")

        val quantityStatusOn = com.example.synergic_pos_offline.utils.SettingsCache
            .value(requireContext(), "G", "Quantity Status") == "1"
        val shown = if (pendingOnly) item.pending else item.qty
        ProductEntryDialog.show(
            context = requireContext(),
            inflater = layoutInflater,
            product = p,
            startRate = item.rate,
            startQty = shown,
            confirmLabel = "Update",
            // THE SAME RULE AS THE ADD POPUP. "Enter Quantity" is about whether a
            // quantity is ever typed on this till, not about which popup is open - so
            // with it off the box is fixed here too, and the cart line's steppers are
            // how a quantity moves. Left open, this popup was a way round the setting:
            // tap the line, type anything.
            //
            // A fractional unit still opens it, for the reason it does on the add.
            qtyEditable = quantityStatusOn || p.allowFraction,
            focusQty = quantityStatusOn || p.allowFraction,
            // Manual Rate still governs the rate, the same as on the add above - a
            // line's price is no more re-typeable after the fact than it was when the
            // dish went on.
            rateEditable = com.example.synergic_pos_offline.utils.SettingsCache
                .value(requireContext(), "A", "Manual Rate") == "1",
            taxRegime = taxRegime,
            taxInclusive = taxInclusive,
            itemwiseDiscountActive = itemwiseDiscountActive,
            discountPreTax = discountPreTax
        ) { qty, rate ->
            // Back to a whole-line quantity: on the panel the figure typed is the new
            // pending round, and what the kitchen already has stays on top of it.
            val newQty = if (pendingOnly) item.kotQty + qty else qty
            if (newQty > item.qty && exceedsStock(item.productId.toString(), newQty, item.dbItemId)) {
                return@show
            }
            roDao.setItemLine(item.dbItemId, newQty, rate)
            reloadItems(order)
            renderCart()
            fillAgain()   // the More popup, if it is the one open behind this
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
        // The VAT rate goes on with the GST ones. Without it a VAT-rated dish was
        // stored on the order rated at zero, and no later step could recover it.
        // The product's own discount goes on with the rates, for the same reason: an
        // item-wise discount is a fact about what was sold, and reading it off the
        // master at billing time would bill a table at a discount it never had.
        roDao.addItem(
            order.dbId, p.id.toLongOrNull() ?: 0L, p.name, qty, rate,
            p.cgst, p.sgst, p.vat, p.discValue, p.discType
        )
        // ADDING AN ITEM NO LONGER TAKES THE TABLE.
        //
        // It used to mark the table Occupied on its first item. The moment is now the
        // KOT going out (see doPrintKot): until then the order is still being built at
        // the till and can be emptied, cancelled or abandoned without anybody having
        // been served, and a table showing red for a basket nobody committed to is a
        // table the next waiter cannot take. Once the kitchen has the ticket, food is
        // being cooked for that table and it genuinely is taken.
        reloadItems(order)
        renderCart()
    }

    /** "N item(s) added" for the running Direct-Add-to-Cart toast; [total] is the
     *  order's total quantity, shown whole when it has no fraction. */
    private fun itemsAddedMessage(total: Double): String {
        val display = if (total % 1.0 == 0.0) total.toInt().toString() else total.toString()
        return "$display ${if (total == 1.0) "item" else "items"} added"
    }

    /**
     * Keeps cart rows sized to whatever height the panel currently has.
     *
     * The target is ten lines visible at once. How much room ten lines have is not a
     * constant: it differs between a phone and a 12" tablet, and on one device it
     * changes as the tax fold opens and closes beneath the list. So rather than pick a
     * row height in XML and hope, the panel is measured as it is laid out and the rows
     * are squeezed to suit - see CartDensity for what gives way first.
     *
     * A layout listener rather than a one-off measure at startup, because the height
     * that matters is not the one the panel has when the screen is built. Rows already
     * on screen are re-sized in place, so an order taken with the fold shut does not
     * have to be redrawn from scratch when it opens.
     */
    private fun watchCartHeight(root: View) {
        val scroller = root.findViewById<View>(R.id.svOrderItems) ?: return
        val container = root.findViewById<LinearLayout>(R.id.llOrderItems)
        scroller.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            val scale = CartDensity.scaleFor(
                v.height - v.paddingTop - v.paddingBottom,
                v.resources.displayMetrics.density
            )
            if (kotlin.math.abs(scale - cartRowScale) < 0.01f) return@addOnLayoutChangeListener
            cartRowScale = scale
            for (i in 0 until container.childCount) {
                CartDensity.apply(container.getChildAt(i), scale)
            }
            container.requestLayout()
        }
    }

    /** Rebuilds the order-item rows from the SELECTED order's cart and recomputes totals. */
    private fun renderCart() {
        val root = view ?: return
        val container = root.findViewById<LinearLayout>(R.id.llOrderItems)
        val inflater = LayoutInflater.from(requireContext())
        val accent = ThemeManager.getThemeColor(requireContext())
        val order = currentOrder()
        val locked = order?.completed == true      // billed → read-only
        val all = order?.items ?: emptyList<CartItem>()

        // THE PANEL SHOWS WHAT HAS NOT GONE TO THE KITCHEN YET.
        //
        // Once a round is sent, the cart becomes the NEXT round. A waiter standing at
        // a table that has already ordered is taking what comes after it, and a list
        // that keeps every course already cooking pushes the two or three lines they
        // are actually working on off the bottom of a narrow panel.
        //
        // One rule covers both states rather than a flag: a line shows while it has
        // quantity that has not been sent. Before any KOT every line qualifies, so a
        // fresh order builds up exactly as it always did; after one, only what was
        // added since. A line whose quantity was raised after its KOT still shows,
        // because part of it is still waiting to go.
        //
        // NOTHING IS HIDDEN FROM THE BILL. The totals below, the KOT, the printed bill
        // and the More popup all read order.items - the whole order - and only this
        // list is narrowed. See updateTotals and showOrderItemsDialog.
        val cart = if (locked) all else all.filter { it.pending > 0.0 }
        val sentOnly = all.size - cart.size

        // Says where the rest of the order is, on the header and in the list's own
        // empty space, so a short list under a busy table never reads as lost items.
        root.findViewById<TextView>(R.id.tvOrderItemsHeader).text =
            if (sentOnly > 0) "ORDER ITEMS  ·  $sentOnly SENT" else "ORDER ITEMS"
        root.findViewById<TextView>(R.id.tvCartAllSent).visibility =
            if (cart.isEmpty() && all.isNotEmpty() && !locked) View.VISIBLE else View.GONE

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
            // showPending only while the order is live. A billed one has no next round
            // to build, so its panel states the whole line the bill was made from.
            bindOrderRow(row, item, locked, accent, showPending = !locked) { renderCart() }
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
        // No showPending: this builds the More popup's rows, which state the whole
        // order because that is what the bill is made from.
        .also { bindOrderRow(it, item, locked, accent, onChanged = onChanged) }

    /**
     * An empty row of [layoutRes], themed once. A row's colours come from the theme
     * and its numbers from the item, so the theme pass belongs here - with the
     * inflate, which happens once per line - and not in the bind, which happens on
     * every tap.
     */
    private fun newOrderRow(inflater: LayoutInflater, parent: LinearLayout, layoutRes: Int): View =
        inflater.inflate(layoutRes, parent, false).also {
            ThemeManager.applyTheme(it)
            // Only the panel's own narrow row is squeezed; the More popup is roomy by
            // definition and its wide row has no business being compressed.
            if (layoutRes == R.layout.item_order_line_compact) CartDensity.apply(it, cartRowScale)
        }

    /** Fills a row (new or reused) with one cart line and wires its steppers. */
    private fun bindOrderRow(
        row: View,
        item: CartItem,
        locked: Boolean,
        accent: Int,
        /**
         * Whether this row states the line's PENDING quantity rather than its total.
         *
         * True on the order panel, which is the next round to go to the kitchen: send
         * one biryani, add another, and the panel says 1 - the one still to go - not 2.
         * Two would be counting the plate already on the pass, and a waiter reading the
         * panel to see what the kitchen still owes them would send it again.
         *
         * False in the More popup, which is the WHOLE order and has to add up to the
         * bill. The same line reads 2 there, marked as one sent and one new.
         */
        showPending: Boolean = false,
        onChanged: () -> Unit
    ) {
        val order = currentOrder()
        // What this row is about: the outstanding part on the panel, the whole line
        // everywhere else. The amount follows the quantity, so a line always reads as
        // its own qty × rate rather than quoting a total for a quantity not shown.
        val shownQty = if (showPending) item.pending else item.qty
        row.findViewById<TextView>(R.id.tvLineName).text = item.name
        row.findViewById<TextView>(R.id.tvLineQty).text = qtyText(shownQty)
        row.findViewById<TextView>(R.id.tvLineRate).text = money(item.rate)
        row.findViewById<TextView>(R.id.tvLineAmount).text = money(shownQty * item.rate)
        row.findViewById<TextView>(R.id.tvLineNote).apply {
            when {
                // On the panel the quantity IS the pending count, so "NEW ×n" would
                // say it twice. What it cannot show is the part already gone, and on a
                // line that was topped up that is the thing worth knowing.
                showPending && item.kotQty > 0.0 -> {
                    text = "${qtyText(item.kotQty)} sent"; setTextColor(0xFF9AA0A6.toInt())
                }
                showPending -> { text = "NEW"; setTextColor(accent) }
                // In the popup the quantity is the whole line, so the split is spelled out.
                item.pending > 0.0 -> { text = "NEW ×${qtyText(item.pending)}"; setTextColor(accent) }
                else -> { text = "✓ Sent"; setTextColor(0xFF9AA0A6.toInt()) }
            }
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
        // TAP THE LINE TO OPEN IT, the way the grocery cart does. The steppers move a
        // quantity by one; this is for the rest - a quantity typed rather than tapped
        // to, and a rate priced by hand where App Settings allows it. Same popup the
        // menu tile opens, so a line is corrected in the screen it was created in.
        //
        // Off on a billed order, which is read-only, and off on the row's own controls:
        // the stepper and the bin sit inside the row, and a tap on either must not also
        // open the popup behind it.
        row.setOnClickListener(if (locked) null else View.OnClickListener {
            editCartLine(item, showPending)
        })
        row.isClickable = !locked

        if (!locked) {
            btnPlus.setOnClickListener {
                // Only a step up can outrun the shelf; stepping down never can.
                if (exceedsStock(item.productId.toString(), item.qty + 1.0, item.dbItemId)) {
                    return@setOnClickListener
                }
                roDao.setItemQty(item.dbItemId, item.qty + 1.0); order?.let { reloadItems(it) }; onChanged()
            }
            btnMinus.setOnClickListener {
                // The panel steps down only as far as what has been sent. Below that
                // it would start cancelling food the kitchen is already cooking, and
                // the panel is where the NEXT round is built - cancelling a sent dish
                // is a deliberate act that belongs in the More popup.
                val floor = if (showPending) item.kotQty else 0.0
                roDao.setItemQty(item.dbItemId, (item.qty - 1.0).coerceAtLeast(floor))
                order?.let { reloadItems(it) }; onChanged()
            }
            btnRemove.setOnClickListener {
                if (showPending) {
                    // Drops what has not gone yet and leaves what has. On a wholly new
                    // line that is a quantity of zero, which deletes the row outright -
                    // so one call covers both without asking which kind this is.
                    roDao.setItemQty(item.dbItemId, item.kotQty)
                } else {
                    // The popup removes the line entirely, sent quantity included, and
                    // keeps it at zero so the cancellation can be printed.
                    roDao.removeItem(item.dbItemId)
                }
                order?.let { reloadItems(it) }; onChanged()
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
    /**
     * How tightly cart rows are drawn, 1 roomy … 0 tight - see CartDensity. Recomputed
     * whenever the panel changes height, which is both at first layout and every time
     * the tax fold opens or closes underneath it.
     */
    private var cartRowScale = 1f

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
    /**
     * Runs [then] once a TAKE-AWAY order has a customer on it, asking for one first if
     * it has none. Any other order type goes straight through.
     *
     * A take-away bill carries the customer's name and number (see [buildBillDraft]),
     * and a slip printed without them is a slip nobody can be matched to when they
     * come back to the counter to collect - which is the whole reason the counter
     * takes a name. So the bill is where the details stop being optional.
     *
     * NOT at the start of the order. Skip is offered there on purpose - a queue does
     * not wait for an address, and the order still has to be able to be taken. Asking
     * again here, once, is what makes both true: the order starts immediately, and the
     * bill still goes out with a customer on it.
     *
     * Nothing prints if the prompt is turned down. That is what "no details, no bill"
     * means, and it is said out loud rather than silently doing nothing.
     */
    private fun withCustomerIfTakeAway(order: OrderCard, then: () -> Unit) {
        // Nothing to require where the till keeps no customers. With General Settings ▸
        // Customer Info off there is no customer list to look anybody up in, so
        // insisting on details before a bill would be refusing to print over a record
        // the shop has chosen not to keep.
        if (!customerInfoOn()) return then()
        if (!order.type.equals("Take Away", ignoreCase = true) || order.phone.isNotBlank()) {
            return then()
        }
        com.example.synergic_pos_offline.utils.CustomerPrompt.showDetails(
            context = requireContext(),
            title = "Customer details — needed for the bill",
            positiveText = "Save & Print",
            // NO SKIP HERE, unlike the prompt that starts the order. There is nothing
            // for a skip to mean at this point: the operator has asked for a bill, and
            // a take-away bill without a customer is the thing being prevented.
            showSkip = false,
            onCancel = { toast("Bill not printed — a take-away bill needs customer details") },
            onPicked = { c ->
                // Kept on the running order, not just in memory: the bill is written
                // from the order, reprints read it back, and the number has to survive
                // the screen being left and come back with the order.
                order.phone = c.phone
                roDao.setPhone(order.dbId, c.phone)
                showOrderDetail(order)   // the header stops saying "Walk-in"
                then()
            }
        )
    }

    /**
     * Settles a take-away order in place: how it was paid, what was handed over, done.
     *
     * The same settlement the checkout screen performs - it ends in [settlePaidOrder]
     * exactly as the checkout's result listener does, so the bill is written, the
     * token closed and the stock moved by the one path that has always done it. What
     * is different is only where the operator stands while answering.
     *
     * The itemised list the checkout screen shows is deliberately not repeated. The
     * order is on the panel behind this dialog, rung up moments ago in front of the
     * person paying; the two questions worth asking at the counter are how they are
     * paying and what they handed over.
     */
    private fun showQuickPayment(order: OrderCard) {
        val ctx = com.example.synergic_pos_offline.utils.FixedFontScale.wrap(requireContext())
        val accent = ThemeManager.getThemeColor(ctx)
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_quick_payment, null)
        val dialog = AlertDialog.Builder(ctx).setView(v).create()
            .also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Rounded here rather than at settlement, so what the operator reads, what
        // gets validated against the cash handed over, and what persistBill() later
        // charges are all the one figure - not the exact total with the rounding
        // sprung on the counter only once the bill is saved.
        val total = payableTotal(computeBill(order.items, serviceRateFor(order.section), order.type).total)
        v.findViewById<TextView>(R.id.tvQpTotal).text = "₹ ${money(total)}"
        v.findViewById<TextView>(R.id.tvQpSubtitle).text = buildString {
            append(order.id.replace("TA-", "Token #"))
            val who = customerNameFor(order.phone)?.takeIf { it.isNotBlank() } ?: order.phone
            if (who.isNotBlank()) append("  ·  ").append(who)
        }

        val etTendered = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etQpTendered)
        val tvChange = v.findViewById<TextView>(R.id.tvQpChange)
        val llCash = v.findViewById<LinearLayout>(R.id.llQpCash)

        var payMethod = "Cash"
        val methods = mapOf(
            R.id.btnQpCash to "Cash", R.id.btnQpCard to "Card", R.id.btnQpOnline to "Online"
        )
        fun paintMethods() {
            methods.forEach { (id, name) ->
                v.findViewById<com.google.android.material.button.MaterialButton>(id).apply {
                    val on = name == payMethod
                    backgroundTintList = ColorStateList.valueOf(if (on) accent else Color.TRANSPARENT)
                    setTextColor(if (on) Color.WHITE else accent)
                    strokeColor = ColorStateList.valueOf(accent)
                    strokeWidth = if (on) 0 else (resources.displayMetrics.density * 1.2f).toInt()
                }
            }
            // Tendered and change belong to cash alone - a card or a UPI transfer is
            // paid to the penny by the machine, so the box would be asking a question
            // with only one answer. The QR appears on the same rule, in reverse.
            llCash.visibility = if (payMethod == "Cash") View.VISIBLE else View.GONE
            com.example.synergic_pos_offline.utils.CheckoutUpiQr.bind(
                v, total, online = payMethod == "Online"
            )
        }
        methods.forEach { (id, name) ->
            v.findViewById<com.google.android.material.button.MaterialButton>(id).setOnClickListener {
                payMethod = name; paintMethods()
            }
        }

        // Pre-filled with the exact amount, the way the checkout screen fills it: the
        // common case is the customer handing over the total, and a counter that has
        // to retype the figure it was just shown is being asked to do the till's work.
        // UNGROUPED. money() puts a thousands comma in - "1,491.00" - which is right
        // for a figure being read and wrong for one that has to be read BACK: the
        // parse stopped at the comma, came back null, fell through to 0, and told a
        // counter that the exact total it had just been shown was less than the amount
        // due. Anything over ₹999 was unsettleable. See Amounts.
        val tilTendered = v.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilQpTendered)
        fun showChange() {
            val tendered = com.example.synergic_pos_offline.utils.Amounts.parse(etTendered.text?.toString())
            tvChange.text =
                if (tendered != null && tendered >= total) "₹ ${money(tendered - total)}" else "—"
        }
        etTendered.setText(com.example.synergic_pos_offline.utils.Amounts.editable(total))
        etTendered.addTextChangedListener {
            tilTendered.error = null   // clear the complaint as soon as it is being answered
            showChange()
        }
        // Driven by the same function as every later edit, rather than a fixed string:
        // the pre-filled amount is a real entry and its change is worked out like any
        // other. It was hard-coded to zero, which was right only by coincidence.
        showChange()

        val btnConfirm = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFormPositive)
        val btnCancel = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFormNegative)
        ThemeManager.applyTheme(v)
        ThemeManager.styleDialogButtons(btnConfirm, btnCancel)
        paintMethods()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            // Cash short of the total is a mis-key, not a part payment: the counter
            // does not hand food over for less than the bill, and settling it here
            // would write a paid bill for money nobody received.
            val tendered = if (payMethod == "Cash")
                com.example.synergic_pos_offline.utils.Amounts.parse(etTendered.text?.toString()) ?: 0.0
            else 0.0
            if (payMethod == "Cash" && tendered < total) {
                tilTendered.error = "Less than the amount due"
                return@setOnClickListener
            }
            dialog.dismiss()
            // Print, then settle - the two halves of "Print & Settlement", in that
            // order. The slip is cut first so it carries the mode that was just
            // chosen; settling second because it is what closes the token and takes
            // the stock off.
            printThenSettle(order, payMethod, tendered)

            // BACK TO THE FLOOR PLAN, not into another take-away.
            //
            // A settled order leaves the operator needing to be somewhere, and the
            // table picker is the one screen every next order starts from - the same
            // place Print KOT returns to, and the same place the screen opens on. It
            // does not assume what comes next: the next customer may be a table, and
            // cutting a fresh token for them would have been an order nobody asked for
            // and a number spent before anyone stood at the counter.
            //
            // Take Away is still one tap from here - closing the picker without
            // choosing a table opens a counter order, which is exactly the case this
            // used to force.
            //
            // Posted, so the settle's own repaint and its toast land before the picker
            // comes up over them.
            view?.post { if (isAdded) showChooseTableDialog() }
        }
        dialog.show()
    }

    /**
     * The take-away "Print & Settlement": cuts the bill, then settles the order.
     *
     * NEITHER HALF IS THE TABLE FLOW'S. [doPrintBill] exists for a table, where the
     * bill goes out before the money comes in - so it carries no payment mode, takes
     * the stock off on the spot and locks the order to wait for payment. None of that
     * fits a counter: the mode is known (it was just chosen), and settling immediately
     * afterwards is what takes the stock off and closes the token. Reusing it here
     * would deduct the stock twice over and lock an order a line later.
     *
     * Settling happens whether or not the slip could be printed. The customer has paid
     * and walked off with the food; a till that refused to record that because no
     * printer was configured would be holding an order open for a sale that is over.
     */
    private fun printThenSettle(order: OrderCard, payMethod: String, tendered: Double) {
        val printers = com.example.synergic_pos_offline.database.OperatingPrinterDao(requireContext())
            .getAll().filter { it.printFlag.equals("B", ignoreCase = true) }
        val settle = {
            settlePaidOrder(order, payMethod, tendered)
            loadProductsFromDb()   // stock has moved
        }
        val default = printers.firstOrNull { it.isDefault }
        when {
            printers.isEmpty() -> {
                toast("No bill printer set up — payment saved without a printed bill")
                settle()
            }
            default != null -> { printSettledBill(order, default, payMethod, tendered); settle() }
            // Settling waits for the choice, so the printed slip and the saved bill
            // are the same sale rather than two things racing each other.
            else -> showPrinterChooser(printers, "Select bill printer") { p ->
                printSettledBill(order, p, payMethod, tendered); settle()
            }
        }
    }

    /**
     * The counter's receipt: this order, on [printer], carrying the mode it was paid
     * by and the cash handed over. Numbered with the bill number the settlement is
     * about to write, so the slip in the customer's hand and the row in Bill history
     * agree.
     */
    private fun printSettledBill(
        order: OrderCard,
        printer: com.example.synergic_pos_offline.database.OperatingPrinterDao.OperatingPrinter,
        payMethod: String,
        tendered: Double
    ) {
        // Reserved the same way the table bill reserves it. The settlement follows a
        // line later, so the window is small - but it is not zero, and a second till
        // ringing up at the same moment is exactly the case a counter has.
        val next = com.example.synergic_pos_offline.database.BillDao(requireContext()).nextNumber()
        roDao.setBillSeq(order.dbId, next.seq)
        printGroceryStyleBill(order, printer, billNumber = next.number, payment = payMethod, tendered = tendered)
    }

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
        val renderer = com.example.synergic_pos_offline.utils.BillReceiptRenderer(requireContext())
        // One continuous bitmap even when the order mixes GST and VAT lines - see
        // BillReceiptRenderer.populate()'s own demarcation between them ("BILL NO:
        // NA"), each with its own summary, one grand total at the foot of the whole
        // thing. Not cut apart into two slips - see BillPrinter.copiesFor's comment
        // on why that is NOT what this does.
        val first = renderer.renderDraftToBitmap(draft, config.paperDots)
            ?: run { toast("Could not render the bill"); return }

        // Bill Settings' "Two Copy" toggle, which this path did not honour at all -
        // Print Bill in a restaurant put out one slip however the setting was set,
        // while the grocery checkout put out two. Restaurant bills print from a DRAFT
        // (the sale is not written until payment is confirmed), so BillPrinter's
        // receipt-number version cannot be used here; the pair is built the same way
        // it builds one, and for the same reason: the customer's copy carries the
        // bill's own caption, the shop's is stamped DUPLICATE, and two identical
        // originals would be two documents each claiming to be the bill.
        val twoCopy = com.example.synergic_pos_offline.database.BillSettingsDao(requireContext())
            .load().twoCopyBill
        val second = if (twoCopy) {
            renderer.renderDraftToBitmap(draft, config.paperDots, duplicate = true) ?: first
        } else null
        // The counter coupons, after every copy of the bill - the same slips the
        // grocery path gets from BillPrinter.copiesFor. They are built from the order
        // in hand rather than from the sale, because there is no sale yet: a
        // restaurant bill prints from a draft and the transaction tables are written
        // only when payment is confirmed. The category comes off the catalogue the
        // grid was built from, which already carries it.
        val coupons = com.example.synergic_pos_offline.utils.CouponPrinter.couponsFrom(
            requireContext(),
            order.items.map { line ->
                com.example.synergic_pos_offline.utils.CouponPrinter.CategorisedLine(
                    category = allProducts
                        .firstOrNull { it.product.id == line.productId.toString() }
                        ?.product?.category.orEmpty(),
                    name = line.name,
                    quantity = line.qty
                )
            },
            billNumber = billNumber,
            dateTime = draft.dateTime,
            paperDots = config.paperDots
        )
        val copies = listOfNotNull(first, second) + coupons

        com.example.synergic_pos_offline.utils.ThermalPrinter
            .printSequence(requireContext(), copies, config) { result ->
                toast(when (result) {
                    is com.example.synergic_pos_offline.utils.ThermalPrinter.Result.Success -> "Bill printed at ${printer.printerName}"
                    is com.example.synergic_pos_offline.utils.ThermalPrinter.Result.Sent -> "Bill sent to ${printer.printerName}"
                    is com.example.synergic_pos_offline.utils.ThermalPrinter.Result.Failure -> "Bill print failed: ${result.message}"
                })
                // distinct(), because the fallback above can put the same bitmap in
                // twice - recycling it a second time would throw.
                copies.distinct().forEach { it.recycle() }
            }
    }

    /**
     * The customer's total outstanding balance (md_customers.balance_amount) matched by
     * [phone], or null when there is no matching customer / nothing owed - for the
     * OUTSTANDING line printed with the totals on the restaurant bill.
     */
    /**
     * The name on file for [phone], or null when there is none to print.
     *
     * Null rather than a placeholder: a blank name leaves the bill's name line out
     * entirely, where "Guest" or "Walk-in" would print a word the customer never gave
     * and make an anonymous sale look like a named one.
     */
    private fun customerNameFor(phone: String): String? =
        customerFor(phone)?.name?.takeIf { it.isNotBlank() }

    /**
     * The customer filed under [phone], or null for a walk-in.
     *
     * An order carries only a phone number - that is what identifies a customer - so
     * everything else the slip prints about them is read back off the customer list
     * they were filed in when the order was taken.
     */
    private fun customerFor(phone: String): com.example.synergic_pos_offline.database.CustomerDao.Customer? {
        if (phone.isBlank()) return null
        return runCatching {
            com.example.synergic_pos_offline.database.CustomerDao(requireContext()).findByPhone(phone)
        }.getOrNull()
    }

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
        val b = computeBill(order.items, serviceRateFor(order.section), order.type)
        val chargeOrderType = if (order.type.equals("Take Away", ignoreCase = true)) "TAKEAWAY" else "DINE_IN"
        val items = order.items.map { line ->
            // HSN comes off the catalogue, because a cart line does not carry one: the
            // order stores what was sold and at what price, and the tax code belongs to
            // the product rather than to the sale of it.
            //
            // This was missing, and it is why Bill Settings' HSN Code did nothing in
            // Restaurant mode however it was switched. A restaurant bill prints from
            // this draft - the sale has not been written when the slip comes out - and
            // a draft with no HSN on its lines has nothing for the renderer to print,
            // whatever the setting says. A REPRINT from Bill History looked right,
            // because that path reads the saved lines and joins md_products for the
            // code, which is what made the fault look intermittent.
            val product = allProducts.firstOrNull { it.product.id == line.productId.toString() }?.product
            com.example.synergic_pos_offline.utils.BillReceiptRenderer.Draft.Item(
                name = line.name, quantity = line.qty, rate = line.rate,
                cgstRate = line.cgstRate, sgstRate = line.sgstRate,
                vatRate = line.vatRate,
                hsn = product?.hsn,
                // This line's share of the discount, in the shape BillPricing takes -
                // against the line's raw pre-tax base. Without it the slip priced every
                // line at full and then showed a discounted total, so the printed lines
                // did not add up to the figure at the foot of the bill.
                discountAmount = com.example.synergic_pos_offline.utils.CartMath.lineDiscount(
                    line.toMathLine(), cartConfig(),
                    com.example.synergic_pos_offline.utils.CartMath
                        .subtotal(order.items.map { it.toMathLine() }),
                    b.discount
                ),
                // The unit, already resolved to its short name or the first three
                // characters of its name - see UnitDao.shortNameOf, applied where the
                // menu is loaded. The draft never carried one, so the table bill
                // printed a bare quantity while the same sale reprinted from Bill
                // History (which reads the unit off the database) showed it.
                unit = product?.unit
            )
        }
        // The table as the bill's own field - see Draft.table. Carries its section,
        // since a table number repeats in every section.
        val who = customerFor(order.phone)
        val billTable = if (order.type.equals("Take Away", ignoreCase = true))
            "Take Away ${order.id.replace("TA-", "Token #")}"
        else if (order.section.isBlank()) order.id
        else "${order.id} (${order.section})"
        val now = java.text.SimpleDateFormat("dd-MM-yyyy hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
        return com.example.synergic_pos_offline.utils.BillReceiptRenderer.Draft(
            billNumber = billNumber,
            dateTime = now,
            cashier = order.cashier,
            // The customer, by NAME as well as number. The order only carries a phone -
            // that is what identifies them - so the name is read off the customer list
            // it was filed in when the order was started. Without this the slip could
            // only ever show a number, whatever Bill Settings asked for, because the
            // draft had no name in it to print.
            customer = com.example.synergic_pos_offline.utils.BillReceiptRenderer.Draft.Customer(
                name = who?.name?.takeIf { it.isNotBlank() },
                phone = order.phone.takeIf { it.isNotBlank() },
                // Read once, alongside the name. Without it the slip had no address in
                // it to print at all, so a take-away - where the address is part of the
                // order, not a detail about the customer - went out without one.
                address = who?.address?.takeIf { it.isNotBlank() },
                outstanding = customerOutstanding(order.phone)
            ),
            table = billTable,
            items = items,
            // The slip shows what came off, whichever way it was arrived at.
            discount = b.discount, roundOff = roundOffAmount(b.total), netAmount = payableTotal(b.total),
            paymentModes = if (payment.isNotBlank()) listOf(payment.uppercase(java.util.Locale.US)) else emptyList(),
            serviceCharge = b.service,   // shown as its own totals line, not an item
            // The figures already quoted on the order panel, handed to the slip rather
            // than worked out again - so what prints is what the customer was told.
            charges = b.charges.map { it.name to it.amount },
            chargeTypes = b.charges.map { it.type.name },
            chargeApplicabilities = b.charges.map { it.applicability.name },
            orderType = chargeOrderType,
            returnAmount = (tendered - payableTotal(b.total)).coerceAtLeast(0.0)   // cash to hand back
        )
    }

    /** Prints the (provisional) bill in grocery format, then completes the table. */
    private fun doPrintBill(
        order: OrderCard,
        printer: com.example.synergic_pos_offline.database.OperatingPrinterDao.OperatingPrinter
    ) {
        // THE NUMBER IS RESERVED HERE, not asked for again at settlement.
        //
        // A table bill is printed minutes before it is paid, and the bill row is only
        // written when it is. Nothing used to hold the number across that gap: it was
        // worked out at print, worked out again at save, and both readings came off a
        // table that only changes when a bill is SAVED. So every table printed the
        // same number until one of them settled, and a table settled after another had
        // gone through was booked under a different number from the slip its customer
        // was holding.
        //
        // Written to the running order, which does two things at once: the settlement
        // reads it back so the books match the slip, and BillDao counts it as taken so
        // the next table to print gets the number after it.
        val next = com.example.synergic_pos_offline.database.BillDao(requireContext()).nextNumber()
        roDao.setBillSeq(order.dbId, next.seq)
        // This is the provisional table bill, printed before payment - so it carries no
        // payment mode. The mode is only known and printed on the paid receipt, after
        // Settlement -> Confirm (see settlePaidOrder -> printGroceryStyleBill with payMethod).
        printGroceryStyleBill(order, printer, billNumber = next.number, payment = "")
        // What was served has left the shelf, and this is the moment it did: the
        // kitchen has cooked the order, the bill is on the table and completeTable
        // below locks it, so no item can be added, changed or removed afterwards.
        //
        // Waiting for payment would leave the count wrong for as long as the table
        // takes to settle - and wrong permanently for a table that walks out, where
        // the food is just as gone. The lock is what makes this safe to do early:
        // once billed, the order this deducted for is the order that gets paid.
        //
        // Deducted ONCE. The bill written at payment carries stockAlreadyMoved, so
        // BillDao does not take the same items off again.
        deductStockForOrder(order)
        completeTable(order)   // billed → locked (stays until paid)

        // BACK TO THE FLOOR PLAN. The bill is on the table and the order is locked, so
        // there is nothing further to do to it here - it is waiting on the guests, not
        // on the till. Leaving the screen sitting on a table that can no longer be
        // changed means the next order starts by clearing it off first.
        //
        // The same move Print KOT makes, for the same reason: the waiter has finished
        // with this table for now. It stays in Active Orders as Completed - Billed, so
        // Settlement is one tap away when the guests are ready.
        view?.post { if (isAdded) showChooseTableDialog() }
    }

    /**
     * Draws this order's items off the shelf, against the table it was served at.
     *
     * Guarded by the stock flag: with stock tracking off the till never touches the
     * stock tables at all, which is how it behaved before they were kept.
     */
    private fun deductStockForOrder(order: OrderCard) {
        if (!stockTrackingOn) return
        stockDao.recordSale(
            reference = tableLabel(order),
            lines = order.items.map {
                com.example.synergic_pos_offline.database.StockDao.SaleLine(
                    it.productId.toInt(), it.qty.toDouble()
                )
            }
        )
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
        val b = computeBill(order.items, serviceRateFor(order.section), order.type)

        val billType = when (payMethod.lowercase(java.util.Locale.US)) {
            "card" -> "CARD"; "online" -> "ONLINE"; else -> "CASH"
        }
        // What the customer actually owes, Round Off applied the same way it is
        // everywhere else on this bill - the DB record has to agree with the slip
        // that was printed from the same order.
        val payable = payableTotal(b.total)
        // Cash handed over more than the bill → book the change and record what was
        // actually tendered; otherwise the payment settles exactly the total.
        val change = (tendered - payable).coerceAtLeast(0.0)
        val amountPaid = if (tendered > payable) tendered else payable
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
                            sgstRate = it.sgstRate,
                            // Saved with the line, so a reprint from Bill History
                            // charges the same VAT the original slip did.
                            vatRate = it.vatRate,
                            // The share of the discount this line carries, in the same
                            // shape td_bill_items stores it - against the line's raw
                            // pre-tax base. BillDao prices the line from this, so the
                            // stored bill reproduces exactly what the panel quoted.
                            discountAmount = com.example.synergic_pos_offline.utils.CartMath.lineDiscount(
                                it.toMathLine(), cartConfig(),
                                com.example.synergic_pos_offline.utils.CartMath
                                    .subtotal(order.items.map { l -> l.toMathLine() }),
                                b.discount
                            )
                        )
                    },
                    payment = com.example.synergic_pos_offline.database.BillDao.Payment(
                        mode = billType, amountPaid = amountPaid, changeAmount = change,
                        custPhone = order.phone.takeIf { it.isNotBlank() }, custId = custId
                    ),
                    totalPrice = b.subtotal,
                    discountAmount = b.discount,
                    discountPercentage = b.discountPercent,
                    discountIsPercent = b.discountIsPercent,
                    cgstAmount = b.cgst,
                    sgstAmount = b.sgst,
                    netAmount = payable,
                    roundOffAmount = roundOffAmount(b.total),
                    // The shop's own extra charges (Parcel Charge among them) - not the
                    // service charge, which has its own column just below.
                    otherChargesAmount = b.chargesTotal,
                    waiterId = waiterId,
                    tableNumber = order.id,
                    tableSection = order.section,
                    orderType = order.type,
                    serviceChargeAmount = b.service,
                    // True only when the bill was actually PRINTED, because that is
                    // the moment doPrintBill takes the stock off - and `completed` is
                    // exactly "this order has been billed".
                    //
                    // Not a constant, because a table can be paid without the bill
                    // ever being printed: Settlement is reachable straight from the
                    // order. Hard-coding true would mean that sale moved no stock at
                    // all, which is the same bug as deducting twice pointing the other
                    // way. Printed -> deducted there; not printed -> deducted here.
                    stockAlreadyMoved = order.completed,
                    // The number the slip was printed under, where one was printed.
                    // Null when the order is being settled without a bill ever having
                    // been cut, and then the sale takes the next number as normal.
                    reservedBillSeq = roDao.billSeqOf(order.dbId)
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
        // Split sub-table. Cancel means two different things here, and which one it
        // means depends on whether the part has been ordered on:
        //
        //  - it HAS items → clear them, keep the part. The guests are still there and
        //    the seat is still theirs; they are just starting again.
        //  - it is EMPTY → drop the part altogether. An empty part is one nobody is
        //    using, and this is the operator saying so - which is the only way a split
        //    ends now that the till no longer decides that for itself. Dropping the
        //    last part hands the table back whole.
        if (order.id.contains(" ")) {
            val accent = ThemeManager.getThemeColor(requireContext())
            if (order.items.isNotEmpty()) {
                roDao.clearItems(order.dbId)
                order.items.clear()
                updateTableStatus(order.id, order.section, "Available")
                populateOrders(root, accent)
                renderCart()
                toast("Sub-table ${order.id} cleared — available to re-order")
                return
            }
            roDao.close(order.dbId)
            orders.removeAll { it.dbId == order.dbId }
            subTableDao.remove(order.id, order.section)
            // Frees the parent if that was the last part standing.
            freeParentIfSplitDone(order.id, order.section)
            populateOrders(root, accent)
            clearDetail(root)
            toast(
                if (orders.any { it.id.startsWith("${order.id.substringBeforeLast(" ")} ") })
                    "Sub-table ${order.id} removed"
                else "Table ${order.id.substringBeforeLast(" ")} is whole again"
            )
            return
        }
        val mergedTables = roDao.mergedTablesOf(order.dbId)
        roDao.close(order.dbId)                          // delete order + items, close KOT
        if (!order.type.equals("Take Away", ignoreCase = true))
            updateTableStatus(order.id, order.section, "Available")   // free the dine-in table
        // Merged tables are same-section by construction, so they free with it.
        mergedTables.forEach { updateTableStatus(it, order.section, "Available") }
        orders.removeAll { it.dbId == order.dbId }
        // A merged SPLIT PART goes back to its parent here too - see settlePaidOrder.
        mergedTables.forEach { freeParentIfSplitDone(it, order.section) }
        populateOrders(root, ThemeManager.getThemeColor(requireContext()))
        clearDetail(root)
        toast("Order cleared")
    }

    /**
     * Payment confirmed (Bill & Pay ▸ Confirm): settle the order — save the bill,
     * close & remove it, free the table. NOTHING IS PRINTED here at all: not the bill
     * (see the note at the foot of this function) and not a KOT.
     *
     * NO KOT IS CUT ON PAYMENT, for any order type. Payment is the end of a sale, and
     * a kitchen ticket at that moment is a ticket for food that has already been made
     * and handed over - it reaches the pass after the customer has left with the
     * order. A KOT belongs to the moment the order is TAKEN, which is what Print KOT
     * is for.
     */
    private fun settlePaidOrder(order: OrderCard, payMethod: String, tendered: Double = 0.0) {
        val saved = persistBill(order, payMethod, tendered)  // save to td_bills / td_bill_items / td_payments
        val mergedTables = roDao.mergedTablesOf(order.dbId)
        roDao.close(order.dbId)                          // payment done → remove from temp table
        updateTableStatus(order.id, order.section, "Available")  // table freed for the next guest
        // merged tables freed too (same section as the kept one)
        mergedTables.forEach { updateTableStatus(it, order.section, "Available") }
        orders.removeAll { it.dbId == order.dbId }
        // free the parent once all parts are done
        freeParentIfSplitDone(order.id, order.section)
        // AND THE PARENT OF ANY SPLIT PART THAT WAS MERGED INTO THIS ORDER.
        //
        // Merge 5 A into table 10 and the split lives on inside 10's bill: 5 A has no
        // order of its own any more, so settling 10 is the moment it is finished with.
        // Only its status was being restored, which left table 5 split for good - a
        // parent holding parts that nothing owned, unable to be seated as one table
        // again. Run after the removal above, so the list this reads no longer counts
        // the order just settled.
        mergedTables.forEach { freeParentIfSplitDone(it, order.section) }
        view?.let { root ->
            populateOrders(root, ThemeManager.getThemeColor(requireContext()))
            clearDetail(root)
        }
        val billNo = saved?.billNumber
            ?: com.example.synergic_pos_offline.database.BillDao(requireContext()).lastBillNumber().orEmpty()
        // Confirm Payment settles and saves; it does not print.
        //
        // In a restaurant the bill is printed BEFORE it is paid - it goes to the table,
        // the guest reads it and then hands over cash or a card - so by the time this
        // runs the customer has had their copy from Print Bill. Printing again on
        // confirm produced a second slip nobody had asked for, and it came out after
        // the guest had already been given one, which is the worst moment for a till
        // to hand over a duplicate of a bill.
        //
        // So payment records payment: persistBill above has written td_bills, its
        // items and td_payments, the table is freed, and that is the whole act. The
        // paper is Print Bill's job, and it is still one button away for a reprint.
        //
        // There is no exception for Take Away any more: it used to have its KOT cut
        // here, on the grounds that it never sends one while the order is taken. That
        // ticket arrived after the food had been paid for, which is too late to cook
        // from - see the note at the head of this function.
        //
        // No completion popup either: the order is already settled and the Orders
        // (sale) screen was refreshed above, so just confirm with a toast.
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
        // Grey KOT and Print Bill here rather than waiting for the order to be
        // re-selected: this IS the moment it was billed, and the operator is looking
        // at the panel when it happens.
        setBilledLock(root, billed = true)
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

        // THIS IS THE MOMENT A TABLE BECOMES OCCUPIED, and the only one.
        //
        // Not opening it, and not adding items to it: until the ticket is cut the
        // order is still being built at the till and can be emptied, cancelled or
        // walked away from without anybody having been served. Once the kitchen has
        // it, food is being cooked for that table - which is what "occupied" means to
        // a floor - and it stays that way until the bill is paid.
        //
        // The status written is "Occupied", not "KOT Printed". That was tried and is
        // not one of the values the table master allows ('Available','Occupied',
        // 'Reserved','Cleaning','Billing','Blocked'), so it either failed to save or
        // read back as unknown - and an unknown status showed the table as FREE while
        // guests were sitting at it.
        if (!order.type.equals("Take Away", ignoreCase = true)) {
            updateTableStatus(order.id, order.section, "Occupied")
            roDao.mergedTablesOf(order.dbId)
                .forEach { updateTableStatus(it, order.section, "Occupied") }
        }

        reloadItems(order)
        renderCart()
        com.example.synergic_pos_offline.utils.KotPrinter.print(requireContext(), batch, printer) { msg -> toast(msg) }

        // The order is with the kitchen, so this table is finished with for now - back
        // to the floor plan, ready for the next one.
        //
        // Sending a KOT is the end of taking an order, the way payment is the end of a
        // sale: the waiter turns from this table to the next, and leaving the screen on
        // a table whose items have all gone means the next order starts with a table to
        // clear off it first. Posted, so the toast and the cart redraw above land before
        // the picker comes up over them.
        //
        // EVERY ORDER TYPE, take-away included. It used to be dine-in only, on the
        // reasoning that a counter order has nowhere to go next because Settlement is
        // what happens to it. That reads the wrong way round: sending the ticket is
        // the end of TAKING the order, and what a counter does next is take the next
        // one - it does not stand at the till watching food cook. The token stays open
        // in Active Orders and is one tap away when it is time to settle it.
        view?.post { if (isAdded) showChooseTableDialog() }
    }

    /**
     * The whole-bill discount box, wired the way the grocery cart wires it.
     *
     * Hidden outright unless Tax Settings has Discount on and set to Bill wise. Under
     * item-wise each product carries its own and the total already reflects it, so a
     * box here could only mislead; with discount off there is nothing to enter.
     */
    private fun setUpDiscountBox(root: View) {
        val section = root.findViewById<View>(R.id.sectionOrderDiscount)
        section.visibility = if (billwiseDiscountActive) View.VISIBLE else View.GONE
        if (!billwiseDiscountActive) return

        val et = root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etOrderDiscount)
        val rg = root.findViewById<android.widget.RadioGroup>(R.id.rgOrderDiscountMode)
        applyDiscountHint(root)

        et.addTextChangedListener { text ->
            val typed = text?.toString().orEmpty()
            discountValue = when (discountMode) {
                // A percentage is capped at 100 as it is typed: past that the figure
                // stops meaning anything, and the cap is easier to understand on the
                // box than as a total that refuses to go below zero.
                com.example.synergic_pos_offline.utils.GstCalculator.DiscountMode.PERCENT ->
                    (com.example.synergic_pos_offline.utils.Amounts.parse(typed) ?: 0.0).coerceIn(0.0, 100.0)
                com.example.synergic_pos_offline.utils.GstCalculator.DiscountMode.AMOUNT ->
                    (com.example.synergic_pos_offline.utils.Amounts.parse(typed) ?: 0.0).coerceAtLeast(0.0)
            }
            updateTotals()
        }
        rg.setOnCheckedChangeListener { _, checked ->
            discountMode = if (checked == R.id.rbOrderDiscountAmount)
                com.example.synergic_pos_offline.utils.GstCalculator.DiscountMode.AMOUNT
            else com.example.synergic_pos_offline.utils.GstCalculator.DiscountMode.PERCENT
            // Cleared on a switch: "10" means two different things either side of it,
            // and carrying the number across would quietly change the bill.
            applyDiscountHint(root)
            et.setText("")
            discountValue = 0.0
            updateTotals()
        }
    }

    /** The box says which of the two it is taking, so the number in it is unambiguous. */
    private fun applyDiscountHint(root: View) {
        root.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilOrderDiscount).hint =
            if (discountMode == com.example.synergic_pos_offline.utils.GstCalculator.DiscountMode.AMOUNT)
                "Discount (₹)" else "Discount (%)"
    }

    /** Empties the discount box - see [discountValue] for why it does not travel. */
    private fun clearDiscountEntry() {
        if (discountValue == 0.0) return
        discountValue = 0.0
        view?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etOrderDiscount)
            ?.setText("")
    }

    private fun updateTotals() {
        val root = view ?: return
        val order = currentOrder()
        val b = computeBill(order?.items ?: emptyList(), serviceRateFor(order?.section.orEmpty()), order?.type)
        val payable = payableTotal(b.total)
        root.findViewById<TextView>(R.id.tvSubtotal).text = "₹ ${money(b.subtotal)}"
        root.findViewById<TextView>(R.id.tvService).text = "₹ ${money(b.service)}"
        root.findViewById<TextView>(R.id.tvCgst).text = "₹ ${money(b.cgst)}"
        root.findViewById<TextView>(R.id.tvSgst).text = "₹ ${money(b.sgst)}"

        // The discount line, shown only when there is one - under bill-wise the figure
        // typed here, under item-wise what the products' own discounts came to. Signed
        // so it reads as a deduction among lines that all add.
        root.findViewById<View>(R.id.rowOrderDiscount).visibility =
            if (b.discount > 0.0) View.VISIBLE else View.GONE
        root.findViewById<TextView>(R.id.tvOrderDiscountLabel).text =
            if (itemwiseDiscountActive) "Discount (item-wise)" else "Discount"
        root.findViewById<TextView>(R.id.tvOrderDiscountAmt).text = "- ₹ ${money(b.discount)}"
        // VAT only where a line actually carries it.
        root.findViewById<View>(R.id.rowOrderVat).visibility =
            if (b.vat > 0.0) View.VISIBLE else View.GONE
        root.findViewById<TextView>(R.id.tvOrderVat).text = "₹ ${money(b.vat)}"

        // A row per charge that actually applies to THIS order - the Extra Charges and
        // the Parcel Charge, each under its own name. Built here rather than declared
        // in the layout because how many there are, and which of them apply, is not
        // known until the order type is: a TAKEAWAY-only Parcel Charge is on a counter
        // order's bill and not on a table's. See ChargeDao.amountsOn.
        val llCharges = root.findViewById<LinearLayout>(R.id.llOrderCharges)
        llCharges.removeAllViews()
        b.charges.forEach { applied ->
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_order_summary_line, llCharges, false)
            // The rate is put beside the name, because a percentage charge that only
            // shows its rupee amount cannot be checked against the master by anyone
            // reading the screen.
            row.findViewById<TextView>(R.id.tvSummaryLabel).text =
                if (applied.type == com.example.synergic_pos_offline.database.ChargeDao.Type.PERCENTAGE)
                    "${applied.name} (${qtyText(applied.value)}%)"
                else applied.name
            row.findViewById<TextView>(R.id.tvSummaryValue).text = "₹ ${money(applied.amount)}"
            llCharges.addView(row)
        }

        root.findViewById<TextView>(R.id.tvOrderTotal).text = "₹ ${money(payable)}"
        // The same figure on the fold's handle, for while the fold is shut.
        root.findViewById<TextView>(R.id.tvTotalBar).text = "₹ ${money(payable)}"
        // One assignment, not two. This read `text = "Settlement ( … )"` with a bare
        // settlementLabel(...) call stranded on the line under it - so the take-away
        // button had quietly gone back to saying "Settlement" instead of "Print &
        // Settlement", and the label function was being evaluated and thrown away.
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBillPay).text =
            settlementLabel(order, payable)
        // Re-checked on every cart redraw, which is what happens when the first item
        // goes on and when the KOT is cut - the two moments the answer changes.
        setSettlementEnabled(root, order)
        // Reflect the running total on the active order card. Only that one figure
        // moves as items go on, so the card is patched where it stands - rebuilding
        // the whole list meant inflating a card per open table on every tap.
        order?.let {
            it.amount = "₹ ${money(payable)}"
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

        /**
         * How many parts a table may be split into.
         *
         * Two is the floor because splitting into one is not splitting. Six is the
         * ceiling: the parts are lettered from 'A', so six is 101 A to 101 F and still
         * reads as one table's worth of bills. Named here rather than typed into the
         * dropdown and the clamp separately - they were 2..4 in two places, and a
         * dropdown offering a number the clamp then quietly reduced would be the kind
         * of bug nobody reports because it looks like it worked.
         */
        const val MIN_SPLIT_PARTS = 2
        const val MAX_SPLIT_PARTS = 6
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

    /** Refresh product names when app language changes, without affecting other UI. */
    fun refreshProductDisplay() {
        view?.let { root ->
            root.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvProductGrid)?.adapter?.notifyDataSetChanged()
            root.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvTables)?.adapter?.notifyDataSetChanged()
        }
    }
}
