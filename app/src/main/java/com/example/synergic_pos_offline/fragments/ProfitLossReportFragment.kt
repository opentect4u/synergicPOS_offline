package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.ProfitLossReportDao
import com.example.synergic_pos_offline.database.StockDao
import com.example.synergic_pos_offline.utils.PeriodReportRenderer

/**
 * Profit-Loss Report - what each item made over a period.
 *
 * Profit here is the gap between the item's listed rate and what it was actually
 * charged at, times the quantity sold - see [ProfitLossReportDao], which explains
 * what that measures and what it does not.
 *
 * Everything a date-range report does comes from [PeriodReportFragment]; what is here
 * is only what makes this a profit report.
 */
class ProfitLossReportFragment : PeriodReportFragment<ProfitLossReportDao.Report>() {

    override val screenTitle = "Profit & Loss Report"

    override val rowNoun = "items"

    private val dao: ProfitLossReportDao by lazy { ProfitLossReportDao(requireContext()) }

    override fun load(fromDate: String, toDate: String): ProfitLossReportDao.Report =
        dao.between(fromDate, toDate)

    override fun isEmpty(report: ProfitLossReportDao.Report): Boolean = report.isEmpty

    override fun headline(report: ProfitLossReportDao.Report): String =
        "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}   •   " +
            "${report.itemCount} item(s)"

    override fun columnsFor(report: ProfitLossReportDao.Report): List<Column> = listOf(
        Column("ITEM NAME", 280, alignEnd = false),
        Column("QTY", 120, alignEnd = true),
        Column("PROFIT", 150, alignEnd = true)
    )

    override fun rowsOf(report: ProfitLossReportDao.Report): List<List<String>> =
        report.lines.map { line ->
            listOf(line.name, money(line.quantity), money(line.profit))
        }

    override fun summaryOf(report: ProfitLossReportDao.Report): List<Pair<String, String>> =
        listOf(
            "Total Items" to report.itemCount.toString(),
            "Total Qty" to money(report.totalQuantity)
        )

    /** The one figure the report is read for. */
    override fun totalOf(report: ProfitLossReportDao.Report): Pair<String, String> =
        "Total Profit" to money(report.totalProfit)

    /**
     * The printed slip, in the format these tills have always printed it: the name
     * across the left, the two figures at the right, and a closing TOTAL row in the
     * same columns so each lands under what it totals.
     */
    override fun printContent(report: ProfitLossReportDao.Report): PeriodReportRenderer.Content =
        PeriodReportRenderer.Content(
            title = "Profit-Loss Report",
            period = "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}",
            subtitle = "${report.itemCount} item(s)",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = "F.DT:${shortDate(report.fromDate)}" to "TO.DT:${shortDate(report.toDate)}",
            // The item name, put into the print language as it is on a bill.
            nameColumns = setOf(0),
            columns = listOf("ITEM NAME", "QTY", "PROFIT"),
            rows = rowsOf(report),
            footerRow = listOf(
                "TOTAL :",
                money(report.totalQuantity),
                money(report.totalProfit)
            ),
            summary = emptyList(),
            emptyNote = "Nothing was sold in this period."
        )

    /** "2026-08-12" as "12-08-26" - how the F.DT / TO.DT line has always read. */
    private fun shortDate(date: String): String =
        pretty(date).let { it.take(6) + it.takeLast(2) }

    override fun emptyMessage(
        report: ProfitLossReportDao.Report,
        fromDate: String,
        toDate: String
    ): Pair<String, String> = "Nothing sold in this period" to
        "No items were billed between ${pretty(fromDate)} and ${pretty(toDate)}."
}
