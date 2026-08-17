package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.CategoryWiseReportDao
import com.example.synergic_pos_offline.database.StockDao
import com.example.synergic_pos_offline.utils.PeriodReportRenderer

/**
 * Department Report - what each part of the shop sold over a period: a line per
 * department, with how much of it went and what that came to.
 *
 * Biggest takings first, so the department the shop actually runs on is at the top of
 * the screen and the top of the roll.
 *
 * Everything a date-range report does comes from [PeriodReportFragment]; what is
 * here is only what makes this a department report.
 */
class CategoryWiseReportFragment : PeriodReportFragment<CategoryWiseReportDao.Report>() {

    override val screenTitle = "Category/Dept Wise Bill Report"

    override val rowNoun = "departments"

    private val dao: CategoryWiseReportDao by lazy { CategoryWiseReportDao(requireContext()) }

    override fun load(fromDate: String, toDate: String): CategoryWiseReportDao.Report =
        dao.between(fromDate, toDate)

    override fun isEmpty(report: CategoryWiseReportDao.Report): Boolean = report.isEmpty

    override fun headline(report: CategoryWiseReportDao.Report): String =
        "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}   •   " +
            "${report.categoryCount} department(s)"

    override fun columnsFor(report: CategoryWiseReportDao.Report): List<Column> = listOf(
        Column("DEPT NAME", 240, alignEnd = false),
        Column("S.QTY", 120, alignEnd = true),
        Column("AMOUNT", 150, alignEnd = true)
    )

    override fun rowsOf(report: CategoryWiseReportDao.Report): List<List<String>> =
        report.lines.map { line ->
            listOf(line.category, StockDao.trim(line.quantity), StockDao.trim(line.amount))
        }

    override fun summaryOf(report: CategoryWiseReportDao.Report): List<Pair<String, String>> =
        buildList {
            add("Total Departments" to report.categoryCount.toString())
            add("Total S.Qty" to StockDao.trim(report.totalQuantity))
            // Worth naming only when there is another department to have beaten.
            if (report.categoryCount > 1) {
                report.best?.let {
                    add("Best Department" to "${it.category}  ${StockDao.trim(it.amount)}")
                }
            }
        }

    /** The one figure the report is read for. */
    override fun totalOf(report: CategoryWiseReportDao.Report): Pair<String, String> =
        "Total Amount" to StockDao.trim(report.totalAmount)

    /**
     * The printed slip, in the format these tills have always printed it: three
     * columns spread across the roll, and nothing under them.
     *
     * No totals block. A department report is usually a handful of lines that can be
     * added by eye, and the slip has never carried one.
     */
    override fun printContent(report: CategoryWiseReportDao.Report): PeriodReportRenderer.Content =
        PeriodReportRenderer.Content(
            title = "Department Report",
            period = "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}",
            subtitle = "${report.categoryCount} department(s)",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = "F.DT:${shortDate(report.fromDate)}" to "TO.DT:${shortDate(report.toDate)}",
            // The department is a name the shop typed, like a product's.
            nameColumns = setOf(0),
            columns = listOf("DEPT NAME", "S.QTY", "AMOUNT"),
            // Three columns across a whole roll: sized to their contents they would
            // huddle against the right-hand edge with the paper blank beside them.
            evenColumns = true,
            rows = rowsOf(report),
            summary = emptyList(),
            emptyNote = "Nothing was sold in this period."
        )

    /** "2026-08-12" as "12-08-26" - how the F.DT / TO.DT line has always read. */
    private fun shortDate(date: String): String =
        pretty(date).let { it.take(6) + it.takeLast(2) }

    override fun emptyMessage(
        report: CategoryWiseReportDao.Report,
        fromDate: String,
        toDate: String
    ): Pair<String, String> = "No sales in this period" to
        "Nothing was sold between ${pretty(fromDate)} and ${pretty(toDate)}."
}
