package com.example.synergic_pos_offline.fragments

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.BillDao
import com.example.synergic_pos_offline.utils.CalculatorBill
import com.example.synergic_pos_offline.utils.PeriodReportPrinter
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

/**
 * Calculator mode - the whole application, on one screen.
 *
 * A rate and a quantity are typed on the keypad and `=` stacks what they came to onto
 * the bill. There are no products to look up, no tax to apply and no customer to
 * record, which is the point of the mode: a till for a shop that prices in its head
 * and wants a printed slip at the end of it.
 *
 * The keypad rather than the device's own: the two fields take digits and a decimal
 * point and nothing else, and a soft keyboard on a counter tablet is both slower and
 * larger than the eleven keys this actually needs.
 */
class CalculatorFragment : Fragment(), TitledScreen {

    override val screenTitle = "Calculator"

    /** What has been stacked up but not yet saved. */
    private val lines = mutableListOf<CalculatorBill.Line>()

    /** Which of the two fields has the caret - what the keypad types into. */
    private val active: TextInputEditText get() = if (etRate.hasFocus()) etRate else etQty

    private lateinit var root: View
    private lateinit var etRate: TextInputEditText
    private lateinit var etQty: TextInputEditText

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_calculator, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view

        etRate = view.findViewById(R.id.etCalcRate)
        etQty = view.findViewById(R.id.etCalcQty)

        // Typable from a keyboard - Tab walks quantity to rate, Enter stacks the
        // line - but the system's own soft keyboard never opens over the keypad
        // below. A counter tablet has one or the other, not both at once.
        listOf(etQty, etRate).forEach { field ->
            field.showSoftInputOnFocus = false
            field.setOnFocusChangeListener { _, _ -> paintFocus() }
            // Enter, whichever key the keypad sends it as.
            //
            // Acted on once, on the way down, and consumed on the way up as well -
            // an Enter left unconsumed is turned into an editor action by the
            // TextView itself, which would run this a second time. That second run
            // saw the caret already on the rate, stacked the line and sent the focus
            // back to the quantity, which is the bug it looked like.
            field.setOnKeyListener { _, code, event ->
                val entered = code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                if (!entered) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_DOWN) onEnter(field)
                true
            }
        }

        view.findViewById<RecyclerView>(R.id.rvCalcLines).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = LineAdapter()
        }

        buildKeypad(view.findViewById(R.id.glCalcKeys))

        view.findViewById<MaterialButton>(R.id.btnCalcSave).setOnClickListener { save() }

        ThemeManager.applyTheme(view)
        // Opens on the quantity, which is what is typed first.
        etQty.requestFocus()
        paintFocus()
        refresh()
    }

    // ---- The keypad ----------------------------------------------------------

    /**
     * Eleven keys, laid out four across.
     *
     * `=` is the one that does the work: it takes whatever is in the two fields,
     * stacks the line and clears them for the next one.
     */
    private fun buildKeypad(grid: GridLayout) {
        grid.removeAllViews()
        val keys = listOf(
            "7", "8", "9", "C",
            "4", "5", "6", "⌫",
            "1", "2", "3", "NEXT",
            "0", ".", "00", "="
        )
        keys.forEach { key ->
            grid.addView(
                MaterialButton(requireContext()).apply {
                    text = key
                    textSize = 18f
                    isAllCaps = false
                    insetTop = 0
                    insetBottom = 0
                    cornerRadius = dp(12)
                    setOnClickListener { onKey(key) }
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = dp(52)
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    }
                }
            )
        }
        ThemeManager.applyTheme(grid)
    }

    private fun onKey(key: String) {
        val field = active
        when (key) {
            "C" -> { etRate.setText(""); etQty.setText(""); etQty.requestFocus() }
            "⌫" -> field.setText(field.text?.toString().orEmpty().dropLast(1))
            "NEXT" -> (if (field === etQty) etRate else etQty).requestFocus()
            "=" -> onEnter(field)
            // A second decimal point would make the value unreadable as a number.
            "." -> if (!field.text?.toString().orEmpty().contains(".")) field.append(".")
            else -> field.append(key)
        }
        // Caret to the end of whatever is now in the field the keypad is aimed at -
        // read after the key, since some of them move it to the other field.
        active.setSelection(active.text?.length ?: 0)
    }

    /**
     * What Enter and `=` do, which depends on where the caret is.
     *
     * On the quantity it moves to the rate - the line is not finished yet, and
     * stacking a quantity with no price would be a line of nothing. On the rate it
     * stacks, which is the whole gesture: quantity, Tab, rate, Enter.
     */
    private fun onEnter(field: TextInputEditText) {
        if (field === etQty) etRate.requestFocus() else stack()
    }

    /** Lights whichever field the caret is in, so the keypad's target is visible. */
    private fun paintFocus() {
        val accent = ThemeManager.getThemeColor(requireContext())
        val plain = Color.parseColor("#222222")
        etQty.setTextColor(if (etQty.hasFocus()) accent else plain)
        etRate.setTextColor(if (etRate.hasFocus()) accent else plain)
    }

    // ---- The bill ------------------------------------------------------------

    /**
     * Takes what is in the two fields onto the bill.
     *
     * Both have to carry something: a rate with no quantity is not a line, and the
     * one thing worse than refusing it is stacking a zero nobody meant.
     */
    private fun stack() {
        val rate = etRate.text?.toString()?.toDoubleOrNull()
        val qty = etQty.text?.toString()?.toDoubleOrNull()
        when {
            rate == null || rate <= 0.0 -> toast("Enter a rate")
            qty == null || qty <= 0.0 -> toast("Enter a quantity")
            else -> {
                lines.add(CalculatorBill.Line(lines.size + 1, rate, qty))
                etRate.setText("")
                etQty.setText("")
                // Back to the quantity, ready for the next line without a tap.
                etQty.requestFocus()
                refresh()
            }
        }
    }

    private fun refresh() {
        val bill = bill()
        root.findViewById<RecyclerView>(R.id.rvCalcLines).adapter?.notifyDataSetChanged()
        root.findViewById<View>(R.id.rvCalcLines).visibility =
            if (bill.isEmpty) View.GONE else View.VISIBLE
        root.findViewById<View>(R.id.tvCalcEmpty).visibility =
            if (bill.isEmpty) View.VISIBLE else View.GONE
        root.findViewById<TextView>(R.id.tvCalcTotal).text = "TOTAL  ${money(bill.totalAmount)}"
    }

    /** What is on the bill right now, unsaved and unnumbered. */
    private fun bill(number: String = "-") =
        CalculatorBill.Bill(number, CalculatorBill.now(), lines.toList())

    /**
     * Saves the bill, prints it, then shows it.
     *
     * Written through [BillDao.createBill] like any other sale, so a calculator bill
     * takes its number from the same counter and turns up in Bill History and the
     * day's takings. Its lines carry no product and no tax, which is what a
     * calculator bill is.
     */
    private fun save() {
        if (lines.isEmpty()) {
            toast("Nothing to save")
            return
        }
        val total = bill().totalAmount
        val result = BillDao(requireContext()).createBill(
            BillDao.NewBill(
                billType = "CASH",
                customerId = null,
                items = lines.map {
                    BillDao.Item(
                        productId = null,
                        name = "Item ${it.serial}",
                        quantity = it.quantity,
                        rate = it.rate
                    )
                },
                payment = BillDao.Payment(mode = "CASH", amountPaid = total),
                totalPrice = total,
                discountAmount = 0.0,
                discountPercentage = 0.0,
                cgstAmount = 0.0,
                sgstAmount = 0.0,
                netAmount = total
            )
        )
        if (result == null) {
            toast("Could not save the bill")
            return
        }
        // Saved and printed in one press: a calculator till hands the customer the
        // slip, and a Save that stopped short of the printer would only ever be
        // followed by a second tap.
        val saved = bill(result.billNumber)
        PeriodReportPrinter.print(requireContext(), CalculatorBill.content(saved), "lines") {
            if (isAdded) toast(it)
        }
        lines.clear()
        refresh()
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, CalculatorBillFragment.of(saved))
            .addToBackStack(null)
            .commit()
    }

    // ---- Rows ----------------------------------------------------------------

    private inner class LineAdapter : RecyclerView.Adapter<LineAdapter.Holder>() {

        inner class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                // Full width, or the four weighted cells share only as much room as
                // their own text needs and stop lining up under the headings above.
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(dp(10), dp(10), dp(10), dp(10))
                repeat(4) { i ->
                    addView(TextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                        gravity = if (i == 0) Gravity.START else Gravity.END
                        textSize = 14f
                        maxLines = 1
                        setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                        setTextColor(resources.getColor(R.color.text_main, null))
                    })
                }
            })

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val line = lines[position]
            listOf(
                line.serial.toString(), money(line.rate), money(line.quantity), money(line.amount)
            ).forEachIndexed { i, value ->
                (holder.row.getChildAt(i) as TextView).text = value
            }
            // Zebra by where the row sits now: a recycled row carries the shade of
            // whichever row it used to be.
            holder.row.setBackgroundColor(
                if (position % 2 == 1) Color.parseColor("#FFFFFF") else Color.parseColor("#F7F8FA")
            )
        }

        override fun getItemCount() = lines.size
    }

    // ---- Small helpers -------------------------------------------------------

    private fun money(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
