package com.example.synergic_pos_offline.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.synergic_pos_offline.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

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
        val sgst: Double = 0.0
    ) {
        val gst: Double get() = cgst + sgst
    }

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
        onConfirm: (qty: Int, rate: Double) -> Unit
    ) {
        val accent = ThemeManager.getThemeColor(context)
        val view = inflater.inflate(R.layout.dialog_product_entry, null)

        view.findViewById<TextView>(R.id.tvDialogTitle).text = product.name
        view.findViewById<TextView>(R.id.tvDialogCategory).text = product.category
        view.findViewById<TextView>(R.id.tvDetSku).text = product.sku
        view.findViewById<TextView>(R.id.tvDetHsn).text = product.hsn
        view.findViewById<TextView>(R.id.tvDetUnit).text = product.unit
        view.findViewById<TextView>(R.id.tvDetMrp).text = money(product.price)
        view.findViewById<TextView>(R.id.tvDetGst).text = pct(product.gst)
        view.findViewById<TextView>(R.id.tvDetCgst).text = pct(product.cgst)
        view.findViewById<TextView>(R.id.tvDetSgst).text = pct(product.sgst)
        view.findViewById<TextView>(R.id.tvCgstLabel).text = "CGST (${pct(product.cgst)})"
        view.findViewById<TextView>(R.id.tvSgstLabel).text = "SGST (${pct(product.sgst)})"

        val etRate = view.findViewById<TextInputEditText>(R.id.etRate)
        val etQty = view.findViewById<TextInputEditText>(R.id.etQty)
        val tvTaxable = view.findViewById<TextView>(R.id.tvTaxable)
        val tvCgstAmt = view.findViewById<TextView>(R.id.tvCgstAmt)
        val tvSgstAmt = view.findViewById<TextView>(R.id.tvSgstAmt)
        val tvAmount = view.findViewById<TextView>(R.id.tvLineAmount)

        etRate.setText(String.format("%.2f", startRate))
        etQty.setText(startQty.toString())

        fun refreshAmount() {
            val rate = etRate.text?.toString()?.toDoubleOrNull() ?: 0.0
            val qty = etQty.text?.toString()?.toIntOrNull() ?: 0
            val taxable = GstCalculator.taxableValue(rate, qty, 0)
            val cgst = GstCalculator.taxAmount(taxable, product.cgst)
            val sgst = GstCalculator.taxAmount(taxable, product.sgst)
            tvTaxable.text = money(taxable)
            tvCgstAmt.text = money(cgst)
            tvSgstAmt.text = money(sgst)
            tvAmount.text = money(taxable + cgst + sgst)
        }
        etRate.addTextChangedListener(watcher { refreshAmount() })
        etQty.addTextChangedListener(watcher { refreshAmount() })
        refreshAmount()

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
        dialog.show()
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
