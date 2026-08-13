package com.example.synergic_pos_offline.fragments

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.MainActivity
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.utils.SettingsCache
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.card.MaterialCardView

class ReportsFragment : Fragment() {

    private lateinit var rvReports: RecyclerView

    // Color palette (bg, icon) cycled across the report cards.
    private val palette = listOf(
        R.color.menu_master to R.color.menu_master_icon,
        R.color.menu_sale to R.color.menu_sale_icon,
        R.color.menu_report to R.color.menu_report_icon,
        R.color.menu_inventory to R.color.menu_inventory_icon,
        R.color.menu_settings to R.color.menu_settings_icon,
        R.color.menu_delete to R.color.menu_delete_icon
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_reports, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvReports = view.findViewById(R.id.rvReports)

        val columns = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 4 else 2
        rvReports.layoutManager = GridLayoutManager(requireContext(), columns)

        val titles = listOf(
            "Bill Wise Report",
            "Item Wise Report",
            "Operator Wise Report",
            "Void Bill Report",
            "Tax Report",
            "Duplicate Bill Report",
            "Stock Report",
            "Item Bill Report",
            "Returned Bill Report",
            "UDF-Wise Report",
            "Payment-Wise Report",
            "Unsold Product Report",
            "Opr Bill Report",
            "Category/Dept Wise Bill Report",
            "Payment & Receipt",
            "Customer Payment",
            "Customer Ledger",
            "Profit & Loss Report",
            "KOT Cancel Report",
            "Day-Wise Report",
            "Month Wise Report",
            "Year Wise Report",
            "UDF Wise Item Report",
            "Customer Item Wise RPT",
            "Time Wise Item Report"
        )

        // Stock Report only exists where there is a count to report on, and the KOT
        // and UDF reports only in Restaurant - see [isVisible], which the sidebar
        // asks the same question of.
        val shown = titles.filter { isVisible(requireContext(), it) }

        val reportItems = shown.mapIndexed { index, title ->
            val (bg, icon) = palette[index % palette.size]
            ReportItem(title, android.R.drawable.ic_menu_view, bg, icon)
        }

        rvReports.adapter = ReportsAdapter(reportItems) { item ->
            when (item.title) {
                "Bill Wise Report" -> openFragment(BillWiseReportFragment())
                "Item Wise Report" -> openFragment(ItemWiseReportFragment())
                "Operator Wise Report" -> openFragment(OperatorWiseReportFragment())
                "Tax Report" -> openFragment(TaxReportFragment())
                "Payment-Wise Report" -> openFragment(PaymentWiseReportFragment())
                "Returned Bill Report" -> openFragment(ReturnedBillReportFragment())
                "Unsold Product Report" -> openFragment(UnsoldProductReportFragment())
                "Category/Dept Wise Bill Report" -> openFragment(CategoryWiseReportFragment())
                "Opr Bill Report" -> openFragment(OperatorBilledReportFragment())
                "Item Bill Report" -> openFragment(ItemBillReportFragment())
                "Time Wise Item Report" -> openFragment(TimeWiseItemReportFragment())
                "Duplicate Bill Report" -> openFragment(DuplicateReportFragment())
                "Void Bill Report" -> openFragment(VoidBillReportFragment())
                "Profit & Loss Report" -> openFragment(ProfitLossReportFragment())
                "Day-Wise Report" -> openFragment(DayWiseReportFragment())
                "Month Wise Report" -> openFragment(MonthWiseReportFragment())
                "Year Wise Report" -> openFragment(YearWiseReportFragment())
                STOCK_REPORT -> openFragment(StockReportFragment())
                "Customer Payment" -> openFragment(CustomerPaymentReportFragment())
                "Customer Ledger" -> openFragment(CustomerLedgerFragment())
                else -> Toast.makeText(requireContext(), "Opening ${item.title}...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        /** Named once: the tile is filtered by this and opened by it. */
        private const val STOCK_REPORT = "Stock Report"

        /**
         * Reports that only exist in Restaurant mode.
         *
         * A KOT is a kitchen ticket and a UDF is a field of a restaurant order;
         * neither is ever raised on a grocery till. The tiles would open screens that
         * could only be empty, and an empty report reads as a broken one.
         */
        private val RESTAURANT_ONLY = setOf(
            "KOT Cancel Report",
            "UDF-Wise Report",
            "UDF Wise Item Report"
        )

        /**
         * Whether [title] belongs on this till at all.
         *
         * The one rule, and both places that list reports ask it: this grid and the
         * sidebar's Reports branch. They are two descriptions of one menu, and a
         * report hidden from one of them only is worse than one never hidden - it is
         * still reachable, just no longer where anyone looks for it.
         */
        fun isVisible(context: Context, title: String): Boolean = when {
            title == STOCK_REPORT -> GeneralSettingsDao.isStockEnabled(context)
            title in RESTAURANT_ONLY -> SettingsCache.value(context, "G", "Mode") == "R"
            else -> true
        }
    }

    private fun openFragment(fragment: Fragment) {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    data class ReportItem(
        val title: String,
        val iconRes: Int,
        val bgColorRes: Int,
        val iconColorRes: Int
    )

    private inner class ReportsAdapter(
        private val items: List<ReportItem>,
        private val onItemClick: (ReportItem) -> Unit
    ) : RecyclerView.Adapter<ReportsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivMenuIcon)
            val tvTitle: TextView = view.findViewById(R.id.tvMenuTitle)
            val cardIconContainer: MaterialCardView = view.findViewById(R.id.cardIconContainer)

            init {
                view.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onItemClick(items[pos])
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_menu_card, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            holder.ivIcon.setImageResource(item.iconRes)

            val iconColor = ContextCompat.getColor(requireContext(), item.iconColorRes)
            val bgColor = ContextCompat.getColor(requireContext(), item.bgColorRes)

            holder.ivIcon.imageTintList = ColorStateList.valueOf(iconColor)
            holder.cardIconContainer.setCardBackgroundColor(bgColor)
        }

        override fun getItemCount() = items.size
    }
}
