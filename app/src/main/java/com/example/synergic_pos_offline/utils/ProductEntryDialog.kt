package com.example.synergic_pos_offline.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.synergic_pos_offline.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * The product line popup: HSN / GST / CGST / SGST with an editable rate and
 * quantity, and a running taxable / tax / amount breakdown.
 *
 * Shared by the billing screen and the checkout screen so both present the same
 * dialog with the same arithmetic - a copy in each would be free to drift.
 */
object ProductEntryDialog {

    /** What the dialog needs to know about a product, independent of any screen. */
    data class Product(
        val id: String,
        val name: String,
        val sku: String,
        val category: String,
        val price: Double,
        val hsn: String = "0000",
        val unit: String = "pcs",
        val cgst: Double = 0.0,
        val sgst: Double = 0.0,
        /** All sellable rates for this product; drives the rate dropdown when >1. */
        val rates: List<Rate> = emptyList()
    ) {
        val gst: Double get() = cgst + sgst
    }

    /** One named rate a product can be sold at, with its own GST split. */
    data class Rate(
        val name: String,
        val rate: Double,
        val cgst: Double = 0.0,
        val sgst: Double = 0.0
    )

    /**
     * @param startRate rate to open with, so an edit reopens on the line's own rate
     * @param startQty  quantity to open with
     * @param confirmLabel text for the confirm button ("Add to cart" / "Update")
     * @param onConfirm receives the validated quantity and rate
     */
    fun show(
        context: Context,
        inflater: LayoutInflater,
        product: Product,
        startRate: Double = product.price,
        startQty: Int = 1,
        confirmLabel: String = "Add to cart",
        focusQty: Boolean = false,
        focusRate: Boolean = false,
        rateEditable: Boolean = true,
        onConfirm: (qty: Int, rate: Double) -> Unit
    ) {
        val accent = ThemeManager.getThemeColor(context)
        val view = inflater.inflate(R.layout.dialog_product_entry, null)

        view.findViewById<TextView>(R.id.tvDialogTitle).text = product.name
        view.findViewById<TextView>(R.id.tvDialogCategory).text = product.category
        view.findViewById<TextView>(R.id.tvDetSku).text = product.sku
        view.findViewById<TextView>(R.id.tvDetHsn).text = product.hsn
        view.findViewById<TextView>(R.id.tvDetUnit).text = product.unit
        val tvDetMrp = view.findViewById<TextView>(R.id.tvDetMrp)
        val tvDetGst = view.findViewById<TextView>(R.id.tvDetGst)
        val tvDetCgst = view.findViewById<TextView>(R.id.tvDetCgst)
        val tvDetSgst = view.findViewById<TextView>(R.id.tvDetSgst)
        val tvCgstLabel = view.findViewById<TextView>(R.id.tvCgstLabel)
        val tvSgstLabel = view.findViewById<TextView>(R.id.tvSgstLabel)
        tvDetMrp.text = money(product.price)

        // GST split can change with the selected rate, so it is held mutable.
        var curCgst = product.cgst
        var curSgst = product.sgst
        fun applyTaxLabels(cgst: Double, sgst: Double) {
            curCgst = cgst
            curSgst = sgst
            tvDetGst.text = pct(cgst + sgst)
            tvDetCgst.text = pct(cgst)
            tvDetSgst.text = pct(sgst)
            tvCgstLabel.text = "CGST (${pct(cgst)})"
            tvSgstLabel.text = "SGST (${pct(sgst)})"
        }
        applyTaxLabels(product.cgst, product.sgst)

        val etRate = view.findViewById<TextInputEditText>(R.id.etRate)
        val etQty = view.findViewById<TextInputEditText>(R.id.etQty)
        val tvTaxable = view.findViewById<TextView>(R.id.tvTaxable)
        val tvCgstAmt = view.findViewById<TextView>(R.id.tvCgstAmt)
        val tvSgstAmt = view.findViewById<TextView>(R.id.tvSgstAmt)
        val tvAmount = view.findViewById<TextView>(R.id.tvLineAmount)

        etRate.setText(String.format("%.2f", startRate))
        etQty.setText(startQty.toString())

        // Manual rate off: the rate is fixed to the product's price and can't be edited.
        if (!rateEditable) {
            etRate.isFocusable = false
            etRate.isFocusableInTouchMode = false
            etRate.isCursorVisible = false
            etRate.keyListener = null
        }

        fun refreshAmount() {
            val rate = etRate.text?.toString()?.toDoubleOrNull() ?: 0.0
            val qty = etQty.text?.toString()?.toIntOrNull() ?: 0
            val taxable = GstCalculator.taxableValue(rate, qty, 0)
            val cgst = GstCalculator.taxAmount(taxable, curCgst)
            val sgst = GstCalculator.taxAmount(taxable, curSgst)
            tvTaxable.text = money(taxable)
            tvCgstAmt.text = money(cgst)
            tvSgstAmt.text = money(sgst)
            tvAmount.text = money(taxable + cgst + sgst)
        }
        etRate.addTextChangedListener(watcher { refreshAmount() })
        etQty.addTextChangedListener(watcher { refreshAmount() })
        refreshAmount()

        // Multiple rates: a dropdown swaps the rate and its own GST split. The rate
        // is chosen from the list, so manual entry into the Rate field is disabled.
        if (product.rates.size > 1) {
            val til = view.findViewById<TextInputLayout>(R.id.tilRateSelect)
            val act = view.findViewById<MaterialAutoCompleteTextView>(R.id.actRateSelect)
            til.visibility = android.view.View.VISIBLE
            etRate.isFocusable = false
            etRate.isFocusableInTouchMode = false
            etRate.isCursorVisible = false
            etRate.keyListener = null
            val labels = product.rates.map { r ->
                "${r.name.ifBlank { "Rate" }} (${money(r.rate)})"
            }
            act.setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, labels))
            // Open on whichever rate matches the starting rate, else the first.
            val startIdx = product.rates.indexOfFirst { it.rate == startRate }.coerceAtLeast(0)
            act.setText(labels[startIdx], false)
            act.setOnItemClickListener { _, _, pos, _ ->
                val r = product.rates[pos]
                etRate.setText(String.format("%.2f", r.rate))
                tvDetMrp.text = money(r.rate)
                applyTaxLabels(r.cgst, r.sgst)
                refreshAmount()
            }
        }

        val dialog = AlertDialog.Builder(context).setView(view).create()
        dialog.setCanceledOnTouchOutside(false)

        val btnCancel = view.findViewById<MaterialButton>(R.id.btnDialogCancel)
        val btnAdd = view.findViewById<MaterialButton>(R.id.btnDialogAdd)
        btnAdd.text = confirmLabel
        styleOutlined(btnCancel, accent)
        btnAdd.backgroundTintList = ColorStateList.valueOf(accent)
        btnAdd.setTextColor(Color.WHITE)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnAdd.setOnClickListener {
            val rate = etRate.text?.toString()?.toDoubleOrNull()
            val qty = etQty.text?.toString()?.toIntOrNull()
            when {
                rate == null || rate <= 0 -> toast(context, "Enter a valid rate")
                qty == null || qty <= 0 -> toast(context, "Enter a valid quantity")
                else -> {
                    onConfirm(qty, rate)
                    dialog.dismiss()
                }
            }
        }
        // Open focused (keyboard up) on whichever field the operator is expected to
        // fill in. Rate wins when both apply, since it comes first on the form.
        if (focusRate || focusQty) {
            dialog.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            )
        }
        dialog.show()
        when {
            focusRate -> { etRate.requestFocus(); etRate.selectAll() }
            focusQty -> { etQty.requestFocus(); etQty.selectAll() }
        }
    }

    private fun money(v: Double): String = "₹" + String.format("%.2f", v)

    private fun pct(v: Double): String =
        (if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()) + "%"

    private fun toast(context: Context, msg: String) =
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    private fun styleOutlined(btn: MaterialButton, accent: Int) {
        btn.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        btn.setTextColor(accent)
        btn.strokeColor = ColorStateList.valueOf(accent)
        btn.strokeWidth = (btn.resources.displayMetrics.density * 1.5f).toInt()
    }

    private fun watcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = onChange()
        override fun afterTextChanged(s: Editable?) {}
    }
}
