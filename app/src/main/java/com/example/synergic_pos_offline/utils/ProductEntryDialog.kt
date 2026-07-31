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
import android.graphics.Bitmap
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
        /**
         * The product's picture, already decoded by whichever screen is showing the
         * grid - those screens keep a cache of them for the tiles, and decoding the
         * same JPEG again to open a dialog would be work for nothing. Null where the
         * product has no image, and the slot then takes no space.
         */
        val photo: Bitmap? = null,
        val cgst: Double = 0.0,
        val sgst: Double = 0.0,
        val vat: Double = 0.0,
        /** The rate's own pre-configured discount (Tax Settings' item-wise discount).
         *  [discType] is "P"/"A" (percent/amount) or null when none is configured. */
        val discValue: Double = 0.0,
        val discType: String? = null,
        /** All sellable rates for this product; drives the rate dropdown when >1. */
        val rates: List<Rate> = emptyList()
    ) {
        val gst: Double get() = cgst + sgst
    }

    /** One named rate a product can be sold at, with its own GST/VAT split and discount. */
    data class Rate(
        val name: String,
        val rate: Double,
        val cgst: Double = 0.0,
        val sgst: Double = 0.0,
        val vat: Double = 0.0,
        val discValue: Double = 0.0,
        val discType: String? = null
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
        taxRegime: GstCalculator.TaxRegime = GstCalculator.TaxRegime.GST,
        taxInclusive: Boolean = false,
        /** Tax Settings' Discount on and set to Item wise - each product's own
         *  pre-configured discount applies, priced per [discountPreTax]. */
        itemwiseDiscountActive: Boolean = false,
        discountPreTax: Boolean = true,
        onConfirm: (qty: Int, rate: Double) -> Unit
    ) {
        val accent = ThemeManager.getThemeColor(context)
        val view = inflater.inflate(R.layout.dialog_product_entry, null)

        view.findViewById<android.widget.ImageView>(R.id.ivDialogPhoto).apply {
            if (product.photo != null) {
                setImageBitmap(product.photo)
                visibility = android.view.View.VISIBLE
            } else {
                setImageDrawable(null)
                visibility = android.view.View.GONE
            }
        }
        view.findViewById<TextView>(R.id.tvDialogTitle).text = product.name
        view.findViewById<TextView>(R.id.tvDialogCategory).text = product.category
        view.findViewById<TextView>(R.id.tvDetSku).text = product.sku
        view.findViewById<TextView>(R.id.tvDetHsn).text = product.hsn
        view.findViewById<TextView>(R.id.tvDetUnit).text = product.unit
        val tvDetMrp = view.findViewById<TextView>(R.id.tvDetMrp)
        val llDetGst = view.findViewById<android.view.View>(R.id.llDetGst)
        val llDetCgst = view.findViewById<android.view.View>(R.id.llDetCgst)
        val llDetSgst = view.findViewById<android.view.View>(R.id.llDetSgst)
        val lblDetCgst = view.findViewById<TextView>(R.id.lblDetCgst)
        val tvDetGst = view.findViewById<TextView>(R.id.tvDetGst)
        val tvDetCgst = view.findViewById<TextView>(R.id.tvDetCgst)
        val tvDetSgst = view.findViewById<TextView>(R.id.tvDetSgst)
        val rowCgst = view.findViewById<android.view.View>(R.id.rowCgst)
        val rowSgst = view.findViewById<android.view.View>(R.id.rowSgst)
        val tvCgstLabel = view.findViewById<TextView>(R.id.tvCgstLabel)
        val tvSgstLabel = view.findViewById<TextView>(R.id.tvSgstLabel)
        tvDetMrp.text = money(product.price)

        // Which detail rows even apply depends on the active regime - GST shows all
        // three (combined/CGST/SGST); VAT relabels the CGST slot and drops SGST;
        // NONE (no tax switched on) drops the whole block.
        llDetGst.visibility = if (taxRegime == GstCalculator.TaxRegime.GST) android.view.View.VISIBLE else android.view.View.GONE
        llDetCgst.visibility = if (taxRegime == GstCalculator.TaxRegime.NONE) android.view.View.GONE else android.view.View.VISIBLE
        llDetSgst.visibility = if (taxRegime == GstCalculator.TaxRegime.GST) android.view.View.VISIBLE else android.view.View.GONE
        rowSgst.visibility = if (taxRegime == GstCalculator.TaxRegime.GST) android.view.View.VISIBLE else android.view.View.GONE
        rowCgst.visibility = if (taxRegime == GstCalculator.TaxRegime.NONE) android.view.View.GONE else android.view.View.VISIBLE
        lblDetCgst.text = if (taxRegime == GstCalculator.TaxRegime.VAT) "VAT" else "CGST"

        // The line's tax rate(s) can change with the selected rate row, so they are
        // held mutable. For VAT, [curCgst] carries the single VAT rate and [curSgst]
        // stays zero - one combined-rate code path serves both regimes.
        var curCgst = when (taxRegime) {
            GstCalculator.TaxRegime.VAT -> product.vat
            GstCalculator.TaxRegime.GST -> product.cgst
            GstCalculator.TaxRegime.NONE -> 0.0
        }
        var curSgst = if (taxRegime == GstCalculator.TaxRegime.GST) product.sgst else 0.0
        fun applyTaxLabels(cgst: Double, sgst: Double) {
            curCgst = cgst
            curSgst = sgst
            tvDetGst.text = pct(cgst + sgst)
            tvDetCgst.text = pct(cgst)
            tvDetSgst.text = pct(sgst)
            tvCgstLabel.text = "${lblDetCgst.text} (${pct(cgst)})"
            tvSgstLabel.text = "SGST (${pct(sgst)})"
        }
        applyTaxLabels(curCgst, curSgst)

        // The rate row's own pre-configured discount, held mutable so it can also
        // change with the selected rate in Multiple item-rate mode.
        var curDiscValue = product.discValue
        var curDiscType = product.discType
        val discMode = { if (curDiscType == "A") GstCalculator.DiscountMode.AMOUNT else GstCalculator.DiscountMode.PERCENT }
        val rowItemDiscount = view.findViewById<android.view.View>(R.id.rowItemDiscount)
        val tvItemDiscountLabel = view.findViewById<TextView>(R.id.tvItemDiscountLabel)
        val tvItemDiscountAmt = view.findViewById<TextView>(R.id.tvItemDiscountAmt)

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
            val mrp = rate * qty
            val combinedRate = curCgst + curSgst

            if (itemwiseDiscountActive && curDiscValue > 0.0 && curDiscType != null) {
                val pricing = GstCalculator.priceItem(mrp, combinedRate, taxInclusive, discountPreTax, discMode(), curDiscValue)
                val discAgainstBase = GstCalculator.itemDiscountAgainstRawBase(
                    mrp, combinedRate, taxInclusive, discountPreTax, discMode(), curDiscValue
                )
                rowItemDiscount.visibility = android.view.View.VISIBLE
                tvItemDiscountLabel.text = if (discMode() == GstCalculator.DiscountMode.PERCENT) {
                    "Discount (${pct(curDiscValue)})"
                } else {
                    "Discount"
                }
                tvItemDiscountAmt.text = "-${money(discAgainstBase)}"
                tvTaxable.text = money(pricing.taxable)
                tvCgstAmt.text = money(GstCalculator.taxAmount(pricing.taxable, curCgst))
                tvSgstAmt.text = money(GstCalculator.taxAmount(pricing.taxable, curSgst))
                tvAmount.text = money(pricing.salePrice)
            } else {
                rowItemDiscount.visibility = android.view.View.GONE
                val taxable = GstCalculator.taxableBase(mrp, combinedRate, taxInclusive)
                val cgst = GstCalculator.taxAmount(taxable, curCgst)
                val sgst = GstCalculator.taxAmount(taxable, curSgst)
                tvTaxable.text = money(taxable)
                tvCgstAmt.text = money(cgst)
                tvSgstAmt.text = money(sgst)
                tvAmount.text = money(taxable + cgst + sgst)
            }
        }
        etRate.addTextChangedListener(watcher { refreshAmount() })
        etQty.addTextChangedListener(watcher { refreshAmount() })
        refreshAmount()

        // Multiple rates: a dropdown swaps the rate and its own tax split. The rate
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
                curDiscValue = r.discValue
                curDiscType = r.discType
                when (taxRegime) {
                    GstCalculator.TaxRegime.VAT -> applyTaxLabels(r.vat, 0.0)
                    GstCalculator.TaxRegime.GST -> applyTaxLabels(r.cgst, r.sgst)
                    GstCalculator.TaxRegime.NONE -> applyTaxLabels(0.0, 0.0)
                }
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
