package com.example.synergic_pos_offline.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.example.synergic_pos_offline.R
import com.google.android.material.button.MaterialButton

/**
 * Shows what is on file for a customer: who they are, what they owe, and whatever
 * contact details have been captured.
 *
 * Shared by the billing screen, where it confirms the result of a phone lookup
 * before attaching the customer to a sale, and by checkout, where it is read-only.
 * One implementation so the two cannot drift into showing different things.
 */
object CustomerCardDialog {

    /** What the card knows about a customer, independent of any screen or table. */
    data class Customer(
        val name: String,
        val phone: String,
        val address: String = "",
        val gstin: String = "",
        val dob: String = "",
        val dom: String = "",
        val creditEnabled: Boolean = false,
        val creditLimit: Double = 0.0,
        val balance: Double = 0.0
    )

    /**
     * @param status small caption above the name, e.g. "CUSTOMER FOUND"
     * @param confirmText primary button label; null shows the card read-only
     * @param note extra line under the details; null picks a sensible default
     * @param onConfirm invoked when the primary button is pressed
     */
    fun show(
        context: Context,
        inflater: LayoutInflater,
        customer: Customer,
        status: String,
        confirmText: String? = null,
        note: String? = null,
        onConfirm: (() -> Unit)? = null
    ) {
        val accent = ThemeManager.getThemeColor(context)
        val view = inflater.inflate(R.layout.dialog_customer_result, null)
        val dialog = AlertDialog.Builder(context).setView(view).create()
            .also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val name = customer.name.trim()
        view.findViewById<TextView>(R.id.tvResultName).text =
            name.ifEmpty { "Unnamed customer" }
        view.findViewById<TextView>(R.id.tvResultPhone).text =
            customer.phone.ifEmpty { "No phone on file" }
        view.findViewById<TextView>(R.id.tvResultStatus).apply {
            text = status
            setTextColor(accent)
        }

        // Initials from the name; a record with only a phone falls back to a glyph.
        view.findViewById<TextView>(R.id.tvResultInitials).apply {
            text = name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }
                .take(2).joinToString("").ifEmpty { "+" }
            background?.mutate()?.setTint(accent)
        }

        // Chips: shown only when they carry information.
        view.findViewById<TextView>(R.id.tvChipCredit).apply {
            if (customer.creditEnabled) {
                text = if (customer.creditLimit > 0) {
                    "Credit · ${money(customer.creditLimit)}"
                } else "Credit allowed"
                visibility = View.VISIBLE
            } else visibility = View.GONE
        }
        view.findViewById<TextView>(R.id.tvChipBalance).apply {
            if (customer.balance > 0.001) {
                text = "Outstanding ${money(customer.balance)}"
                background?.mutate()?.setTint(Color.parseColor("#FDE8E8"))
                setTextColor(Color.parseColor("#B3261E"))
                visibility = View.VISIBLE
            } else visibility = View.GONE
        }

        val details = view.findViewById<LinearLayout>(R.id.llResultDetails)
        details.removeAllViews()
        listOf(
            "Address" to customer.address,
            "GSTIN" to customer.gstin,
            "Birthday" to customer.dob,
            "Anniversary" to customer.dom
        ).forEach { (label, value) ->
            if (value.isNotBlank()) details.addView(detailRow(context, label, value))
        }

        view.findViewById<TextView>(R.id.tvResultNote).apply {
            val fallback = if (details.childCount == 0) {
                "No other details recorded for this customer yet."
            } else null
            val message = note ?: fallback
            text = message.orEmpty()
            visibility = if (message == null) View.GONE else View.VISIBLE
        }

        val btnConfirm = view.findViewById<MaterialButton>(R.id.btnResultUse)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnResultCancel)
        if (confirmText == null) {
            // Read-only: the single button just closes the card.
            btnConfirm.visibility = View.GONE
            btnCancel.text = "Close"
        } else {
            btnConfirm.text = confirmText
            btnConfirm.backgroundTintList = ColorStateList.valueOf(accent)
            btnConfirm.setOnClickListener {
                onConfirm?.invoke()
                dialog.dismiss()
            }
        }
        btnCancel.setTextColor(accent)
        btnCancel.strokeColor = ColorStateList.valueOf(accent)
        btnCancel.strokeWidth = (btnCancel.resources.displayMetrics.density * 1.5f).toInt()
        btnCancel.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun money(v: Double): String = "₹" + String.format("%.2f", v)

    /** One "Label   value" row in the card. */
    private fun detailRow(context: Context, label: String, value: String): View {
        val density = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (7 * density).toInt(), 0, (7 * density).toInt())
            addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams((110 * density).toInt(), -2)
            })
            addView(TextView(context).apply {
                text = value
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.text_main))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
        }
    }
}
