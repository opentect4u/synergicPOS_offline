package com.example.synergic_pos_offline.database

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Read access to the bill history stored in [DatabaseHelper.Tables.TD_BILLS],
 * joined with customers (name) and bill items (product names). Powers the
 * Bill History screen with real, persisted data.
 */
class BillDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

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
