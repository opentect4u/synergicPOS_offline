package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.CustomerDao
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * "Customers" management screen — a concrete [DataTableFragment] backed by the
 * [CustomerDao] SQLite table. Full Add/Edit/Delete persisted.
 *
 * The Credit toggle gates the Credit Limit / Credit Days inputs.
 */
class CustomerFragment : DataTableFragment() {

    override val screenTitle = "Customers"

    // Table columns. Cell layout per row: [name, phone, credit, balance].
    override val columns = listOf("Name", "Phone", "Credit", "Balance")

    private val dao: CustomerDao by lazy { CustomerDao(requireContext()) }

    /** Full customers keyed by row id, for edit prefill. */
    private val cache = mutableMapOf<String, CustomerDao.Customer>()

    // ---- Data --------------------------------------------------------------

    override fun loadRows(): MutableList<DataRow> {
        cache.clear()
        val customers = dao.getAll()
        for (c in customers) cache[c.id.toString()] = c
        return customers.map { it.toRow() }.toMutableList()
    }

    private fun CustomerDao.Customer.toRow(): DataRow = DataRow(
        id.toString(),
        listOf(name, phone, if (creditEnabled) "Yes" else "No", money(balance))
    )

    private fun money(v: Double): String = "₹ " + String.format("%.2f", v)

    // ---- Custom Add / Edit popups -----------------------------------------

    override fun onAddRow() = showCustomerDialog(null)

    override fun onEditRow(row: DataRow) = showCustomerDialog(row)

    override fun onRowsDeleted(ids: Set<String>) {
        dao.delete(ids.mapNotNull { it.toLongOrNull() })
    }

    private fun showCustomerDialog(row: DataRow?) {
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)
        val existing = row?.let { cache[it.id] }

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_customer, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val etName = view.findViewById<TextInputEditText>(R.id.etName)
        val etPhone = view.findViewById<TextInputEditText>(R.id.etPhone)
        val etAddress = view.findViewById<TextInputEditText>(R.id.etAddress)
        val etGstin = view.findViewById<TextInputEditText>(R.id.etGstin)
        val etBalance = view.findViewById<TextInputEditText>(R.id.etBalance)
        val swCredit = view.findViewById<SwitchMaterial>(R.id.swCredit)
        val tvCreditState = view.findViewById<TextView>(R.id.tvCreditState)
        val tilCreditLimit = view.findViewById<TextInputLayout>(R.id.tilCreditLimit)
        val tilCreditDays = view.findViewById<TextInputLayout>(R.id.tilCreditDays)
        val etCreditLimit = view.findViewById<TextInputEditText>(R.id.etCreditLimit)
        val etCreditDays = view.findViewById<TextInputEditText>(R.id.etCreditDays)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnFormNegative)

        tvTitle.text = if (existing == null) "Add Customer" else "Edit Customer"
        etName.setText(existing?.name.orEmpty())
        etPhone.setText(existing?.phone.orEmpty())
        etAddress.setText(existing?.address.orEmpty())
        etGstin.setText(existing?.gstin.orEmpty())
        etBalance.setText(existing?.let { trimNumber(it.balance) }.orEmpty())
        etCreditLimit.setText(existing?.let { trimNumber(it.creditLimit) }.orEmpty())
        etCreditDays.setText(existing?.creditDays?.takeIf { it != 0 || existing.creditEnabled }?.toString().orEmpty())
        btnSave.text = if (existing == null) "Add" else "Update"

        fun applyCreditState(enabled: Boolean) {
            tvCreditState.text = if (enabled) "Yes" else "No"
            tilCreditLimit.isEnabled = enabled
            tilCreditDays.isEnabled = enabled
        }
        swCredit.isChecked = existing?.creditEnabled ?: false
        applyCreditState(swCredit.isChecked)
        swCredit.setOnCheckedChangeListener { _, checked -> applyCreditState(checked) }

        ThemeManager.applyTheme(view)
        swCredit.thumbTintList = ColorStateList.valueOf(accent)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent)
        // ThemeManager fills every MaterialButton's background; restore the
        // outlined (border) look for the negative/Cancel button.
        btnCancel.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        btnCancel.setTextColor(accent)
        btnCancel.strokeColor = ColorStateList.valueOf(accent)

        btnSave.setOnClickListener {
            val name = etName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                etName.error = "Name is required"
                return@setOnClickListener
            }
            val credit = swCredit.isChecked
            val customer = CustomerDao.Customer(
                id = existing?.id ?: 0L,
                name = name,
                address = etAddress.text?.toString()?.trim().orEmpty(),
                phone = etPhone.text?.toString()?.trim().orEmpty(),
                gstin = etGstin.text?.toString()?.trim().orEmpty(),
                creditEnabled = credit,
                creditLimit = if (credit) etCreditLimit.text?.toString()?.toDoubleOrNull() ?: 0.0 else 0.0,
                creditDays = if (credit) etCreditDays.text?.toString()?.toIntOrNull() ?: 0 else 0,
                balance = etBalance.text?.toString()?.toDoubleOrNull() ?: 0.0
            )

            if (existing == null) {
                val id = dao.insert(customer)
                if (id == -1L) { toast("Save failed"); return@setOnClickListener }
                dialog.dismiss()
                cache[id.toString()] = customer.copy(id = id)
                addRow(DataRow(id.toString(), listOf(name, customer.phone, if (credit) "Yes" else "No", money(customer.balance))))
                toast("Customer added")
            } else {
                dao.update(existing.id, customer)
                dialog.dismiss()
                val key = existing.id.toString()
                cache[key] = customer.copy(id = existing.id)
                updateRow(key, listOf(name, customer.phone, if (credit) "Yes" else "No", money(customer.balance)))
                toast("Customer updated")
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.CENTER)
        }
    }

    /** Renders a stored amount without a trailing ".0" for whole numbers. */
    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
