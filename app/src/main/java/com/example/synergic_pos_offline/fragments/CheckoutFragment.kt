package com.example.synergic_pos_offline.fragments

import android.content.ContentValues
import android.content.res.ColorStateList
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.CartManager
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CheckoutFragment : Fragment(), TitledScreen {

    override val screenTitle = "Checkout"

    private lateinit var rvCheckoutItems: RecyclerView
    private lateinit var tvSubtotal: TextView
    private lateinit var tvGrandTotal: TextView
    private lateinit var llTaxBreakdown: LinearLayout
    private lateinit var etCustomerName: TextInputEditText
    private lateinit var rvCustomers: RecyclerView
    private lateinit var btnPaymentCash: MaterialButton
    private lateinit var btnPaymentCard: MaterialButton
    private lateinit var btnPaymentUPI: MaterialButton
    private lateinit var tvSelectedPayment: TextView
    private lateinit var btnSaveOrder: MaterialButton
    private lateinit var btnCancel: MaterialButton

    private var selectedPaymentMethod = "CASH"
    private var selectedCustomerId: Int? = null
    private var selectedCustomerName = ""
    private var allCustomers = listOf<Customer>()

    data class Customer(
        val id: Int,
        val name: String,
        val phone: String?
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_checkout, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvCheckoutItems = view.findViewById(R.id.rvCheckoutItems)
        tvSubtotal = view.findViewById(R.id.tvSubtotal)
        tvGrandTotal = view.findViewById(R.id.tvGrandTotal)
        llTaxBreakdown = view.findViewById(R.id.llTaxBreakdown)
        etCustomerName = view.findViewById(R.id.etCustomerName)
        rvCustomers = view.findViewById(R.id.rvCustomers)
        btnPaymentCash = view.findViewById(R.id.btnPaymentCash)
        btnPaymentCard = view.findViewById(R.id.btnPaymentCard)
        btnPaymentUPI = view.findViewById(R.id.btnPaymentUPI)
        tvSelectedPayment = view.findViewById(R.id.tvSelectedPayment)
        btnSaveOrder = view.findViewById(R.id.btnSaveOrder)
        btnCancel = view.findViewById(R.id.btnCancel)

        setupCheckoutItems()
        setupCustomerSearch()
        setupPaymentMethodButtons()
        setupActionButtons()
        updateTotalCalculations()

        ThemeManager.applyTheme(view)
    }

    private fun setupCheckoutItems() {
        val items = CartManager.getCartItems()
        val adapter = CheckoutItemAdapter(items.toMutableList())
        rvCheckoutItems.layoutManager = LinearLayoutManager(requireContext())
        rvCheckoutItems.adapter = adapter
    }

    private fun setupCustomerSearch() {
        allCustomers = fetchCustomersFromDB()
        val customerAdapter = CustomerAdapter(allCustomers.toMutableList()) { customer ->
            selectedCustomerId = customer.id
            selectedCustomerName = customer.name
            etCustomerName.setText(customer.name)
        }
        rvCustomers.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvCustomers.adapter = customerAdapter

        etCustomerName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                selectedCustomerName = s.toString()
                selectedCustomerId = null
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun fetchCustomersFromDB(): List<Customer> {
        return try {
            val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
            val sql = """
                SELECT id, customer_name, phone_number
                FROM ${DatabaseHelper.Tables.MD_CUSTOMERS}
                LIMIT 50
            """.trimIndent()

            val customers = mutableListOf<Customer>()
            db.rawQuery(sql, null).use { cursor ->
                while (cursor.moveToNext()) {
                    customers.add(
                        Customer(
                            id = cursor.getInt(0),
                            name = cursor.getString(1).orEmpty(),
                            phone = cursor.getString(2)
                        )
                    )
                }
            }
            customers
        } catch (e: Exception) {
            android.util.Log.e("Checkout", "Error fetching customers", e)
            emptyList()
        }
    }

    private fun setupPaymentMethodButtons() {
        btnPaymentCash.setOnClickListener { selectPaymentMethod("CASH", btnPaymentCash) }
        btnPaymentCard.setOnClickListener { selectPaymentMethod("CARD", btnPaymentCard) }
        btnPaymentUPI.setOnClickListener { selectPaymentMethod("UPI", btnPaymentUPI) }

        // Set Cash as default
        selectPaymentMethod("CASH", btnPaymentCash)
    }

    private fun selectPaymentMethod(method: String, button: MaterialButton) {
        val accent = ThemeManager.getThemeColor(requireContext())
        selectedPaymentMethod = method
        
        // Reset all to outlined style (white background, accent border)
        listOf(btnPaymentCash, btnPaymentCard, btnPaymentUPI).forEach { btn ->
            btn.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            btn.setTextColor(accent)
            btn.strokeColor = ColorStateList.valueOf(accent)
            btn.strokeWidth = (resources.displayMetrics.density * 1.2f).toInt()
            btn.cornerRadius = (resources.displayMetrics.density * 12).toInt()
        }

        // Highlight selected
        button.backgroundTintList = ColorStateList.valueOf(accent)
        button.setTextColor(Color.WHITE)
        
        tvSelectedPayment.text = "Selected: $method"
    }

    private fun setupActionButtons() {
        val accent = ThemeManager.getThemeColor(requireContext())
        
        // Cancel button: White background, accent border
        btnCancel.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        btnCancel.setTextColor(accent)
        btnCancel.strokeColor = ColorStateList.valueOf(accent)
        btnCancel.strokeWidth = (resources.displayMetrics.density * 1.2f).toInt()
        btnCancel.cornerRadius = (resources.displayMetrics.density * 12).toInt()

        btnCancel.setOnClickListener { requireActivity().onBackPressed() }
        btnSaveOrder.setOnClickListener { saveOrder() }
    }

    private fun updateTotalCalculations() {
        val items = CartManager.getCartItems()
        val subtotal = items.sumOf { it.totalPrice }
        val cgstTotal = items.sumOf { it.cgstAmount }
        val sgstTotal = items.sumOf { it.sgstAmount }
        val igstTotal = items.sumOf { it.igstAmount }
        val vatTotal = items.sumOf { it.vatAmount }
        val grandTotal = items.sumOf { it.finalPrice }

        tvSubtotal.text = String.format("₹%.2f", subtotal)
        tvGrandTotal.text = String.format("₹%.2f", grandTotal)

        // Build tax breakdown
        llTaxBreakdown.removeAllViews()
        if (cgstTotal > 0) addTaxRow("CGST", cgstTotal)
        if (sgstTotal > 0) addTaxRow("SGST", sgstTotal)
        if (igstTotal > 0) addTaxRow("IGST", igstTotal)
        if (vatTotal > 0) addTaxRow("VAT", vatTotal)
    }

    private fun addTaxRow(taxName: String, amount: Double) {
        val row = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 4, 0, 4) }
            orientation = LinearLayout.HORIZONTAL
        }

        val label = TextView(requireContext()).apply {
            text = taxName
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(requireContext().getColor(R.color.text_secondary))
        }

        val value = TextView(requireContext()).apply {
            text = String.format("₹%.2f", amount)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setTextColor(requireContext().getColor(R.color.text_secondary))
        }

        row.addView(label)
        row.addView(value)
        llTaxBreakdown.addView(row)
    }

    private fun saveOrder() {
        android.util.Log.d("Checkout", "=== SAVE ORDER STARTED ===")

        if (CartManager.getCartItems().isEmpty()) {
            Toast.makeText(requireContext(), "Cart is empty", Toast.LENGTH_SHORT).show()
            return
        }

        if (selectedCustomerName.isEmpty() && selectedCustomerId == null) {
            Toast.makeText(requireContext(), "Please select or enter customer name", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val db = DatabaseHelper.getInstance(requireContext()).writableDatabase
            db.beginTransaction()

            try {
                // Generate bill number
                val billNumber = generateBillNumber()
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val dateOnly = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

                val items = CartManager.getCartItems()
                val subtotal = items.sumOf { it.totalPrice }
                val cgstTotal = items.sumOf { it.cgstAmount }
                val sgstTotal = items.sumOf { it.sgstAmount }
                val igstTotal = items.sumOf { it.igstAmount }
                val vatTotal = items.sumOf { it.vatAmount }
                val grandTotal = subtotal + cgstTotal + sgstTotal + igstTotal + vatTotal

                android.util.Log.d("Checkout", "Bill Number: $billNumber")
                android.util.Log.d("Checkout", "Store ID: ${SessionManager.currentUser?.storeId ?: 1}")
                android.util.Log.d("Checkout", "Total: $grandTotal, Items: ${items.size}")

                // Insert into td_bills
                val billValues = ContentValues().apply {
                    put("store_id", SessionManager.currentUser?.storeId ?: 1)
                    put("outlet_id", 1)
                    put("bill_number", billNumber)
                    put("bill_date", dateOnly)
                    put("bill_date_time", now)
                    // Only insert customer_id if it's a valid ID from the database
                    selectedCustomerId?.let { custId ->
                        if (custId > 0) put("customer_id", custId)
                    }
                    // Don't insert operator_id - it requires a valid FK reference
                    // put("operator_id", operatorId)
                    put("bill_type", "CASH")
                    put("tot_price", subtotal)
                    put("tot_cgst_amount", cgstTotal)
                    put("tot_sgst_amount", sgstTotal)
                    put("tot_igst_amount", igstTotal)
                    put("tot_vat_amount", vatTotal)
                    put("net_amount", grandTotal)
                    put("bill_status", "COMPLETED")
                    put("created_by", SessionManager.currentUser?.userId.toString())
                }

                val billId = db.insert(DatabaseHelper.Tables.TD_BILLS, null, billValues)
                android.util.Log.d("Checkout", "Bill inserted with ID: $billId")

                // Insert items into td_bill_items
                items.forEach { item ->
                    val itemValues = ContentValues().apply {
                        put("bill_id", billId)
                        put("receipt_no", billId)
                        put("product_id", item.productId)
                        put("quantity", item.quantity)
                        put("rate", item.sellingPrice)
                        put("item_subtotal", item.totalPrice)
                        put("cgst_rate", item.cgst)
                        put("sgst_rate", item.sgst)
                        put("igst_rate", item.igst)
                        put("vat_rate", item.vat)
                        put("cgst_amount", item.cgstAmount)
                        put("sgst_amount", item.sgstAmount)
                        put("igst_amount", item.igstAmount)
                        put("vat_amount", item.vatAmount)
                        put("item_total", item.finalPrice)
                        put("created_by", SessionManager.currentUser?.userId.toString())
                    }
                    db.insert(DatabaseHelper.Tables.TD_BILL_ITEMS, null, itemValues)
                }
                android.util.Log.d("Checkout", "Bill items inserted: ${items.size}")

                // Insert into td_payments
                val paymentValues = ContentValues().apply {
                    put("bill_id", billId)
                    put("receipt_no", billId)
                    put("payment_mode", selectedPaymentMethod)
                    put("amount_paid", grandTotal)
                    put("payment_status", "COMPLETED")
                    put("payment_date", now)
                    put("cust_name", selectedCustomerName)
                    // Only insert cust_id if it's a valid ID from the database
                    selectedCustomerId?.let { custId ->
                        if (custId > 0) put("cust_id", custId)
                    }
                    put("created_by", SessionManager.currentUser?.userId.toString())
                }
                db.insert(DatabaseHelper.Tables.TD_PAYMENTS, null, paymentValues)
                android.util.Log.d("Checkout", "Payment record inserted")

                db.setTransactionSuccessful()
                android.util.Log.d("Checkout", "Transaction committed successfully")

                Toast.makeText(requireContext(), "Order saved successfully! Bill #$billNumber", Toast.LENGTH_LONG).show()

                // Clear cart
                CartManager.clearCart()

                // Navigate to recent bills
                navigateToRecentBills()

            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            android.util.Log.e("Checkout", "Error saving order", e)
            e.printStackTrace()
            Toast.makeText(requireContext(), "Error saving order: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateBillNumber(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val timestamp = System.currentTimeMillis() % 10000
        return "BILL-${dateFormat.format(Date())}-$timestamp"
    }

    private fun navigateToRecentBills() {
        val fragment = RecentBillsFragment()
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private inner class CheckoutItemAdapter(
        private val items: MutableList<CartManager.CartItem>
    ) : RecyclerView.Adapter<CheckoutItemAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvProductName: TextView = view.findViewById(R.id.tvProductName)
            val tvProductCode: TextView = view.findViewById(R.id.tvProductCode)
            val tvUnitPrice: TextView = view.findViewById(R.id.tvUnitPrice)
            val tvQuantity: TextView = view.findViewById(R.id.tvQuantity)
            val tvItemSubtotal: TextView = view.findViewById(R.id.tvItemSubtotal)
            val tvItemTotal: TextView = view.findViewById(R.id.tvItemTotal)
            val llTaxes: LinearLayout = view.findViewById(R.id.llTaxes)

            fun bind(item: CartManager.CartItem) {
                tvProductName.text = item.name
                tvProductCode.text = item.code
                tvUnitPrice.text = String.format("₹%.2f", item.sellingPrice)
                tvQuantity.text = item.quantity.toString()
                tvItemSubtotal.text = String.format("₹%.2f", item.totalPrice)
                tvItemTotal.text = String.format("₹%.2f", item.finalPrice)

                llTaxes.removeAllViews()
                if (item.cgstAmount > 0) addTaxRow(llTaxes, "CGST", item.cgstAmount)
                if (item.sgstAmount > 0) addTaxRow(llTaxes, "SGST", item.sgstAmount)
                if (item.igstAmount > 0) addTaxRow(llTaxes, "IGST", item.igstAmount)
                if (item.vatAmount > 0) addTaxRow(llTaxes, "VAT", item.vatAmount)
            }

            private fun addTaxRow(parent: LinearLayout, taxName: String, amount: Double) {
                val row = LinearLayout(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 2, 0, 2) }
                    orientation = LinearLayout.HORIZONTAL
                }

                val label = TextView(requireContext()).apply {
                    text = "$taxName:"
                    textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setTextColor(requireContext().getColor(R.color.text_secondary))
                }

                val value = TextView(requireContext()).apply {
                    text = String.format("₹%.2f", amount)
                    textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    setTextColor(requireContext().getColor(R.color.text_secondary))
                }

                row.addView(label)
                row.addView(value)
                parent.addView(row)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_checkout_product, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size
    }

    private inner class CustomerAdapter(
        private val customers: MutableList<Customer>,
        private val onCustomerSelected: (Customer) -> Unit
    ) : RecyclerView.Adapter<CustomerAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvCustomerName: TextView = view.findViewById(R.id.tvCustomerName)
            val tvCustomerPhone: TextView = view.findViewById(R.id.tvCustomerPhone)

            fun bind(customer: Customer) {
                tvCustomerName.text = customer.name
                tvCustomerPhone.text = customer.phone ?: "No phone"

                itemView.setOnClickListener {
                    onCustomerSelected(customer)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_customer_selector, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(customers[position])
        }

        override fun getItemCount() = customers.size
    }
}
