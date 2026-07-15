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

class InventoryFragment : Fragment() {

    private lateinit var rvInventory: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_inventory, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvInventory = view.findViewById(R.id.rvInventory)

        val columns = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 4 else 2
        rvInventory.layoutManager = GridLayoutManager(requireContext(), columns)

        val inventoryItems = listOf(
            InventoryItem("Purchase Item", android.R.drawable.ic_menu_add, R.color.menu_sale, R.color.menu_sale_icon),
            InventoryItem("Purchase Return", android.R.drawable.ic_menu_revert, R.color.menu_delete, R.color.menu_delete_icon),
            InventoryItem("Generate Barcode", android.R.drawable.ic_menu_edit, R.color.menu_master, R.color.menu_master_icon),
            InventoryItem("Print Barcode", android.R.drawable.ic_menu_set_as, R.color.menu_report, R.color.menu_report_icon),
            InventoryItem("Write Off Damage Item", android.R.drawable.ic_menu_close_clear_cancel, R.color.menu_inventory, R.color.menu_inventory_icon),
            InventoryItem("Reset Stock", android.R.drawable.ic_menu_rotate, R.color.menu_settings, R.color.menu_settings_icon)
        )

        rvInventory.adapter = InventoryAdapter(inventoryItems) { item ->
            Toast.makeText(requireContext(), "Opening ${item.title}...", Toast.LENGTH_SHORT).show()
        }
    }

    data class InventoryItem(
        val title: String,
        val iconRes: Int,
        val bgColorRes: Int,
        val iconColorRes: Int
    )

    private inner class InventoryAdapter(
        private val items: List<InventoryItem>,
        private val onItemClick: (InventoryItem) -> Unit
    ) : RecyclerView.Adapter<InventoryAdapter.ViewHolder>() {

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
