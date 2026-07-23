package com.example.synergic_pos_offline.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.synergic_pos_offline.utils.AmountInWords
import com.example.synergic_pos_offline.utils.GstCalculator
import com.example.synergic_pos_offline.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data-access layer that generates a completed sale, writing atomically to
 * [DatabaseHelper.Tables.TD_BILLS], [DatabaseHelper.Tables.TD_BILL_ITEMS] and
 * [DatabaseHelper.Tables.TD_PAYMENTS].
 */

/**
 * Read access to the bill history stored in [DatabaseHelper.Tables.TD_BILLS],
 * joined with customers (name) and bill items (product names). Powers the
 * Bill History screen with real, persisted data.
 */
class BillDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** A single line on the bill. */
    data class Item(
        val productId: Long?,
        val name: String,
        val quantity: Double,
        val rate: Double,
        val cgstRate: Double = 0.0,
        val sgstRate: Double = 0.0,
        /** This line's share of the bill discount; GST is charged on the remainder. */
        val discountAmount: Double = 0.0
    )

    /** Payment collected against the bill. */
    data class Payment(
        val mode: String,          // CASH / UPI / CARD / CHEQUE / ONLINE
        val amountPaid: Double,
        val changeAmount: Double = 0.0,
        val custName: String? = null,
        val custPhone: String? = null,
        val custGstin: String? = null,
        val custId: Long? = null
    )

    /** Everything needed to persist a completed sale. */
    data class NewBill(
        val billType: String,      // CASH / CREDIT / ONLINE / VOID
        val customerId: Long?,
        val items: List<Item>,
        val payment: Payment,
        val totalPrice: Double,
        val discountAmount: Double,
        val discountPercentage: Double,
        val cgstAmount: Double,
        val sgstAmount: Double,
        val netAmount: Double,
        val igstAmount: Double = 0.0,
        val vatAmount: Double = 0.0,
        val otherChargesAmount: Double = 0.0,
        val roundOffAmount: Double = 0.0,
        val waiterId: Long? = null,
        val isMrpBilling: Boolean = false,
        val isReturnBill: Boolean = false
    )

    /** Result of a successful generation. */
    data class Result(val receiptNo: Long, val billNumber: String)

    /**
     * Persists [bill] across the three transaction tables in one atomic
     * transaction. Returns the new receipt number and its formatted bill number,
     * or null if the write failed.
     */
    fun createBill(bill: NewBill): Result? {
        val db = helper.writableDatabase
        val storeId = currentStoreId()
        val outletId = currentOutletId()
        val operatorId = currentOperatorId()
        val user = currentUser()
        val nowDateTime = now()
        val nowDate = today()

        db.beginTransaction()
        try {
            // 1) Bill header (bill_number filled in after we know the receipt_no).
            val billValues = ContentValues().apply {
                put("store_id", storeId)
                put("outlet_id", outletId)
                put("bill_date", nowDate)
                put("bill_date_time", nowDateTime)
                if (bill.customerId != null) put("customer_id", bill.customerId)
                if (operatorId != null) put("operator_id", operatorId)
                put("bill_type", bill.billType)
                put("tot_price", bill.totalPrice)
                put("tot_discount_amount", bill.discountAmount)
                put("tot_discount_percentage", bill.discountPercentage)
                put("discount_flag", if (bill.discountAmount > 0) 1 else 0)
                put("discount_type", if (bill.discountPercentage > 0) "PERCENTAGE" else if (bill.discountAmount > 0) "FLAT" else null)
                put("tot_cgst_amount", bill.cgstAmount)
                put("tot_sgst_amount", bill.sgstAmount)
                put("tot_igst_amount", bill.igstAmount)
                put("tot_vat_amount", bill.vatAmount)
                put("tot_other_charges_amount", bill.otherChargesAmount)
                put("tot_round_off_amount", bill.roundOffAmount)
                put("net_amount", bill.netAmount)
                put("amount_in_words", AmountInWords.of(bill.netAmount))
                put("gst_flag", if (bill.cgstAmount + bill.sgstAmount + bill.igstAmount > 0) 1 else 0)
                put("vat_flag", if (bill.vatAmount > 0) 1 else 0)
                if (bill.waiterId != null) put("waiter_id", bill.waiterId)
                put("is_mrp_billing", if (bill.isMrpBilling) 1 else 0)
                put("is_return_bill", if (bill.isReturnBill) 1 else 0)
                put("is_duplicate", 0)
                put("is_voided", if (bill.billType == "VOID") 1 else 0)
                put("bill_status", if (bill.billType == "VOID") "CANCELLED" else "COMPLETED")
                put("created_by", user)
            }
            val receiptNo = db.insert(DatabaseHelper.Tables.TD_BILLS, null, billValues)
            if (receiptNo == -1L) return null

            val billNumber = formatBillNumber(receiptNo)
            db.update(
                DatabaseHelper.Tables.TD_BILLS,
                ContentValues().apply { put("bill_number", billNumber) },
                "receipt_no=?", arrayOf(receiptNo.toString())
            )

            // 2) Bill items.
            bill.items.forEach { item ->
                val subtotal = item.rate * item.quantity
                // GST applies to what is actually charged, so discount comes off first.
                val taxable = GstCalculator.taxableValue(subtotal, item.discountAmount)
                val cgstAmt = GstCalculator.taxAmount(taxable, item.cgstRate)
                val sgstAmt = GstCalculator.taxAmount(taxable, item.sgstRate)
                val itemValues = ContentValues().apply {
                    put("receipt_no", receiptNo)
                    put("trans_dt", nowDateTime)
                    put("bill_id", receiptNo)
                    if (item.productId != null) put("product_id", item.productId)
                    put("quantity", item.quantity)
                    put("rate", item.rate)
                    put("item_subtotal", subtotal)
                    put("discount_amount", item.discountAmount)
                    put("cgst_rate", item.cgstRate)
                    put("sgst_rate", item.sgstRate)
                    put("cgst_amount", cgstAmt)
                    put("sgst_amount", sgstAmt)
                    put("item_total", taxable + cgstAmt + sgstAmt)
                    put("created_by", user)
                }
                db.insert(DatabaseHelper.Tables.TD_BILL_ITEMS, null, itemValues)
            }

            // 3) Payment.
            val paymentValues = ContentValues().apply {
                put("receipt_no", receiptNo)
                put("bill_id", receiptNo)
                put("payment_mode", bill.payment.mode)
                put("amount_paid", bill.payment.amountPaid)
                put("change_amount", bill.payment.changeAmount)
                put("payment_status", paymentStatusFor(bill))
                put("balance_amount", balanceDueFor(bill))
                put("payment_date", nowDateTime)
                bill.payment.custName?.let { put("cust_name", it) }
                bill.payment.custGstin?.let { put("cust_gstin", it) }
                bill.payment.custPhone?.let { put("cust_phone", it) }
                bill.payment.custId?.let { put("cust_id", it) }
                put("created_by", user)
            }
            val paymentId = db.insert(DatabaseHelper.Tables.TD_PAYMENTS, null, paymentValues)

            // 4) Anything still owed goes on the customer's ledger for recovery.
            val balance = balanceDueFor(bill)
            val customerId = bill.customerId ?: bill.payment.custId
            if (balance > 0.001 && customerId != null && paymentId != -1L) {
                recordBalanceDue(db, customerId, receiptNo, paymentId, balance, nowDateTime, user)
            }

            db.setTransactionSuccessful()
            return Result(receiptNo, billNumber)
        } finally {
            db.endTransaction()
        }
    }

    /**
     * What is still owed on the bill. Change is not deducted - handing over 32.00
     * for a 31.50 bill settles it in full - and the comparison carries a small
     * tolerance because the total holds rounded paise.
     */
    private fun balanceDueFor(bill: NewBill): Double =
        (bill.netAmount - bill.payment.amountPaid).coerceAtLeast(0.0)

    /**
     * A payment is only COMPLETED once the bill is covered. A credit sale is billed
     * now and collected later, so it stays PENDING until something is taken, and
     * PARTIAL while it is short.
     */
    private fun paymentStatusFor(bill: NewBill): String = when {
        balanceDueFor(bill) <= 0.001 -> "COMPLETED"
        bill.payment.amountPaid > 0.001 -> "PARTIAL"
        else -> "PENDING"
    }

    /**
     * Books an outstanding bill against the customer so it can be recovered later:
     * a DEBIT line on their ledger carrying the running balance, and the same total
     * on their master record.
     *
     * Skipped when the sale has no customer - there would be nobody to chase, and
     * the balance is still recorded on the payment row either way.
     */
    private fun recordBalanceDue(
        db: SQLiteDatabase,
        customerId: Long,
        receiptNo: Long,
        paymentId: Long,
        balance: Double,
        nowDateTime: String,
        user: String?
    ) {
        val previous = db.rawQuery(
            "SELECT balance_amount FROM ${DatabaseHelper.Tables.MD_CUSTOMERS} WHERE id = ?",
            arrayOf(customerId.toString())
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0) else 0.0 }
        val running = previous + balance

        db.insert(
            DatabaseHelper.Tables.TD_CUSTOMER_LEDGER, null,
            ContentValues().apply {
                put("customer_id", customerId)
                put("bill_id", receiptNo)
                put("payment_id", paymentId)
                put("transaction_type", "DEBIT")
                put("amount", balance)
                put("balance", running)
                put("transaction_date", nowDateTime)
                put("created_by", user)
            }
        )
        db.update(
            DatabaseHelper.Tables.MD_CUSTOMERS,
            ContentValues().apply { put("balance_amount", running) },
            "id = ?", arrayOf(customerId.toString())
        )
    }

    /** The bill number the next completed sale will be given, e.g. "INV-000010". */
    fun nextBillNumber(): String = formatBillNumber(nextReceiptNo())

    /**
     * The receipt number the next bill will take.
     *
     * Read from sqlite_sequence rather than MAX(receipt_no): td_bills is
     * AUTOINCREMENT, so deleting the last bill does not release its number and the
     * counter keeps going. MAX is still folded in as a guard for a table whose
     * sequence row is missing - a restored or hand-edited database - where
     * trusting the sequence alone would hand out a number already in use.
     */
    fun nextReceiptNo(): Long {
        helper.readableDatabase.rawQuery(
            """
            SELECT MAX(
                COALESCE((SELECT seq FROM sqlite_sequence WHERE name = ?), 0),
                COALESCE((SELECT MAX(receipt_no) FROM ${DatabaseHelper.Tables.TD_BILLS}), 0)
            ) + 1
            """.trimIndent(),
            arrayOf(DatabaseHelper.Tables.TD_BILLS)
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return 1L
    }

    /** The most recent bill's number (e.g. "INV-000009"), or null if none exist yet. */
    fun lastBillNumber(): String? {
        helper.readableDatabase.rawQuery(
            """
            SELECT bill_number, receipt_no FROM ${DatabaseHelper.Tables.TD_BILLS}
            ORDER BY receipt_no DESC LIMIT 1
            """.trimIndent(),
            null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return c.getString(0)?.takeIf { it.isNotBlank() } ?: formatBillNumber(c.getLong(1))
        }
    }

    /** The most recent bill's receipt number, or null if none exist yet. */
    fun lastReceiptNo(): Long? {
        helper.readableDatabase.rawQuery(
            "SELECT receipt_no FROM ${DatabaseHelper.Tables.TD_BILLS} ORDER BY receipt_no DESC LIMIT 1",
            null
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    /** Looks up a customer's id by phone number, or null if not found. */
    fun findCustomerIdByPhone(phone: String?): Long? {
        if (phone.isNullOrBlank()) return null
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_CUSTOMERS, arrayOf("id"),
            "phone_number=?", arrayOf(phone), null, null, "id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    private fun formatBillNumber(receiptNo: Long): String =
        "INV-" + String.format(Locale.US, "%06d", receiptNo)


    private fun currentStoreId(): Long? {
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    private fun currentOutletId(): Long? {
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("outlet_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    /** Resolves the logged-in user's md_users.id from their user_id. */
    private fun currentOperatorId(): Long? {
        val userId = SessionManager.currentUser?.userId ?: return null
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_USERS, arrayOf("id"),
            "user_id=?", arrayOf(userId), null, null, "id ASC", "1"
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    private fun currentUser(): String? = SessionManager.currentUser?.userId

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    /** One bill summary row, shaped for the Bill History list. */
    data class Bill(
        val receiptNo: Long,
        val billNo: String,
        val name: String,
        val date: String,       // dd-MM-yyyy
        val time: String,       // HH:mm
        val total: String,      // formatted net amount, e.g. "1,285.75"
        val items: List<String>,
        val cancelled: Boolean
    ) {
        /** Numeric total, tolerant of thousands separators. */
        val amount: Double get() = total.replace(",", "").toDoubleOrNull() ?: 0.0
    }

    /** All bills, newest first, each with its customer name and item names. */
    fun getAll(): List<Bill> {
        val itemsByBill = loadItemsByBill()
        val list = mutableListOf<Bill>()

        val sql = """
            SELECT b.receipt_no, b.bill_number, b.bill_date, b.bill_date_time,
                   b.net_amount, b.bill_status, c.customer_name
            FROM td_bills b
            LEFT JOIN md_customers c ON c.id = b.customer_id
            ORDER BY b.receipt_no DESC
        """.trimIndent()

        helper.readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                val receiptNo = c.getLong(0)
                val billNumber = c.getString(1)?.takeIf { it.isNotBlank() } ?: receiptNo.toString()
                val rawDate = c.getString(2)
                val rawDateTime = c.getString(3)
                val net = c.getDouble(4)
                val status = c.getString(5).orEmpty()
                val customer = c.getString(6)?.takeIf { it.isNotBlank() } ?: "Walk-in"

                list.add(
                    Bill(
                        receiptNo = receiptNo,
                        billNo = billNumber,
                        name = customer,
                        date = formatDate(rawDateTime, rawDate),
                        time = formatTime(rawDateTime),
                        total = String.format(Locale.US, "%,.2f", net),
                        items = itemsByBill[receiptNo].orEmpty(),
                        cancelled = status.equals("CANCELLED", ignoreCase = true)
                    )
                )
            }
        }
        return list
    }

    /** Distinct product names across all bills (for the item filter suggestions). */
    fun allItems(): List<String> =
        loadItemsByBill().values.flatten().distinct().sorted()

    /** Maps each bill's receipt_no to the list of its product names. */
    private fun loadItemsByBill(): Map<Long, MutableList<String>> {
        val map = hashMapOf<Long, MutableList<String>>()
        val sql = """
            SELECT bi.bill_id, p.product_name
            FROM td_bill_items bi
            LEFT JOIN md_products p ON p.id = bi.product_id
        """.trimIndent()

        helper.readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                if (c.isNull(0)) continue
                val billId = c.getLong(0)
                val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                map.getOrPut(billId) { mutableListOf() }.add(name)
            }
        }
        return map
    }

    private val dbDateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val dbDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val outDate = SimpleDateFormat("dd-MM-yyyy", Locale.US)
    private val outTime = SimpleDateFormat("HH:mm", Locale.US)

    private fun formatDate(dateTime: String?, date: String?): String {
        parse(dbDateTime, dateTime)?.let { return outDate.format(it) }
        parse(dbDate, date)?.let { return outDate.format(it) }
        return date.orEmpty()
    }

    private fun formatTime(dateTime: String?): String =
        parse(dbDateTime, dateTime)?.let { outTime.format(it) } ?: ""

    private fun parse(fmt: SimpleDateFormat, text: String?) =
        try { if (text.isNullOrBlank()) null else fmt.parse(text) } catch (_: Exception) { null }
}
