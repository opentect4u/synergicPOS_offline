package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.OperatorWiseReportDao
import com.example.synergic_pos_offline.utils.PeriodReportRenderer

/**
 * Operator Wise Report - who billed what over a period: a line per operator, with
 * how many bills they raised and what those bills came to.
 *
 * The screen behind the Operator Wise Report tile. Biggest takings first, so the
 * line that answers the question is at the top of the screen and the top of the roll.
 *
 * Everything a date-range report does - the pickers, the sideways-scrolling table,
 * printing what was generated rather than re-reading the period - comes from
 * [PeriodReportFragment]. What is here is only what makes this an operator report.
 */
class OperatorWiseReportFragment : PeriodReportFragment<OperatorWiseReportDao.Report>() {

    override val screenTitle = "Operator Wise Report"

    override val rowNoun = "operators"

    private val dao: OperatorWiseReportDao by lazy { OperatorWiseReportDao(requireContext()) }

    override fun load(fromDate: String, toDate: String): OperatorWiseReportDao.Report =
        dao.between(fromDate, toDate)

    override fun isEmpty(report: OperatorWiseReportDao.Report): Boolean = report.isEmpty

    override fun headline(report: OperatorWiseReportDao.Report): String =
        "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}   •   " +
            "${report.operatorCount} operator(s)   •   ${report.totalBills} bill(s)"

    override fun columnsFor(report: OperatorWiseReportDao.Report): List<Column> = listOf(
        Column("USER ID", 120, alignEnd = false),
        Column("SL NO", 80, alignEnd = true),
        Column("USER NAME", 180, alignEnd = false),
        Column("TOTAL BILLS", 110, alignEnd = true),
        Column("TOTAL AMOUNT", 130, alignEnd = true)
    )

    override fun rowsOf(report: OperatorWiseReportDao.Report): List<List<String>> =
        report.lines.map { line ->
            listOf(
                line.userId,
                // A bill that names no operator has no serial number to show, and a
                // dash says that - where a 0 would read as a user numbered zero.
                line.serialNo?.toString() ?: "-",
                line.userName,
                line.billCount.toString(),
                money(line.totalAmount)
            )
        }

    override fun summaryOf(report: OperatorWiseReportDao.Report): List<Pair<String, String>> =
        buildList {
            add("Total Operators" to report.operatorCount.toString())
            add("Total Bills" to report.totalBills.toString())
        }

    /** The one figure the report is read for. */
    override fun totalOf(report: OperatorWiseReportDao.Report): Pair<String, String> =
        "Total Amount" to money(report.totalAmount)

    /**
     * The printed report, in the format these tills have always printed: the
     * operator's code, how many receipts they took and what those came to.
     *
     * The code rather than the name, and no name under it: a shift is settled against
     * the operator number on the slip, the person settling it knows whose number it
     * is, and a roll is too narrow to spend on saying so.
     */
    override fun printContent(report: OperatorWiseReportDao.Report): PeriodReportRenderer.Content =
        PeriodReportRenderer.Content(
            title = "Operator-Wise Report",
            period = "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}",
            subtitle = "${report.operatorCount} operator(s) · ${report.totalBills} bill(s)",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = "F.DT:${shortDate(report.fromDate)}" to "TO.DT:${shortDate(report.toDate)}",
            columns = listOf("OP.CODE", "TOT.RCPTS", "AMOUNT"),
            // Three columns across a whole roll: sized to their contents they would
            // huddle against the right-hand edge with the paper blank beside them.
            evenColumns = true,
            rows = report.lines.map { line ->
                listOf(
                    line.serialNo?.toString() ?: line.userId,
                    line.billCount.toString(),
                    money(line.totalAmount)
                )
            },
            summary = emptyList(),
            // A row of the table, so the two figures land under the columns they
            // total rather than at the right-hand edge together.
            footerRow = listOf("TOTAL :", report.totalBills.toString(), money(report.totalAmount)),
            emptyNote = "No bills in this period."
        )

    /** "2026-08-11" as "11-08-26" - how the F.DT / TO.DT line has always read. */
    private fun shortDate(date: String): String =
        pretty(date).let { it.take(6) + it.takeLast(2) }

    override fun emptyMessage(
        report: OperatorWiseReportDao.Report,
        fromDate: String,
        toDate: String
    ): Pair<String, String> = "No bills in this period" to
        "Nobody billed on this device between ${pretty(fromDate)} and ${pretty(toDate)}."
}
