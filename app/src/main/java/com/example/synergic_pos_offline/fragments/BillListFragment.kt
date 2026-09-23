package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.BillDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.database.ReturnDao
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.BillPrinter
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Bill History - an item-wise list of past bills, sourced from the database via
 * [BillDao].
 *
 * Each row opens the receipt preview ([BillFragment]), or prints straight from the
 * list for when the bill only needs to be reproduced and not read.
 */
class BillListFragment : Fragment(), TitledScreen {

    /**
     * When true this is the bill picker for a sale return rather than history: the
     * same search, filters and list, but View opens the bill for return instead of
     * for reading, and there is nothing to print from here.
     *
     * The list is reused rather than copied because a return is found the same way
     * a bill is - by number, customer, date, amount or item - and a second screen
     * would be the same filters maintained twice.
     */
    private val pickingForReturn: Boolean
        get() = arguments?.getBoolean(ARG_PICK_FOR_RETURN) == true

    override val screenTitle: String
        get() = if (pickingForReturn) "Sale Return" else "Bill History"

    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var llLoading: View
    private val dao by lazy { BillDao(requireContext()) }
    private var actItem: MaterialAutoCompleteTextView? = null

    /**
     * The bills on screen so far - [PAGE_SIZE] at a time, the next page read as the
     * list nears its end. Never the whole book: a till with a lakh bills behind it
     * used to read every one of them, and every line on every one, on the main
     * thread each time this screen was shown - see [BillDao.page].
     */
    private val bills = mutableListOf<BillDao.Bill>()
    private lateinit var adapter: BillAdapter
    private var loading = false
    private var endReached = false

    /**
     * Bumped by every fresh read. A page that comes back for an older generation -
     * the filter moved on while it was being read - is dropped rather than shown
     * under a filter it does not belong to.
     */
    private var generation = 0

    /** One reader, so pages land in the order they were asked for. */
    private var loadExecutor = Executors.newSingleThreadExecutor()

    /** Typing re-reads once the operator pauses, not once per key. */
    private val debounce = Handler(Looper.getMainLooper())
    private val debouncedRefresh = Runnable { if (view != null) refresh() }

    private var query = ""
    private var itemQuery = ""
    private var minAmount: Double? = null
    private var maxAmount: Double? = null
    private var showCancelled = false

    /** Date-range filter options and the currently selected one. */
    private enum class Range(val label: String, val days: Int?) {
        TODAY("Today", null),
        LAST_DAY("Previous day", 1),
        LAST_WEEK("Previous week", 7),
        LAST_MONTH("Previous month", 30),
        CUSTOM("Custom range", null),
        /** No date bound at all - every bill the store has. */
        ALL("All dates", null)
    }
    private var range = Range.TODAY
    private var customFrom: Calendar? = null
    private var customTo: Calendar? = null
    private val billDateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.US)
    /** bill_date's own format, for the bounds [BillDao.page] compares it against. */
    private val dbDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Sort options for the bill list. */
    private enum class Sort(val label: String) {
        BILL_NO_DESC("Bill No. (descending)"),
        BILL_NO_ASC("Bill No. (ascending)"),
        DATE_DESC("Date (newest)"),
        DATE_ASC("Date (oldest)"),
        AMOUNT_DESC("Amount (high–low)"),
        AMOUNT_ASC("Amount (low–high)")
    }

    /**
     * BILL NUMBER, HIGHEST FIRST - the last bill written, at the top.
     *
     * History is opened most often to reach the bill that just went out: to reprint
     * it, to check it, to return against it. That one is the highest number there is,
     * so putting it first is putting the answer where the screen opens, with no
     * scrolling to the foot of a day's trading to reach the most recent sale.
     *
     * Ascending is the option directly beneath it, for reading the book as a run
     * rather than reaching into it - see [BillDao.page], whose ordering both share.
     *
     * First in the list as well as the default, so it is where the eye lands when the
     * sort is opened to come back to it.
     */
    private var sort = Sort.BILL_NO_DESC

    private lateinit var llCustomRange: View
    private lateinit var etFromDate: TextInputEditText
    private lateinit var etToDate: TextInputEditText

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_bill_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rv = view.findViewById(R.id.rvBills)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        llLoading = view.findViewById(R.id.llLoading)
        // In the till's own accent, like the rest of this screen's controls.
        view.findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(R.id.pbLoading)
            .setIndicatorColor(ThemeManager.getThemeColor(requireContext()))

        loadExecutor = Executors.newSingleThreadExecutor()
        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = BillAdapter(
            bills,
            onView = { if (pickingForReturn) openForReturn(it) else openBill(it) },
            onPrint = { printBill(it) },
            onCancel = { confirmCancelBill(it) },
            showPrint = !pickingForReturn
        )
        rv.adapter = adapter
        // The next page is read while a few rows are still below the fold, so a
        // steady scroll does not hit the end of the list and wait.
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) loadMoreIfNearEnd()
            }
        })

        // Status radio group: Active bills (default) vs Cancelled.
        val accent = ThemeManager.getThemeColor(requireContext())
        val rbActive = view.findViewById<android.widget.RadioButton>(R.id.rbActive)
        val rbCancelled = view.findViewById<android.widget.RadioButton>(R.id.rbCancelled)
        val tint = ColorStateList.valueOf(accent)
        rbActive.buttonTintList = tint
        rbCancelled.buttonTintList = tint
        view.findViewById<android.widget.RadioGroup>(R.id.rgStatus)
            .setOnCheckedChangeListener { _, checkedId ->
                showCancelled = checkedId == R.id.rbCancelled
                refresh()
            }

        view.findViewById<TextInputEditText>(R.id.etSearch).onChange { query = it }
        view.findViewById<TextInputEditText>(R.id.etMinAmount).onChange { minAmount = it.toDoubleOrNull() }
        view.findViewById<TextInputEditText>(R.id.etMaxAmount).onChange { maxAmount = it.toDoubleOrNull() }

        // Item filter: type an item name to show every bill that contains it.
        actItem = view.findViewById<MaterialAutoCompleteTextView>(R.id.actItem).apply {
            onChange { itemQuery = it }
        }

        llCustomRange = view.findViewById(R.id.llCustomRange)
        etFromDate = view.findViewById(R.id.etFromDate)
        etToDate = view.findViewById(R.id.etToDate)
        etFromDate.setOnClickListener { pickDate(isFrom = true) }
        etToDate.setOnClickListener { pickDate(isFrom = false) }

        // What bounds the picker is the return window, not a date range, so it opens
        // on every date and shows exactly the bills that can still be returned
        // against. History still opens on today, which is what it is usually for.
        if (pickingForReturn) range = Range.ALL

        // Date-range dropdown
        val actRange = view.findViewById<MaterialAutoCompleteTextView>(R.id.actRange)
        val ranges = Range.entries.toTypedArray()
        actRange.setAdapter(NoFilterAdapter(requireContext(), ranges.map { it.label }))
        actRange.setText(range.label, false)
        actRange.setOnItemClickListener { _, _, pos, _ ->
            range = ranges[pos]
            llCustomRange.visibility = if (range == Range.CUSTOM) View.VISIBLE else View.GONE
            refresh()
        }

        // Sort dropdown
        val actSort = view.findViewById<MaterialAutoCompleteTextView>(R.id.actSort)
        val sorts = Sort.entries.toTypedArray()
        actSort.setAdapter(NoFilterAdapter(requireContext(), sorts.map { it.label }))
        actSort.setText(sort.label, false)
        actSort.setOnItemClickListener { _, _, pos, _ ->
            sort = sorts[pos]
            refresh()
        }

        // Item suggestions are every name ever sold - a pass over every bill line - so
        // they are read once per visit and off the main thread. The first page of
        // bills is read by onResume, which always follows.
        val ctx = requireContext().applicationContext
        loadExecutor.execute {
            val names = runCatching { BillDao(ctx).allItems() }
                .onFailure { android.util.Log.e("BillListFragment", "Item names read failed", it) }
                .getOrNull() ?: return@execute
            view?.post {
                if (isAdded) actItem?.setAdapter(
                    ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names)
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroyView() {
        debounce.removeCallbacks(debouncedRefresh)
        loadExecutor.shutdownNow()
        super.onDestroyView()
    }

    /**
     * Re-reads what is on screen, keeping the place: as many bills as were already
     * showing, and the scroll position over them. Coming back from a bill, or
     * cancelling one, should not throw the operator back to the top of the list.
     */
    private fun reload() {
        load(
            offset = 0, limit = maxOf(PAGE_SIZE, bills.size), replace = true,
            scrollState = rv.layoutManager?.onSaveInstanceState()
        )
    }

    /** A new filter or sort: back to the first page. */
    private fun refresh() {
        debounce.removeCallbacks(debouncedRefresh)
        load(offset = 0, limit = PAGE_SIZE, replace = true)
        rv.scrollToPosition(0)
    }

    private fun loadMoreIfNearEnd() {
        if (loading || endReached) return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        if (lm.findLastVisibleItemPosition() >= bills.size - PREFETCH_DISTANCE) {
            load(offset = bills.size, limit = PAGE_SIZE, replace = false)
        }
    }

    /**
     * Reads [limit] bills from [offset] on [loadExecutor], then shows them: in place
     * of the list when [replace], after it otherwise. The list on screen stays up
     * until its replacement has arrived, so a new filter does not blank the screen
     * while it is read.
     */
    private fun load(offset: Int, limit: Int, replace: Boolean, scrollState: android.os.Parcelable? = null) {
        val gen = if (replace) ++generation else generation
        loading = true
        // Nothing on screen yet - the first visit, or a list that was empty - so the
        // wait would read as "No bills found" or a blank card. A list already showing
        // stays up while its replacement is read, and needs no spinner over it.
        if (replace && bills.isEmpty()) showLoading(true)
        val filter = currentFilter()
        val dao = dao   // resolved here, on the main thread, not first touched on the reader's
        loadExecutor.execute {
            val rows = runCatching { dao.page(filter, offset, limit) }
                .onFailure { android.util.Log.e("BillListFragment", "Bill page read failed", it) }
                .getOrDefault(emptyList())
            view?.post {
                if (!isAdded || gen != generation) return@post
                loading = false
                showLoading(false)
                if (replace) {
                    bills.clear()
                    bills.addAll(rows)
                    adapter.notifyDataSetChanged()
                    scrollState?.let { rv.layoutManager?.onRestoreInstanceState(it) }
                } else {
                    val start = bills.size
                    bills.addAll(rows)
                    adapter.notifyItemRangeInserted(start, rows.size)
                }
                endReached = rows.size < limit
                showEmptyState()
                // A page that does not fill the screen gives nothing to scroll, so the
                // next one would never be asked for.
                rv.post { if (isAdded) loadMoreIfNearEnd() }
            }
        }
    }

    /** Opens a date picker for the From/To field, then re-filters. */
    private fun pickDate(isFrom: Boolean) {
        val current = (if (isFrom) customFrom else customTo) ?: Calendar.getInstance()
        android.app.DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                val picked = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
                if (isFrom) {
                    customFrom = picked
                    etFromDate.setText(billDateFormat.format(picked.time))
                } else {
                    customTo = picked
                    etToDate.setText(billDateFormat.format(picked.time))
                }
                refresh()
            },
            current.get(Calendar.YEAR), current.get(Calendar.MONTH), current.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    /** Return Days for the picker, 0 for History or no limit - see [currentFilter]. */
    private var returnDays = 0

    /**
     * The screen's filters as [BillDao.page] reads them. Every rule the list used to
     * apply in memory is here as a bound on the query instead:
     *
     * - a preset window keeps bills dated after the day [Range.days] back - the
     *   rolling cutoff it always was, read at day granularity;
     * - Today and a custom range are inclusive dates;
     * - the picker's Sale Return Days window keeps bills from that many days back,
     *   so a bill past it is not offered at all rather than listed and then refused
     *   when opened. Read per refresh rather than held, so changing it in General
     *   Settings and coming back shows the new window;
     * - History files a returned bill with the cancelled ones; the picker judges by
     *   the bill's own status alone, or the rest of a part-return could never be taken.
     *
     * Bill-number order is prefix as text, counter as a number, receipt_no as the
     * tie-break - so INV-9 sits above INV-10, and two bills called INV-0001 from
     * either side of an erase keep a steady order. See [BillDao.page].
     */
    private fun currentFilter(): BillDao.HistoryFilter {
        fun daysBack(n: Int): String =
            dbDateFormat.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -n) }.time)
        val today = dbDateFormat.format(Date())
        returnDays = if (pickingForReturn) {
            GeneralSettingsDao(requireContext()).load().saleReturnDays
        } else 0
        return BillDao.HistoryFilter(
            text = query.trim(),
            item = itemQuery.trim(),
            minAmount = minAmount,
            maxAmount = maxAmount,
            after = range.days?.let { daysBack(it) },
            from = when (range) {
                Range.TODAY -> today
                Range.CUSTOM -> customFrom?.let { dbDateFormat.format(it.time) }
                else -> null
            },
            to = when (range) {
                Range.TODAY -> today
                Range.CUSTOM -> customTo?.let { dbDateFormat.format(it.time) }
                else -> null
            },
            returnableFrom = if (pickingForReturn && returnDays > 0) daysBack(returnDays) else null,
            showCancelled = showCancelled,
            pickingForReturn = pickingForReturn,
            sort = BillDao.HistorySort.valueOf(sort.name)
        )
    }

    /** The "Loading bills…" spinner, in place of the empty message while it shows. */
    private fun showLoading(on: Boolean) {
        llLoading.visibility = if (on) View.VISIBLE else View.GONE
        if (on) tvEmpty.visibility = View.GONE
    }

    private fun showEmptyState() {
        tvEmpty.visibility = if (bills.isEmpty()) View.VISIBLE else View.GONE
        // An empty picker is usually the return window rather than an empty till, and
        // "No bills found" would send the operator looking for a bill that is there.
        tvEmpty.text = if (pickingForReturn && returnDays > 0) {
            "No bills within the $returnDays-day return window"
        } else {
            "No bills found"
        }
    }

    /** Runs [onText] with the trimmed text on every change, then re-filters once typing pauses. */
    private fun android.widget.EditText.onChange(onText: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                onText(s?.toString()?.trim().orEmpty())
                debounce.removeCallbacks(debouncedRefresh)
                debounce.postDelayed(debouncedRefresh, TYPING_PAUSE_MS)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    /**
     * Prints without opening the bill first. Marked a duplicate for the same reason
     * the preview is: this list is history, so the customer already has the original.
     */
    /**
     * Asks before a bill is thrown away, then throws it away.
     *
     * The same destructive popup the rest of the till uses for a deletion it cannot
     * undo - red confirm, a plain statement of what happens, Cancel first. What it
     * says is specific to THIS bill rather than to cancelling in general: the number,
     * the amount and the customer, because those are what an operator checks before
     * agreeing, and "Cancel this bill?" over a list of twenty is not something anybody
     * can safely say yes to.
     *
     * The refusal path matters as much as the confirm. A bill with a sale return
     * against it cannot go, and the reason is said in the popup rather than as a toast
     * that appears and vanishes - see [BillDao.cancelBill].
     */
    private fun confirmCancelBill(bill: BillDao.Bill) {
        val who = bill.name.takeIf { it.isNotBlank() && !it.equals("Guest", true) }
        DialogUtils.showConfirm(
            context = requireContext(),
            title = "Cancel bill ${bill.billNo}?",
            message = buildString {
                append("₹ ${bill.total}")
                who?.let { append("  ·  $it") }
                append("  ·  ${bill.date}")
                append("\n\nThe bill stops counting in every sales report. You will ")
                append("still find it under Cancelled bills here in Bill History, and ")
                append("on the Void Bill Report.")
                append("\n\nThe stock it sold goes back on the shelf, and the sale's own ")
                append("stock movement is removed, so the till reads as though it never ")
                append("happened.")
                append("\n\nA credit sale comes off the customer's ledger and their ")
                append("balance, so nothing is left owing for a bill that no longer counts.")
                append("\n\nThis cannot be undone.")
            },
            positiveText = "Cancel Bill",
            negativeText = "Keep It",
            destructive = true,
            messageStart = true
        ) {
            val result = BillDao(requireContext()).cancelBill(bill.receiptNo)
            if (!result.ok) {
                DialogUtils.showSuccess(
                    context = requireContext(),
                    title = "Bill not cancelled",
                    message = result.refusal.orEmpty()
                )
                return@showConfirm
            }
            android.widget.Toast.makeText(
                requireContext(),
                if (result.itemsRestored > 0)
                    "Bill ${bill.billNo} cancelled - ${result.itemsRestored} item(s) back in stock"
                else "Bill ${bill.billNo} cancelled",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            // RELOAD, not refresh. Cancelling moves the bill between two tables, so
            // the row in hand is stale in a way re-filtering cannot fix: [refresh]
            // only re-filters what was last read, so the bill went on showing as
            // active and never appeared under Cancelled until the screen was left and
            // come back to. [reload] re-reads both tables first.
            reload()
        }
    }

    private fun printBill(bill: BillDao.Bill) {
        BillPrinter.print(requireContext(), bill.receiptNo, duplicate = true) { message ->
            if (isAdded) android.widget.Toast.makeText(
                requireContext(), message, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Opens the bill for return. A bill outside the Sale Return Days window is
     * refused here rather than at save time - the operator finds out before picking
     * through the lines, not after.
     */
    private fun openForReturn(bill: BillDao.Bill) {
        val settings = GeneralSettingsDao(requireContext()).load()
        if (!ReturnDao(requireContext()).withinReturnWindow(bill.receiptNo, settings.saleReturnDays)) {
            DialogUtils.showSuccess(
                context = requireContext(),
                title = "Outside the return window",
                message = "Bill ${bill.billNo} is older than the ${settings.saleReturnDays}-day " +
                    "return limit set in General Settings.",
                iconRes = android.R.drawable.ic_dialog_alert
            )
            return
        }
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, BillReturnFragment.newInstance(bill.receiptNo, bill.billNo))
            .addToBackStack(null)
            .commit()
    }

    private fun openBill(bill: BillDao.Bill) {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_container,
                BillFragment.newInstance(
                    bill.billNo, bill.name, bill.date, bill.time, bill.total, bill.receiptNo,
                    // Opened from Bill history: the customer already has the
                    // original, so anything printed from here is a second copy.
                    duplicate = true
                )
            )
            .addToBackStack(null)
            .commit()
    }

    private class NoFilterAdapter(context: android.content.Context, items: List<String>) :
        ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items.toList()) {

        private val all = items.toList()
        private val passthrough = object : android.widget.Filter() {
            override fun performFiltering(constraint: CharSequence?) =
                FilterResults().apply { values = all; count = all.size }
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) = notifyDataSetChanged()
        }

        override fun getFilter(): android.widget.Filter = passthrough
    }

    private inner class BillAdapter(
        private val items: List<BillDao.Bill>,
        private val onView: (BillDao.Bill) -> Unit,
        private val onPrint: (BillDao.Bill) -> Unit,
        private val onCancel: (BillDao.Bill) -> Unit,
        private val showPrint: Boolean = true
    ) : RecyclerView.Adapter<BillAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvBillNo: TextView = view.findViewById(R.id.tvRowBillNo)
            val tvName: TextView = view.findViewById(R.id.tvRowName)
            val tvDate: TextView = view.findViewById(R.id.tvRowDate)
            val tvTime: TextView = view.findViewById(R.id.tvRowTime)
            val tvAmount: TextView = view.findViewById(R.id.tvRowAmount)
            val btnView: MaterialButton = view.findViewById(R.id.btnViewBill)
            val btnPrint: MaterialButton = view.findViewById(R.id.btnPrintBillRow)
            val btnCancel: MaterialButton = view.findViewById(R.id.btnCancelBillRow)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_bill_row, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val bill = items[position]
            holder.tvBillNo.text = bill.billNo
            holder.tvName.text = bill.name
            holder.tvDate.text = bill.date
            holder.tvTime.text = bill.time
            holder.tvAmount.text = "₹ ${bill.total}"

            val accent = ThemeManager.getThemeColor(holder.itemView.context)
            val tint = ColorStateList.valueOf(accent)
            holder.btnView.setTextColor(accent)
            holder.btnView.strokeColor = tint
            holder.btnView.iconTint = tint
            holder.btnView.setOnClickListener { onView(bill) }

            holder.btnPrint.visibility = if (showPrint) View.VISIBLE else View.GONE
            holder.btnPrint.strokeColor = tint
            holder.btnPrint.iconTint = tint
            // Offered on a cancelled bill too.
            //
            // It used to be greyed out on the reasoning that a cancelled bill has
            // nothing to reproduce. It has: the slip is exactly what somebody asks for
            // when a customer comes back about a sale that was voided, and the
            // renderer already reads a cancelled bill out of the archive and captions
            // it CANCELLED - see BillReceiptRenderer.billsTableFor and renderCaptions
            // - so the paper says plainly that the sale no longer stands. The bill
            // SCREEN has always let it be printed; only this list refused, which is
            // the same one-button-two-meanings split the delete icon had.
            holder.btnPrint.isEnabled = true
            holder.btnPrint.alpha = 1f
            holder.btnPrint.setOnClickListener { onPrint(bill) }

            // CANCEL is offered only while this list is the real Bill History, and only
            // on a bill there is still something to cancel. A bill already gone, or one
            // being picked for a return, has nothing for it to do - and a destructive
            // button that answers to nothing is worse than no button.
            val canCancel = showPrint && !bill.cancelled && !bill.returned
            holder.btnCancel.visibility = if (canCancel) View.VISIBLE else View.GONE
            holder.btnCancel.setOnClickListener { onCancel(bill) }
        }

        override fun getItemCount() = items.size
    }

    companion object {
        private const val ARG_PICK_FOR_RETURN = "pick_for_return"

        /** Bills read per page. */
        private const val PAGE_SIZE = 20

        /** How many rows from the end the next page is asked for. */
        private const val PREFETCH_DISTANCE = 5

        private const val TYPING_PAUSE_MS = 250L

        /** The bill picker for a sale return - see [pickingForReturn]. */
        fun forReturn(): BillListFragment = BillListFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_PICK_FOR_RETURN, true) }
        }
    }
}
