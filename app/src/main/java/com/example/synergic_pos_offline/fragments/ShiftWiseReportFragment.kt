package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.ShiftDao
import com.example.synergic_pos_offline.database.ShiftWiseReportDao
import com.example.synergic_pos_offline.utils.PeriodReportRenderer

/**
 * Shift Wise Report - one shift's bills over a period, a line each.
 *
 * A date range and a shift go in; every bill that shift's operators rang up comes out
 * with what it carried and what it came to, laid out as the Bill Wise Report lays its
 * bills out. Deliberately so: a shopkeeper reconciling a shift against the day is
 * comparing two readings of the same books, and two shapes of table would make that
 * harder than it is.
 *
 * The one column Bill Wise does not carry is the operator, which is the whole point
 * of running the report by shift: a shift's total is only useful if it can be read
 * back to the people who took the money.
 *
 * See [ShiftWiseReportDao] for which bills belong to a shift - it is the operator who
 * decides, not the clock - and for what that means when somebody's rota changes.
 */
class ShiftWiseReportFragment : PeriodReportFragment<ShiftWiseReportDao.Report>() {

    override val screenTitle = "Shift Wise Report"

    override val rowNoun = "bills"

    override val filterHint = "Shift"

    private val dao: ShiftWiseReportDao by lazy { ShiftWiseReportDao(requireContext()) }

    /** Read once: the shift master does not change while a report is being run. */
    private val shifts: List<ShiftDao.Shift> by lazy { dao.shifts() }

    override val filterOptions: List<String> by lazy { shifts.map { it.label } }

    override fun load(fromDate: String, toDate: String): ShiftWiseReportDao.Report {
        val shift = shifts.firstOrNull { it.label == filterChoice }
        // No shift picked, or a till with none on the master. An empty report says so
        // rather than quietly reporting on whichever shift happened to be first.
            ?: return ShiftWiseReportDao.Report(fromDate, toDate, null, emptyList())
        return dao.between(fromDate, toDate, shift)
    }

    override fun isEmpty(report: ShiftWiseReportDao.Report): Boolean = report.isEmpty

    override fun headline(report: ShiftWiseReportDao.Report): String =
        "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}   •   " +
            "${report.shift?.name.orEmpty()}   •   ${report.billCount} bill(s)"

    /**
     * The Bill Wise columns with the operator among them, and IGST/VAT only where a
     * bill in the period actually carried one - the same rule Bill Wise applies, so
     * a GST-only shop is not made to read a column of zeroes.
     */
    override fun columnsFor(report: ShiftWiseReportDao.Report): List<Column> = buildList {
        add(Column("BILL", 120, alignEnd = true))
        add(Column("DATE", 110, alignEnd = false))
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

    override fun rowsOf(report: ShiftWiseReportDao.Report): List<List<String>> =
        report.lines.map { line ->
            buildList {
                add(line.billNumber)
                add(pretty(line.date))
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

    override fun summaryOf(report: ShiftWiseReportDao.Report): List<Pair<String, String>> =
        buildList {
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
        }

    /** The one figure the report is read for: what the shift took. */
    override fun totalOf(report: ShiftWiseReportDao.Report): Pair<String, String> =
        "Total Amount" to money(report.totalAmount)

    /**
     * Tells a shop with no shifts on the master from a shift that simply did not
     * bill, because only one of them is something to go and fix.
     */
    override fun emptyMessage(
        report: ShiftWiseReportDao.Report,
        fromDate: String,
        toDate: String
    ): Pair<String, String> = when {
        shifts.isEmpty() -> "No shifts set up" to
            "Add the shop's shifts in Master › Shifts, then put each user on one in " +
                "Master › User Management."
        report.shift == null -> "Pick a shift" to
            "Choose which shift to report on, then generate."
        else -> "No bills" to
            "${report.shift.name} raised no bills between ${pretty(fromDate)} and " +
                "${pretty(toDate)}."
    }

    /** "2026-08-12" as "12-08-26" - how the F.DT / TO.DT line has always read. */
    private fun shortDate(date: String): String =
        pretty(date).let { it.take(6) + it.takeLast(2) }

    /**
     * The printed slip: the bill and its total across the roll, with the operator,
     * the date and the rest on a second line beneath.
     *
     * Ten columns will not fit a receipt, and shrinking them until they do is how a
     * slip becomes unreadable. The bill number and what it came to are what the paper
     * is scanned for; everything else supports them from the line below.
     */
    override fun printContent(report: ShiftWiseReportDao.Report): PeriodReportRenderer.Content =
        PeriodReportRenderer.Content(
            title = "Shift Wise Report",
            period = "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}",
            subtitle = "${report.billCount} bill(s)",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = "F.DT:${shortDate(report.fromDate)}" to "TO.DT:${shortDate(report.toDate)}",
            heading = listOf("SHIFT" to (report.shift?.label ?: "-")),
            columns = listOf("BILL", "TOTAL AMT"),
            rows = report.lines.map { listOf(it.billNumber, money(it.netAmount)) },
            columns2 = listOf("", "DATE", "OPERATOR", "MODE"),
            rows2 = report.lines.map {
                listOf("", shortDate(it.date), it.operator, it.payMode)
            },
            ruleBetweenRows = true,
            evenColumns = true,
            alignFirstColumnEnd = true,
            summary = summaryOf(report).map { (label, value) -> label.uppercase() to value },
            total = totalOf(report).let { (label, value) -> label.uppercase() to value },
            emptyNote = "No bills for this shift."
        )
}
