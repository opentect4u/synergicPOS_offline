package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.card.MaterialCardView

class SalesFragment : Fragment(), TitledScreen {

    override val screenTitle = "Sales"

    private lateinit var rvTiles: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_sales, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvTiles = view.findViewById(R.id.rvSalesTiles)

        val columns = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 4 else 2
        rvTiles.layoutManager = GridLayoutManager(requireContext(), columns)

        val tiles = listOf(
            SalesTile("Recent Bills", android.R.drawable.ic_menu_view, "View past bills"),
            SalesTile("Categorywise Search", android.R.drawable.ic_menu_agenda, "Browse by category"),
            SalesTile("Itemwise Search", android.R.drawable.ic_menu_search, "Search products")
        )

        rvTiles.adapter = TilesAdapter(tiles) { tile ->
            when (tile.title) {
                "Recent Bills" -> navigateTo(RecentBillsFragment())
                "Categorywise Search" -> navigateTo(CategorywiseSearchFragment())
                "Itemwise Search" -> navigateTo(ItemwiseSearchFragment())
            }
        }

        ThemeManager.applyTheme(view)
    }

    private fun navigateTo(fragment: Fragment) {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    data class SalesTile(
        val title: String,
        val iconRes: Int,
        val subtitle: String
    )

    private inner class TilesAdapter(
        private val tiles: List<SalesTile>,
        private val onTileClick: (SalesTile) -> Unit
    ) : RecyclerView.Adapter<TilesAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivTileIcon)
            val tvTitle: TextView = view.findViewById(R.id.tvTileTitle)
            val tvSubtitle: TextView = view.findViewById(R.id.tvTileSubtitle)
            val card: MaterialCardView = view as MaterialCardView

            init {
                view.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onTileClick(tiles[pos])
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sales_tile, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val tile = tiles[position]
            holder.tvTitle.text = tile.title
            holder.tvSubtitle.text = tile.subtitle
            holder.ivIcon.setImageResource(tile.iconRes)

            val themeColor = ThemeManager.getThemeColor(requireContext())
            holder.ivIcon.imageTintList = ColorStateList.valueOf(themeColor)
        }

        override fun getItemCount() = tiles.size
    }
}
