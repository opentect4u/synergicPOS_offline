package com.example.synergic_pos_offline.fragments

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.CartManager
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class CartFragment : Fragment(), TitledScreen {

    override val screenTitle = "Shopping Cart"

    private lateinit var rvCart: RecyclerView
    private lateinit var tvEmptyCart: TextView
    private lateinit var llCartFooter: LinearLayout
    private lateinit var tvItemCount: TextView
    private lateinit var tvTotalAmount: TextView
    private lateinit var btnClearCart: MaterialButton
    private lateinit var btnCheckout: MaterialButton
    private var cartListener: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_cart, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            rvCart = view.findViewById(R.id.rvCartItems) ?: return
            tvEmptyCart = view.findViewById(R.id.tvEmptyCart) ?: return
            llCartFooter = view.findViewById(R.id.llCartFooter) ?: return
            tvItemCount = view.findViewById(R.id.tvItemCount) ?: return
            tvTotalAmount = view.findViewById(R.id.tvTotalAmount) ?: return
            btnClearCart = view.findViewById(R.id.btnClearCart) ?: return
            btnCheckout = view.findViewById(R.id.btnCheckout) ?: return

            rvCart.layoutManager = LinearLayoutManager(requireContext())
            rvCart.adapter = CartAdapter(CartManager.getCartItems())

            btnClearCart.setOnClickListener { confirmClearCart() }
            btnCheckout.setOnClickListener { processCheckout() }

            cartListener = { updateCartUI() }
            CartManager.addListener(cartListener!!)

            updateCartUI()
            ThemeManager.applyTheme(view)
        } catch (e: Exception) {
            android.util.Log.e("Cart", "Error in onViewCreated", e)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cartListener?.let { CartManager.removeListener(it) }
    }

    private fun updateCartUI() {
        val items = CartManager.getCartItems()

        if (items.isEmpty()) {
            rvCart.visibility = View.GONE
            tvEmptyCart.visibility = View.VISIBLE
            llCartFooter.visibility = View.GONE
        } else {
            rvCart.visibility = View.VISIBLE
            tvEmptyCart.visibility = View.GONE
            llCartFooter.visibility = View.VISIBLE

            tvItemCount.text = "${CartManager.getCartQuantityTotal()} items"
            tvTotalAmount.text = String.format("₹%.2f", CartManager.getCartTotal())
            rvCart.adapter?.notifyDataSetChanged()
        }
    }

    private fun confirmClearCart() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear Cart")
            .setMessage("Are you sure you want to remove all items from the cart?")
            .setPositiveButton("Clear") { _, _ ->
                CartManager.clearCart()
                Toast.makeText(requireContext(), "Cart cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel") { _, _ -> }
            .show()
    }

    private fun processCheckout() {
        if (CartManager.getCartItems().isEmpty()) {
            Toast.makeText(requireContext(), "Cart is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val checkoutFragment = CheckoutFragment()
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, checkoutFragment)
            .addToBackStack(null)
            .commit()
    }

    private inner class CartAdapter(
        private val items: List<CartManager.CartItem>
    ) : RecyclerView.Adapter<CartAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivImage: ImageView = view.findViewById(R.id.ivProductImage)
            val tvName: TextView = view.findViewById(R.id.tvProductName)
            val tvCode: TextView = view.findViewById(R.id.tvProductCode)
            val tvUnitPrice: TextView = view.findViewById(R.id.tvUnitPrice)
            val tvQuantity: TextView = view.findViewById(R.id.tvQuantity)
            val tvTotalPrice: TextView = view.findViewById(R.id.tvTotalPrice)
            val btnRemove: ImageButton = view.findViewById(R.id.btnRemove)
            val btnIncrement: ImageButton = view.findViewById(R.id.btnIncrement)
            val btnDecrement: ImageButton = view.findViewById(R.id.btnDecrement)

            fun bind(item: CartManager.CartItem) {
                tvName.text = item.name
                tvCode.text = "Code: ${item.code}"
                tvUnitPrice.text = String.format("₹%.2f", item.sellingPrice)
                tvQuantity.text = item.quantity.toString()
                tvTotalPrice.text = String.format("₹%.2f", item.totalPrice)

                if (item.image != null) {
                    try {
                        val bitmap = BitmapFactory.decodeByteArray(item.image, 0, item.image.size)
                        ivImage.setImageBitmap(bitmap)
                    } catch (_: Exception) {
                        ivImage.setImageResource(R.drawable.ic_placeholder_image)
                    }
                } else {
                    ivImage.setImageResource(R.drawable.ic_placeholder_image)
                }

                btnRemove.setOnClickListener {
                    CartManager.removeItem(item.productId)
                }

                btnIncrement.setOnClickListener {
                    CartManager.updateQuantity(item.productId, item.quantity + 1)
                }

                btnDecrement.setOnClickListener {
                    if (item.quantity > 1) {
                        CartManager.updateQuantity(item.productId, item.quantity - 1)
                    } else {
                        CartManager.removeItem(item.productId)
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_cart_product, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size
    }
}
