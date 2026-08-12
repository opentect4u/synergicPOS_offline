package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.DuplicateReportDao
import com.example.synergic_pos_offline.utils.PeriodReportRenderer

/**
 * Duplicate Receipt Report - which bills were printed again over a period, and how
 * often.
 *
 * Read off the print log the till keeps as it prints, so a duplicate on this report
 * is one that actually came out of a printer. Most reprinted first: a bill run off
 * six times is what the report is opened to find.
 *
 * Everything a date-range report does comes from [PeriodReportFragment]; what is
 * here is only what makes this a duplicate report.
 */
class DuplicateReportFragment : PeriodReportFragment<DuplicateReportDao.Report>() {

    override val screenTitle = "Duplicate Bill Report"

    override val rowNoun = "bills"

    private val dao: DuplicateReportDao by lazy { DuplicateReportDao(requireContext()) }

    override fun load(fromDate: String, toDate: String): DuplicateReportDao.Report =
        dao.between(fromDate, toDate)

    override fun isEmpty(report: DuplicateReportDao.Report): Boolean = report.isEmpty

    override fun headline(report: DuplicateReportDao.Report): String =
        "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}   •   " +
            "${report.billCount} bill(s)   •   ${report.totalTimes} duplicate(s)"

    override fun columnsFor(report: DuplicateReportDao.Report): List<Column> = listOf(
        Column("BILL", 200, alignEnd = false),
        Column("NO. OF TIMES", 200, alignEnd = true)
    )

    override fun rowsOf(report: DuplicateReportDao.Report): List<List<String>> =
        report.lines.map { listOf(it.billNumber, it.times.toString()) }

    override fun summaryOf(report: DuplicateReportDao.Report): List<Pair<String, String>> =
        listOf("Bills Duplicated" to report.billCount.toString())

    /** The one figure the report is read for. */
    override fun totalOf(report: DuplicateReportDao.Report): Pair<String, String> =
        "Total Duplicates" to report.totalTimes.toString()

    /**
     * The printed slip, in the format these tills have always printed it: two
     * columns, each down the middle of its own half.
     *
     * No totals block. Two short columns of counts add up by eye, and the slip has
     * never carried one.
     */
    override fun printContent(report: DuplicateReportDao.Report): PeriodReportRenderer.Content =
        PeriodReportRenderer.Content(
            title = "Duplicate Receipt Report",
            period = "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}",
            subtitle = "${report.billCount} bill(s)",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = "F.DT:${shortDate(report.fromDate)}" to "TO.DT:${shortDate(report.toDate)}",
            columns = listOf("BILL", "NO. OF TIMES"),
            // Two short values on a wide roll: flushed to the edges they would sit at
            // opposite ends of the paper with nothing between them.
            evenColumns = true,
            centreColumns = true,
            rows = rowsOf(report),
            summary = emptyList(),
            emptyNote = "No bill was reprinted in this period."
        )

    /** "2026-08-12" as "12-08-26" - how the F.DT / TO.DT line has always read. */
    private fun shortDate(date: String): String =
        pretty(date).let { it.take(6) + it.takeLast(2) }

    /**
     * An empty report here is good news, not an absence of data, and says so - "no
     * results" would read as a report that failed to run.
     */
    override fun emptyMessage(
        report: DuplicateReportDao.Report,
        fromDate: String,
        toDate: String
    ): Pair<String, String> = "No duplicates in this period" to
        "No bill was printed a second time between ${pretty(fromDate)} and ${pretty(toDate)}."
}
