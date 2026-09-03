package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.UnsoldProductReportDao
import com.example.synergic_pos_offline.utils.PeriodReportRenderer

/**
 * Unsold Product Report - the products nobody bought over a period.
 *
 * The Item Wise Report from the other end: that one lists what moved, this one what
 * did not. Read before a shelf is cleared, a line dropped or a discount put on, so
 * it is ordered by name - there is nothing to rank these by, and the operator is
 * checking a shelf against the list.
 *
 * Everything a date-range report does comes from [PeriodReportFragment]; what is
 * here is only what makes this a report of what did not sell.
 */
class UnsoldProductReportFragment : PeriodReportFragment<UnsoldProductReportDao.Report>() {

    override val screenTitle = "Unsold Product Report"

    override val rowNoun = "products"

    private val dao: UnsoldProductReportDao by lazy { UnsoldProductReportDao(requireContext()) }

    override fun load(fromDate: String, toDate: String): UnsoldProductReportDao.Report =
        dao.between(fromDate, toDate)

    override fun isEmpty(report: UnsoldProductReportDao.Report): Boolean = report.isEmpty

    override fun headline(report: UnsoldProductReportDao.Report): String =
        "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}   •   " +
            "${report.unsoldCount} of ${report.productCount} product(s) unsold"

    /**
     * A numbered list of names, and nothing else.
     *
     * There are no figures to put beside a product that did not sell - a rate is what
     * it *would* have fetched and a stock figure is a different report's question -
     * and columns of them only make the one thing this report is read for, the names,
     * harder to run an eye down.
     */
    override fun columnsFor(report: UnsoldProductReportDao.Report): List<Column> = listOf(
        Column("SL", 60, alignEnd = true),
        Column("ITEM NAME", 300, alignEnd = false)
    )

    override fun rowsOf(report: UnsoldProductReportDao.Report): List<List<String>> =
        report.lines.map { line -> listOf(line.serial.toString(), line.name) }

    override fun summaryOf(report: UnsoldProductReportDao.Report): List<Pair<String, String>> =
        listOf(
            "Products On Master" to report.productCount.toString(),
            "Sold In This Period" to report.soldCount.toString()
        )

    /** The one figure the report is read for. */
    override fun totalOf(report: UnsoldProductReportDao.Report): Pair<String, String> =
        "Total Unsold Products" to report.unsoldCount.toString()

    /**
     * The printed report: a rule, a heading, and the names.
     *
     * No serial numbers and no totals. The whole slip is one column, so a name has
     * the width of the roll and prints in full; and there is nothing to total - the
     * list *is* the answer, and its length is plain from looking at it.
     */
    override fun printContent(report: UnsoldProductReportDao.Report): PeriodReportRenderer.Content =
        PeriodReportRenderer.Content(
            title = "Unsold Products Report",
            period = "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}",
            subtitle = "${report.unsoldCount} of ${report.productCount} product(s) unsold",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = "F.DT:${shortDate(report.fromDate)}" to "TO.DT:${shortDate(report.toDate)}",
            // The item name, put into the print language as it is on a bill.
            nameColumns = setOf(0),
            columns = listOf("ITEM NAME"),
            rows = report.lines.map { line -> listOf(line.name) },
            summary = emptyList(),
            emptyNote = "Everything sold in this period."
        )

    /** "2026-08-12" as "12-08-26" - how the F.DT / TO.DT line has always read. */
    private fun shortDate(date: String): String =
        pretty(date).let { it.take(6) + it.takeLast(2) }

    /**
     * An empty report here is good news, not an absence of data, and says so - "no
     * results" would read as a report that failed to run.
     */
    override fun emptyMessage(
        report: UnsoldProductReportDao.Report,
        fromDate: String,
        toDate: String
    ): Pair<String, String> = when {
        report.productCount == 0 -> "No products on the master" to
            "There is nothing to report as unsold. Add products under Master → Products."

        else -> "Everything sold" to
            "All ${report.productCount} product(s) sold at least once between " +
                "${pretty(fromDate)} and ${pretty(toDate)}."
    }
}
