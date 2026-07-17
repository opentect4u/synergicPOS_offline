package com.example.synergic_pos_offline.fragments

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.ThemeManager

class CategoryProductsFragment : Fragment(), TitledScreen {

    override val screenTitle: String
        get() = "Products in ${categoryName ?: "Category"}"

    private lateinit var rvProducts: RecyclerView
    private lateinit var tvNoProducts: TextView
    private var categoryId: Long = 0
    private var categoryName: String = ""
    private var products = mutableListOf<Product>()

    data class Product(
        val id: Int,
        val serialNumber: Int,
        val name: String,
        val code: String,
        val barcode: String,
        val image: ByteArray?
    ) {
        override fun equals(other: Any?): Boolean = other is Product && id == other.id
        override fun hashCode(): Int = id.hashCode()
    }

    companion object {
        fun newInstance(categoryId: Long, categoryName: String): CategoryProductsFragment {
            return CategoryProductsFragment().apply {
                arguments = Bundle().apply {
                    putLong("category_id", categoryId)
                    putString("category_name", categoryName)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            categoryId = it.getLong("category_id", 0)
            categoryName = it.getString("category_name", "")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_category_products, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            rvProducts = view.findViewById(R.id.rvCategoryProducts) ?: return
            tvNoProducts = view.findViewById(R.id.tvNoProducts) ?: return

            rvProducts.layoutManager = LinearLayoutManager(requireContext())
            rvProducts.adapter = ProductsAdapter(products)

            loadCategoryProducts()

            ThemeManager.applyTheme(view)
        } catch (e: Exception) {
            android.util.Log.e("CategoryProducts", "Error in onViewCreated", e)
        }
    }

    private fun loadCategoryProducts() {
        try {
            products.clear()
            val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
            val sql = """
                SELECT p.id, p.product_name, p.hsn_code, p.bar_code, p.product_image
                FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p
                WHERE p.store_id = ? AND p.category_id = ?
                ORDER BY p.product_name COLLATE NOCASE
            """.trimIndent()

            db.rawQuery(sql, arrayOf(storeId().toString(), categoryId.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    val productId = cursor.getInt(0)
                    products.add(
                        Product(
                            id = productId,
                            serialNumber = productId,
                            name = cursor.getString(1).orEmpty(),
                            code = cursor.getString(2).orEmpty(),
                            barcode = cursor.getString(3).orEmpty(),
                            image = if (cursor.isNull(4)) null else cursor.getBlob(4)
                        )
                    )
                }
            }

            if (products.isEmpty()) {
                rvProducts.visibility = View.GONE
                tvNoProducts.visibility = View.VISIBLE
            } else {
                rvProducts.visibility = View.VISIBLE
                tvNoProducts.visibility = View.GONE
                rvProducts.adapter?.notifyDataSetChanged()
            }
        } catch (e: Exception) {
            android.util.Log.e("CategoryProducts", "Error loading products", e)
            rvProducts.visibility = View.GONE
            tvNoProducts.visibility = View.VISIBLE
        }
    }

    private fun storeId(): Int = SessionManager.currentUser?.storeId ?: 0

    private inner class ProductsAdapter(
        private val items: List<Product>
    ) : RecyclerView.Adapter<ProductsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivImage: ImageView? = view.findViewById(R.id.ivProductImage)
            val tvSerialNo: TextView? = view.findViewById(R.id.tvProductSerialNo)
            val tvName: TextView? = view.findViewById(R.id.tvProductName)
            val tvCode: TextView? = view.findViewById(R.id.tvProductCode)
            val tvBarcode: TextView? = view.findViewById(R.id.tvProductBarcode)

            fun bind(product: Product) {
                tvSerialNo?.text = "#${product.serialNumber}"
                tvName?.text = product.name
                tvCode?.text = "Code: ${product.code}"

                if (product.barcode.isNotEmpty()) {
                    tvBarcode?.text = "Barcode: ${product.barcode}"
                    tvBarcode?.visibility = View.VISIBLE
                } else {
                    tvBarcode?.visibility = View.GONE
                }

                if (product.image != null) {
                    try {
                        val bitmap = BitmapFactory.decodeByteArray(product.image, 0, product.image.size)
                        ivImage?.setImageBitmap(bitmap)
                    } catch (_: Exception) {
                        ivImage?.setImageResource(R.drawable.ic_placeholder_image)
                    }
                } else {
                    ivImage?.setImageResource(R.drawable.ic_placeholder_image)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_product_search_result, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size
    }
}
