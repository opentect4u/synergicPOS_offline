package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding

/**
 * The restaurant bill ITEMS of a period, grouped by UDF (the table they were sold on)
 * and, within a UDF, by product - each with its total quantity and amount. Powers the
 * UDF-Wise Item Report. The product name comes from the master (td_bill_items keeps
 * only the id); the amount is the line total the item was billed at.
 *
 * A table number repeats in every section, so the group is the table AND the section
 * the bill records - two rooms' table 5 are two groups, not one heap. A bill with no
 * section (grocery, take-away, or one raised before the bill carried it) groups by
 * its number alone, as it always did.
 */
class UdfWiseItemReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** One product line within a UDF group. */
    data class Item(val name: String, val qty: Double, val amount: Double)

    /** One UDF (table) and everything sold on it. */
    data class Group(
        /** The UDF - the table it was billed on and its section, e.g. "5 (AC)". */
        val udf: String,
        val items: List<Item>,
        val qty: Double,
        val amount: Double
    )

    data class Report(
        val fromDate: String,
        val toDate: String,
        val groups: List<Group>,
        val totalQty: Double,
        val totalAmount: Double
    ) {
        val isEmpty: Boolean get() = groups.isEmpty()
    }

    /** Every item sold, grouped by table then product, between [from] and [to]. */
    fun between(from: String, to: String): Report {
        // (table, name) -> (qty, amount), in query order (table asc, name asc).
        data class Flat(val udf: String, val name: String, val qty: Double, val amount: Double)
        val flat = mutableListOf<Flat>()
        helper.readableDatabase.rawQuery(
            """
            SELECT b.table_number,
                   COALESCE(b.table_section, '') AS section,
                   COALESCE(p.product_name, 'Item #' || bi.product_id) AS name,
                   SUM(COALESCE(bi.quantity, 0)) AS qty,
                   SUM(COALESCE(bi.item_total, bi.item_subtotal, 0)) AS amount
            FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} bi
            JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = bi.bill_id
            LEFT JOIN ${DatabaseHelper.Tables.MD_PRODUCTS} p ON p.id = bi.product_id
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND b.table_number IS NOT NULL AND TRIM(b.table_number) <> ''
              AND COALESCE(b.bill_status, '') <> 'CANCELLED'
            GROUP BY b.table_number, section, name
            ORDER BY CAST(b.table_number AS INTEGER), b.table_number, section, name
            """.trimIndent(),
            arrayOf(from, to)
        ).use { c ->
            while (c.moveToNext()) {
                val table = c.getString(0).orEmpty()
                val section = c.getString(1).orEmpty()
                flat.add(
                    Flat(
                        udf = if (section.isBlank()) table else "$table ($section)",
                        name = c.getString(2).orEmpty().uppercase(),
                        qty = c.getDouble(3),
                        amount = BillRounding.toPaise(c.getDouble(4))
                    )
                )
            }
        }

        val groups = flat.groupBy { it.udf }.map { (udf, rows) ->
            Group(
                udf = udf,
                items = rows.map { Item(it.name, it.qty, it.amount) },
                qty = rows.sumOf { it.qty },
                amount = BillRounding.toPaise(rows.sumOf { it.amount })
            )
        }
        return Report(
            fromDate = from,
            toDate = to,
            groups = groups,
            totalQty = groups.sumOf { it.qty },
            totalAmount = BillRounding.toPaise(groups.sumOf { it.amount })
        )
    }
}
