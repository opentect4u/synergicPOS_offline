package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.os.Bundle
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
    private val dao by lazy { BillDao(requireContext()) }
    private var allBills: List<BillDao.Bill> = emptyList()
    private var actItem: MaterialAutoCompleteTextView? = null

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
    private val billDateTimeFormat = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US)

    /** Sort options for the bill list. */
    private enum class Sort(val label: String) {
        BILL_NO_ASC("Bill No. (ascending)"),
        DATE_DESC("Date (newest)"),
        DATE_ASC("Date (oldest)"),
        AMOUNT_DESC("Amount (high–low)"),
        AMOUNT_ASC("Amount (low–high)")
    }

    /**
     * BILL NUMBER, ASCENDING - the order the book was written in.
     *
     * The list opened on newest-first, which is the right answer for a till being
     * watched during service and the wrong one for the book itself: bill history is
     * read to find a bill, to check a run of them, or to tie the day's slips to the
     * screen, and all three are done by number, upwards, the way the slips come off
     * the printer.
     *
     * First in the list as well as the default, so it is where the eye lands when the
     * sort is opened to come back to it.
     */
    private var sort = Sort.BILL_NO_ASC

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

        rv.layoutManager = LinearLayoutManager(requireContext())

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

        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** Loads bills from the database, refreshes item suggestions, then re-filters. */
    private fun reload() {
        allBills = dao.getAll()
        actItem?.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, dao.allItems())
        )
        refresh()
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

    private fun refresh() {
        val q = query.trim()

        // Preset windows use a rolling cutoff; custom range uses From/To bounds.
        val cutoff = range.days?.let { d ->
            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -d) }.time
        }
        val today = Calendar.getInstance()
        val from = when (range) {
            Range.CUSTOM -> customFrom?.let { startOfDay(it) }
            Range.TODAY -> startOfDay(today)
            else -> null
        }
        val to = when (range) {
            Range.CUSTOM -> customTo?.let { endOfDay(it) }
            Range.TODAY -> endOfDay(today)
            else -> null
        }

        // The return window only bounds the picker, and only when a limit is set.
        // Read per refresh rather than held, so changing it in General Settings and
        // coming back shows the new window.
        val returnDays = if (pickingForReturn) {
            GeneralSettingsDao(requireContext()).load().saleReturnDays
        } else 0

        val item = itemQuery.trim()
        val filtered = allBills.filter { b ->
            val matchesText = q.isEmpty() ||
                b.billNo.contains(q, true) || b.name.contains(q, true) ||
                b.date.contains(q, true) || b.time.contains(q, true) ||
                b.total.contains(q, true)
            val d = parseDate(b.date)
            val matchesRange = when {
                cutoff != null -> d != null && !d.before(cutoff)
                from != null || to != null ->
                    d != null && (from == null || !d.before(from)) && (to == null || !d.after(to))
                else -> true
            }
            val matchesItem = item.isEmpty() || b.items.any { it.contains(item, true) }
            val matchesAmount = (minAmount == null || b.amount >= minAmount!!) &&
                (maxAmount == null || b.amount <= maxAmount!!)
            // History files a returned bill with the cancelled ones - some or all of
            // it has come back, so it is not a live sale any more. The picker keeps
            // judging by the bill's own status alone: a partly-returned bill has to
            // stay listed, or the rest of that return could never be taken.
            val matchesStatus =
                if (pickingForReturn) b.cancelled == showCancelled
                else (b.cancelled || b.returned) == showCancelled
            // A bill past the Sale Return Days limit is not offered for return at all,
            // rather than being listed and then refused when it is opened.
            val matchesReturnWindow = !pickingForReturn ||
                ReturnDao.withinReturnWindow(d, returnDays)
            matchesText && matchesRange && matchesItem && matchesAmount && matchesStatus &&
                matchesReturnWindow
        }

        val sorted = when (sort) {
            Sort.BILL_NO_ASC -> filtered.sortedWith(byBillNo)
            Sort.DATE_DESC -> filtered.sortedByDescending { sortMillis(it) }
            Sort.DATE_ASC -> filtered.sortedBy { sortMillis(it) }
            Sort.AMOUNT_DESC -> filtered.sortedByDescending { it.amount }
            Sort.AMOUNT_ASC -> filtered.sortedBy { it.amount }
        }

        rv.adapter = BillAdapter(
            sorted,
            onView = { if (pickingForReturn) openForReturn(it) else openBill(it) },
            onPrint = { printBill(it) },
            showPrint = !pickingForReturn
        )
        tvEmpty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        // An empty picker is usually the return window rather than an empty till, and
        // "No bills found" would send the operator looking for a bill that is there.
        tvEmpty.text = if (pickingForReturn && returnDays > 0) {
            "No bills within the $returnDays-day return window"
        } else {
            "No bills found"
        }
    }

    /**
     * Bills in bill-number order, the way a book reads.
     *
     * ## Why not a plain string sort
     *
     * A bill number is a prefix and a counter - "INV-9", "INV-10" - and comparing those
     * as text puts 10 before 9, because "1" sorts before "9". The list would look
     * ordered and be wrong exactly where a long day makes it matter, at the tens and
     * hundreds boundaries.
     *
     * So the two parts are compared as what they are: the prefix as text, the counter
     * as a number. A shop that changes its prefix mid-book gets its series grouped
     * rather than interleaved, which is also what somebody looking for a bill expects.
     *
     * ## The tie-break
     *
     * [BillDao.Bill.receiptNo] settles two bills that read the same. Numbering restarts
     * when the book is erased (see BillErase), so a till can genuinely hold two bills
     * called INV-0001 from either side of that line - and a sort that left their order
     * to chance would shuffle them between one opening of this screen and the next.
     * receiptNo never restarts, so the older one stays above the newer.
     */
    private val byBillNo: Comparator<BillDao.Bill> =
        compareBy<BillDao.Bill> { it.billNo.takeWhile { c -> !c.isDigit() } }
            .thenBy { b -> b.billNo.filter { it.isDigit() }.toLongOrNull() ?: Long.MAX_VALUE }
            .thenBy { it.receiptNo }

    /** Sortable timestamp (date + time) for a bill; 0 if unparseable. */
    private fun sortMillis(b: BillDao.Bill): Long =
        try { billDateTimeFormat.parse("${b.date} ${b.time}")?.time ?: 0L } catch (_: Exception) { 0L }

    private fun startOfDay(c: Calendar): Date = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.time

    private fun endOfDay(c: Calendar): Date = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
    }.time

    private fun parseDate(text: String): Date? =
        try { billDateFormat.parse(text) } catch (_: Exception) { null }

    /** Runs [onText] with the trimmed text on every change, then re-filters. */
    private fun android.widget.EditText.onChange(onText: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                onText(s?.toString()?.trim().orEmpty())
                refresh()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    /**
     * Prints without opening the bill first. Marked a duplicate for the same reason
     * the preview is: this list is history, so the customer already has the original.
     */
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
            // A cancelled bill has nothing to reproduce, so it is not offered.
            holder.btnPrint.isEnabled = !bill.cancelled
            holder.btnPrint.alpha = if (bill.cancelled) 0.4f else 1f
            holder.btnPrint.setOnClickListener { onPrint(bill) }
        }

        override fun getItemCount() = items.size
    }

    companion object {
        private const val ARG_PICK_FOR_RETURN = "pick_for_return"

        /** The bill picker for a sale return - see [pickingForReturn]. */
        fun forReturn(): BillListFragment = BillListFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_PICK_FOR_RETURN, true) }
        }
    }
}
