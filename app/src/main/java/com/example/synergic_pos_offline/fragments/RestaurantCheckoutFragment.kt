package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * Restaurant checkout — order review + payment panel. Opened from the Orders
 * screen's "Bill & Pay". Design pass with placeholder data; wired later.
 */
class RestaurantCheckoutFragment : Fragment(), TitledScreen {

    override val screenTitle = "Checkout"

    private data class Line(val name: String, val qty: Int, val rate: Double)

    private val lines = listOf(
        Line("Chicken Biryani", 1, 280.0),
        Line("Paneer Butter Masala", 1, 220.0),
        Line("Garlic Naan", 2, 60.0),
        Line("Masala Papad", 1, 30.0),
        Line("Fresh Lime Soda", 2, 40.0),
        Line("Chocolate Brownie", 1, 120.0)
    )

    private var total = 0.0
    private var payMethod = "Cash"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_restaurant_checkout, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        populateItems(view)

        // Payment method selection — always re-read the current theme colour.
        mapOf(R.id.btnPayCash to "Cash", R.id.btnPayCard to "Card", R.id.btnPayOnline to "Online")
            .forEach { (id, name) ->
                view.findViewById<MaterialButton>(id).setOnClickListener { payMethod = name; restyle(view) }
            }

        // Amount tendered → change due.
        val etTendered = view.findViewById<TextInputEditText>(R.id.etTendered)
        val tvChange = view.findViewById<TextView>(R.id.tvChangeDue)
        etTendered.addTextChangedListener {
            val tendered = it?.toString()?.toDoubleOrNull()
            tvChange.text = if (tendered != null && tendered >= total) "₹ ${money(tendered - total)}" else "—"
        }

        view.findViewById<MaterialButton>(R.id.btnBackToBilling).setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        view.findViewById<MaterialButton>(R.id.btnConfirmPay).setOnClickListener {
            android.widget.Toast.makeText(requireContext(), "Payment confirmed ($payMethod)", android.widget.Toast.LENGTH_SHORT).show()
        }

        // Re-apply our accents after MainActivity's global theme pass.
        view.post { restyle(view) }
    }

    override fun onResume() {
        super.onResume()
        view?.let { v -> v.post { restyle(v) } }
    }

    /** Called by MainActivity when the palette colour changes — recolour instantly. */
    fun onThemeChanged() {
        view?.let { v -> v.post { restyle(v) } }
    }

    private fun populateItems(root: View) {
        val container = root.findViewById<LinearLayout>(R.id.llCheckoutItems)
        val inflater = LayoutInflater.from(requireContext())
        var subtotal = 0.0
        lines.forEach { l ->
            val row = inflater.inflate(R.layout.item_rest_checkout_line, container, false)
            row.findViewById<TextView>(R.id.tvLineName).text = l.name
            row.findViewById<TextView>(R.id.tvLineQty).text = l.qty.toString()
            row.findViewById<TextView>(R.id.tvLineRate).text = money(l.rate)
            row.findViewById<TextView>(R.id.tvLineAmount).text = money(l.qty * l.rate)
            container.addView(row)
            subtotal += l.qty * l.rate
        }
        val service = subtotal * 0.05
        val taxable = subtotal + service
        val cgst = taxable * 0.025
        val sgst = taxable * 0.025
        total = subtotal + service + cgst + sgst
        root.findViewById<TextView>(R.id.tvSubtotal).text = "₹ ${money(subtotal)}"
        root.findViewById<TextView>(R.id.tvService).text = "₹ ${money(service)}"
        root.findViewById<TextView>(R.id.tvCgst).text = "₹ ${money(cgst)}"
        root.findViewById<TextView>(R.id.tvSgst).text = "₹ ${money(sgst)}"
        root.findViewById<TextView>(R.id.tvTotalAmount).text = "₹ ${money(total)}"
        root.findViewById<MaterialButton>(R.id.btnConfirmPay).text = "Confirm Payment  ( ₹ ${money(total)} )"
    }

    /** Reads the CURRENT theme colour fresh (never captured), so it can't go stale. */
    private fun restyle(root: View) {
        val accent = ThemeManager.getThemeColor(requireContext())
        val white = android.graphics.Color.WHITE
        val strokePx = (resources.displayMetrics.density * 1.5f).toInt()
        fun filled(id: Int) = root.findViewById<MaterialButton>(id).apply {
            backgroundTintList = ColorStateList.valueOf(accent); setTextColor(white)
            iconTint = ColorStateList.valueOf(white); strokeWidth = 0
        }
        fun outlined(id: Int) = root.findViewById<MaterialButton>(id).apply {
            backgroundTintList = ColorStateList.valueOf(white); setTextColor(accent)
            strokeColor = ColorStateList.valueOf(accent); strokeWidth = strokePx
            iconTint = ColorStateList.valueOf(accent)
        }
        outlined(R.id.btnReceiptPreview); outlined(R.id.btnBackToBilling)
        filled(R.id.btnConfirmPay)
        root.findViewById<TextView>(R.id.tvTotalAmount).setTextColor(accent)
        root.findViewById<TextView>(R.id.tvChangeDue).setTextColor(accent)

        // Payment method: selected filled, others outlined.
        mapOf(R.id.btnPayCash to "Cash", R.id.btnPayCard to "Card", R.id.btnPayOnline to "Online")
            .forEach { (id, name) -> if (name == payMethod) filled(id) else outlined(id) }
    }

    private fun money(v: Double): String = String.format(java.util.Locale.US, "%,.2f", v)
}
