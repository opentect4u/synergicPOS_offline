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

class MasterFragment : Fragment() {

    private lateinit var rvMaster: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_master, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvMaster = view.findViewById(R.id.rvMaster)

        val columns = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 4 else 2
        rvMaster.layoutManager = GridLayoutManager(requireContext(), columns)

        val masterItems = listOf(
            MasterItem("Header & Footer", android.R.drawable.ic_menu_crop, R.color.menu_master, R.color.menu_master_icon),
            MasterItem("Date & Time", android.R.drawable.ic_menu_recent_history, R.color.menu_report, R.color.menu_report_icon),
            MasterItem("User Management", android.R.drawable.ic_menu_manage, R.color.menu_sale, R.color.menu_sale_icon),
            MasterItem("Database Settings", android.R.drawable.ic_menu_save, R.color.menu_settings, R.color.menu_settings_icon)
        )

        rvMaster.adapter = MasterAdapter(masterItems) { item ->
            handleAction(item.title)
        }

        ThemeManager.applyTheme(view)
    }

    private fun handleAction(title: String) {
        when (title) {
            "Header & Footer" -> openFragment(HeaderFooterFragment())
            "User Management" -> openFragment(UserManagementFragment())
            "Database Settings" -> openFragment(DatabaseSettingsFragment())
            else -> Toast.makeText(requireContext(), "Opening $title...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFragment(fragment: Fragment) {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    data class MasterItem(
        val title: String,
        val iconRes: Int,
        val bgColorRes: Int,
        val iconColorRes: Int
    )

    private inner class MasterAdapter(
        private val items: List<MasterItem>,
        private val onItemClick: (MasterItem) -> Unit
    ) : RecyclerView.Adapter<MasterAdapter.ViewHolder>() {

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
