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

class DatabaseSettingsFragment : Fragment() {

    private lateinit var rvDatabase: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_database_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvDatabase = view.findViewById(R.id.rvDatabase)

        val columns = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 4 else 2
        rvDatabase.layoutManager = GridLayoutManager(requireContext(), columns)

        val items = listOf(
            DatabaseItem("Category/Department", android.R.drawable.ic_menu_sort_by_size, R.color.menu_master, R.color.menu_master_icon),
            DatabaseItem("Products", android.R.drawable.ic_menu_agenda, R.color.menu_sale, R.color.menu_sale_icon),
            DatabaseItem("Customers", android.R.drawable.ic_menu_myplaces, R.color.menu_report, R.color.menu_report_icon),
            DatabaseItem("Description/Ledger", android.R.drawable.ic_menu_info_details, R.color.menu_inventory, R.color.menu_inventory_icon),
            DatabaseItem("Units", android.R.drawable.ic_menu_crop, R.color.menu_settings, R.color.menu_settings_icon),
            DatabaseItem("Waiter", android.R.drawable.ic_menu_manage, R.color.menu_delete, R.color.menu_delete_icon)
        )

        rvDatabase.adapter = DatabaseAdapter(items) { item ->
            when (item.title) {
                "Category/Department" -> openFragment(CategoryDepartmentFragment())
                "Units" -> openFragment(UnitFragment())
                "Waiter" -> openFragment(WaiterFragment())
                "Customers" -> openFragment(CustomerFragment())
                "Description/Ledger" -> openFragment(DescriptionLedgerFragment())
                else -> Toast.makeText(requireContext(), "Opening ${item.title}...", Toast.LENGTH_SHORT).show()
            }
        }

        ThemeManager.applyTheme(view)
    }

    private fun openFragment(fragment: Fragment) {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    data class DatabaseItem(
        val title: String,
        val iconRes: Int,
        val bgColorRes: Int,
        val iconColorRes: Int
    )

    private inner class DatabaseAdapter(
        private val items: List<DatabaseItem>,
        private val onItemClick: (DatabaseItem) -> Unit
    ) : RecyclerView.Adapter<DatabaseAdapter.ViewHolder>() {

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
