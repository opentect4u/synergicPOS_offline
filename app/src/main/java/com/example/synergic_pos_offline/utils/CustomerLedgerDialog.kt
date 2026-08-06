package com.example.synergic_pos_offline.utils

import android.app.DatePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.CustomerDao
import com.example.synergic_pos_offline.database.CustomerLedgerDao
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * One customer's ledger, opened from their row on the Customers master.
 *
 * The Customer Ledger report starts from a phone number because it starts from
 * nothing; here the customer is already known, so only the date range is asked
 * for. Everything below that - the figures, the movements, and the printed copy -
 * is the report's, through [CustomerLedgerView], so the two cannot disagree about
 * an account.
 */
object CustomerLedgerDialog {

    fun show(context: Context, inflater: LayoutInflater, customer: CustomerDao.Customer) {
        val accent = ThemeManager.getThemeColor(context)
        val view = inflater.inflate(R.layout.dialog_customer_ledger, null)
        val dialog = AlertDialog.Builder(context).setView(view).create()
            .also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT); setGravity(android.view.Gravity.CENTER) }

        val dao = CustomerLedgerDao(context)
        val etFrom = view.findViewById<TextInputEditText>(R.id.etDialogFrom)
        val etTo = view.findViewById<TextInputEditText>(R.id.etDialogTo)
        val btnGenerate = view.findViewById<MaterialButton>(R.id.btnDialogGenerate)
        val btnPrint = view.findViewById<MaterialButton>(R.id.btnLedgerDialogPrint)
        val btnClose = view.findViewById<MaterialButton>(R.id.btnLedgerDialogClose)
        val result = view.findViewById<View>(R.id.llLedgerResult)

        view.findViewById<TextView>(R.id.tvLedgerDialogSubtitle).text =
            "${customer.name.trim().ifEmpty { "Unnamed customer" }}  ·  " +
                customer.phone.ifBlank { "No phone on file" }

        // Opens on the month to date - the range a statement is usually asked for -
        // so the first tap of Generate already has something to work with.
        val today = Calendar.getInstance()
        val monthStart = (today.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        etFrom.setText(iso(monthStart.time))
        etTo.setText(iso(today.time))
        etFrom.setOnClickListener { pickDate(context, etFrom) }
        etTo.setOnClickListener { pickDate(context, etTo) }

        var ledger: CustomerLedgerDao.Ledger? = null

        fun generate() {
            val from = etFrom.text?.toString()?.trim().orEmpty()
            val to = etTo.text?.toString()?.trim().orEmpty()
            if (from.isEmpty() || to.isEmpty()) {
                toast(context, "Pick both dates")
                return
            }
            if (from > to) {
                // ISO dates sort lexicographically, so this is the whole check.
                toast(context, "The From date is after the To date")
                return
            }
            // Looked up by phone like the report does. A customer with no number on
            // file cannot be found that way, so their id stands in.
            val built = if (customer.phone.isNotBlank()) {
                dao.forPhone(customer.phone, from, to)
            } else {
                dao.forCustomer(customer.id, from, to)
            }
            if (built == null) {
                toast(context, "Could not load this customer's ledger")
                return
            }
            ledger = built
            result.visibility = View.VISIBLE
            CustomerLedgerView.bind(view, built)
            btnPrint.isEnabled = true
        }

        btnGenerate.setOnClickListener { generate() }
        btnPrint.setOnClickListener {
            ledger?.let { CustomerLedgerView.print(context, it) { message -> toast(context, message) } }
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        ThemeManager.applyTheme(view)
        // ThemeManager fills every MaterialButton; Close is the way out, not an
        // action, so it keeps its outlined look.
        btnClose.apply {
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(accent)
            strokeColor = ColorStateList.valueOf(accent)
        }

        // Opened on the month to date rather than empty, so the operator sees the
        // account straight away and only touches the dates to look further back.
        generate()

        dialog.show()
        dialog.window?.apply {
            val metrics = context.resources.displayMetrics
            setLayout(
                (metrics.widthPixels * WIDTH_FRACTION).toInt(),
                (metrics.heightPixels * HEIGHT_FRACTION).toInt()
            )
            setGravity(android.view.Gravity.CENTER)
        }
    }

    /** Opens a calendar seeded with whatever [field] holds, and writes the pick back. */
    private fun pickDate(context: Context, field: TextInputEditText) {
        val calendar = Calendar.getInstance()
        field.text?.toString()?.takeIf { it.isNotBlank() }?.let { current ->
            runCatching {
                val parts = current.split("-")
                calendar.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
            }
        }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                field.setText(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun iso(date: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

    private fun toast(context: Context, message: String) =
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    private const val WIDTH_FRACTION = 0.92f
    private const val HEIGHT_FRACTION = 0.86f
}
