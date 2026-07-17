package com.example.synergic_pos_offline.fragments

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.CategoryDao
import com.example.synergic_pos_offline.utils.ThemeManager

class CategorywiseSearchFragment : Fragment(), TitledScreen {

    override val screenTitle = "Category Search"

    private lateinit var rvCategories: RecyclerView
    private var categories = mutableListOf<CategoryDao.Category>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_categorywise_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            rvCategories = view.findViewById(R.id.rvCategories) ?: return

            val columns = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 3 else 2
            rvCategories.layoutManager = GridLayoutManager(requireContext(), columns)

            loadCategories()
            rvCategories.adapter = CategoriesAdapter(categories)

            ThemeManager.applyTheme(view)
        } catch (e: Exception) {
            android.util.Log.e("CategorySearch", "Error in onViewCreated", e)
        }
    }

    private fun loadCategories() {
        categories.clear()
        val dao = CategoryDao(requireContext())
        categories.addAll(dao.getAll())
    }

    private inner class CategoriesAdapter(
        private val items: List<CategoryDao.Category>
    ) : RecyclerView.Adapter<CategoriesAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivImage: ImageView? = view.findViewById(R.id.ivCategoryImage)
            val tvName: TextView? = view.findViewById(R.id.tvCategoryName)
            val tvCode: TextView? = view.findViewById(R.id.tvCategoryCode)

            init {
                view.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val category = items[pos]
                        navigateToCategory(category)
                    }
                }
            }

            fun bind(category: CategoryDao.Category) {
                tvName?.text = category.name
                tvCode?.text = category.code

                if (category.image != null) {
                    try {
                        val bitmap = BitmapFactory.decodeByteArray(category.image, 0, category.image.size)
                        ivImage?.setImageBitmap(bitmap)
                    } catch (_: Exception) {
                        ivImage?.setBackgroundColor(getRandomColor())
                    }
                } else {
                    ivImage?.setBackgroundColor(getRandomColor())
                }
            }

            private fun getRandomColor(): Int {
                val colors = listOf(
                    0xFF6C63FF.toInt(),
                    0xFFFF6B6B.toInt(),
                    0xFF4ECDC4.toInt(),
                    0xFFFFD93D.toInt(),
                    0xFF6BCB77.toInt(),
                    0xFF4D96FF.toInt(),
                    0xFFFF8FAB.toInt(),
                    0xFF9E44FF.toInt()
                )
                return colors[adapterPosition % colors.size]
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_category_tile, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size
    }

    private fun navigateToCategory(category: CategoryDao.Category) {
        val fragment = CategoryProductsFragment.newInstance(category.id, category.name)
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }
}
