package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.VoidBillReportDao
import com.example.synergic_pos_offline.utils.PeriodReportRenderer

/**
 * Void Bill Report - the bills that were taken out of the takings.
 *
 * Deleted bills and voided ones together: neither counts anywhere else on this till,
 * so without this report they would have left no trace at all. See
 * [VoidBillReportDao].
 *
 * Everything a date-range report does comes from [PeriodReportFragment]; what is here
 * is only what makes this a void report.
 */
class VoidBillReportFragment : PeriodReportFragment<VoidBillReportDao.Report>() {

    override val screenTitle = "Void Bill Report"

    override val rowNoun = "bills"

    private val dao: VoidBillReportDao by lazy { VoidBillReportDao(requireContext()) }

    override fun load(fromDate: String, toDate: String): VoidBillReportDao.Report =
        dao.between(fromDate, toDate)

    override fun isEmpty(report: VoidBillReportDao.Report): Boolean = report.isEmpty

    override fun headline(report: VoidBillReportDao.Report): String =
        "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}   •   " +
            "${report.billCount} bill(s)"

    override fun columnsFor(report: VoidBillReportDao.Report): List<Column> = listOf(
        Column("BILL", 130, alignEnd = true),
        Column("AMOUNT", 130, alignEnd = true),
        Column("TAX", 120, alignEnd = true),
        Column("TOTAL", 140, alignEnd = true)
    )

    override fun rowsOf(report: VoidBillReportDao.Report): List<List<String>> =
        report.lines.map { line ->
            listOf(line.billNumber, money(line.amount), money(line.tax), money(line.total))
        }

    override fun summaryOf(report: VoidBillReportDao.Report): List<Pair<String, String>> =
        buildList {
            add("Void Bills" to report.billCount.toString())
            add("Total Amount" to money(report.totalAmount))
            // Each tax its own line rather than one blended figure - a GST return
            // is filed against SGST and CGST separately, and IGST/VAT earn their
            // place only where a voided bill actually carried one.
            add("Total SGST" to money(report.totalSgst))
            add("Total CGST" to money(report.totalCgst))
            if (report.hasIgst) add("Total IGST" to money(report.totalIgst))
            if (report.hasVat) add("Total VAT" to money(report.totalVat))
            // Shown only where a voided bill actually carried one - a shop that
            // never charges Service or an Extra Charge should not read a zero row
            // saying so.
            if (report.totalServiceCharge > 0.005) add("Service Charge" to money(report.totalServiceCharge))
            if (report.totalOtherCharges > 0.005) add("Extra Charges" to money(report.totalOtherCharges))
            if (report.totalParcelCharge > 0.005) add("Parcel Charge" to money(report.totalParcelCharge))
        }

    /** The one figure the report is read for: what came out of the day's takings. */
    override fun totalOf(report: VoidBillReportDao.Report): Pair<String, String> =
        "Total" to money(report.grandTotal)

    /**
     * The printed slip, in the format these tills have always printed it.
     *
     * Four columns of figures with no name among them, so the bill number is set to
     * the right with the rest - it reads as a figure, and a column that started at
     * the left would break the run of them.
     */
    override fun printContent(report: VoidBillReportDao.Report): PeriodReportRenderer.Content =
        PeriodReportRenderer.Content(
            title = "Void Bill Report",
            period = "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}",
            subtitle = "${report.billCount} bill(s)",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = "F.DT:${shortDate(report.fromDate)}" to "TO.DT:${shortDate(report.toDate)}",
            columns = listOf("BILL", "AMOUNT", "TAX", "TOTAL"),
            evenColumns = true,
            alignFirstColumnEnd = true,
            rows = rowsOf(report),
            summary = buildList {
                add("SGST :" to money(report.totalSgst))
                add("CGST :" to money(report.totalCgst))
                if (report.hasIgst) add("IGST :" to money(report.totalIgst))
                if (report.hasVat) add("VAT :" to money(report.totalVat))
                if (report.totalServiceCharge > 0.005) add("SERVICE CHG :" to money(report.totalServiceCharge))
                if (report.totalOtherCharges > 0.005) add("EXTRA CHGS :" to money(report.totalOtherCharges))
                if (report.totalParcelCharge > 0.005) add("PARCEL CHG :" to money(report.totalParcelCharge))
                // The one line the slip has always closed on, set across the whole
                // width rather than repeated per column.
                add("TOTAL :" to money(report.grandTotal))
            },
            emptyNote = "No bill was voided in this period."
        )

    /** "2026-08-12" as "12-08-26" - how the F.DT / TO.DT line has always read. */
    private fun shortDate(date: String): String =
        pretty(date).let { it.take(6) + it.takeLast(2) }

    /**
     * An empty report here is good news, not an absence of data, and says so - "no
     * results" would read as a report that failed to run.
     */
    override fun emptyMessage(
        report: VoidBillReportDao.Report,
        fromDate: String,
        toDate: String
    ): Pair<String, String> = "No void bills in this period" to
        "No bill was voided or deleted between ${pretty(fromDate)} and ${pretty(toDate)}."
}
