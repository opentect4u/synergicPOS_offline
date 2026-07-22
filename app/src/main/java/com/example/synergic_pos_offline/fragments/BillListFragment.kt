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
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Item-wise list of bills, sourced from the database via [BillDao]. Each row has a
 * "View" button that opens the already-designed receipt preview ([BillFragment]).
 */
class BillListFragment : Fragment(), TitledScreen {

    override val screenTitle = "Bills"

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
        CUSTOM("Custom range", null)
    }
    private var range = Range.TODAY
    private var customFrom: Calendar? = null
    private var customTo: Calendar? = null
    private val billDateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.US)
    private val billDateTimeFormat = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US)

    /** Sort options for the bill list. */
    private enum class Sort(val label: String) {
        DATE_DESC("Date (newest)"),
        DATE_ASC("Date (oldest)"),
        AMOUNT_DESC("Amount (high–low)"),
        AMOUNT_ASC("Amount (low–high)")
    }
    private var sort = Sort.DATE_DESC

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

        // Date-range dropdown
        val actRange = view.findViewById<MaterialAutoCompleteTextView>(R.id.actRange)
        val ranges = Range.values()
        actRange.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, ranges.map { it.label })
        )
        actRange.setText(range.label, false)
        actRange.setOnItemClickListener { _, _, pos, _ ->
            range = ranges[pos]
            llCustomRange.visibility = if (range == Range.CUSTOM) View.VISIBLE else View.GONE
            refresh()
        }

        // Sort dropdown
        val actSort = view.findViewById<MaterialAutoCompleteTextView>(R.id.actSort)
        val sorts = Sort.values()
        actSort.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, sorts.map { it.label })
        )
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
            val matchesStatus = b.cancelled == showCancelled
            matchesText && matchesRange && matchesItem && matchesAmount && matchesStatus
        }

        val sorted = when (sort) {
            Sort.DATE_DESC -> filtered.sortedByDescending { sortMillis(it) }
            Sort.DATE_ASC -> filtered.sortedBy { sortMillis(it) }
            Sort.AMOUNT_DESC -> filtered.sortedByDescending { it.amount }
            Sort.AMOUNT_ASC -> filtered.sortedBy { it.amount }
        }

        rv.adapter = BillAdapter(sorted) { openBill(it) }
        tvEmpty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
    }

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

    private fun openBill(bill: BillDao.Bill) {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_container,
                BillFragment.newInstance(bill.billNo, bill.name, bill.date, bill.time, bill.total, bill.receiptNo)
            )
            .addToBackStack(null)
            .commit()
    }

    private inner class BillAdapter(
        private val items: List<BillDao.Bill>,
        private val onView: (BillDao.Bill) -> Unit
    ) : RecyclerView.Adapter<BillAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvBillNo: TextView = view.findViewById(R.id.tvRowBillNo)
            val tvAmount: TextView = view.findViewById(R.id.tvRowAmount)
            val tvName: TextView = view.findViewById(R.id.tvRowName)
            val tvDateTime: TextView = view.findViewById(R.id.tvRowDateTime)
            val btnView: MaterialButton = view.findViewById(R.id.btnViewBill)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_bill_row, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val bill = items[position]
            holder.tvBillNo.text = "Bill No: ${bill.billNo}"
            holder.tvAmount.text = "₹ ${bill.total}"
            holder.tvName.text = bill.name
            holder.tvDateTime.text = "${bill.date}  ${bill.time}"

            val accent = ThemeManager.getThemeColor(holder.itemView.context)
            holder.btnView.setTextColor(accent)
            holder.btnView.strokeColor = ColorStateList.valueOf(accent)
            holder.btnView.iconTint = ColorStateList.valueOf(accent)
            holder.btnView.setOnClickListener { onView(bill) }
        }

        override fun getItemCount() = items.size
    }
}
