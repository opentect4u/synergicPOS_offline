package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.StockDao
import com.example.synergic_pos_offline.database.StockReportDao
import com.example.synergic_pos_offline.utils.PeriodReportRenderer

/**
 * Stock Report - what came in, what went out, and what is left.
 *
 * A date range in; a line per item out, carrying what was stocked in over the period,
 * what was sold, and what is on the shelf now. See [StockReportDao] for where each of
 * the three comes from, and why only two of them belong to the period.
 *
 * Everything a date-range report does comes from [PeriodReportFragment]; what is here
 * is only what makes this a stock report.
 */
class StockReportFragment : PeriodReportFragment<StockReportDao.Report>() {

    override val screenTitle = "Stock Report"

    override val rowNoun = "items"

    private val dao: StockReportDao by lazy { StockReportDao(requireContext()) }

    override fun load(fromDate: String, toDate: String): StockReportDao.Report =
        dao.between(fromDate, toDate)

    override fun isEmpty(report: StockReportDao.Report): Boolean = report.isEmpty

    override fun headline(report: StockReportDao.Report): String =
        "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}   •   " +
            "${report.itemCount} item(s)   •   ${report.outOfStock} out of stock"

    override fun columnsFor(report: StockReportDao.Report): List<Column> = listOf(
        Column("SL NO", 70, alignEnd = false),
        Column("ITEM NAME", 260, alignEnd = false),
        Column("PUR.STOCK", 120, alignEnd = true),
        Column("SLD.STK.", 120, alignEnd = true),
        Column("C.STOCK", 120, alignEnd = true)
    )

    override fun rowsOf(report: StockReportDao.Report): List<List<String>> =
        report.lines.map { line ->
            listOf(
                line.serial.toString(),
                line.name,
                StockDao.trim(line.purchased),
                StockDao.trim(line.sold),
                StockDao.trim(line.current)
            )
        }

    override fun summaryOf(report: StockReportDao.Report): List<Pair<String, String>> =
        listOf(
            "Total Items" to report.itemCount.toString(),
            "Out Of Stock" to report.outOfStock.toString(),
            "Total Purchased" to StockDao.trim(report.totalPurchased),
            "Total Sold" to StockDao.trim(report.totalSold)
        )

    /** The one figure the report is read for: what is on the shelf now. */
    override fun totalOf(report: StockReportDao.Report): Pair<String, String> =
        "Total In Stock" to StockDao.trim(report.totalCurrent)

    /**
     * The printed slip, in the format these tills have always printed it.
     *
     * The item's name has a line to itself and its three figures the line beneath, so
     * a name long enough to be recognised is never cut short to make room for them.
     * A rule between items, or the figures of one and the name of the next would read
     * as a single block.
     */
    override fun printContent(report: StockReportDao.Report): PeriodReportRenderer.Content =
        PeriodReportRenderer.Content(
            title = "Stock Report",
            period = "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}",
            subtitle = "${report.itemCount} item(s)",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = "F.DT:${shortDate(report.fromDate)}" to "TO.DT:${shortDate(report.toDate)}",
            // The item name, put into the print language as it is on a bill.
            nameColumns = setOf(0),
            columns = listOf("ITEM NAME"),
            rows = report.lines.map { listOf(it.name) },
            // The leading blank keeps the three figures under their headings rather
            // than pushing the first one out to the left margin.
            columns2 = listOf("", "PUR.STOCK", "SLD.STK.", "C.STOCK"),
            rows2 = report.lines.map {
                listOf("", StockDao.trim(it.purchased), StockDao.trim(it.sold), StockDao.trim(it.current))
            },
            ruleBetweenRows = true,
            summary = emptyList(),
            emptyNote = "No products on this till."
        )

    /** "2026-08-12" as "12-08-26" - how the F.DT / TO.DT line has always read. */
    private fun shortDate(date: String): String =
        pretty(date).let { it.take(6) + it.takeLast(2) }

    override fun emptyMessage(
        report: StockReportDao.Report,
        fromDate: String,
        toDate: String
    ): Pair<String, String> = "No products on this till" to
        "Add products under Master → Products before running this report."
}
