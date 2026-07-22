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
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Item-wise list of bills, loaded from [DatabaseHelper.Tables.TD_BILLS]. Each row
 * has a "View" button that opens the receipt preview ([BillFragment]) for that bill.
 */
class BillListFragment : Fragment(), TitledScreen {

    override val screenTitle = "Bills"

    /** One bill summary row. */
    private data class Bill(
        val billNo: String,
        val name: String,
        val date: String,
        val time: String,
        val total: String,
        val receiptNo: Long
    )

    private val bills = mutableListOf<Bill>()

    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_bill_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rv = view.findViewById(R.id.rvBills)
        tvEmpty = view.findViewById(R.id.tvEmpty)

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = BillAdapter(bills) { openBill(it) }

        loadBills()
    }

    override fun onResume() {
        super.onResume()
        loadBills()
    }

    private fun loadBills() {
        bills.clear()
        try {
            val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
            val storeId = storeId()

            // Bills are saved with the registration store_id (see BillDao). Filter by
            // it when known; otherwise show all bills so nothing is silently hidden.
            val sql = buildString {
                append("SELECT b.bill_number, b.bill_date_time, b.bill_date, b.net_amount, b.receipt_no, b.customer_id ")
                append("FROM ${DatabaseHelper.Tables.TD_BILLS} b ")
                if (storeId != null) append("WHERE b.store_id = ? ")
                append("ORDER BY b.receipt_no DESC LIMIT 50")
            }
            val args = if (storeId != null) arrayOf(storeId.toString()) else null

            db.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    val billNumber = c.getString(0) ?: c.getInt(4).toString()
                    val dateTime = c.getString(1) ?: c.getString(2) ?: ""
                    val amount = c.getDouble(3)
                    val receiptNo = c.getLong(4)
                    val customerId = if (c.isNull(5)) null else c.getLong(5)

                    val (date, time) = splitDateTime(dateTime)
                    val name = customerName(db, customerId, receiptNo)

                    bills.add(
                        Bill(
                            billNo = billNumber,
                            name = name,
                            date = date,
                            time = time,
                            total = String.format(Locale.US, "%,.2f", amount),
                            receiptNo = receiptNo
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BillList", "Error loading bills", e)
        }

        rv.adapter?.notifyDataSetChanged()
        tvEmpty.visibility = if (bills.isEmpty()) View.VISIBLE else View.GONE
        rv.visibility = if (bills.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Resolves the display name: customer master first, then the payment record. */
    private fun customerName(
        db: android.database.sqlite.SQLiteDatabase,
        customerId: Long?,
        receiptNo: Long
    ): String {
        if (customerId != null) {
            db.query(
                DatabaseHelper.Tables.MD_CUSTOMERS, arrayOf("customer_name"),
                "id=?", arrayOf(customerId.toString()), null, null, null, "1"
            ).use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        }
        db.query(
            DatabaseHelper.Tables.TD_PAYMENTS, arrayOf("cust_name"),
            "bill_id=?", arrayOf(receiptNo.toString()), null, null, "id ASC", "1"
        ).use { c ->
            if (c.moveToFirst()) {
                val n = c.getString(0)
                if (!n.isNullOrBlank()) return n
            }
        }
        return "Guest"
    }

    /** Splits "yyyy-MM-dd HH:mm:ss" into ("dd-MM-yyyy", "HH:mm"). */
    private fun splitDateTime(value: String): Pair<String, String> {
        return try {
            val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(value)
            if (parsed != null) {
                SimpleDateFormat("dd-MM-yyyy", Locale.US).format(parsed) to
                    SimpleDateFormat("HH:mm", Locale.US).format(parsed)
            } else value to ""
        } catch (_: Exception) {
            value to ""
        }
    }

    /** Store id as saved on bills — read from md_registration (same as BillDao). */
    private fun storeId(): Long? {
        val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
        db.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    private fun openBill(bill: Bill) {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_container,
                BillFragment.newInstance(bill.billNo, bill.name, bill.date, bill.time, bill.total, bill.receiptNo)
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
