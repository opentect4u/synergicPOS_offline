package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.WaiterDao
import com.example.synergic_pos_offline.database.WaiterWiseReportDao
import com.example.synergic_pos_offline.utils.PeriodReportRenderer

/**
 * Waiter Wise Report - one waiter's bills over a period, a line each, or every
 * waiter's at once. Restaurant only - see [ReportsFragment.isVisible], which gates
 * the tile on Restaurant mode the same way it gates KOT Cancel and the UDF reports.
 *
 * A date range and a waiter go in; every bill served at that waiter's tables comes out
 * with what it carried and what it came to, laid out as the Shift Wise Report lays its
 * bills out - the two are the same shape of question (a period's bills, filtered to
 * one person) asked of a different person. [ALL_LABEL] in the same dropdown, the way
 * the Payment-Wise Report offers one, drops the filter instead of narrowing it - every
 * waiter's bills come back together, each row saying which of them it was, so the
 * floor's whole night can be read as one report rather than one waiter at a time.
 *
 * See [WaiterWiseReportDao] for which bills belong to a waiter - the bill's own
 * `waiter_id`, set once at settlement from the table it was raised on.
 */
class WaiterWiseReportFragment : PeriodReportFragment<WaiterWiseReportDao.Report>() {

    override val screenTitle = "Waiter Wise Report"

    override val rowNoun = "bills"

    override val filterHint = "Waiter"

    private val dao: WaiterWiseReportDao by lazy { WaiterWiseReportDao(requireContext()) }

    /** Read once: the waiter master does not change while a report is being run. */
    private val waiters: List<WaiterDao.Waiter> by lazy { dao.waiters() }

    private fun label(w: WaiterDao.Waiter) = "${w.name} (${w.code})"

    override val filterOptions: List<String> by lazy { listOf(ALL_LABEL) + waiters.map { label(it) } }

    override fun load(fromDate: String, toDate: String): WaiterWiseReportDao.Report {
        if (filterChoice == ALL_LABEL) return dao.between(fromDate, toDate, null)
        val waiter = waiters.firstOrNull { label(it) == filterChoice }
        // No waiter picked, or a till with none on the master. An empty report says so
        // rather than quietly reporting on whichever waiter happened to be first.
            ?: return WaiterWiseReportDao.Report(fromDate, toDate, null, allWaiters = false, lines = emptyList())
        return dao.between(fromDate, toDate, waiter)
    }

    override fun isEmpty(report: WaiterWiseReportDao.Report): Boolean = report.isEmpty

    override fun headline(report: WaiterWiseReportDao.Report): String {
        val who = if (report.allWaiters) "${report.waiterCount} waiter(s)" else report.waiter?.name.orEmpty()
        return "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}   •   " +
            "$who   •   ${report.billCount} bill(s)"
    }

    /**
     * The Shift Wise columns, operator among them (the cashier, not the waiter - the
     * waiter is the one already chosen in the picker above, so its own column earns
     * its place only when [ALL_LABEL] was picked instead), and IGST/VAT only where a
     * bill in the period actually carried one.
     */
    override fun columnsFor(report: WaiterWiseReportDao.Report): List<Column> = buildList {
        add(Column("BILL", 120, alignEnd = true))
        add(Column("DATE", 110, alignEnd = false))
        if (report.allWaiters) add(Column("WAITER", 140, alignEnd = false))
        add(Column("OPERATOR", 160, alignEnd = false))
        add(Column("PAY MODE", 110, alignEnd = false))
        add(Column("AMT", 110, alignEnd = true))
        add(Column("SGST", 100, alignEnd = true))
        add(Column("CGST", 100, alignEnd = true))
        if (report.hasIgst) add(Column("IGST", 100, alignEnd = true))
        if (report.hasVat) add(Column("VAT", 100, alignEnd = true))
        add(Column("DISC.", 100, alignEnd = true))
        add(Column("TOTAL AMT", 130, alignEnd = true))
    }

    override fun rowsOf(report: WaiterWiseReportDao.Report): List<List<String>> =
        report.lines.map { line ->
            buildList {
                add(line.billNumber)
                add(pretty(line.date))
                if (report.allWaiters) add(line.waiterName)
                add(line.operator)
                add(line.payMode)
                add(money(line.mrp))
                add(money(line.sgst))
                add(money(line.cgst))
                if (report.hasIgst) add(money(line.igst))
                if (report.hasVat) add(money(line.vat))
                add(money(line.discount))
                add(money(line.netAmount))
            }
        }

    override fun summaryOf(report: WaiterWiseReportDao.Report): List<Pair<String, String>> =
        buildList {
            // Only worth a row when there is more than one waiter to have counted -
            // the same rule the Payment-Wise Report's own All shows its mode count by.
            if (report.allWaiters) add("Waiters" to report.waiterCount.toString())
            add("Total Bills" to report.billCount.toString())
            add("Operators" to report.operatorCount.toString())
            add("Total Amt" to money(report.totalMrp))
            add("Total SGST" to money(report.totalSgst))
            add("Total CGST" to money(report.totalCgst))
            if (report.hasIgst) add("Total IGST" to money(report.totalIgst))
            if (report.hasVat) add("Total VAT" to money(report.totalVat))
            add("Total Disc." to money(report.totalDiscount))
            // Shown only where the period actually carried one - a shop that never
            // charges Service or an Extra Charge should not read a zero row saying so.
            if (report.totalServiceCharge > 0.005) add("Service Charge" to money(report.totalServiceCharge))
            if (report.totalOtherCharges > 0.005) add("Extra Charges" to money(report.totalOtherCharges))
            if (report.totalParcelCharge > 0.005) add("Parcel Charge" to money(report.totalParcelCharge))
        }

    /** The one figure the report is read for: what the waiter's tables took. */
    override fun totalOf(report: WaiterWiseReportDao.Report): Pair<String, String> =
        "Total Amount" to money(report.totalAmount)

    /**
     * Tells a shop with no waiters on the master from a waiter (or all of them) that
     * simply served nothing, because only one of them is something to go and fix.
     */
    override fun emptyMessage(
        report: WaiterWiseReportDao.Report,
        fromDate: String,
        toDate: String
    ): Pair<String, String> = when {
        waiters.isEmpty() -> "No waiters set up" to
            "Add the shop's waiters in Database Settings › Waiter, then assign one to " +
                "each table in Database Settings › Table."
        report.waiter == null && !report.allWaiters -> "Pick a waiter" to
            "Choose which waiter to report on, or pick $ALL_LABEL to see every waiter, " +
                "then generate."
        report.allWaiters -> "No bills" to
            "No waiter served a bill between ${pretty(fromDate)} and ${pretty(toDate)}."
        else -> "No bills" to
            "${report.waiter!!.name} served no bills between ${pretty(fromDate)} and " +
                "${pretty(toDate)}."
    }

    /** "2026-08-12" as "12-08-26" - how the F.DT / TO.DT line has always read. */
    private fun shortDate(date: String): String =
        pretty(date).let { it.take(6) + it.takeLast(2) }

    /**
     * The printed slip: the bill and its total across the roll, with the operator,
     * the date and the rest on a second line beneath - the same shape Shift Wise
     * prints its own bills in.
     */
    override fun printContent(report: WaiterWiseReportDao.Report): PeriodReportRenderer.Content =
        PeriodReportRenderer.Content(
            title = "Waiter Wise Report",
            period = "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}",
            subtitle = "${report.billCount} bill(s)",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = "F.DT:${shortDate(report.fromDate)}" to "TO.DT:${shortDate(report.toDate)}",
            heading = listOf(
                "WAITER" to when {
                    report.allWaiters -> "$ALL_LABEL (${report.waiterCount})"
                    report.waiter != null -> label(report.waiter)
                    else -> "-"
                }
            ),
            columns = listOf("BILL", "TOTAL AMT"),
            rows = report.lines.map { listOf(it.billNumber, money(it.netAmount)) },
            columns2 = if (report.allWaiters) listOf("", "WAITER", "DATE", "OPERATOR", "MODE")
            else listOf("", "DATE", "OPERATOR", "MODE"),
            rows2 = report.lines.map {
                if (report.allWaiters) listOf("", it.waiterName, shortDate(it.date), it.operator, it.payMode)
                else listOf("", shortDate(it.date), it.operator, it.payMode)
            },
            ruleBetweenRows = true,
            evenColumns = true,
            alignFirstColumnEnd = true,
            summary = summaryOf(report).map { (label, value) -> label.uppercase() to value },
            total = totalOf(report).let { (label, value) -> label.uppercase() to value },
            emptyNote = "No bills for this waiter."
        )

    private companion object {
        /** The dropdown's own wording for "every waiter" - matches the Payment-Wise
         *  Report's own All entry. */
        const val ALL_LABEL = "All"
    }
}
