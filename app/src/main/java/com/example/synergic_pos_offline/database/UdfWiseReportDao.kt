package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding

/**
 * Groups the restaurant bills of a period by UDF - "<table>-<section id>" - for the
 * UDF-Wise Report. Each row is one table/section: how many bills it raised and their
 * tax, discount and bill totals. A bill's section is resolved from the table master
 * ([DatabaseHelper.Tables.MD_TABLE_UNIT] by table_code), since the bill itself only
 * records the table it was on.
 */
class UdfWiseReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** One UDF (table-section) group. */
    data class Row(
        /** "<table>-<section id>", e.g. "5-1". */
        val udf: String,
        val bills: Int,
        val taxAmount: Double,
        val discount: Double,
        val billAmount: Double
    )

    data class Report(
        val fromDate: String,
        val toDate: String,
        val rows: List<Row>,
        val totalBills: Int,
        val totalTax: Double,
        val totalDiscount: Double,
        val totalBillAmount: Double
    ) {
        val isEmpty: Boolean get() = rows.isEmpty()
    }

    /** Every table/section that billed between [from] and [to] (inclusive). */
    fun between(from: String, to: String): Report {
        val rows = mutableListOf<Row>()
        helper.readableDatabase.rawQuery(
            """
            SELECT b.table_number,
                   (SELECT tu.section_id FROM ${DatabaseHelper.Tables.MD_TABLE_UNIT} tu
                    WHERE tu.table_code = b.table_number LIMIT 1) AS section_id,
                   COUNT(*) AS bills,
                   SUM(COALESCE(b.tot_cgst_amount,0) + COALESCE(b.tot_sgst_amount,0)
                       + COALESCE(b.tot_igst_amount,0) + COALESCE(b.tot_vat_amount,0)) AS tax,
                   SUM(COALESCE(b.tot_discount_amount,0)) AS disc,
                   SUM(COALESCE(b.net_amount,0)) AS billamt
            FROM ${DatabaseHelper.Tables.TD_BILLS} b
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND b.table_number IS NOT NULL AND TRIM(b.table_number) <> ''
              AND COALESCE(b.bill_status, '') <> 'CANCELLED'
            GROUP BY b.table_number, section_id
            ORDER BY CAST(b.table_number AS INTEGER), b.table_number
            """.trimIndent(),
            arrayOf(from, to)
        ).use { c ->
            while (c.moveToNext()) {
                val table = c.getString(0).orEmpty()
                val section = if (c.isNull(1)) "0" else c.getInt(1).toString()
                rows.add(
                    Row(
                        udf = "$table-$section",
                        bills = c.getInt(2),
                        taxAmount = BillRounding.toPaise(c.getDouble(3)),
                        discount = BillRounding.toPaise(c.getDouble(4)),
                        billAmount = BillRounding.toPaise(c.getDouble(5))
                    )
                )
            }
        }
        return Report(
            fromDate = from,
            toDate = to,
            rows = rows,
            totalBills = rows.sumOf { it.bills },
            totalTax = BillRounding.toPaise(rows.sumOf { it.taxAmount }),
            totalDiscount = BillRounding.toPaise(rows.sumOf { it.discount }),
            totalBillAmount = BillRounding.toPaise(rows.sumOf { it.billAmount })
        )
    }
}
