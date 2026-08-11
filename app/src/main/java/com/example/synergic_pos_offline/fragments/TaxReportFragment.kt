package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.TaxReportDao
import com.example.synergic_pos_offline.utils.PeriodReportRenderer

/**
 * Tax Report - what tax was collected over a period, bill by bill, with the period's
 * CGST, SGST and IGST totalled under it.
 *
 * The screen behind the Tax Report tile, and the report a return is filed from: the
 * summary is the answer, and the rows are there so the answer can be traced back to
 * the sales it came from.
 *
 * Everything a date-range report does - the pickers, the sideways-scrolling table,
 * printing what was generated rather than re-reading the period - comes from
 * [PeriodReportFragment]. What is here is only what makes this report a tax report.
 */
class TaxReportFragment : PeriodReportFragment<TaxReportDao.Report>() {

    override val screenTitle = "Tax Report"

    override val rowNoun = "bills"

    private val dao: TaxReportDao by lazy { TaxReportDao(requireContext()) }

    override fun load(fromDate: String, toDate: String): TaxReportDao.Report =
        dao.between(fromDate, toDate)

    override fun isEmpty(report: TaxReportDao.Report): Boolean = report.isEmpty

    override fun headline(report: TaxReportDao.Report): String =
        "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}   •   ${report.billCount} bill(s)"

    /**
     * BILL NO, DATE, the taxes, what they came to, and the bill they were charged on.
     *
     * VAT and IGST are only columns where the period holds them. A GST-only shop
     * that never sells inter-state would otherwise read two stripes of 0.00 across
     * the whole report, and the two columns that matter would be pushed off the
     * right-hand edge to make room for them.
     */
    override fun columnsFor(report: TaxReportDao.Report): List<Column> = buildList {
        add(Column("BILL NO", 120, alignEnd = false))
        add(Column("DATE", 100, alignEnd = false))
        add(Column("CGST", 90, alignEnd = true))
        add(Column("SGST", 90, alignEnd = true))
        if (report.hasIgst) add(Column("IGST", 90, alignEnd = true))
        if (report.hasVat) add(Column("VAT", 90, alignEnd = true))
        add(Column("TOTAL TAX", 110, alignEnd = true))
        add(Column("BILL AMOUNT", 120, alignEnd = true))
    }

    override fun rowsOf(report: TaxReportDao.Report): List<List<String>> =
        report.lines.map { line ->
            buildList {
                add(line.billNumber)
                add(pretty(line.date))
                // A tax that did not apply to this bill reads as a dash rather than
                // 0.00. Zero says the tax was charged and came to nothing; a dash
                // says it was never in play, which is what a VAT bill's CGST column
                // actually means.
                add(if (line.isVat) "-" else money(line.cgst))
                add(if (line.isVat) "-" else money(line.sgst))
                if (report.hasIgst) add(if (line.isVat) "-" else money(line.igst))
                if (report.hasVat) add(if (line.isVat) money(line.vat) else "-")
                add(money(line.totalTax))
                add(money(line.netAmount))
            }
        }

    override fun summaryOf(report: TaxReportDao.Report): List<Pair<String, String>> = buildList {
        add("Total Bills" to report.billCount.toString())
        add("Total CGST" to money(report.totalCgst))
        add("Total SGST" to money(report.totalSgst))
        add("Total IGST" to money(report.totalIgst))
        // Only where the period actually holds VAT bills - a GST-only shop is not
        // reading a line of zeroes, and a shop with VAT in the period does not have
        // that tax left off the totals entirely.
        if (report.hasVat) add("Total VAT" to money(report.totalVat))
        add("Total Bill Amount" to money(report.totalAmount))
    }

    /** The one figure the report is read for. */
    override fun totalOf(report: TaxReportDao.Report): Pair<String, String> =
        "Total Tax Collected" to money(report.totalTax)

    /**
     * The printed report.
     *
     * A roll fits about four columns, so the two taxes that are always charged go on
     * the bill's own line and the rest - IGST, VAT where there is any - go on a
     * second line under it. Squeezing all of them across the paper would set the type
     * so small that the report could not be read, which is the only thing a printed
     * report is for.
     */
    override fun printContent(report: TaxReportDao.Report): PeriodReportRenderer.Content =
        PeriodReportRenderer.Content(
            title = "Tax Report",
            period = "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}",
            subtitle = "${report.billCount} bill(s)",
            columns = listOf("BILL NO", "CGST", "SGST", "TAX"),
            rows = report.lines.map { line ->
                listOf(
                    line.billNumber,
                    if (line.isVat) "-" else money(line.cgst),
                    if (line.isVat) "-" else money(line.sgst),
                    money(line.totalTax)
                )
            },
            details = report.lines.map { line ->
                buildString {
                    append("  ")
                    append(pretty(line.date))
                    if (report.hasIgst) append("  IGST ${if (line.isVat) "-" else money(line.igst)}")
                    if (report.hasVat) append("  VAT ${if (line.isVat) money(line.vat) else "-"}")
                    append("  AMT ${money(line.netAmount)}")
                }
            },
            summary = summaryOf(report).map { (label, value) -> label.uppercase() to value },
            total = totalOf(report).let { (label, value) -> label.uppercase() to value },
            emptyNote = "No bills in this period."
        )

    override fun emptyMessage(
        report: TaxReportDao.Report,
        fromDate: String,
        toDate: String
    ): Pair<String, String> = "No bills in this period" to
        "Nothing was billed between ${pretty(fromDate)} and ${pretty(toDate)}, so no tax was charged."
}
