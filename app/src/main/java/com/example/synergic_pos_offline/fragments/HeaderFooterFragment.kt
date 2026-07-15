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

class HeaderFooterFragment : Fragment() {

    private lateinit var rvHeaderFooter: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_header_footer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvHeaderFooter = view.findViewById(R.id.rvHeaderFooter)

        val columns = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 4 else 2
        rvHeaderFooter.layoutManager = GridLayoutManager(requireContext(), columns)

        val items = listOf(
            HeaderFooterItem("Bill Header & Footer", android.R.drawable.ic_menu_edit, R.color.menu_master, R.color.menu_master_icon),
            HeaderFooterItem("KOT Header & Footer", android.R.drawable.ic_menu_agenda, R.color.menu_sale, R.color.menu_sale_icon),
            HeaderFooterItem("Bill Header Footer Logo", android.R.drawable.ic_menu_gallery, R.color.menu_report, R.color.menu_report_icon),
            HeaderFooterItem("KOT Header Footer Logo", android.R.drawable.ic_menu_gallery, R.color.menu_inventory, R.color.menu_inventory_icon)
        )

        rvHeaderFooter.adapter = HeaderFooterAdapter(items) { item ->
            when (item.title) {
                "Bill Header & Footer" -> requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, BillHeaderFooterFragment())
                    .addToBackStack(null)
                    .commit()
                else -> Toast.makeText(requireContext(), "Opening ${item.title}...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    data class HeaderFooterItem(
        val title: String,
        val iconRes: Int,
        val bgColorRes: Int,
        val iconColorRes: Int
    )

    private inner class HeaderFooterAdapter(
        private val items: List<HeaderFooterItem>,
        private val onItemClick: (HeaderFooterItem) -> Unit
    ) : RecyclerView.Adapter<HeaderFooterAdapter.ViewHolder>() {

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
