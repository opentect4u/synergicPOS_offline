package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton

/**
 * Item-wise list of bills. Each row has a "View" button that opens the
 * already-designed receipt preview ([BillFragment]) for that bill.
 */
class BillListFragment : Fragment(), TitledScreen {

    override val screenTitle = "Bills"

    /** One bill summary row. */
    private data class Bill(
        val billNo: String,
        val name: String,
        val date: String,
        val time: String,
        val total: String
    )

    private val sampleBills = listOf(
        Bill("3", "SOMNATH", "17-07-2026", "13:18", "962.50"),
        Bill("2", "RAKESH KUMAR", "17-07-2026", "12:47", "540.00"),
        Bill("1", "PRIYA SHARMA", "16-07-2026", "20:05", "1,285.75")
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_bill_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = view.findViewById<RecyclerView>(R.id.rvBills)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmpty)

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = BillAdapter(sampleBills) { openBill(it) }
        tvEmpty.visibility = if (sampleBills.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openBill(bill: Bill) {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_container,
                BillFragment.newInstance(bill.billNo, bill.name, bill.date, bill.time, bill.total)
            )
            .addToBackStack(null)
            .commit()
    }

    private inner class BillAdapter(
        private val items: List<Bill>,
        private val onView: (Bill) -> Unit
    ) : RecyclerView.Adapter<BillAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvBillNo: TextView = view.findViewById(R.id.tvRowBillNo)
            val tvAmount: TextView = view.findViewById(R.id.tvRowAmount)
            val tvName: TextView = view.findViewById(R.id.tvRowName)
            val tvDateTime: TextView = view.findViewById(R.id.tvRowDateTime)
            val btnView: MaterialButton = view.findViewById(R.id.btnViewBill)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_bill_row, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val bill = items[position]
            holder.tvBillNo.text = "Bill No: ${bill.billNo}"
            holder.tvAmount.text = "₹ ${bill.total}"
            holder.tvName.text = bill.name
            holder.tvDateTime.text = "${bill.date}  ${bill.time}"

            val accent = ThemeManager.getThemeColor(holder.itemView.context)
            holder.btnView.setTextColor(accent)
            holder.btnView.strokeColor = ColorStateList.valueOf(accent)
            holder.btnView.iconTint = ColorStateList.valueOf(accent)
            holder.btnView.setOnClickListener { onView(bill) }
        }

        override fun getItemCount() = items.size
    }
}
