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

class SettingsFragment : Fragment() {

    private lateinit var rvSettings: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvSettings = view.findViewById(R.id.rvSettings)

        val columns = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 4 else 2
        rvSettings.layoutManager = GridLayoutManager(requireContext(), columns)

        val settingsItems = listOf(
            SettingsItem("General Settings", android.R.drawable.ic_menu_preferences, R.color.menu_settings, R.color.menu_settings_icon),
            SettingsItem("Bill Settings", android.R.drawable.ic_menu_edit, R.color.menu_master, R.color.menu_master_icon),
            SettingsItem("Tax Settings", android.R.drawable.ic_menu_sort_by_size, R.color.menu_report, R.color.menu_report_icon),
            SettingsItem("Inventory & Stock Settings", android.R.drawable.ic_menu_agenda, R.color.menu_inventory, R.color.menu_inventory_icon),
            SettingsItem("Printer Settings", R.drawable.ic_print, R.color.menu_report, R.color.menu_report_icon),
            SettingsItem("App Settings", android.R.drawable.ic_menu_manage, R.color.menu_sale, R.color.menu_sale_icon)
        )

        rvSettings.adapter = SettingsAdapter(settingsItems) { item ->
            when (item.title) {
                "Printer Settings" -> openFragment(PrinterSettingsFragment())
                else -> Toast.makeText(requireContext(), "Opening ${item.title}...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openFragment(fragment: Fragment) {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    data class SettingsItem(
        val title: String,
        val iconRes: Int,
        val bgColorRes: Int,
        val iconColorRes: Int
    )

    private inner class SettingsAdapter(
        private val items: List<SettingsItem>,
        private val onItemClick: (SettingsItem) -> Unit
    ) : RecyclerView.Adapter<SettingsAdapter.ViewHolder>() {

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
