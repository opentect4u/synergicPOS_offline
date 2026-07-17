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
import com.example.synergic_pos_offline.R
import com.google.android.material.card.MaterialCardView

class MenuFragment : Fragment() {

    private lateinit var rvMenu: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_menu, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvMenu = view.findViewById(R.id.rvMenu)

        val columns = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 4 else 2
        rvMenu.layoutManager = GridLayoutManager(requireContext(), columns)

        val menuItems = listOf(
            MenuItem("Master", android.R.drawable.ic_menu_edit, R.color.menu_master, R.color.menu_master_icon),
            MenuItem("Settings", android.R.drawable.ic_menu_preferences, R.color.menu_settings, R.color.menu_settings_icon),
            MenuItem("Stock & Inventory", android.R.drawable.ic_menu_agenda, R.color.menu_inventory, R.color.menu_inventory_icon),
            MenuItem("Sale", android.R.drawable.ic_menu_add, R.color.menu_sale, R.color.menu_sale_icon),
            MenuItem("Sale Return", android.R.drawable.ic_menu_revert, R.color.menu_delete, R.color.menu_delete_icon),
            MenuItem("Advance Payment", android.R.drawable.ic_menu_today, R.color.menu_sale, R.color.menu_sale_icon),
            MenuItem("Duplicate Bill", android.R.drawable.ic_menu_today, R.color.menu_master, R.color.menu_master_icon),
            MenuItem("Delete Bill", android.R.drawable.ic_menu_delete, R.color.menu_delete, R.color.menu_delete_icon),
            MenuItem("Reports", android.R.drawable.ic_menu_view, R.color.menu_report, R.color.menu_report_icon)
        )

        rvMenu.adapter = MenuAdapter(menuItems) { item ->
            handleAction(item.title)
        }
    }

    /** Navigates to sub-menu pages, or shows a placeholder for leaf actions. */
    private fun handleAction(title: String) {
        when (title) {
            "Master" -> openFragment(MasterFragment())
            "Settings" -> openFragment(SettingsFragment())
            "Stock & Inventory" -> openFragment(InventoryFragment())
            "Reports" -> openFragment(ReportsFragment())
            "Sale" -> openFragment(SalesFragment())
            else -> Toast.makeText(requireContext(), "Opening $title...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFragment(fragment: Fragment) {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    data class MenuItem(
        val title: String,
        val iconRes: Int,
        val bgColorRes: Int,
        val iconColorRes: Int
    )

    private inner class MenuAdapter(
        private val items: List<MenuItem>,
        private val onItemClick: (MenuItem) -> Unit
    ) : RecyclerView.Adapter<MenuAdapter.ViewHolder>() {

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