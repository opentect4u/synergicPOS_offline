package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.OperatorBilledReportDao
import com.example.synergic_pos_offline.database.StockDao
import com.example.synergic_pos_offline.utils.PeriodReportRenderer

/**
 * Operator Billed Report - one operator's bills over a period, a line each.
 *
 * The Operator Wise Report says which operator to look at; this is the looking. A
 * date range and an operator go in, and every bill they rang up comes out with what
 * it carried, what it was taxed, what came off it and what it came to.
 *
 * Everything a date-range report does comes from [PeriodReportFragment], including
 * the operator picker - what is here is only what makes this an operator's own report.
 */
class OperatorBilledReportFragment : PeriodReportFragment<OperatorBilledReportDao.Report>() {

    override val screenTitle = "Opr Bill Report"

    override val rowNoun = "bills"

    override val filterHint = "Operator"

    /**
     * Typed at rather than scrolled: a till of twenty staff is a long list to page
     * through, and whoever is running the report already knows the code, the login or
     * the name. Each entry carries all three so any of them finds it.
     */
    override val filterSearchable = true

    private val dao: OperatorBilledReportDao by lazy { OperatorBilledReportDao(requireContext()) }

    /** Read once: the user master does not change while a report is being run. */
    private val operators: List<OperatorBilledReportDao.Operator> by lazy { dao.operators() }

    override val filterOptions: List<String> by lazy { operators.map { it.label } }

    override fun load(fromDate: String, toDate: String): OperatorBilledReportDao.Report {
        val operator = operators.firstOrNull { it.label == filterChoice }
            // Nobody matched what is in the box - a typed fragment left unpicked, or
            // a till with no users at all. An empty report says so rather than
            // quietly reporting on whichever operator happened to be first.
            ?: return OperatorBilledReportDao.Report(fromDate, toDate, null, emptyList())
        return dao.between(fromDate, toDate, operator)
    }

    override fun isEmpty(report: OperatorBilledReportDao.Report): Boolean = report.isEmpty

    override fun headline(report: OperatorBilledReportDao.Report): String =
        "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}   •   " +
            "${report.operator?.userName.orEmpty()}   •   ${report.billCount} bill(s)"

    override fun columnsFor(report: OperatorBilledReportDao.Report): List<Column> = listOf(
        Column("BILL", 130, alignEnd = true),
        Column("ITEMS", 110, alignEnd = true),
        Column("TAX", 110, alignEnd = true),
        Column("DISC", 110, alignEnd = true),
        Column("TOTAL", 130, alignEnd = true)
    )

    override fun rowsOf(report: OperatorBilledReportDao.Report): List<List<String>> =
        report.lines.map { line ->
            listOf(
                line.billNumber,
                money(line.items),
                money(line.tax),
                money(line.discount),
                money(line.total)
            )
        }

    override fun summaryOf(report: OperatorBilledReportDao.Report): List<Pair<String, String>> =
        buildList {
            add("Total Bills" to report.billCount.toString())
            add("Total Items" to StockDao.trim(report.totalItems))
            // Each tax its own line rather than one blended figure - a GST return
            // is filed against SGST and CGST separately, and IGST/VAT earn their
            // place only where a bill in the period actually carried one.
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

    /** The one figure the report is read for. */
    override fun totalOf(report: OperatorBilledReportDao.Report): Pair<String, String> =
        "Grand Total" to money(report.grandTotal)

    /**
     * The printed slip, in the format these tills have always printed it: the
     * operator named under the range, then a line per bill.
     *
     * Five columns of figures with no name among them, so the bill number is set to
     * the right with the rest - it reads as a figure, and a column that starts at the
     * left would break the run of them.
     */
    override fun printContent(report: OperatorBilledReportDao.Report): PeriodReportRenderer.Content =
        PeriodReportRenderer.Content(
            title = "Operator Billed Report",
            period = "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}",
            subtitle = "${report.billCount} bill(s)",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = "F.DT:${shortDate(report.fromDate)}" to "TO.DT:${shortDate(report.toDate)}",
            heading = listOf(
                "OPCODE  :" to (report.operator?.serialNo?.toString() ?: "-"),
                "OPR. NM :" to (report.operator?.userName?.uppercase() ?: "-")
            ),
            columns = listOf("BILL", "ITEMS", "TAX", "DISC", "TOTAL"),
            evenColumns = true,
            alignFirstColumnEnd = true,
            rows = rowsOf(report),
            summary = buildList {
                add("TOTAL BILLS" to report.billCount.toString())
                add("TOTAL ITEMS" to StockDao.trim(report.totalItems))
                add("TOTAL SGST " to money(report.totalSgst))
                add("TOTAL CGST " to money(report.totalCgst))
                if (report.hasIgst) add("TOTAL IGST " to money(report.totalIgst))
                if (report.hasVat) add("TOTAL VAT  " to money(report.totalVat))
                add("TOTAL DISC." to money(report.totalDiscount))
                if (report.totalServiceCharge > 0.005) add("SERVICE CHG" to money(report.totalServiceCharge))
                if (report.totalOtherCharges > 0.005) add("EXTRA CHGS " to money(report.totalOtherCharges))
                add("GRAND TOTAL" to money(report.grandTotal))
            }.map { (label, value) -> "$label :" to value },
            emptyNote = "No bills in this period."
        )

    /** "2026-08-12" as "12-08-26" - how the F.DT / TO.DT line has always read. */
    private fun shortDate(date: String): String =
        pretty(date).let { it.take(6) + it.takeLast(2) }

    override fun emptyMessage(
        report: OperatorBilledReportDao.Report,
        fromDate: String,
        toDate: String
    ): Pair<String, String> = when {
        operators.isEmpty() -> "No operators on this till" to
            "Add users under Settings → User Management before running this report."

        report.operator == null -> "Pick an operator" to
            "Choose one from the list before generating - typing a name is not the " +
                "same as picking it."

        else -> "No bills in this period" to
            "${report.operator.userName} billed nothing between " +
                "${pretty(fromDate)} and ${pretty(toDate)}."
    }
}
