package com.example.synergic_pos_offline.fragments

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.ReturnDao
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.ReturnPrinter
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

/**
 * Bill-wise sale return: the original bill in edit mode.
 *
 * Every line is listed with a checkbox and an editable quantity, defaulted to
 * however much of it can still come back - the sold quantity less anything already
 * returned on an earlier visit, so the same goods cannot be refunded twice. Only
 * ticked lines are returned, and the refund follows what is typed.
 */
class BillReturnFragment : Fragment(), TitledScreen {

    override val screenTitle = "Sale Return"

    private val dao: ReturnDao by lazy { ReturnDao(requireContext()) }

    private val receiptNo: Long get() = arguments?.getLong(ARG_RECEIPT_NO) ?: -1L
    private val billNumber: String get() = arguments?.getString(ARG_BILL_NO).orEmpty()

    /** One row's state: the bill line, whether it is ticked, and how much of it. */
    private data class Row(
        val line: ReturnDao.BillLine,
        var selected: Boolean = false,
        var quantity: Double = line.returnableQuantity
    )

    private val rows = mutableListOf<Row>()

    /**
     * The tax rules this bill was raised under, frozen onto it at the time of sale.
     *
     * Read once when the screen opens and used for every line, so a refund gives
     * back what the bill actually charged - not what the same goods would be
     * charged today. Tax Settings change over the life of a till, and a return
     * priced under today's would be wrong the moment they do.
     */
    private val basis: ReturnDao.PricingBasis by lazy { dao.basisForBill(receiptNo) }

    private lateinit var root: View
    private lateinit var btnSave: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_return_billwise, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view
        btnSave = view.findViewById(R.id.btnSaveBillReturn)

        view.findViewById<TextView>(R.id.tvReturnBillNo).text = "Bill $billNumber"

        // Lines with nothing left to return are dropped rather than shown disabled:
        // the operator is choosing what comes back, and a line that cannot is noise.
        rows.clear()
        rows.addAll(
            dao.linesOfBill(receiptNo)
                .filter { it.returnableQuantity > 0.0 }
                .map { Row(it) }
        )

        val rv = view.findViewById<RecyclerView>(R.id.rvReturnLines)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = LineAdapter()
        view.findViewById<View>(R.id.tvNothingReturnable).visibility =
            if (rows.isEmpty()) View.VISIBLE else View.GONE

        view.findViewById<MaterialButton>(R.id.btnSelectAll).setOnClickListener { toggleAll() }
        btnSave.setOnClickListener { saveReturn() }

        ThemeManager.applyTheme(view)
        refreshTotal()
    }

    // ---- Selection ---------------------------------------------------------

    /** Ticks everything, or clears it when everything is already ticked. */
    private fun toggleAll() {
        val turnOn = rows.any { !it.selected }
        rows.forEach { it.selected = turnOn }
        root.findViewById<RecyclerView>(R.id.rvReturnLines).adapter?.notifyDataSetChanged()
        refreshTotal()
    }

    // ---- The figures -------------------------------------------------------

    /**
     * Prices [row] at its current quantity, with its share of whatever discount the
     * line was sold under - see [ReturnDao.BillLine.discountFor].
     *
     * The one place a row is priced, so the figure in the list, the total at the
     * foot and what is finally saved cannot drift apart.
     */
    private fun priced(row: Row): ReturnDao.ReturnLine = dao.priceLine(
        name = row.line.name,
        productId = row.line.productId,
        billItemId = row.line.billItemId,
        quantity = row.quantity,
        rate = row.line.rate,
        cgstRate = row.line.cgstRate,
        sgstRate = row.line.sgstRate,
        vatRate = row.line.vatRate,
        discountAmount = row.line.discountFor(row.quantity),
        basis = basis
    )

    /** The ticked lines, priced. */
    private fun selectedLines(): List<ReturnDao.ReturnLine> = rows
        .filter { it.selected && it.quantity > 0.0 }
        .map { priced(it) }

    private fun refreshTotal() {
        val lines = selectedLines()
        val block = root.findViewById<LinearLayout>(R.id.llReturnSummary)
        block.removeAllViews()

        if (lines.isEmpty()) {
            block.addView(summaryRow("Nothing selected", "", emphasis = false))
        } else {
            // Totalled exactly as the printed slip totals it - same rows, same
            // order, same thresholds - because it is the same list.
            ReturnDao.summaryRows(lines).forEach { row ->
                block.addView(
                    summaryRow(
                        label = row.label,
                        value = if (row.isMoney) money(row.value) else row.value.toInt().toString(),
                        emphasis = row.emphasis
                    )
                )
            }
        }

        btnSave.isEnabled = lines.isNotEmpty()
        btnSave.alpha = if (lines.isNotEmpty()) 1f else 0.5f
    }

    /** One "LABEL     figure" line of the summary; the refund is set apart. */
    private fun summaryRow(label: String, value: String, emphasis: Boolean): View {
        val density = resources.displayMetrics.density
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
            addView(TextView(requireContext()).apply {
                text = label
                textSize = if (emphasis) 15f else 13f
                setTypeface(typeface, if (emphasis) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (emphasis) R.color.text_main else R.color.text_secondary
                    )
                )
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            addView(TextView(requireContext()).apply {
                text = value
                textSize = if (emphasis) 22f else 13f
                gravity = Gravity.END
                setTypeface(typeface, if (emphasis) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(ContextCompat.getColor(context, R.color.text_main))
                layoutParams = LinearLayout.LayoutParams(-2, -2)
            })
        }
    }

    // ---- Saving ------------------------------------------------------------

    private fun saveReturn() {
        val lines = selectedLines()
        if (lines.isEmpty()) return

        val result = dao.save(lines, originalBillId = receiptNo)
        if (result == null) {
            toast("Could not record the return")
            return
        }

        DialogUtils.showSuccess(
            context = requireContext(),
            title = "Return recorded",
            message = "${lines.size} line${if (lines.size > 1) "s" else ""} taken back off " +
                "bill $billNumber. Refund ${money(result.totalAmount)}.",
            buttonText = "Print receipt"
        ) {
            ReturnPrinter.print(requireContext(), result) { if (isAdded) toast(it) }
            // Back to the bill list: this bill's returnable quantities have changed,
            // and the list reloads them on resume.
            if (isAdded) parentFragmentManager.popBackStack()
        }
    }

    // ---- Rows --------------------------------------------------------------

    private inner class LineAdapter : RecyclerView.Adapter<LineAdapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val check: CheckBox = view.findViewById(R.id.cbReturnLine)
            val name: TextView = view.findViewById(R.id.tvLineName)
            val sold: TextView = view.findViewById(R.id.tvLineSold)
            val tax: TextView = view.findViewById(R.id.tvLineTax)
            val rate: TextView = view.findViewById(R.id.tvLineRate)
            val qty: TextInputEditText = view.findViewById(R.id.etLineQty)
            val refund: TextView = view.findViewById(R.id.tvLineRefund)

            /** Held so it can be detached while the field is being rebound. */
            var watcher: TextWatcher? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_return_line, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]

            holder.name.text = row.line.name
            holder.rate.text = money(row.line.rate)
            holder.sold.text = buildString {
                append("Sold ${trim(row.line.soldQuantity)}")
                // Only worth saying when some of it has already come back.
                if (row.line.returnedQuantity > 0.0) {
                    append("  ·  returned ${trim(row.line.returnedQuantity)}")
                }
                append("  ·  up to ${trim(row.line.returnableQuantity)}")
            }
            // The rates this line was taxed at, and any discount it carried.
            holder.tax.text = buildString {
                if (row.line.cgstRate > 0.0) append("CGST ${rate(row.line.cgstRate)}%   ")
                if (row.line.sgstRate > 0.0) append("SGST ${rate(row.line.sgstRate)}%   ")
                if (row.line.vatRate > 0.0) append("VAT ${rate(row.line.vatRate)}%   ")
                if (row.line.discountAmount > 0.005) append("DISC ${money(row.line.discountAmount)}")
                if (isEmpty()) append("No tax or discount on this line")
            }

            // Detached first: a recycled holder still carries the previous row's
            // watcher, which would otherwise write this row's text onto that one.
            holder.watcher?.let { holder.qty.removeTextChangedListener(it) }
            holder.qty.setText(trim(row.quantity))
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val typed = s?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
                    // Capped at what is left on the line: more than that was never
                    // sold, or has already been given back.
                    row.quantity = typed.coerceIn(0.0, row.line.returnableQuantity)
                    holder.refund.text = money(refundFor(row))
                    refreshTotal()
                }
            }
            holder.qty.addTextChangedListener(watcher)
            holder.watcher = watcher

            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = row.selected
            holder.check.setOnCheckedChangeListener { _, checked ->
                row.selected = checked
                refreshTotal()
            }

            holder.refund.text = money(refundFor(row))
        }

        override fun getItemCount() = rows.size
    }

    /** What this row would refund at its current quantity. */
    private fun refundFor(row: Row): Double =
        if (row.quantity <= 0.0) 0.0 else priced(row).amount

    // ---- Small helpers -----------------------------------------------------

    private fun money(v: Double): String = "₹ " + String.format(Locale.US, "%.2f", v)

    private fun trim(v: Double): String =
        if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.2f", v)

    /** A tax rate trimmed of a needless ".00" - "5" not "5.00", but "2.50" kept. */
    private fun rate(v: Double): String =
        if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.2f", v)

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ARG_RECEIPT_NO = "receipt_no"
        private const val ARG_BILL_NO = "bill_no"

        fun newInstance(receiptNo: Long, billNumber: String): BillReturnFragment =
            BillReturnFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_RECEIPT_NO, receiptNo)
                    putString(ARG_BILL_NO, billNumber)
                }
            }
    }
}
