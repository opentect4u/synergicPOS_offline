package com.example.synergic_pos_offline.fragments

import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.CustomerDao
import com.example.synergic_pos_offline.database.CustomerLedgerDao
import com.example.synergic_pos_offline.utils.CustomerLedgerView
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Customer Ledger - the running account between the business and one customer over
 * a period.
 *
 * A phone number identifies the customer (it is what the till captures on a sale),
 * a date range bounds the statement, and the result reads the way a ledger reads:
 * an opening balance, every movement that touched the account, and the closing
 * balance those movements arrive at.
 *
 * "In" is money received from the customer; "Out" is value put on their account by
 * a credit sale. The whole statement comes from [CustomerLedgerDao], which is also
 * what the printed copy renders from, so paper and screen cannot disagree.
 */
class CustomerLedgerFragment : Fragment(), TitledScreen {

    override val screenTitle = "Customer Ledger"

    private val dao: CustomerLedgerDao by lazy { CustomerLedgerDao(requireContext()) }
    private val customers: CustomerDao by lazy { CustomerDao(requireContext()) }

    /** The generated statement, held so Print sends exactly what is on screen. */
    private var ledger: CustomerLedgerDao.Ledger? = null

    private lateinit var root: View
    private lateinit var actvPhone: MaterialAutoCompleteTextView
    private lateinit var tilPhone: TextInputLayout
    private lateinit var etFrom: TextInputEditText
    private lateinit var etTo: TextInputEditText
    private lateinit var btnPrint: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_customer_ledger, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view

        actvPhone = view.findViewById(R.id.actvPhone)
        tilPhone = view.findViewById(R.id.tilPhone)
        etFrom = view.findViewById(R.id.etFrom)
        etTo = view.findViewById(R.id.etTo)
        btnPrint = view.findViewById(R.id.btnPrintLedger)

        // Opens on the month to date - the range a statement is usually asked for -
        // rather than empty, so Generate works on the first tap.
        val today = Calendar.getInstance()
        val monthStart = (today.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        etFrom.setText(iso(monthStart.time))
        etTo.setText(iso(today.time))

        etFrom.setOnClickListener { pickDate(etFrom) }
        etTo.setOnClickListener { pickDate(etTo) }

        val suggestions = PhoneSuggestionAdapter()
        actvPhone.setAdapter(suggestions)
        actvPhone.setOnItemClickListener { _, _, position, _ ->
            actvPhone.setText(suggestions.getItem(position).phone, false)
            tilPhone.error = null
        }

        view.findViewById<MaterialButton>(R.id.btnGenerate).setOnClickListener { generate() }
        btnPrint.setOnClickListener {
            ledger?.let { led -> CustomerLedgerView.print(requireContext(), led) { if (isAdded) toast(it) } }
        }

        ThemeManager.applyTheme(view)
        // ThemeManager fills every MaterialButton; Print is the secondary action here
        // and keeps its outlined look.
        val accent = ThemeManager.getThemeColor(requireContext())
        btnPrint.apply {
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(accent)
            strokeColor = ColorStateList.valueOf(accent)
        }
    }

    // ---- Generating --------------------------------------------------------

    private fun generate() {
        val phone = actvPhone.text?.toString()?.trim().orEmpty()
        if (phone.isEmpty()) {
            tilPhone.error = "Enter a phone number"
            return
        }
        val from = etFrom.text?.toString()?.trim().orEmpty()
        val to = etTo.text?.toString()?.trim().orEmpty()
        if (from.isEmpty() || to.isEmpty()) {
            toast("Pick both dates")
            return
        }
        if (from > to) {
            // ISO dates sort lexicographically, so this is the whole check.
            toast("The From date is after the To date")
            return
        }
        tilPhone.error = null

        val result = dao.forPhone(phone, from, to)
        if (result == null) {
            ledger = null
            showEmpty("No customer on $phone", "Check the number, or add them under Master → Customers.")
            return
        }

        ledger = result
        bind(result)
    }

    private fun bind(led: CustomerLedgerDao.Ledger) {
        root.findViewById<View>(R.id.llLedgerEmpty).visibility = View.GONE
        root.findViewById<View>(R.id.llLedgerResult).visibility = View.VISIBLE
        btnPrint.isEnabled = true
        CustomerLedgerView.bind(root, led)
    }

    private fun showEmpty(title: String, hint: String) {
        root.findViewById<View>(R.id.llLedgerResult).visibility = View.GONE
        root.findViewById<View>(R.id.llLedgerEmpty).visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.tvLedgerEmptyTitle).text = title
        root.findViewById<TextView>(R.id.tvLedgerEmptyHint).text = hint
        btnPrint.isEnabled = false
    }

    // ---- Phone type-ahead --------------------------------------------------

    /**
     * Suggests customers as a number is typed. The field takes a phone number, but
     * matching on name too means an operator who knows the customer by name and not
     * by number can still find them.
     */
    private inner class PhoneSuggestionAdapter : ArrayAdapter<CustomerDao.Customer>(
        requireContext(), R.layout.item_customer_suggestion, mutableListOf()
    ) {
        private val matches = mutableListOf<CustomerDao.Customer>()

        override fun getCount(): Int = matches.size

        override fun getItem(position: Int): CustomerDao.Customer = matches[position]

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(
                R.layout.item_customer_suggestion, parent, false
            )
            val customer = matches[position]
            view.findViewById<TextView>(R.id.tvSuggestionName).text =
                customer.name.trim().ifEmpty { "Unnamed customer" }
            view.findViewById<TextView>(R.id.tvSuggestionPhone).text =
                customer.phone.ifBlank { "No phone on file" }
            view.findViewById<TextView>(R.id.tvSuggestionCredit).apply {
                text = if (customer.creditEnabled) "CREDIT" else "NO CREDIT"
                setTextColor(
                    if (customer.creditEnabled) ThemeManager.getThemeColor(context)
                    else Color.parseColor("#B3261E")
                )
            }
            return view
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            getView(position, convertView, parent)

        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val term = constraint?.toString().orEmpty()
                val found = if (term.isBlank()) emptyList()
                else runCatching { customers.search(term) }.getOrDefault(emptyList())
                return FilterResults().apply { values = found; count = found.size }
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                matches.clear()
                @Suppress("UNCHECKED_CAST")
                (results.values as? List<CustomerDao.Customer>)?.let { matches.addAll(it) }
                if (results.count > 0) notifyDataSetChanged() else notifyDataSetInvalidated()
            }

            /** The field holds the number, so that is what a pick puts in it. */
            override fun convertResultToString(resultValue: Any?): CharSequence =
                (resultValue as? CustomerDao.Customer)?.phone.orEmpty()
        }
    }

    // ---- Small helpers -----------------------------------------------------

    /** Opens a calendar seeded with whatever [field] holds, and writes the pick back. */
    private fun pickDate(field: TextInputEditText) {
        val calendar = Calendar.getInstance()
        field.text?.toString()?.takeIf { it.isNotBlank() }?.let { current ->
            runCatching {
                val parts = current.split("-")
                calendar.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
            }
        }
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                field.setText(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun iso(date: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
