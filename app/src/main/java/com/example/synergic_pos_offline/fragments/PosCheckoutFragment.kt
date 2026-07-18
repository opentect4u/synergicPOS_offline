package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/** In-process hand-off of the current sale from billing to checkout. */
object CheckoutSession {
    data class Line(val name: String, val sku: String, var price: Double, var qty: Int)

    var lines: MutableList<Line> = mutableListOf()
    var discountPercent: Int = 0
    var couponApplied: Boolean = false
    var customerName: String? = null
    var orderNo: Int = 1042
}

/**
 * POS checkout screen, modelled on the shared design: a bill preview on the left
 * (editable line items / receipt view) and a payment panel on the right (mode,
 * cash / card / wallet / split, receipt delivery, complete). Responsive width
 * for the payment panel.
 */
class PosCheckoutFragment : Fragment(), TitledScreen {

    override val screenTitle = "Checkout"

    private enum class Method { CASH, CARD, WALLET, SPLIT }
    private enum class Receipt { PRINT, EMAIL, SMS, NONE }

    // Working copy of the sale (edits here don't touch billing).
    private val lines = CheckoutSession.lines.map { it.copy() }.toMutableList()
    private var discountPercent = CheckoutSession.discountPercent
    private var couponApplied = CheckoutSession.couponApplied

    private var editMode = true
    private var method = Method.CASH
    private var receipt = Receipt.PRINT
    private var accent = 0

    private lateinit var root: View
    private val clockHandler = Handler(Looper.getMainLooper())
    private lateinit var clockRunnable: Runnable

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_pos_checkout, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view
        val ctx = requireContext()
        accent = ThemeManager.getThemeColor(ctx)
        val density = resources.displayMetrics.density

        // Header
        id<TextView>(R.id.tvOrder).text = "#${CheckoutSession.orderNo}"
        id<TextView>(R.id.tvCustName).text = CheckoutSession.customerName ?: "Guest"
        id<TextView>(R.id.tvCustSub).text =
            if (CheckoutSession.customerName != null) "340 pts · Loyalty" else "Walk-in"
        id<TextView>(R.id.tvCustInitials).text =
            (CheckoutSession.customerName ?: "Guest").split(" ")
                .mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString("")

        id<MaterialButton>(R.id.btnBackBilling).setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        // Accent bars
        id<View>(R.id.barLeftTotal).setBackgroundColor(accent)
        id<View>(R.id.barAmountDue).setBackgroundColor(accent)

        // Mode toggle
        id<MaterialButton>(R.id.btnModeEdit).setOnClickListener { setMode(true) }
        id<MaterialButton>(R.id.btnModeReceipt).setOnClickListener { setMode(false) }

        // Add line
        id<MaterialButton>(R.id.btnAddLine).setOnClickListener { addLine() }
        // Coupon / discount
        id<MaterialButton>(R.id.btnApplyCoupon).setOnClickListener {
            applyCoupon(id<TextInputEditText>(R.id.etCoupon).text?.toString().orEmpty())
        }
        id<TextInputEditText>(R.id.etDiscount).addTextChangedListener(watcher {
            discountPercent = (it.toIntOrNull() ?: 0).coerceIn(0, 100); refreshTotals()
        })

        // Payment mode tiles
        id<MaterialButton>(R.id.btnCash).setOnClickListener { setMethod(Method.CASH) }
        id<MaterialButton>(R.id.btnCard).setOnClickListener { setMethod(Method.CARD) }
        id<MaterialButton>(R.id.btnWallet).setOnClickListener { setMethod(Method.WALLET) }
        id<MaterialButton>(R.id.btnSplit).setOnClickListener { setMethod(Method.SPLIT) }

        // Cash inputs
        id<TextInputEditText>(R.id.etCash).addTextChangedListener(watcher { refreshTotals() })
        id<TextInputEditText>(R.id.etSplitCash).addTextChangedListener(watcher { refreshTotals() })
        id<TextInputEditText>(R.id.etSplitCard).addTextChangedListener(watcher { refreshTotals() })
        id<MaterialButton>(R.id.btnExact).setOnClickListener { id<TextInputEditText>(R.id.etCash).setText(fmtPlain(total())) }
        id<MaterialButton>(R.id.btn20).setOnClickListener { id<TextInputEditText>(R.id.etCash).setText("20") }
        id<MaterialButton>(R.id.btn50).setOnClickListener { id<TextInputEditText>(R.id.etCash).setText("50") }
        id<MaterialButton>(R.id.btn100).setOnClickListener { id<TextInputEditText>(R.id.etCash).setText("100") }

        // Receipt delivery
        id<MaterialButton>(R.id.btnRcPrint).setOnClickListener { setReceipt(Receipt.PRINT) }
        id<MaterialButton>(R.id.btnRcEmail).setOnClickListener { setReceipt(Receipt.EMAIL) }
        id<MaterialButton>(R.id.btnRcSms).setOnClickListener { setReceipt(Receipt.SMS) }
        id<MaterialButton>(R.id.btnRcNone).setOnClickListener { setReceipt(Receipt.NONE) }

        // Complete
        id<MaterialButton>(R.id.btnComplete).setOnClickListener { complete() }

        // Theme everything
        ThemeManager.applyTheme(view)

        // Toggles and selection states: Manually override the global theme for the ACTIVE button.
        setMode(editMode)
        applyTileStyles()
        setReceipt(receipt)

        clockRunnable = object : Runnable {
            override fun run() {
                id<TextView>(R.id.tvClock).text = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                clockHandler.postDelayed(this, 30_000)
            }
        }
        clockRunnable.run()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clockHandler.removeCallbacks(clockRunnable)
    }

    // ---- Left: items -------------------------------------------------------

    private fun renderItems() {
        val ll = id<LinearLayout>(R.id.llItems)
        ll.removeAllViews()
        lines.forEachIndexed { index, line ->
            val row = layoutInflater.inflate(R.layout.item_checkout_line, ll, false)
            row.findViewById<TextView>(R.id.tvLineNo).text = (index + 1).toString().padStart(2, '0')
            row.findViewById<TextView>(R.id.tvName).text = line.name
            row.findViewById<TextView>(R.id.tvSku).text = "SKU ${line.sku}"
            row.findViewById<TextView>(R.id.tvUnitPrice).text = money(line.price)
            row.findViewById<TextView>(R.id.tvQty).text = line.qty.toString()
            row.findViewById<TextView>(R.id.tvAmount).text = money(line.price * line.qty)
            row.findViewById<ImageButton>(R.id.btnMinus).setOnClickListener {
                line.qty--; if (line.qty <= 0) lines.remove(line); renderItems(); refreshTotals()
            }
            row.findViewById<ImageButton>(R.id.btnPlus).setOnClickListener {
                line.qty++; renderItems(); refreshTotals()
            }
            row.findViewById<ImageButton>(R.id.btnRemove).setOnClickListener {
                lines.remove(line); renderItems(); refreshTotals()
            }
            // Theme the line item buttons
            ThemeManager.applyTheme(row)
            ll.addView(row)
        }
    }

    private fun addLine() {
        val name = id<TextInputEditText>(R.id.etAddName).text?.toString()?.trim().orEmpty()
        val price = id<TextInputEditText>(R.id.etAddPrice).text?.toString()?.toDoubleOrNull()
        if (name.isEmpty() || price == null || price <= 0) { toast("Enter item name and price"); return }
        lines.add(CheckoutSession.Line(name, "MAN", price, 1))
        id<TextInputEditText>(R.id.etAddName).setText("")
        id<TextInputEditText>(R.id.etAddPrice).setText("")
        renderItems(); refreshTotals()
    }

    private fun applyCoupon(code: String) {
        val msg = id<TextView>(R.id.tvCouponMsg)
        when {
            code.trim().uppercase() == "SAVE10" -> {
                couponApplied = true; msg.visibility = View.VISIBLE; msg.text = "SAVE10 applied — 10% off"
            }
            code.isBlank() -> msg.visibility = View.GONE
            else -> { couponApplied = false; msg.visibility = View.VISIBLE; msg.text = "Invalid code" }
        }
        refreshTotals()
    }

    // ---- Mode / method / receipt selection --------------------------------

    private fun setMode(edit: Boolean) {
        editMode = edit
        id<View>(R.id.scrollEdit).visibility = if (edit) View.VISIBLE else View.GONE
        id<View>(R.id.scrollReceipt).visibility = if (edit) View.GONE else View.VISIBLE
        val e = id<MaterialButton>(R.id.btnModeEdit)
        val r = id<MaterialButton>(R.id.btnModeReceipt)
        if (edit) { 
            styleFilled(e)
            styleOutlined(r) 
        } else { 
            styleOutlined(e)
            styleFilled(r) 
        }
        if (!edit) renderReceipt()
    }

    private fun setMethod(m: Method) {
        method = m
        id<View>(R.id.sectionCash).visibility = if (m == Method.CASH) View.VISIBLE else View.GONE
        id<View>(R.id.sectionTerminal).visibility =
            if (m == Method.CARD || m == Method.WALLET) View.VISIBLE else View.GONE
        id<View>(R.id.sectionSplit).visibility = if (m == Method.SPLIT) View.VISIBLE else View.GONE
        val title = titleFor(m)
        id<TextView>(R.id.tvTerminalTitle).text = "$title terminal connected"
        id<TextView>(R.id.tvTerminalMsg).text =
            "Present $title on the reader for ${money(total())}, then confirm below."
        id<TextView>(R.id.tvPayingBy).text = "Paying by $title"
        applyTileStyles()
        refreshTotals()
    }

    private fun setReceipt(r: Receipt) {
        receipt = r
        listOf(
            R.id.btnRcPrint to Receipt.PRINT, R.id.btnRcEmail to Receipt.EMAIL,
            R.id.btnRcSms to Receipt.SMS, R.id.btnRcNone to Receipt.NONE
        ).forEach { (bId, value) ->
            val b = id<MaterialButton>(bId)
            if (value == r) styleFilled(b) else styleOutlined(b)
        }
    }

    private fun applyTileStyles() {
        listOf(
            R.id.btnCash to Method.CASH, R.id.btnCard to Method.CARD,
            R.id.btnWallet to Method.WALLET, R.id.btnSplit to Method.SPLIT
        ).forEach { (bId, value) ->
            val b = id<MaterialButton>(bId)
            if (value == method) {
                styleFilled(b)
            } else {
                styleOutlined(b)
            }
        }
    }

    // ---- Totals ------------------------------------------------------------

    private fun totalPct() = min(100, discountPercent + if (couponApplied) 10 else 0)
    private fun subtotal() = lines.sumOf { it.price * it.qty }
    private fun discountAmt() = subtotal() * totalPct() / 100.0
    private fun taxAmt() = (subtotal() - discountAmt()).coerceAtLeast(0.0) * 0.05
    private fun total() = (subtotal() - discountAmt()).coerceAtLeast(0.0) + taxAmt()

    private fun refreshTotals() {
        val disc = "Discount (${totalPct()}%)"
        id<TextView>(R.id.tvSubtotal).text = money(subtotal())
        id<TextView>(R.id.tvDiscountLabel).text = disc
        id<TextView>(R.id.tvDiscountAmt).text = "- ${money(discountAmt())}"
        id<TextView>(R.id.tvTax).text = money(taxAmt())
        id<TextView>(R.id.tvLeftTotal).text = money(total())
        id<TextView>(R.id.tvAmountDue).text = money(total())

        // Cash change
        val tendered = id<TextInputEditText>(R.id.etCash).text?.toString()?.toDoubleOrNull() ?: 0.0
        id<TextView>(R.id.tvChange).text = money((tendered - total()).coerceAtLeast(0.0))

        // Split remaining
        val sc = id<TextInputEditText>(R.id.etSplitCash).text?.toString()?.toDoubleOrNull() ?: 0.0
        val sd = id<TextInputEditText>(R.id.etSplitCard).text?.toString()?.toDoubleOrNull() ?: 0.0
        val remaining = total() - sc - sd
        id<TextView>(R.id.tvSplitStatus).text = when {
            remaining > 0.001 -> "Remaining to cover"
            remaining < -0.001 -> "Over the total"
            else -> "Fully covered"
        }
        id<TextView>(R.id.tvSplitRemaining).text =
            (if (remaining < -0.001) "+" else "") + money(kotlin.math.abs(remaining))

        // Complete enabled?
        val can = total() > 0 && when (method) {
            Method.CASH -> tendered >= total() - 0.001
            Method.SPLIT -> sc + sd >= total() - 0.001
            else -> true
        }
        val btn = id<MaterialButton>(R.id.btnComplete)
        btn.isEnabled = can
        btn.alpha = if (can) 1f else 0.45f
        btn.text = "Complete Checkout · ${money(total())}"

        if (!editMode) renderReceipt()
    }

    private fun renderReceipt() {
        val ll = id<LinearLayout>(R.id.llReceiptItems)
        ll.removeAllViews()
        val ctx = requireContext()
        lines.forEach { line ->
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
            }
            val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(TextView(ctx).apply {
                text = line.name; textSize = 13f; typeface = Typeface.MONOSPACE
                setTextColor(ContextCompat.getColor(ctx, R.color.text_main))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(TextView(ctx).apply {
                text = money(line.price * line.qty); textSize = 13f; typeface = Typeface.MONOSPACE
                setTextColor(ContextCompat.getColor(ctx, R.color.text_main))
            })
            col.addView(top)
            col.addView(TextView(ctx).apply {
                text = "${line.qty} × ${money(line.price)}"; textSize = 11f; typeface = Typeface.MONOSPACE
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            })
            ll.addView(col)
        }
        id<TextView>(R.id.tvRcSubtotal).text = money(subtotal())
        id<TextView>(R.id.tvRcDiscountLabel).text = "Discount (${totalPct()}%)"
        id<TextView>(R.id.tvRcDiscount).text = "- ${money(discountAmt())}"
        id<TextView>(R.id.tvRcTax).text = money(taxAmt())
        id<TextView>(R.id.tvRcTotal).text = money(total())
    }

    // ---- Complete ----------------------------------------------------------

    private fun complete() {
        if (lines.isEmpty()) { toast("The bill is empty"); return }
        val method = titleFor(method)
        val change = if (this.method == Method.CASH) {
            val t = id<TextInputEditText>(R.id.etCash).text?.toString()?.toDoubleOrNull() ?: 0.0
            " · Change ${money((t - total()).coerceAtLeast(0.0))}"
        } else ""
        AlertDialog.Builder(requireContext())
            .setTitle("Checkout complete")
            .setMessage("Order #${CheckoutSession.orderNo} · Charged ${money(total())} via $method$change")
            .setPositiveButton("Start new sale") { _, _ ->
                CheckoutSession.orderNo++
                requireActivity().supportFragmentManager.popBackStack()
            }
            .setCancelable(false)
            .create()
            .also { it.setCanceledOnTouchOutside(false); it.show() }
    }

    // ---- Helpers -----------------------------------------------------------

    private fun titleFor(m: Method) = when (m) {
        Method.CASH -> "Cash"; Method.CARD -> "Card"; Method.WALLET -> "Wallet"; Method.SPLIT -> "Split"
    }

    private fun styleFilled(btn: MaterialButton) {
        btn.backgroundTintList = ColorStateList.valueOf(accent)
        btn.setTextColor(Color.WHITE)
        btn.strokeColor = ColorStateList.valueOf(accent)
        btn.iconTint = ColorStateList.valueOf(Color.WHITE)
        btn.cornerRadius = (resources.displayMetrics.density * 12).toInt() // Match dialog corner radius
        btn.strokeWidth = 0
    }

    /** Restores an outlined button's white fill + accent border/text/icon. */
    private fun styleOutlined(btn: MaterialButton) {
        // White background, theme-coloured text + border (matching dialog cancel style).
        btn.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        btn.setTextColor(accent)
        btn.strokeColor = ColorStateList.valueOf(accent)
        btn.strokeWidth = (resources.displayMetrics.density * 1.5f).toInt()
        btn.iconTint = ColorStateList.valueOf(accent)
        btn.rippleColor = ColorStateList.valueOf(accent).withAlpha(30)
        btn.cornerRadius = (resources.displayMetrics.density * 12).toInt() // Match dialog corner radius
    }

    private fun money(v: Double) = "₹" + String.format("%.2f", v)
    private fun fmtPlain(v: Double) = String.format("%.2f", v)

    private fun <T : View> id(resId: Int): T = root.findViewById(resId)

    private fun toast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun watcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { onChange(s?.toString()?.trim().orEmpty()) }
        override fun afterTextChanged(s: Editable?) {}
    }
}
