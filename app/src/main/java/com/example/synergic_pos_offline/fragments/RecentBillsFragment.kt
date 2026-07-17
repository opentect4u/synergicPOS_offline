package com.example.synergic_pos_offline.fragments

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
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.ThemeManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecentBillsFragment : Fragment(), TitledScreen {

    override val screenTitle = "Recent Bills"

    private lateinit var rvBills: RecyclerView
    private lateinit var tvNoBills: TextView
    private var bills = mutableListOf<BillSummary>()

    data class BillSummary(
        val billNumber: String,
        val date: String,
        val itemCount: Int,
        val amount: Double
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_recent_bills, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            rvBills = view.findViewById(R.id.rvRecentBills) ?: return
            tvNoBills = view.findViewById(R.id.tvNoBills) ?: return

            rvBills.layoutManager = LinearLayoutManager(requireContext())
            rvBills.adapter = BillsAdapter(bills)

            loadRecentBills()

            ThemeManager.applyTheme(view)
        } catch (e: Exception) {
            android.util.Log.e("RecentBills", "Error in onViewCreated", e)
        }
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.d("RecentBills", "onResume called - reloading bills")
        loadRecentBills()
    }

    private fun loadRecentBills() {
        try {
            android.util.Log.d("RecentBills", "=== LOAD RECENT BILLS STARTED ===")
            bills.clear()
            val storeId = storeId()
            android.util.Log.d("RecentBills", "Store ID: $storeId")

            val db = DatabaseHelper.getInstance(requireContext()).readableDatabase

            // First, let's check if there are any bills at all
            val countSql = """
                SELECT COUNT(*) FROM ${DatabaseHelper.Tables.TD_BILLS}
            """.trimIndent()
            db.rawQuery(countSql, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    val totalBills = cursor.getInt(0)
                    android.util.Log.d("RecentBills", "Total bills in database: $totalBills")
                }
            }

            // Check bills for this store
            val storeCountSql = """
                SELECT COUNT(*) FROM ${DatabaseHelper.Tables.TD_BILLS} WHERE store_id = ?
            """.trimIndent()
            db.rawQuery(storeCountSql, arrayOf(storeId.toString())).use { cursor ->
                if (cursor.moveToFirst()) {
                    val storeBills = cursor.getInt(0)
                    android.util.Log.d("RecentBills", "Bills for store $storeId: $storeBills")
                }
            }

            val sql = """
                SELECT b.bill_number, b.bill_date, b.net_amount, b.receipt_no
                FROM ${DatabaseHelper.Tables.TD_BILLS} b
                WHERE b.store_id = ?
                ORDER BY b.bill_date DESC
                LIMIT 20
            """.trimIndent()

            android.util.Log.d("RecentBills", "Executing query with store_id: $storeId")

            db.rawQuery(sql, arrayOf(storeId.toString())).use { cursor ->
                android.util.Log.d("RecentBills", "Query returned ${cursor.count} rows")
                while (cursor.moveToNext()) {
                    val billNumber = cursor.getString(0)
                    val billDate = cursor.getString(1)
                    val amount = cursor.getDouble(2)
                    val receiptNo = cursor.getInt(3)

                    // Count items separately
                    val itemCountSql = """
                        SELECT COUNT(*) FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} WHERE bill_id = ?
                    """.trimIndent()
                    var itemCount = 0
                    db.rawQuery(itemCountSql, arrayOf(receiptNo.toString())).use { itemCursor ->
                        if (itemCursor.moveToFirst()) {
                            itemCount = itemCursor.getInt(0)
                        }
                    }

                    android.util.Log.d("RecentBills", "Bill: $billNumber, Date: $billDate, Items: $itemCount, Amount: $amount, ReceiptNo: $receiptNo")

                    bills.add(
                        BillSummary(
                            billNumber = billNumber,
                            date = formatDate(billDate),
                            itemCount = itemCount,
                            amount = amount
                        )
                    )
                }
            }

            android.util.Log.d("RecentBills", "Total bills loaded: ${bills.size}")

            if (bills.isEmpty()) {
                android.util.Log.d("RecentBills", "No bills found - showing empty state")
                rvBills.visibility = View.GONE
                tvNoBills.visibility = View.VISIBLE
            } else {
                android.util.Log.d("RecentBills", "Showing ${bills.size} bills")
                rvBills.visibility = View.VISIBLE
                tvNoBills.visibility = View.GONE
                rvBills.adapter?.notifyDataSetChanged()
            }
        } catch (e: Exception) {
            android.util.Log.e("RecentBills", "Error loading bills", e)
            e.printStackTrace()
            rvBills.visibility = View.GONE
            tvNoBills.visibility = View.VISIBLE
        }
    }

    private fun formatDate(dateString: String?): String {
        return try {
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val date = formatter.parse(dateString ?: "")
            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(date ?: Date())
        } catch (_: Exception) {
            dateString ?: ""
        }
    }

    private fun storeId(): Int = SessionManager.currentUser?.storeId ?: 0

    private inner class BillsAdapter(
        private val items: List<BillSummary>
    ) : RecyclerView.Adapter<BillsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvNumber: TextView? = view.findViewById(R.id.tvBillNumber)
            val tvDate: TextView? = view.findViewById(R.id.tvBillDate)
            val tvCount: TextView? = view.findViewById(R.id.tvItemCount)
            val tvAmount: TextView? = view.findViewById(R.id.tvAmount)

            fun bind(bill: BillSummary) {
                tvNumber?.text = bill.billNumber
                tvDate?.text = bill.date
                tvCount?.text = bill.itemCount.toString()
                tvAmount?.text = String.format("₹%.2f", bill.amount)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bill_summary, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size
    }
}
