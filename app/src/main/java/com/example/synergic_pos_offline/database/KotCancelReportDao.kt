package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding

/**
 * The KOT items cancelled in a period, for the KOT Cancel Report. A cancellation is an
 * item pulled from a KOT that had already been sent to the kitchen - recorded as a
 * [DatabaseHelper.Tables.TD_KOT_ITEMS] row with status 'CANCELLED' (the KOT header
 * itself is never cancelled, only OPEN/CLOSED). Each row here is one such item: what it
 * was, which KOT and table it came off, and the cancelled quantity.
 */
class KotCancelReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** One cancelled KOT item. */
    data class Row(
        val item: String,
        val kotNumber: String,
        val table: String,
        val qty: Double
    )

    data class Report(
        val fromDate: String,
        val toDate: String,
        val rows: List<Row>,
        val totalQty: Double
    ) {
        val isEmpty: Boolean get() = rows.isEmpty()
    }

    /** Every KOT item cancelled between [from] and [to] (inclusive), oldest first. */
    fun between(from: String, to: String): Report {
        val rows = mutableListOf<Row>()
        helper.readableDatabase.rawQuery(
            """
            SELECT COALESCE(p.product_name, 'Item #' || ki.product_id) AS item,
                   COALESCE(k.kot_number, CAST(k.id AS TEXT)) AS kno,
                   COALESCE(k.table_number, '-') AS tbl,
                   COALESCE(ki.quantity, 0) AS qty
            FROM ${DatabaseHelper.Tables.TD_KOT_ITEMS} ki
            JOIN ${DatabaseHelper.Tables.TD_KOT} k ON k.id = ki.kot_id
            LEFT JOIN ${DatabaseHelper.Tables.MD_PRODUCTS} p ON p.id = ki.product_id
            WHERE ki.status = 'CANCELLED'
              AND substr(ki.created_at, 1, 10) BETWEEN ? AND ?
            ORDER BY ki.created_at ASC, ki.id ASC
            """.trimIndent(),
            arrayOf(from, to)
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    Row(
                        item = c.getString(0).orEmpty().uppercase(),
                        kotNumber = c.getString(1).orEmpty(),
                        table = c.getString(2).orEmpty(),
                        qty = c.getDouble(3)
                    )
                )
            }
        }
        return Report(from, to, rows, BillRounding.toPaise(rows.sumOf { it.qty }))
    }
}
