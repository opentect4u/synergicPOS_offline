package com.example.synergic_pos_offline.utils

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.PopupWindow
import androidx.appcompat.widget.ListPopupWindow
import com.example.synergic_pos_offline.database.CustomerDao

/**
 * The search dropdown under a customer form's phone box.
 *
 * From the FIRST digit typed, the customers whose number has those digits in it are
 * listed under the box - "name · phone", a handful at a time - and picking one hands
 * that customer back to the form, which fills itself in from them. The same dropdown
 * on every popup a customer is added from (the sale screens' Add Customer, the credit
 * customer form, the customer master's Add Customer), so a regular is found the same
 * way wherever they are being entered.
 *
 * The dropdown never takes the focus: the box keeps the keyboard and the caret, and
 * typing on narrows the list. It comes down once the number is complete - a whole
 * number is the form's own exact lookup, and a list under it would be in the way.
 */
object CustomerPhoneSearch {

    /** A complete phone number - where searching stops and the exact lookup takes over. */
    private const val PHONE_LENGTH = 10

    /** Most customers listed at once. */
    private const val MAX_SUGGESTIONS = 6

    /**
     * Attaches the dropdown to [field], hanging it under [anchor] (the field's own
     * TextInputLayout, so it lines up with the outlined box). [onPicked] runs with the
     * customer chosen. Returns the dropdown so the form can close it with itself - it
     * is its own window, and left up it would float over whatever comes next.
     *
     * The customer list is read once, on the first digit, rather than per keystroke:
     * the master is small, and a query per key would be the slow part of typing.
     */
    fun attach(
        context: Context,
        anchor: View,
        field: EditText,
        onPicked: (CustomerDao.Customer) -> Unit
    ): ListPopupWindow {
        val everyone by lazy { runCatching { CustomerDao(context).getAll() }.getOrDefault(emptyList()) }
        var offered: List<CustomerDao.Customer> = emptyList()
        val dropdown = ListPopupWindow(context).apply {
            anchorView = anchor
            isModal = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            setOnItemClickListener { _, _, position, _ ->
                val picked = offered.getOrNull(position) ?: return@setOnItemClickListener
                dismiss()
                onPicked(picked)
            }
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val typed = s?.toString()?.trim().orEmpty()
                // Only while the operator is typing the number - not while the form is
                // filling itself in, and not once the number is whole.
                offered = if (!field.hasFocus() || typed.isEmpty() || typed.length >= PHONE_LENGTH ||
                    !typed.all { it.isDigit() }
                ) emptyList()
                else everyone.filter { it.phone.contains(typed) }.take(MAX_SUGGESTIONS)

                if (offered.isEmpty()) { dropdown.dismiss(); return }
                dropdown.setAdapter(
                    ArrayAdapter(
                        context, android.R.layout.simple_list_item_1,
                        offered.map { c -> if (c.name.isBlank()) c.phone else "${c.name}  ·  ${c.phone}" }
                    )
                )
                if (!dropdown.isShowing) dropdown.show()
            }
        })
        return dropdown
    }
}
