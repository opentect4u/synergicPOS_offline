package com.example.synergic_pos_offline.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.Serializable

object CartManager {
    private val cartItems = mutableListOf<CartItem>()
    private var listeners = mutableListOf<() -> Unit>()

    data class CartItem(
        val productId: Int,
        val serialNumber: Int,
        val name: String,
        val code: String,
        val barcode: String,
        val sellingPrice: Double,
        var quantity: Int,
        val image: ByteArray?,
        val cgst: Double = 0.0,
        val sgst: Double = 0.0,
        val igst: Double = 0.0,
        val vat: Double = 0.0
    ) {
        val totalPrice: Double
            get() = sellingPrice * quantity

        val cgstAmount: Double
            get() = (sellingPrice * quantity * cgst) / 100

        val sgstAmount: Double
            get() = (sellingPrice * quantity * sgst) / 100

        val igstAmount: Double
            get() = (sellingPrice * quantity * igst) / 100

        val vatAmount: Double
            get() = (sellingPrice * quantity * vat) / 100

        val totalTaxAmount: Double
            get() = cgstAmount + sgstAmount + igstAmount + vatAmount

        val finalPrice: Double
            get() = totalPrice + totalTaxAmount

        override fun equals(other: Any?): Boolean = other is CartItem && productId == other.productId
        override fun hashCode(): Int = productId.hashCode()
    }

    fun addItem(item: CartItem) {
        val existing = cartItems.find { it.productId == item.productId }
        if (existing != null) {
            existing.quantity += item.quantity
        } else {
            cartItems.add(item)
        }
        notifyListeners()
    }

    fun removeItem(productId: Int) {
        cartItems.removeAll { it.productId == productId }
        notifyListeners()
    }

    fun updateQuantity(productId: Int, quantity: Int) {
        cartItems.find { it.productId == productId }?.quantity = quantity
        notifyListeners()
    }

    fun clearCart() {
        cartItems.clear()
        notifyListeners()
    }

    fun getCartItems(): List<CartItem> = cartItems.toList()

    fun getCartTotal(): Double = cartItems.sumOf { it.totalPrice }

    fun getCartItemCount(): Int = cartItems.size

    fun getCartQuantityTotal(): Int = cartItems.sumOf { it.quantity }

    fun getUniqueItemCount(): Int = cartItems.size

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it.invoke() }
    }
}
