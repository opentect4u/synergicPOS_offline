package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding

/**
 * Groups the restaurant bills of a period by UDF - "<section>-<table>", e.g.
 * "AC-1", "BAR-2" - for the UDF-Wise Report. Each row is one table/section: how
 * many bills it raised and their tax, discount and bill totals.
 *
 * The section comes off the bill itself, which records the NAME it was billed in
 * (the shop's own short section name, not a number nothing but this table would
 * mean anything by). A bill raised before that was kept falls back to the old
 * reading - the table master ([DatabaseHelper.Tables.MD_TABLE_UNIT] by table_code,
 * joined to [DatabaseHelper.Tables.MD_SECTION] for its name) - so historical rows
 * still get a name rather than a bare table number. That fallback can only guess
 * when a table number is used in more than one section, which is why the bill now
 * carries its own name directly.
 */
class UdfWiseReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)

    /** One UDF (section-table) group. */
    data class Row(
        /** "<section>-<table>", e.g. "AC-1" - the table number alone where no
         *  section could be resolved for it at all. */
        val udf: String,
        val bills: Int,
        val cgst: Double = 0.0,
        val sgst: Double = 0.0,
        /** Zero on a shop that never sells inter-state. */
        val igst: Double = 0.0,
        /** Zero on a GST-only shop. */
        val vat: Double = 0.0,
        val discount: Double,
        /** The section's own flat charge. */
        val serviceCharge: Double = 0.0,
        /** The shop's other extra charges, Parcel Charge excluded - see
         *  [parcelCharge]. */
        val otherCharges: Double = 0.0,
        /** Parcel Charge's own share, broken out from [otherCharges] - see
         *  ChargeDao.Kind.PARCEL. Zero on a bill sold before this was tracked. */
        val parcelCharge: Double = 0.0,
        val billAmount: Double
    ) {
        /** Everything this group charged in tax, however the regime split it. */
        val taxAmount: Double get() = cgst + sgst + igst + vat
    }

    data class Report(
        val fromDate: String,
        val toDate: String,
        val rows: List<Row>,
        val totalBills: Int,
        val totalCgst: Double = 0.0,
        val totalSgst: Double = 0.0,
        val totalIgst: Double = 0.0,
        val totalVat: Double = 0.0,
        val totalDiscount: Double,
        val totalServiceCharge: Double = 0.0,
        val totalOtherCharges: Double = 0.0,
        val totalParcelCharge: Double = 0.0,
        val totalBillAmount: Double
    ) {
        /** Every tax the range charged, however the regimes split it. */
        val totalTax: Double get() = BillRounding.toPaise(totalCgst + totalSgst + totalIgst + totalVat)
        /** The IGST column earns its place only where a bill in the range carried it. */
        val hasIgst: Boolean get() = rows.any { it.igst > 0.0 }
        /** The VAT column earns its place only where a bill in the range carried it. */
        val hasVat: Boolean get() = rows.any { it.vat > 0.0 }
        val isEmpty: Boolean get() = rows.isEmpty()
    }

    /** Every table/section that billed between [from] and [to] (inclusive). */
    fun between(from: String, to: String): Report {
        val rows = mutableListOf<Row>()
        helper.readableDatabase.rawQuery(
            """
            SELECT b.table_number,
                   COALESCE(
                     NULLIF(TRIM(b.table_section), ''),
                     (SELECT s.section_name FROM ${DatabaseHelper.Tables.MD_TABLE_UNIT} tu
                      JOIN ${DatabaseHelper.Tables.MD_SECTION} s ON s.id = tu.section_id
                      WHERE tu.table_code = b.table_number LIMIT 1)
                   ) AS section_name,
                   COUNT(*) AS bills,
                   SUM(COALESCE(b.tot_cgst_amount,0)) AS cgst,
                   SUM(COALESCE(b.tot_sgst_amount,0)) AS sgst,
                   SUM(COALESCE(b.tot_igst_amount,0)) AS igst,
                   SUM(COALESCE(b.tot_vat_amount,0)) AS vat,
                   SUM(COALESCE(b.tot_discount_amount,0)) AS disc,
                   SUM(COALESCE(b.service_charge_amount,0)) AS svc,
                   SUM(COALESCE(b.tot_other_charges_amount,0)) AS other,
                   SUM(COALESCE(b.parcel_charge_amount,0)) AS parcel,
                   SUM(COALESCE(b.net_amount,0)) AS billamt
            FROM ${DatabaseHelper.Tables.TD_BILLS} b
            WHERE substr(b.bill_date, 1, 10) BETWEEN ? AND ?
              AND b.table_number IS NOT NULL AND TRIM(b.table_number) <> ''
              AND COALESCE(b.bill_status, '') <> 'CANCELLED'
            GROUP BY b.table_number, section_name
            ORDER BY section_name, CAST(b.table_number AS INTEGER), b.table_number
            """.trimIndent(),
            arrayOf(from, to)
        ).use { c ->
            while (c.moveToNext()) {
                val table = c.getString(0).orEmpty()
                val section = c.getString(1)?.trim()?.takeIf { it.isNotEmpty() }
                rows.add(
                    Row(
                        udf = if (section != null) "$section-$table" else table,
                        bills = c.getInt(2),
                        cgst = BillRounding.toPaise(c.getDouble(3)),
                        sgst = BillRounding.toPaise(c.getDouble(4)),
                        igst = BillRounding.toPaise(c.getDouble(5)),
                        vat = BillRounding.toPaise(c.getDouble(6)),
                        discount = BillRounding.toPaise(c.getDouble(7)),
                        serviceCharge = BillRounding.toPaise(c.getDouble(8)),
                        otherCharges = (BillRounding.toPaise(c.getDouble(9)) - BillRounding.toPaise(c.getDouble(10))).coerceAtLeast(0.0),
                        parcelCharge = BillRounding.toPaise(c.getDouble(10)),
                        billAmount = BillRounding.toPaise(c.getDouble(11))
                    )
                )
            }
        }
        return Report(
            fromDate = from,
            toDate = to,
            rows = rows,
            totalBills = rows.sumOf { it.bills },
            totalCgst = BillRounding.toPaise(rows.sumOf { it.cgst }),
            totalSgst = BillRounding.toPaise(rows.sumOf { it.sgst }),
            totalIgst = BillRounding.toPaise(rows.sumOf { it.igst }),
            totalVat = BillRounding.toPaise(rows.sumOf { it.vat }),
            totalDiscount = BillRounding.toPaise(rows.sumOf { it.discount }),
            totalServiceCharge = BillRounding.toPaise(rows.sumOf { it.serviceCharge }),
            totalOtherCharges = BillRounding.toPaise(rows.sumOf { it.otherCharges }),
            totalParcelCharge = BillRounding.toPaise(rows.sumOf { it.parcelCharge }),
            totalBillAmount = BillRounding.toPaise(rows.sumOf { it.billAmount })
        )
    }
}
