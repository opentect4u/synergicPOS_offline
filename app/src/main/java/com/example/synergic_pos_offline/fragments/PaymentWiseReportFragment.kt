package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.PaymentWiseReportDao
import com.example.synergic_pos_offline.utils.PeriodReportRenderer
import java.util.Locale

/**
 * Payment Wise Report - how the period's takings were paid for: a line per payment
 * mode, with the bills settled that way and what was collected against them.
 *
 * The dates and a payment mode go in; All gives every mode the period holds, and a
 * single mode gives that one alone. Biggest first, so the mode the shop mostly runs
 * on is at the top of the screen and the top of the roll.
 *
 * Everything a date-range report does comes from [PeriodReportFragment], including
 * the mode dropdown - what is here is only what makes this a payment report.
 */
class PaymentWiseReportFragment : PeriodReportFragment<PaymentWiseReportDao.Report>() {

    override val screenTitle = "Payment-Wise Report"

    override val rowNoun = "payment modes"

    override val filterHint = "Payment mode"

    /**
     * All, then every mode the till can record - not only the three most shops use.
     *
     * A mode left off the list would be unaskable *and* would still appear under All,
     * which reads as the report disagreeing with itself. See
     * [PaymentWiseReportDao.MODES].
     */
    override val filterOptions: List<String> =
        listOf(ALL_LABEL) + PaymentWiseReportDao.MODES.map { it.titleCase() }

    private val dao: PaymentWiseReportDao by lazy { PaymentWiseReportDao(requireContext()) }

    override fun load(fromDate: String, toDate: String): PaymentWiseReportDao.Report =
        dao.between(fromDate, toDate, selectedMode())

    /** The dropdown's label as the DAO wants it: a stored mode, or ALL. */
    private fun selectedMode(): String =
        if (filterChoice.isBlank() || filterChoice == ALL_LABEL) PaymentWiseReportDao.ALL
        else filterChoice.uppercase(Locale.US)

    override fun isEmpty(report: PaymentWiseReportDao.Report): Boolean = report.isEmpty

    override fun headline(report: PaymentWiseReportDao.Report): String =
        "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}   •   " +
            "${modeLabel(report)}   •   ${report.totalBills} bill(s)"

    override fun columnsFor(report: PaymentWiseReportDao.Report): List<Column> = listOf(
        Column("PAY MODE", 160, alignEnd = false),
        Column("TOTAL BILLS", 130, alignEnd = true),
        Column("PAID AMOUNT", 150, alignEnd = true)
    )

    override fun rowsOf(report: PaymentWiseReportDao.Report): List<List<String>> =
        report.lines.map { line ->
            listOf(line.mode, line.billCount.toString(), money(line.paidAmount))
        }

    override fun summaryOf(report: PaymentWiseReportDao.Report): List<Pair<String, String>> =
        buildList {
            // Only worth a row when there is more than one mode to have counted.
            if (report.mode == PaymentWiseReportDao.ALL) {
                add("Payment Modes" to report.modeCount.toString())
            }
            add("Total Bills Paid" to report.totalBills.toString())
        }

    /** The one figure the report is read for. */
    override fun totalOf(report: PaymentWiseReportDao.Report): Pair<String, String> =
        "Total Paid Amount" to money(report.totalPaid)

    /**
     * The printed report, in the format these tills have always printed.
     *
     * Credit is stated apart from the rest and then added back: it was billed but not
     * collected, so a single total would credit the drawer with money that is still
     * owed. The two lines and their sum are what the slip has always carried.
     */
    override fun printContent(report: PaymentWiseReportDao.Report): PeriodReportRenderer.Content =
        PeriodReportRenderer.Content(
            title = "Payment-Wise Report",
            period = "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}",
            subtitle = "${modeLabel(report)} · ${report.totalBills} bill(s)",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = "F.DT:${shortDate(report.fromDate)}" to "TO.DT:${shortDate(report.toDate)}",
            columns = listOf("PAYMENT", "BILLS", "PAID AMT"),
            rows = report.lines.map { line ->
                listOf(line.mode, line.billCount.toString(), money(line.paidAmount))
            },
            summary = listOf(
                "TOTAL PAID   :" to money(report.collectedAmount),
                "TOTAL CREDIT :" to money(report.creditAmount)
            ),
            total = "TOTAL AMOUNT :" to money(report.totalPaid),
            emptyNote = "No payments in this period."
        )

    /** "2026-08-11" as "11-08-26" - how the F.DT / TO.DT line has always read. */
    private fun shortDate(date: String): String =
        pretty(date).let { it.take(6) + it.takeLast(2) }

    override fun emptyMessage(
        report: PaymentWiseReportDao.Report,
        fromDate: String,
        toDate: String
    ): Pair<String, String> {
        val period = "between ${pretty(fromDate)} and ${pretty(toDate)}"
        return if (report.mode == PaymentWiseReportDao.ALL) {
            "No payments in this period" to "Nothing was paid for $period."
        } else {
            // Naming the mode matters: the operator picked it, and "no payments" on
            // its own would read as a day with no takings rather than a day with no
            // takings *that way*.
            "No ${report.mode.titleCase()} payments in this period" to
                "Nothing was paid for by ${report.mode.titleCase()} $period. " +
                    "Pick $ALL_LABEL to see every mode."
        }
    }

    /** "All modes" or the one that was asked for, for the line above the table. */
    private fun modeLabel(report: PaymentWiseReportDao.Report): String =
        if (report.mode == PaymentWiseReportDao.ALL) "${report.modeCount} mode(s)"
        else report.mode.titleCase()

    private companion object {
        /** The dropdown's own wording for "every mode". */
        const val ALL_LABEL = "All"

        /** "CASH" as "Cash" - stored upper case, read back in ordinary case. */
        fun String.titleCase(): String =
            lowercase(Locale.US).replaceFirstChar { it.uppercase(Locale.US) }
    }
}
