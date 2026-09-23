package com.example.synergic_pos_offline.utils

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.CustomerDao
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

/**
 * The credit sale's customer form - the whole customer master record, captured at the
 * till: phone (with suggestions as it is typed), name, address, GSTIN, birthday,
 * anniversary, the Credit switch, the credit limit and the balance.
 *
 * It was the grocery checkout's own, and lives here now so the restaurant's payment
 * screens open the very same form rather than a second one that drifts from it - see
 * [CreditSale]. What it saves by is [CreditCustomerForm]; what it saves to is the
 * customer master, matched on the phone typed.
 */
object CreditCustomerDialog {

    /** What the form opens on - the sale's own customer details, before any lookup. */
    data class Prefill(
        val phone: String = "",
        val name: String = "",
        val address: String = "",
        val gstin: String = ""
    )

    /** A small watcher that just runs [block] after every change. */
    private fun watcher(block: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = block()
    }

    /**
     * Writes the details captured for a credit sale to the customer master, so the
     * same questions are not asked again on the next visit, and returns the record
     * as it now stands.
     *
     * Matched on the phone number typed in the form, which is the number the bill
     * goes out under. A number not already on file is inserted rather than left to
     * live only on this one bill: a credit sale has to hang off a customer record for
     * the balance it runs up to be traceable at all.
     */
    private fun save(context: Context, details: CustomerDao.Customer): CustomerDao.Customer {
        val dao = CustomerDao(context)
        runCatching {
            val onFile = dao.findByPhone(details.phone)
            if (onFile == null) dao.insert(details.copy(id = 0L)) else dao.update(onFile.id, details.copy(id = onFile.id))
        }.onFailure { android.util.Log.e("CreditCustomerDialog", "Could not save customer details", it) }
        return runCatching { dao.findByPhone(details.phone) }.getOrNull() ?: details
    }

    /** Opens a calendar seeded with [current] ("yyyy-MM-dd") and returns the pick. */
    private fun pickDate(context: Context, current: String?, onPicked: (String) -> Unit) {
        val cal = java.util.Calendar.getInstance()
        current?.takeIf { it.isNotBlank() }?.let { s ->
            runCatching {
                val p = s.split("-")
                cal.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt())
            }
        }
        android.app.DatePickerDialog(
            context,
            { _, year, month, day ->
                onPicked(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day))
            },
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH),
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        ).show()
    }

    /** A whole number without a needless ".0" - what the master shows in its inputs. */
    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    fun show(
        context: Context,
        prefill: Prefill,
        onFile: CustomerDao.Customer?,
        money: (Double) -> String,
        onSaved: (CustomerDao.Customer) -> Unit
    ) {
        val ctx = context
        var creditCustomerPhone = prefill.phone
        var creditCustomerName = prefill.name
        var creditCustomerAddress = prefill.address
        var creditCustomerGstin = prefill.gstin
        fun toast(message: String) = Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        fun pickDate(current: String?, onPicked: (String) -> Unit) = pickDate(ctx, current, onPicked)
        val inflater = LayoutInflater.from(ctx)
        val view = inflater.inflate(R.layout.dialog_form, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx).setView(view).create().also {
            it.setCanceledOnTouchOutside(false)
        }
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val accent = ThemeManager.getThemeColor(ctx)
        val grid = view.findViewById<GridLayout>(R.id.glFields)
        val btnPositive = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnNegative = view.findViewById<MaterialButton>(R.id.btnFormNegative)

        view.findViewById<TextView>(R.id.tvFormTitle).text = "Credit Sale - Customer Details"
        btnPositive.text = "Save"
        btnNegative.text = "Cancel"

        val density = ctx.resources.displayMetrics.density
        val margin = (8 * density).toInt()

        // Whatever the master already holds for this customer, so the form opens as
        // the record stands and a field left alone saves back unchanged.

        /** Adds one outlined input at [row], spanning [span] of the grid's 2 columns. */
        fun field(hint: String, row: Int, col: Int, span: Int, value: String): TextInputLayout {
            val til = inflater.inflate(R.layout.item_form_field, null, false) as TextInputLayout
            til.hint = hint
            til.layoutParams = GridLayout.LayoutParams().apply {
                rowSpec = GridLayout.spec(row)
                columnSpec = GridLayout.spec(col, span, 1f)
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(margin, margin / 2, margin, margin / 2)
            }
            grid.addView(til)
            til.findViewById<TextInputEditText>(R.id.etField).setText(value)
            return til
        }

        fun editText(til: TextInputLayout) = til.findViewById<TextInputEditText>(R.id.etField)

        // The same fields the customer master keeps, in the same order, so a credit
        // sale can capture the whole record rather than the four details the bill
        // itself needs and leave the rest to be filled in later.
        val tilPhone = field("Phone Number", 0, 0, 2, creditCustomerPhone)
        val etPhone = editText(tilPhone).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(10))
        }
        val tilName = field("Customer Name", 1, 0, 2, creditCustomerName)
        val etName = editText(tilName).apply { InputLimits.cap(this, InputLimits.TEXT) }
        val etAddress = editText(field("Address", 2, 0, 2, creditCustomerAddress)).apply {
            minLines = 3
            maxLines = 5
            isSingleLine = false
            InputLimits.cap(this, InputLimits.TEXT_AREA)
        }
        val etGstin = editText(field("GSTIN", 3, 0, 2, creditCustomerGstin))
            .apply { InputLimits.cap(this, InputLimits.GSTIN) }

        // Dates are picked from a calendar, never typed, so they can only ever be
        // stored in the yyyy-MM-dd the master expects.
        val etBirthday = editText(field("Birthday", 4, 0, 1, onFile?.birthday.orEmpty())).apply {
            isFocusable = false
            setOnClickListener { pickDate(text?.toString()) { setText(it) } }
        }
        val etAnniversary = editText(field("Anniversary", 4, 1, 1, onFile?.anniversary.orEmpty())).apply {
            isFocusable = false
            setOnClickListener { pickDate(text?.toString()) { setText(it) } }
        }

        // Credit switch, laid out as its own full-width row.
        val creditRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = GridLayout.LayoutParams().apply {
                rowSpec = GridLayout.spec(5)
                columnSpec = GridLayout.spec(0, 2, 1f)
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(margin, margin / 2, margin, margin / 2)
            }
        }
        creditRow.addView(TextView(ctx).apply {
            text = "Credit"
            textSize = 16f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_main))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val swCredit = SwitchMaterial(ctx).apply {
            isChecked = onFile?.creditEnabled ?: true
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        creditRow.addView(swCredit)
        grid.addView(creditRow)

        val tilLimit = field("Credit Limit", 6, 0, 1, onFile?.let { trimNumber(it.creditLimit) }.orEmpty())
        val tilBalance = field("Balance Amount", 6, 1, 1, onFile?.let { trimNumber(it.balance) }.orEmpty())
        editText(tilLimit).inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        editText(tilBalance).inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        InputLimits.cap(editText(tilLimit), InputLimits.NUMBER)
        InputLimits.cap(editText(tilBalance), InputLimits.NUMBER)

        // Credit gates the figures that only exist because of it, exactly as the
        // customer master does.
        fun applyCreditState(enabled: Boolean) {
            tilLimit.isEnabled = enabled
            tilBalance.isEnabled = enabled
        }
        applyCreditState(swCredit.isChecked)
        swCredit.setOnCheckedChangeListener { _, checked -> applyCreditState(checked) }

        // Phone autocomplete with suggestions
        val customerDao = CustomerDao(ctx)
        val suggestionsContainer = view.findViewById<LinearLayout>(R.id.llSuggestions)

        // Fills every field from a customer already on file - used both when a phone
        // suggestion is picked and when a full 10-digit number matches one directly.
        fun fillFrom(customer: CustomerDao.Customer) {
            etName.setText(customer.name)
            etAddress.setText(customer.address)
            etGstin.setText(customer.gstin)
            etBirthday.setText(customer.birthday)
            etAnniversary.setText(customer.anniversary)
            swCredit.isChecked = customer.creditEnabled
            editText(tilLimit).setText(trimNumber(customer.creditLimit))
            editText(tilBalance).setText(trimNumber(customer.balance))
        }

        etPhone.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""

                // A complete 10-digit number that matches a customer on file fills the
                // whole record into the fields automatically - no suggestion to pick.
                if (query.length == 10 && query.all { it.isDigit() }) {
                    suggestionsContainer.removeAllViews()
                    suggestionsContainer.visibility = View.GONE
                    customerDao.getAll().firstOrNull { it.phone == query }?.let { fillFrom(it) }
                    return
                }

                // Anything shorter is offered in the dropdown below.
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // The dropdown every customer form shares, from the first digit: picking a
        // customer writes their number in and fills the whole record from them. See
        // CustomerPhoneSearch. It replaces a list of up to five that only appeared
        // from the third digit, drawn inline between the fields.
        val dropdown = CustomerPhoneSearch.attach(ctx, tilPhone, etPhone) { picked ->
            etPhone.setText(picked.phone)   // a whole number, which also fills the record
            etPhone.setSelection(etPhone.text?.length ?: 0)
            fillFrom(picked)
        }
        dialog.setOnDismissListener { dropdown.dismiss() }

        ThemeManager.applyTheme(grid)
        ThemeManager.styleDialogButtons(btnPositive, btnNegative)

        /**
         * Refuses the save on the field that caused it, and says why there.
         *
         * A toast was the form's only complaint, and a toast raised over a dialog is
         * gone - or never seen at all - long before it is read: a form that refused
         * to save was indistinguishable from a Save button that did nothing. The
         * reason now sits under the offending field until it is answered.
         */
        fun refuse(til: TextInputLayout, why: String) {
            til.isErrorEnabled = true
            til.error = why
            editText(til).requestFocus()
            toast(why)
        }

        // And it clears the moment the operator answers it, so a message already
        // dealt with cannot sit there contradicting a form that would now save.
        listOf(tilPhone, tilName, tilLimit, tilBalance).forEach { til ->
            editText(til).addTextChangedListener(watcher { til.error = null })
        }

        btnPositive.setOnClickListener {
            val phone = etPhone.text?.toString()?.trim().orEmpty()
            val name = etName.text?.toString()?.trim().orEmpty()
            val credit = swCredit.isChecked
            val limit = editText(tilLimit).text?.toString()?.toDoubleOrNull() ?: 0.0

            // The record this phone number actually belongs to, looked up now rather
            // than when the dialog opened - see CreditCustomerForm.balanceToSave for
            // why the one captured at open is the wrong record to read a balance off.
            val existing = runCatching { customerDao.findByPhone(phone) }.getOrNull()
            val balance = CreditCustomerForm.balanceToSave(
                creditEnabled = credit,
                typed = editText(tilBalance).text?.toString()?.toDoubleOrNull() ?: 0.0,
                onFile = existing?.balance
            )

            CreditCustomerForm.refusal(phone, name, credit, limit, balance, money)?.let {
                refuse(
                    when (it.field) {
                        CreditCustomerForm.Field.PHONE -> tilPhone
                        CreditCustomerForm.Field.NAME -> tilName
                        CreditCustomerForm.Field.LIMIT -> tilLimit
                    },
                    it.message
                )
                return@setOnClickListener
            }

            creditCustomerPhone = phone
            creditCustomerName = name
            creditCustomerAddress = etAddress.text?.toString()?.trim().orEmpty()
            creditCustomerGstin = etGstin.text?.toString()?.trim().orEmpty()

            val saved = save(
                ctx,
                CustomerDao.Customer(
                    id = existing?.id ?: 0L,
                    name = name,
                    address = creditCustomerAddress,
                    phone = phone,
                    gstin = creditCustomerGstin,
                    creditEnabled = credit,
                    creditLimit = if (credit) limit else 0.0,
                    balance = balance,
                    birthday = etBirthday.text?.toString()?.trim().orEmpty(),
                    anniversary = etAnniversary.text?.toString()?.trim().orEmpty()
                )
            )
            dialog.dismiss()
            toast("Customer details saved")
            // Back to the caller, which puts the sale on this customer and goes straight
            // back through its credit gate on what was just saved.
            onSaved(saved)
        }

        btnNegative.setOnClickListener { dialog.dismiss() }

        dialog.show()
        val window = dialog.window
        window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setGravity(android.view.Gravity.CENTER)
    }
}
