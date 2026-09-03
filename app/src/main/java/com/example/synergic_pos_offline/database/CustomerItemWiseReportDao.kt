package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding

/**
 * One customer's items over a period, for the Customer Item-Wise Report: every
 * product they bought with its quantity, amount and tax (SGST / CGST), plus the
 * period totals. The product name comes from the master; the figures are summed
 * across all that customer's (non-cancelled) bills in the range.
 */
class CustomerItemWiseReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** A customer to pick from. */
    data class Customer(val id: Long, val name: String, val phone: String)

    /** One product line for the customer. */
    data class Item(
        val name: String,
        val qty: Double,
        val amount: Double,
        val sgst: Double,
        val cgst: Double
    )

    data class Report(
        val customerId: Long,
        val customerName: String,
        val fromDate: String,
        val toDate: String,
        val items: List<Item>,
        val totalQty: Double,
        val totalSgst: Double,
        val totalCgst: Double,
        val totalAmount: Double,
        /**
         * This customer's Service Charge and other extra charges (Parcel Charge
         * among them) over the period, read off `td_bills` rather than folded
         * into the item lines above - see [TaxReportDao.billCharges], which this
         * calls with the same customer scoped in.
         */
        val charges: TaxReportDao.BillCharges = TaxReportDao.BillCharges(0.0, 0.0)
    ) {
        val totalServiceCharge: Double get() = charges.service
        val totalOtherCharges: Double get() = charges.other

        val isEmpty: Boolean get() = items.isEmpty()
    }

    /** Every customer, for the report's picker. */
    fun customers(): List<Customer> {
        val out = mutableListOf<Customer>()
        helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_CUSTOMERS,
            arrayOf("id", "customer_name", "phone_number"),
            null, null, null, null, "customer_name COLLATE NOCASE ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Customer(
                        id = c.getLong(0),
                        name = c.getString(1)?.takeIf { it.isNotBlank() } ?: "Customer #${c.getLong(0)}",
                        phone = c.getString(2).orEmpty()
                    )
                )
            }
        }
        return out
    }

    /** The [customerId]'s items billed between [from] and [to]. */
    fun between(customerId: Long, from: String, to: String): Report {
        val name = helper.readableDatabase.query(
            DatabaseHelper.Tables.MD_CUSTOMERS, arrayOf("customer_name"),
            "id = ?", arrayOf(customerId.toString()), null, null, null, "1"
        ).use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" }

        val items = mutableListOf<Item>()
        helper.readableDatabase.rawQuery(
            """
            SELECT COALESCE(p.product_name, 'Item #' || bi.product_id) AS name,
                   SUM(COALESCE(bi.quantity, 0)) AS qty,
                   SUM(COALESCE(bi.item_total, bi.item_subtotal, 0)) AS amount,
                   SUM(COALESCE(bi.sgst_amount, 0)) AS sgst,
                   SUM(COALESCE(bi.cgst_amount, 0)) AS cgst
            FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} bi
            JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = bi.bill_id
            LEFT JOIN ${DatabaseHelper.Tables.MD_PRODUCTS} p ON p.id = bi.product_id
            WHERE b.customer_id = ?
              AND substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND COALESCE(b.bill_status, '') <> 'CANCELLED'
            GROUP BY name
            ORDER BY name COLLATE NOCASE
            """.trimIndent(),
            arrayOf(customerId.toString(), from, to)
        ).use { c ->
            while (c.moveToNext()) {
                items.add(
                    Item(
                        name = c.getString(0).orEmpty().uppercase(),
                        qty = c.getDouble(1),
                        amount = BillRounding.toPaise(c.getDouble(2)),
                        sgst = BillRounding.toPaise(c.getDouble(3)),
                        cgst = BillRounding.toPaise(c.getDouble(4))
                    )
                )
            }
        }
        return Report(
            customerId = customerId,
            customerName = name.uppercase(),
            fromDate = from,
            toDate = to,
            items = items,
            totalQty = items.sumOf { it.qty },
            totalSgst = BillRounding.toPaise(items.sumOf { it.sgst }),
            totalCgst = BillRounding.toPaise(items.sumOf { it.cgst }),
            totalAmount = BillRounding.toPaise(items.sumOf { it.amount }),
            charges = TaxReportDao.billCharges(helper.readableDatabase, from, to, store = null, customerId = customerId)
        )
    }
}
