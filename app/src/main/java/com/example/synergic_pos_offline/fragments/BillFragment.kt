package com.example.synergic_pos_offline.fragments

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R

/**
 * Read-only bill / receipt preview, styled like a thermal print-out.
 *
 * Items are rendered into [R.id.llItems]; the rest of the receipt (store name,
 * tax block, grand total) is static in [R.layout.fragment_bill]. Sample data
 * mirrors the shared reference bill.
 */
class BillFragment : Fragment(), TitledScreen {

    override val screenTitle = "Bill"

    /** One printed line item: serial + name, quantity, unit price, amount. */
    private data class BillItem(
        val sr: Int,
        val name: String,
        val qty: String,
        val price: String,
        val amount: String
    )

    private val sampleItems = listOf(
        BillItem(1, "VEG MANCHURIAN", "2 PLT", "160.00", "320.00"),
        BillItem(2, "BISLERY..1L", "1.00 LTR", "20.00", "20.00"),
        BillItem(3, "PANEER CRISPY", "1 PLT", "220.00", "220.00"),
        BillItem(4, "PANEER 65", "1 PLT", "210.00", "210.00")
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_bill, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Fill the header from the selected bill, when provided.
        arguments?.let { args ->
            args.getString(ARG_BILL_NO)?.let {
                view.findViewById<TextView>(R.id.tvBillNo).text = "BILL NO: $it"
            }
            args.getString(ARG_NAME)?.let {
                view.findViewById<TextView>(R.id.tvName).text = "NAME  : $it"
            }
            args.getString(ARG_DATE)?.let { view.findViewById<TextView>(R.id.tvDate).text = it }
            args.getString(ARG_TIME)?.let { view.findViewById<TextView>(R.id.tvTime).text = it }
            args.getString(ARG_TOTAL)?.let { view.findViewById<TextView>(R.id.tvGrandTotal).text = it }
        }

        val llItems = view.findViewById<LinearLayout>(R.id.llItems)
        sampleItems.forEach { llItems.addView(buildItemRow(it)) }
    }

    /** Builds a 4-column monospace item row matching the header columns. */
    private fun buildItemRow(item: BillItem): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
        }

        fun cell(text: String, weight: Float, gravity: Int): TextView = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            this.text = text
            this.gravity = gravity
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setTextColor(0xFF222222.toInt())
        }

        row.addView(cell("${item.sr} ${item.name}", 3.4f, Gravity.START))
        row.addView(cell(item.qty, 2f, Gravity.CENTER))
        row.addView(cell(item.price, 2f, Gravity.END))
        row.addView(cell(item.amount, 2.2f, Gravity.END))
        return row
    }

    companion object {
        private const val ARG_BILL_NO = "bill_no"
        private const val ARG_NAME = "name"
        private const val ARG_DATE = "date"
        private const val ARG_TIME = "time"
        private const val ARG_TOTAL = "total"

        /** Opens the receipt pre-filled with the given bill's header. */
        fun newInstance(
            billNo: String, name: String, date: String, time: String, total: String
        ): BillFragment = BillFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_BILL_NO, billNo)
                putString(ARG_NAME, name)
                putString(ARG_DATE, date)
                putString(ARG_TIME, time)
                putString(ARG_TOTAL, total)
            }
        }
    }
}
