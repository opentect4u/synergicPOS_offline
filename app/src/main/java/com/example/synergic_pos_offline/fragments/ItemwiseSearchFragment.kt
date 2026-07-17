package com.example.synergic_pos_offline.fragments

import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.CartManager
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

class ItemwiseSearchFragment : Fragment(), TitledScreen {

    override val screenTitle = "Item Search"

    private lateinit var etSearch: TextInputEditText
    private lateinit var rvResults: RecyclerView
    private lateinit var tvNoResults: TextView
    private var displayedProducts = mutableListOf<Product>()
    private lateinit var adapter: ProductAdapter
    private var lastSearchQuery = ""

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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_itemwise_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            etSearch = view.findViewById(R.id.etProductSearch) ?: return
            rvResults = view.findViewById(R.id.rvSearchResults) ?: return
            tvNoResults = view.findViewById(R.id.tvNoResults) ?: return

            rvResults.layoutManager = LinearLayoutManager(requireContext())
            adapter = ProductAdapter(displayedProducts)
            rvResults.adapter = adapter

            etSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchProducts(s.toString())
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            // Setup floating cart
            setupFloatingCart(view)

            ThemeManager.applyTheme(view)
        } catch (e: Exception) {
            android.util.Log.e("ItemwiseSearch", "Error in onViewCreated", e)
        }
    }

    private fun setupFloatingCart(view: View) {
        val cvFloatingCart = view.findViewById<MaterialCardView>(R.id.floatingCart)
        val tvCartItemCount = view.findViewById<TextView>(R.id.tvCartItemCount)
        val tvCartTotal = view.findViewById<TextView>(R.id.tvCartTotal)
        val btnViewCartFloat = view.findViewById<ImageView>(R.id.btnViewCart)
        val btnDeleteCart = view.findViewById<ImageView>(R.id.btnDeleteCart)

        // Set card background color to current theme color
        val themeColor = ThemeManager.getThemeColor(requireContext())
        cvFloatingCart.setCardBackgroundColor(themeColor)

        val cartListener = {
            val items = CartManager.getCartItems()
            if (items.isEmpty()) {
                cvFloatingCart.visibility = View.GONE
            } else {
                cvFloatingCart.visibility = View.VISIBLE
                tvCartItemCount.text = CartManager.getUniqueItemCount().toString()
                tvCartTotal.text = String.format("₹%.2f", CartManager.getCartTotal())
            }
        }

        CartManager.addListener(cartListener)

        btnViewCartFloat.setOnClickListener { showCartScreen() }
        btnDeleteCart.setOnClickListener {
            CartManager.clearCart()
        }

        // Initial update
        cartListener.invoke()
    }

    fun showCartScreen() {
        val fragment = CartFragment()
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun searchProducts(query: String) {
        lastSearchQuery = query
        try {
            displayedProducts.clear()
            val db = DatabaseHelper.getInstance(requireContext()).readableDatabase

            if (query.isEmpty()) {
                tvNoResults.visibility = View.GONE
                adapter.updateData(emptyList())
                return
            }

            val searchPattern = "%$query%"
            val sql = """
                SELECT p.id, p.product_name, p.hsn_code, p.bar_code, p.product_image
                FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p
                WHERE p.store_id = ?
                  AND (p.product_name LIKE ?
                       OR p.hsn_code LIKE ?
                       OR p.bar_code LIKE ?
                       OR CAST(p.id AS TEXT) = ?)
                ORDER BY p.product_name COLLATE NOCASE
                LIMIT 100
            """.trimIndent()

            db.rawQuery(sql, arrayOf(storeId().toString(), searchPattern, searchPattern, searchPattern, query)).use { cursor ->
                while (cursor.moveToNext()) {
                    val productId = cursor.getInt(0)
                    displayedProducts.add(
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

            if (displayedProducts.isEmpty()) {
                adapter.updateData(emptyList())
                tvNoResults.visibility = View.VISIBLE
            } else {
                adapter.updateData(displayedProducts)
                tvNoResults.visibility = View.GONE
            }
        } catch (e: Exception) {
            android.util.Log.e("ItemwiseSearch", "Error searching products", e)
            tvNoResults.visibility = View.VISIBLE
        }
    }

    private fun storeId(): Int = SessionManager.currentUser?.storeId ?: 0

    private fun showAddToCartDialog(product: Product) {
        val context = requireContext()
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_add_to_cart_detailed, null)
        val dialog = AlertDialog.Builder(context).setView(view).create()

        // Fetch product details from database
        val productDetails = getProductDetails(product.id)

        val ivProductImage = view.findViewById<ImageView>(R.id.ivProductImage)
        val tvProductName = view.findViewById<TextView>(R.id.tvProductName)
        val tvProductCode = view.findViewById<TextView>(R.id.tvProductCode)
        val tvHsnCode = view.findViewById<TextView>(R.id.tvHsnCode)
        val tvStock = view.findViewById<TextView>(R.id.tvStock)
        val tvUnit = view.findViewById<TextView>(R.id.tvUnit)
        val tvBasePrice = view.findViewById<TextView>(R.id.tvBasePrice)
        val tvDiscount = view.findViewById<TextView>(R.id.tvDiscount)
        val tvSellingPrice = view.findViewById<TextView>(R.id.tvSellingPrice)
        val tvCgst = view.findViewById<TextView>(R.id.tvCgst)
        val tvSgst = view.findViewById<TextView>(R.id.tvSgst)
        val tvIgst = view.findViewById<TextView>(R.id.tvIgst)
        val tvVat = view.findViewById<TextView>(R.id.tvVat)
        val tvCgstAmount = view.findViewById<TextView>(R.id.tvCgstAmount)
        val tvSgstAmount = view.findViewById<TextView>(R.id.tvSgstAmount)
        val tvIgstAmount = view.findViewById<TextView>(R.id.tvIgstAmount)
        val tvVatAmount = view.findViewById<TextView>(R.id.tvVatAmount)
        val etQuantity = view.findViewById<TextInputEditText>(R.id.etQuantity)
        val tvTotalPrice = view.findViewById<TextView>(R.id.tvTotalPrice)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)
        val btnAddToCart = view.findViewById<MaterialButton>(R.id.btnAddToCart)

        // Set product image
        if (product.image != null) {
            try {
                val bitmap = BitmapFactory.decodeByteArray(product.image, 0, product.image.size)
                ivProductImage.setImageBitmap(bitmap)
            } catch (_: Exception) {
                ivProductImage.setImageResource(R.drawable.ic_placeholder_image)
            }
        } else {
            ivProductImage.setImageResource(R.drawable.ic_placeholder_image)
        }

        // Set product details
        tvProductName.text = product.name
        tvProductCode.text = product.code
        tvHsnCode.text = productDetails.hsnCode
        tvStock.text = "15 units"
        tvUnit.text = productDetails.unitName
        tvBasePrice.text = String.format("₹%.2f", productDetails.sellingPrice)

        val discountText = if (productDetails.discountType == "P") {
            "${productDetails.discount}%"
        } else {
            String.format("₹%.2f", productDetails.discount)
        }
        tvDiscount.text = discountText

        tvSellingPrice.text = String.format("₹%.2f", productDetails.sellingPrice)
        tvCgst.text = String.format("%.2f%%", productDetails.cgst)
        tvSgst.text = String.format("%.2f%%", productDetails.sgst)
        tvIgst.text = String.format("%.2f%%", productDetails.igst)
        tvVat.text = String.format("%.2f%%", productDetails.vat)

        etQuantity.setText("1")
        updateTotalPriceWithTaxes(tvTotalPrice, tvCgstAmount, tvSgstAmount, tvIgstAmount, tvVatAmount,
            productDetails.sellingPrice, productDetails.cgst, productDetails.sgst, productDetails.igst, productDetails.vat, 1.0)

        etQuantity.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val qty = s.toString().toDoubleOrNull() ?: 1.0
                updateTotalPriceWithTaxes(tvTotalPrice, tvCgstAmount, tvSgstAmount, tvIgstAmount, tvVatAmount,
                    productDetails.sellingPrice, productDetails.cgst, productDetails.sgst, productDetails.igst, productDetails.vat, qty)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnAddToCart.setOnClickListener {
            val qty = etQuantity.text.toString().toIntOrNull() ?: 1
            if (qty > 0) {
                val cartItem = CartManager.CartItem(
                    productId = product.id,
                    serialNumber = product.serialNumber,
                    name = product.name,
                    code = product.code,
                    barcode = product.barcode,
                    sellingPrice = productDetails.sellingPrice,
                    quantity = qty,
                    image = product.image,
                    cgst = productDetails.cgst,
                    sgst = productDetails.sgst,
                    igst = productDetails.igst,
                    vat = productDetails.vat
                )
                CartManager.addItem(cartItem)
                Toast.makeText(context, "Added to cart", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        ThemeManager.applyTheme(view)
        dialog.show()
    }

    private fun updateTotalPriceWithTaxes(
        tvTotalPrice: TextView, tvCgstAmount: TextView, tvSgstAmount: TextView,
        tvIgstAmount: TextView, tvVatAmount: TextView,
        sellingPrice: Double, cgst: Double, sgst: Double, igst: Double, vat: Double, qty: Double
    ) {
        val baseTotal = sellingPrice * qty
        val cgstAmount = (baseTotal * cgst) / 100
        val sgstAmount = (baseTotal * sgst) / 100
        val igstAmount = (baseTotal * igst) / 100
        val vatAmount = (baseTotal * vat) / 100
        val finalTotal = baseTotal + cgstAmount + sgstAmount + igstAmount + vatAmount

        tvCgstAmount.text = String.format("₹%.2f", cgstAmount)
        tvSgstAmount.text = String.format("₹%.2f", sgstAmount)
        tvIgstAmount.text = String.format("₹%.2f", igstAmount)
        tvVatAmount.text = String.format("₹%.2f", vatAmount)
        tvTotalPrice.text = String.format("₹%.2f", finalTotal)
    }

    private data class ProductDetails(
        val unitName: String,
        val hsnCode: String,
        val sellingPrice: Double,
        val discount: Double,
        val discountType: String,
        val cgst: Double,
        val sgst: Double,
        val igst: Double,
        val vat: Double
    )

    private fun getProductDetails(productId: Int): ProductDetails {
        return try {
            val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
            val sql = """
                SELECT
                    u.unit_name,
                    p.hsn_code,
                    r.sell_price,
                    r.discount,
                    r.discount_type,
                    r.cgst_rate,
                    r.sgst_rate,
                    r.igst_rate,
                    r.vat_rate
                FROM ${DatabaseHelper.Tables.MD_PRODUCT_RATES} r
                LEFT JOIN ${DatabaseHelper.Tables.MD_UNITS} u ON u.id = r.unit_1_id
                LEFT JOIN ${DatabaseHelper.Tables.MD_PRODUCTS} p ON p.id = r.product_id
                WHERE r.product_id = ?
                LIMIT 1
            """.trimIndent()

            db.rawQuery(sql, arrayOf(productId.toString())).use { cursor ->
                if (cursor.moveToFirst()) {
                    ProductDetails(
                        unitName = cursor.getString(0) ?: "N/A",
                        hsnCode = cursor.getString(1) ?: "N/A",
                        sellingPrice = cursor.getDouble(2),
                        discount = cursor.getDouble(3),
                        discountType = cursor.getString(4) ?: "A",
                        cgst = cursor.getDouble(5),
                        sgst = cursor.getDouble(6),
                        igst = cursor.getDouble(7),
                        vat = cursor.getDouble(8)
                    )
                } else {
                    ProductDetails("N/A", "N/A", 0.0, 0.0, "A", 0.0, 0.0, 0.0, 0.0)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ItemwiseSearch", "Error fetching product details", e)
            ProductDetails("N/A", "N/A", 0.0, 0.0, "A", 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun updateTotalPrice(tvTotalPrice: TextView, sellingPrice: Double, qty: Double) {
        val total = sellingPrice * qty
        tvTotalPrice.text = String.format("₹%.2f", total)
    }

    private fun getSellingPrice(productId: Int): Double {
        return try {
            val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
            val sql = """
                SELECT r.sell_price FROM ${DatabaseHelper.Tables.MD_PRODUCT_RATES} r
                WHERE r.product_id = ?
                LIMIT 1
            """.trimIndent()
            db.rawQuery(sql, arrayOf(productId.toString())).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    cursor.getDouble(0)
                } else {
                    0.0
                }
            }
        } catch (e: Exception) {
            0.0
        }
    }

    private inner class ProductAdapter(
        private var items: List<Product>
    ) : RecyclerView.Adapter<ProductAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivImage: ImageView? = view.findViewById(R.id.ivProductImage)
            val tvSerialNo: TextView? = view.findViewById(R.id.tvProductSerialNo)
            val tvName: TextView? = view.findViewById(R.id.tvProductName)
            val tvCode: TextView? = view.findViewById(R.id.tvProductCode)
            val tvBarcode: TextView? = view.findViewById(R.id.tvProductBarcode)

            init {
                view.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        showAddToCartDialog(displayedProducts[pos])
                    }
                }
            }

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

        fun updateData(newData: List<Product>) {
            items = newData
            notifyDataSetChanged()
        }
    }
}
