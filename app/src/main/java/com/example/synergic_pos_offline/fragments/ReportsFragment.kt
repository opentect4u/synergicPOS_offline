package com.example.synergic_pos_offline.fragments

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

        val reportItems = titles.mapIndexed { index, title ->
            val (bg, icon) = palette[index % palette.size]
            ReportItem(title, android.R.drawable.ic_menu_view, bg, icon)
        }

        rvReports.adapter = ReportsAdapter(reportItems) { item ->
            Toast.makeText(requireContext(), "Opening ${item.title}...", Toast.LENGTH_SHORT).show()
        }
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
