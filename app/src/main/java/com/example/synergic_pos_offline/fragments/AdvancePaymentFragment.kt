package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.AdvancePaymentDao
import com.example.synergic_pos_offline.database.CustomerDao
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.PaymentReceiptRenderer
import com.example.synergic_pos_offline.utils.PrinterSetup
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.ThemeManager
import com.example.synergic_pos_offline.utils.ThermalPrinter
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

/**
 * Advance Payment - collecting what a credit customer already owes.
 *
 * The operator types a name or a phone number, picks the customer out of a
 * type-ahead dropdown, and gets their credit position back: what is due, what has
 * been paid, and how much credit is left. Part or all of the due can then be taken,
 * and the collection prints a receipt.
 *
 * Only a customer with credit switched on has dues to collect, so picking anyone
 * else says so and stops there - there is no balance to work against.
 *
 * The money itself is moved by [AdvancePaymentDao.collect], which is the single
 * place `md_customers` is adjusted; this screen only gathers and reports.
 */
class AdvancePaymentFragment : Fragment(), TitledScreen {

    override val screenTitle = "Advance Payment"

    private val dao: AdvancePaymentDao by lazy { AdvancePaymentDao(requireContext()) }

    /** Tender modes a due can be settled with. */
    private val modes = listOf("CASH", "UPI", "CARD", "CHEQUE", "ONLINE")

    /** The customer on screen, and their figures as last read. */
    private var account: AdvancePaymentDao.Account? = null

    /** The label a pick put in the search box, so an edit to it can be spotted. */
    private var selectedLabel: String? = null

    private lateinit var root: View
    private lateinit var actvCustomer: MaterialAutoCompleteTextView
    private lateinit var actvMode: MaterialAutoCompleteTextView
    private lateinit var tilAmount: TextInputLayout
    private lateinit var etAmount: TextInputEditText
    private lateinit var etNote: TextInputEditText
    private lateinit var llEmptyState: View
    private lateinit var cardNoCredit: MaterialCardView
    private lateinit var cardAccount: MaterialCardView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_advance_payment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view

        actvCustomer = view.findViewById(R.id.actvCustomer)
        actvMode = view.findViewById(R.id.actvMode)
        tilAmount = view.findViewById(R.id.tilAmount)
        etAmount = view.findViewById(R.id.etAmount)
        etNote = view.findViewById(R.id.etNote)
        llEmptyState = view.findViewById(R.id.llEmptyState)
        cardNoCredit = view.findViewById(R.id.cardNoCredit)
        cardAccount = view.findViewById(R.id.cardAccount)

        val suggestions = CustomerSuggestionAdapter()
        actvCustomer.setAdapter(suggestions)
        actvCustomer.setOnItemClickListener { _, _, position, _ ->
            select(suggestions.getItem(position).id)
        }
        // Editing the box after a pick means the operator is looking for somebody
        // else, so the figures on screen - which are about the customer whose label
        // was in the box - stop applying and are taken down.
        actvCustomer.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val label = selectedLabel ?: return
                if (s?.toString() != label) clearSelection()
            }
        })

        actvMode.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, modes))
        actvMode.setText(modes.first(), false)

        // A rejected amount stops being wrong the moment it is edited.
        etAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { tilAmount.error = null }
        })

        view.findViewById<MaterialButton>(R.id.btnPayFull).setOnClickListener {
            val due = account?.totalDue ?: return@setOnClickListener
            etAmount.setText(String.format(Locale.US, "%.2f", due))
            etAmount.setSelection(etAmount.text?.length ?: 0)
        }
        view.findViewById<MaterialButton>(R.id.btnCollect).setOnClickListener { collect() }

        ThemeManager.applyTheme(view)
        // ThemeManager fills every MaterialButton; restore the outlined look on the
        // "Full" shortcut so it reads as secondary to the submit button.
        val accent = ThemeManager.getThemeColor(requireContext())
        view.findViewById<MaterialButton>(R.id.btnPayFull).apply {
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(accent)
            strokeColor = ColorStateList.valueOf(accent)
        }

        showEmptyState()
    }

    // ---- Selection ---------------------------------------------------------

    /** Reads [customerId]'s position and shows it, or explains why it cannot. */
    private fun select(customerId: Long) {
        val loaded = dao.account(customerId)
        if (loaded == null) {
            toast("That customer is no longer on file")
            showEmptyState()
            return
        }

        account = loaded
        val customer = loaded.customer
        selectedLabel = displayName(customer)
        actvCustomer.setText(selectedLabel, false)

        if (!customer.creditEnabled) {
            // Nothing to collect: without credit, nothing was ever put on account.
            account = null
            llEmptyState.visibility = View.GONE
            cardAccount.visibility = View.GONE
            cardNoCredit.visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.tvNoCreditMessage).text =
                "Credit is switched off for ${customer.name.ifBlank { "this customer" }}, so there are " +
                    "no dues to collect. Turn Credit on for them under Master → Customers first."
            return
        }

        cardNoCredit.visibility = View.GONE
        llEmptyState.visibility = View.GONE
        cardAccount.visibility = View.VISIBLE
        bind(loaded)
    }

    /** Paints the account card from [acc]. */
    private fun bind(acc: AdvancePaymentDao.Account) {
        val customer = acc.customer
        val name = customer.name.trim().ifEmpty { "Unnamed customer" }

        root.findViewById<TextView>(R.id.tvAccountInitials).apply {
            text = name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }
                .take(2).joinToString("").ifEmpty { "+" }
            background?.mutate()?.setTint(ThemeManager.getThemeColor(requireContext()))
        }
        root.findViewById<TextView>(R.id.tvAccountName).text = name
        root.findViewById<TextView>(R.id.tvAccountPhone).text =
            customer.phone.ifBlank { "No phone on file" }
        root.findViewById<TextView>(R.id.tvAccountGstin).apply {
            text = "GSTIN: ${customer.gstin}"
            visibility = if (customer.gstin.isBlank()) View.GONE else View.VISIBLE
        }

        // A due that has gone past zero is money the customer is ahead by, not a
        // debt, so it stops reading as a red figure even though the number itself
        // is left exactly as the arithmetic left it.
        root.findViewById<TextView>(R.id.tvStatDue).apply {
            text = money(acc.totalDue)
            setTextColor(
                if (acc.totalDue < -0.005) ContextCompat.getColor(context, R.color.menu_sale_icon)
                else ContextCompat.getColor(context, R.color.menu_delete_icon)
            )
        }
        root.findViewById<TextView>(R.id.tvStatPaid).text = money(acc.totalPaid)
        root.findViewById<TextView>(R.id.tvStatCredit).text = money(acc.creditLimit)
        root.findViewById<TextView>(R.id.tvStatBilled).text = money(acc.totalBilled)

        // Any amount can be taken, including more than is owed - that just leaves
        // the customer in credit. Only the "Full" shortcut needs a due to copy.
        val hasDue = acc.totalDue > 0.005
        root.findViewById<TextView>(R.id.tvDueHint).text = when {
            hasDue -> "${money(acc.totalDue)} outstanding. A part payment is fine, " +
                "and anything over it is carried as credit."
            acc.totalDue < -0.005 -> "This account is ${money(-acc.totalDue)} in credit."
            else -> "Nothing outstanding - anything taken is carried as credit."
        }
        root.findViewById<MaterialButton>(R.id.btnPayFull).isEnabled = hasDue
        tilAmount.error = null
        etAmount.setText("")
        etNote.setText("")
    }

    private fun clearSelection() {
        selectedLabel = null
        if (account == null && cardNoCredit.visibility != View.VISIBLE) return
        showEmptyState()
    }

    private fun showEmptyState() {
        account = null
        selectedLabel = null
        cardAccount.visibility = View.GONE
        cardNoCredit.visibility = View.GONE
        llEmptyState.visibility = View.VISIBLE
    }

    // ---- Collection --------------------------------------------------------

    private fun collect() {
        val acc = account ?: return
        val entered = etAmount.text?.toString()?.trim()?.toDoubleOrNull()
        if (entered == null || entered <= 0.0) {
            tilAmount.error = "Enter the amount received"
            return
        }
        // Paying past the due is allowed: the balance carries on down through zero
        // and the customer ends up in credit. Only the amount itself is checked.
        tilAmount.error = null

        val mode = actvMode.text?.toString()?.takeIf { it in modes } ?: modes.first()
        val collection = dao.collect(
            customerId = acc.customer.id,
            amount = entered,
            mode = mode,
            notes = etNote.text?.toString()?.trim()
        )
        if (collection == null) {
            toast("Could not record the payment")
            return
        }

        // Re-read rather than patching the figures on screen: the master is what
        // the next receipt will quote, so it is what this screen should show.
        dao.account(acc.customer.id)?.let { account = it; bind(it) }

        val receipt = PaymentReceiptRenderer.Receipt(
            receiptNumber = collection.receiptNumber,
            dateTime = collection.dateTime,
            cashier = SessionManager.currentUser?.userId?.uppercase() ?: "---",
            customerId = acc.customer.id,
            customerName = acc.customer.name,
            customerPhone = acc.customer.phone,
            customerGstin = acc.customer.gstin,
            previousDue = collection.previousDue,
            amountPaid = collection.amountPaid,
            totalDue = collection.totalDue,
            totalPaid = collection.totalPaid,
            creditLimit = collection.creditLimit,
            mode = collection.mode
        )

        DialogUtils.showSuccess(
            context = requireContext(),
            title = "Payment recorded",
            message = "${money(collection.amountPaid)} collected from " +
                "${acc.customer.name.ifBlank { "the customer" }}. " +
                when {
                    collection.totalDue > 0.005 -> "Outstanding due is now ${money(collection.totalDue)}."
                    collection.totalDue < -0.005 -> "The account is now ${money(-collection.totalDue)} in credit."
                    else -> "The account is now fully settled."
                },
            buttonText = "Print receipt"
        ) { printReceipt(receipt) }
    }

    // ---- Printing ----------------------------------------------------------

    /**
     * Sends the slip to the BILL printer, the same one a sale receipt goes to. The
     * payment is already saved, so a printer problem is only reported - it never
     * undoes a collection that has been taken at the counter.
     */
    private fun printReceipt(receipt: PaymentReceiptRenderer.Receipt) {
        val config = ThermalPrinter.configForPurpose(requireContext(), "BILL")
            ?: ThermalPrinter.savedConfig(requireContext())
        if (config == null) {
            PrinterSetup.show(requireContext()) { saved -> sendToPrinter(receipt, saved) }
            return
        }
        sendToPrinter(receipt, config)
    }

    private fun sendToPrinter(receipt: PaymentReceiptRenderer.Receipt, config: ThermalPrinter.Config) {
        // May run from the printer-setup dialog's callback after this screen is gone;
        // use the current context and bail rather than crash on requireContext().
        val ctx = context ?: return
        val capture = PaymentReceiptRenderer(ctx).renderToBitmap(receipt, config.paperDots)
        if (capture == null) {
            toast("Could not render the receipt")
            return
        }
        ThermalPrinter.print(ctx, capture, config) { result ->
            if (!isAdded) return@print
            when (result) {
                is ThermalPrinter.Result.Success -> toast("Printed")
                is ThermalPrinter.Result.Sent -> toast("Sent to printer")
                is ThermalPrinter.Result.Failure -> toast("Print failed: ${result.message}")
            }
        }
    }

    // ---- Type-ahead --------------------------------------------------------

    /** "Name · phone", the one label the dropdown and the search box both use. */
    private fun displayName(customer: CustomerDao.Customer): String {
        val name = customer.name.trim().ifEmpty { "Unnamed customer" }
        return if (customer.phone.isBlank()) name else "$name · ${customer.phone}"
    }

    /**
     * Matches customers on name or phone as the operator types. Filtering runs on
     * the framework's filter thread, so the lookup goes to the database on every
     * keystroke rather than being held in memory - the master can be large, and
     * whatever was just edited on the Customers screen is picked up straight away.
     */
    private inner class CustomerSuggestionAdapter : ArrayAdapter<CustomerDao.Customer>(
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
                val found = if (term.isBlank()) emptyList() else runCatching { dao.search(term) }
                    .getOrDefault(emptyList())
                return FilterResults().apply { values = found; count = found.size }
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                matches.clear()
                @Suppress("UNCHECKED_CAST")
                (results.values as? List<CustomerDao.Customer>)?.let { matches.addAll(it) }
                if (results.count > 0) notifyDataSetChanged() else notifyDataSetInvalidated()
            }

            /** What lands in the box once a row is tapped. */
            override fun convertResultToString(resultValue: Any?): CharSequence =
                (resultValue as? CustomerDao.Customer)?.let { displayName(it) }.orEmpty()
        }
    }

    // ---- Small helpers -----------------------------------------------------

    private fun money(v: Double): String = "₹ " + String.format(Locale.US, "%.2f", v)

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
