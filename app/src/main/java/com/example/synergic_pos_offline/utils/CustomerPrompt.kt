package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.CustomerDao

/**
 * "Add Customer": the phone-number prompt the grocery till puts in front of a sale,
 * and the find-or-create behind it.
 *
 * ## Why a phone number and nothing else
 *
 * A counter has a queue behind it. Asking for a name, an address and a tax number
 * before an order can be taken is a form nobody fills in truthfully at a till - and
 * every field but one is already known the second time that customer comes back. The
 * phone IS the customer here: it is the field a person gives the same way twice, and
 * the only one that can find them again.
 *
 * The rest is filled in later, from the Customers master, by whoever has time to type
 * it. That is why a new record is written with a blank name rather than a placeholder
 * one: an empty field asks to be filled, and "Guest" or "Take Away" looks like a
 * completed record and never gets corrected.
 *
 * ## Find or create, never a duplicate
 *
 * The phone is looked up first. A customer who is already known is returned as they
 * are, with their balance, their credit limit and whatever else has been filled in
 * since - nothing is overwritten by an order being taken. Only a phone nobody holds
 * creates a row.
 *
 * Shared so the restaurant's take-away prompt is the same prompt and the same rule.
 * The two were about to be written twice, and a shop's address book collecting
 * duplicates because two screens disagreed about what counts as the same customer is
 * the kind of fault nobody notices until the list is unusable.
 */
object CustomerPrompt {

    /** Digits in the phone number this asks for - a 10-digit mobile. */
    private const val PHONE_LENGTH = 10

    /**
     * The counter's customer form: phone, name and address, with the phone finding the
     * other two.
     *
     * ## What typing a phone number does
     *
     * At [PHONE_LENGTH] digits the customer list is searched. A match fills the name
     * and address in and says so; no match says the record will be added. Below that
     * length nothing is searched - a partial number matches the wrong person, and a
     * name appearing and then changing as more digits arrive is worse than none.
     *
     * The filled-in fields stay editable, and an edit is kept: a customer who has
     * moved is corrected here rather than in the master, which is where the counter
     * actually finds out. That is why this SAVES what is on screen rather than only
     * reading - see [saveDetails].
     *
     * ## Why not the full master form
     *
     * dialog_customer.xml carries a GSTIN, a birthday, an anniversary, a balance and a
     * credit limit. None of those get typed with a queue waiting, and a form long
     * enough to scroll is one a counter learns to dismiss. Three fields is what an
     * order actually needs; the rest is filled in later from the master.
     */
    fun showDetails(
        context: Context,
        title: String = "Customer Details",
        positiveText: String = "Start Order",
        /**
         * Whether a way past the form is offered.
         *
         * Off. A take-away is called out by name at the counter, so the customer is
         * part of the order rather than an extra on it, and a form with a way past it
         * is a form that gets skipped. The back press still closes the dialog - this
         * is a prompt, not a lock - and the caller decides what an unanswered one
         * means through [onCancel].
         */
        showSkip: Boolean = false,
        skipText: String = "Skip",
        onCancel: (() -> Unit)? = null,
        onPicked: (CustomerDao.Customer) -> Unit
    ) {
        val ctx = FixedFontScale.wrap(context)
        val view = android.view.LayoutInflater.from(ctx).inflate(R.layout.dialog_customer_quick, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx).setView(view).create()
            .also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(android.view.Gravity.CENTER)
        }

        val etPhone = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etQuickPhone)
        val etName = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etQuickName)
        val etAddress = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etQuickAddress)
        val tvState = view.findViewById<android.widget.TextView>(R.id.tvQuickCustomerState)
        val btnPositive = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFormPositive)
        val btnNegative = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFormNegative)

        view.findViewById<android.widget.TextView>(R.id.tvQuickCustomerTitle).text = title
        btnPositive.text = positiveText
        btnNegative.text = skipText
        // Gone rather than disabled, and the primary takes the whole row: a greyed
        // button still reads as a choice that might become available.
        btnNegative.visibility = if (showSkip) android.view.View.VISIBLE else android.view.View.GONE
        if (!showSkip) {
            (btnPositive.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let {
                it.marginStart = 0
                btnPositive.layoutParams = it
            }
        }
        ThemeManager.applyTheme(view)
        ThemeManager.styleDialogButtons(btnPositive, btnNegative)

        // The record the typed number matched, if any. Held so Save knows whether it is
        // updating somebody or adding them, without searching again.
        var matched: CustomerDao.Customer? = null

        etPhone.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val phone = s?.toString()?.trim().orEmpty()
                if (phone.length != PHONE_LENGTH) {
                    // Backspacing out of a complete number drops the match, so a
                    // half-edited number cannot save against the customer it used to be.
                    matched = null
                    tvState.visibility = android.view.View.GONE
                    return
                }
                val found = runCatching { CustomerDao(ctx).findByPhone(phone) }.getOrNull()
                matched = found
                if (found != null) {
                    // Overwrites what is in the boxes, deliberately: the number was
                    // just typed, so the record it names is the answer - anything
                    // already in those fields belonged to a different customer.
                    etName.setText(found.name)
                    etAddress.setText(found.address)
                    tvState.text = "Known customer — details filled in"
                } else {
                    tvState.text = "New customer — will be added to the customer list"
                }
                tvState.visibility = android.view.View.VISIBLE
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        btnNegative.setOnClickListener { dialog.dismiss() }
        var saved = false
        btnPositive.setOnClickListener {
            val phone = etPhone.text?.toString()?.trim().orEmpty()
            if (phone.length != PHONE_LENGTH) {
                // Said on the field, where the wrong value is. Not treated as a skip:
                // the operator meant to enter a customer and mistyped.
                view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilQuickPhone)
                    .error = "Enter a $PHONE_LENGTH-digit number"
                return@setOnClickListener
            }
            val customer = saveDetails(
                ctx, matched, phone,
                etName.text?.toString()?.trim().orEmpty(),
                etAddress.text?.toString()?.trim().orEmpty()
            )
            saved = true
            dialog.dismiss()
            if (customer == null) onCancel?.invoke() else onPicked(customer)
        }
        // Skip and the back press both mean "carry on without a customer".
        dialog.setOnDismissListener { if (!saved) onCancel?.invoke() }

        dialog.show()
    }

    /**
     * Writes what the form holds: a new customer, or the changed fields of one that
     * was already there.
     *
     * ONLY NAME AND ADDRESS are ever written to an existing record. Their balance,
     * credit limit, GSTIN and dates were not on this form and are not this screen's to
     * touch - an order being taken must not be able to clear a customer's credit
     * settings because a counter left two boxes empty.
     *
     * And only when something was actually typed: a blank name does not wipe the name
     * already on file. Filling a blank in is the point; emptying a filled one from a
     * form that did not show it would be a silent loss.
     */
    private fun saveDetails(
        context: Context,
        existing: CustomerDao.Customer?,
        phone: String,
        name: String,
        address: String
    ): CustomerDao.Customer? = runCatching {
        val dao = CustomerDao(context)
        val current = existing ?: dao.findByPhone(phone)
        if (current == null) {
            val id = dao.insert(
                CustomerDao.Customer(
                    id = 0L, name = name, address = address, phone = phone, gstin = "",
                    creditEnabled = false, creditLimit = 0.0, balance = 0.0
                )
            )
            if (id > 0) dao.findById(id) else null
        } else {
            val updated = current.copy(
                name = name.ifBlank { current.name },
                address = address.ifBlank { current.address }
            )
            if (updated != current) dao.update(current.id, updated)
            updated
        }
    }.getOrElse {
        android.util.Log.e("CustomerPrompt", "Could not save customer $phone", it)
        null
    }

    /**
     * The customer holding [phone], creating the record if nobody does yet.
     *
     * Null only when the write failed, which the caller reports its own way - the till
     * cannot stop taking orders because the address book would not accept one.
     */
    fun findOrCreate(context: Context, phone: String): CustomerDao.Customer? {
        val dao = CustomerDao(context)
        return runCatching {
            dao.findByPhone(phone) ?: run {
                // Everything but the phone left empty on purpose - see the note above.
                val id = dao.insert(
                    CustomerDao.Customer(
                        id = 0L, name = "", address = "", phone = phone, gstin = "",
                        creditEnabled = false, creditLimit = 0.0, balance = 0.0
                    )
                )
                if (id > 0) dao.findById(id) else null
            }
        }.getOrElse {
            android.util.Log.e("CustomerPrompt", "Could not find or create $phone", it)
            null
        }
    }
}
