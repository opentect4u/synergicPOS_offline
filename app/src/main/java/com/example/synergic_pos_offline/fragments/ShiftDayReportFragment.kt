package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.ShiftDao
import com.example.synergic_pos_offline.database.ShiftDayReportDao
import com.example.synergic_pos_offline.database.ShiftDayReportDao.View as ReportView
import com.example.synergic_pos_offline.utils.PeriodReportRenderer
import com.example.synergic_pos_offline.utils.PrintType
import com.example.synergic_pos_offline.utils.SettingsCache

/**
 * Shift Day Report - a business day's takings, shift by shift.
 *
 * Pick the 10th and the report covers the 10th as the shop worked it: from the first
 * shift's opening on the 10th until that time on the 11th, so a night shift running past
 * midnight is counted on the night it started, not split across two dates. Every shift
 * of the day is shown at once by default, with its own total, or one shift alone.
 *
 * The Mode dropdown lays the same bills out seven ways - one row per bill, or totalled
 * by item, payment mode, table, waiter, tax slab or operator within each day's shift.
 * Table and waiter are offered in Restaurant mode only; a grocery till has neither.
 *
 * See [ShiftDayReportDao] for how the business day and each bill's shift are worked out -
 * by the clock, where the Shift Wise Report goes by the operator's shift.
 */
class ShiftDayReportFragment : PeriodReportFragment<ShiftDayReportDao.Report>() {

    override val screenTitle = TITLE

    override val rowNoun = "rows"

    override val filterHint = "Shift"

    override val modeHint = "Mode"

    private val dao: ShiftDayReportDao by lazy { ShiftDayReportDao(requireContext()) }

    /** Read once: the shift master does not change while a report is being run. */
    private val shifts: List<ShiftDao.Shift> by lazy { dao.shifts() }

    /** "All Shifts" first - the whole business day is what the report is opened for. */
    override val filterOptions: List<String> by lazy { listOf(ALL_SHIFTS) + shifts.map { it.label } }

    /** The views this till can use - tables and waiters only exist in Restaurant mode. */
    private val views: List<ReportView> by lazy {
        val restaurant = SettingsCache.value(requireContext(), "G", "Mode") == "R"
        ReportView.values().filter { restaurant || (it != ReportView.TABLE && it != ReportView.WAITER) }
    }

    /** Bill Wise first - one row per bill is what the report has always opened on. */
    override val modeOptions: List<String> by lazy { views.map { it.label } }

    override fun load(fromDate: String, toDate: String): ShiftDayReportDao.Report =
        dao.between(
            fromDate, toDate,
            shifts.firstOrNull { it.label == filterChoice },
            views.firstOrNull { it.label == modeChoice } ?: ReportView.BILL
        )

    override fun isEmpty(report: ShiftDayReportDao.Report): Boolean = report.isEmpty

    /** "10:00  to  next day 06:00" - the hours each business date covers. */
    private fun hours(report: ShiftDayReportDao.Report): String =
        "${report.dayStart}  to  ${if (report.endsNextDay) "next day " else ""}${report.dayEnd}"

    private fun period(report: ShiftDayReportDao.Report): String =
        if (report.fromDate == report.toDate) pretty(report.fromDate)
        else "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}"

    override fun headline(report: ShiftDayReportDao.Report): String =
        "${period(report)}  (${hours(report)})   •   " +
            "${report.shiftFilter?.name ?: ALL_SHIFTS}   •   ${report.view.label}   •   " +
            "${report.billCount} bill(s)"

    /** "Night Shift · 22:00 - 06:00", or the label for bills outside every shift. */
    private fun shiftName(shift: ShiftDao.Shift?): String = shift?.label ?: OUTSIDE_SHIFTS

    /** A table row's place: "AC Hall 3", or the counter's order type alone. */
    private fun tableName(g: ShiftDayReportDao.Group): String =
        if (g.detail.isBlank()) g.name else "${g.name} ${g.detail}"

    override fun columnsFor(report: ShiftDayReportDao.Report): List<Column> = buildList {
        add(Column("DAY", 110, alignEnd = false))
        add(Column("SHIFT", 200, alignEnd = false))
        when (report.view) {
            ReportView.BILL -> {
                add(Column("BILL", 100, alignEnd = true))
                add(Column("DATE & TIME", 160, alignEnd = false))
                add(Column("OPERATOR", 140, alignEnd = false))
                add(Column("PAY MODE", 110, alignEnd = false))
                add(Column("AMT", 110, alignEnd = true))
                add(Column("SGST", 100, alignEnd = true))
                add(Column("CGST", 100, alignEnd = true))
                if (report.hasIgst) add(Column("IGST", 100, alignEnd = true))
                if (report.hasVat) add(Column("VAT", 100, alignEnd = true))
                add(Column("DISC.", 100, alignEnd = true))
                add(Column("TOTAL AMT", 130, alignEnd = true))
            }
            ReportView.ITEM -> {
                add(Column("ITEM", 240, alignEnd = false))
                add(Column("QTY", 80, alignEnd = true))
                add(Column("BILLS", 80, alignEnd = true))
                add(Column("AMOUNT", 130, alignEnd = true))
            }
            ReportView.PAYMENT -> {
                add(Column("PAY MODE", 140, alignEnd = false))
                add(Column("BILLS", 80, alignEnd = true))
                add(Column("AMOUNT", 130, alignEnd = true))
            }
            ReportView.TABLE -> {
                add(Column("SECTION", 160, alignEnd = false))
                add(Column("TABLE", 80, alignEnd = false))
                add(Column("BILLS", 80, alignEnd = true))
                add(Column("AMOUNT", 130, alignEnd = true))
            }
            ReportView.WAITER -> {
                add(Column("WAITER", 180, alignEnd = false))
                add(Column("BILLS", 80, alignEnd = true))
                add(Column("AMOUNT", 130, alignEnd = true))
            }
            ReportView.TAX -> {
                add(Column("TAX", 120, alignEnd = false))
                add(Column("BILLS", 70, alignEnd = true))
                add(Column("TAXABLE", 120, alignEnd = true))
                add(Column("SGST", 100, alignEnd = true))
                add(Column("CGST", 100, alignEnd = true))
                if (report.hasIgst) add(Column("IGST", 100, alignEnd = true))
                if (report.hasVat) add(Column("VAT", 100, alignEnd = true))
                add(Column("TAX AMT", 110, alignEnd = true))
                add(Column("AMOUNT", 130, alignEnd = true))
            }
            ReportView.OPERATOR -> {
                add(Column("OPERATOR", 180, alignEnd = false))
                add(Column("BILLS", 80, alignEnd = true))
                add(Column("AMOUNT", 130, alignEnd = true))
            }
        }
    }

    /** "2026-11-11 01:30:12" as "11-11-2026 01:30" - the real moment, next to its business day. */
    private fun dateTime(value: String): String = "${pretty(value)} ${value.drop(11).take(5)}".trim()

    override fun rowsOf(report: ShiftDayReportDao.Report): List<List<String>> =
        if (report.view == ReportView.BILL) report.lines.map { line ->
            buildList {
                add(pretty(line.businessDate))
                add(shiftName(line.shift))
                add(line.billNumber)
                add(dateTime(line.dateTime))
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
        } else report.groups.map { g ->
            buildList {
                add(pretty(g.businessDate))
                add(shiftName(g.shift))
                add(g.name)
                when (report.view) {
                    ReportView.ITEM -> add(quantity(g.quantity))
                    ReportView.TABLE -> add(g.detail.ifBlank { "-" })
                    else -> Unit
                }
                add(g.bills.toString())
                if (report.view == ReportView.TAX) {
                    add(money(g.taxable))
                    add(money(g.sgst))
                    add(money(g.cgst))
                    if (report.hasIgst) add(money(g.igst))
                    if (report.hasVat) add(money(g.vat))
                    add(money(g.tax))
                }
                add(money(g.amount))
            }
        }

    override fun summaryOf(report: ShiftDayReportDao.Report): List<Pair<String, String>> =
        buildList {
            // Each shift's share first - that is what the report is run to read.
            report.byShiftInView.forEach { t ->
                add("${t.shift?.name ?: OUTSIDE_SHIFTS} (${t.bills} bill${if (t.bills == 1) "" else "s"})" to money(t.amount))
            }
            add("Total Bills" to report.billCount.toString())
            when (report.view) {
                ReportView.BILL -> {
                    add("Total Amt" to money(report.totalMrp))
                    add("Total SGST" to money(report.totalSgst))
                    add("Total CGST" to money(report.totalCgst))
                    if (report.hasIgst) add("Total IGST" to money(report.totalIgst))
                    if (report.hasVat) add("Total VAT" to money(report.totalVat))
                    add("Total Disc." to money(report.totalDiscount))
                    addCharges(report)
                }
                ReportView.ITEM -> {
                    add("Total Qty" to quantity(report.totalQuantity))
                    // What the bills carried besides their items, so the items' total
                    // can be squared with the bills' total.
                    addCharges(report)
                    add("Bills Total" to money(report.totalAmount))
                }
                ReportView.PAYMENT -> report.byMode.forEach { (mode, amount) -> add(mode to money(amount)) }
                ReportView.TAX -> {
                    add("Taxable Amt" to money(report.viewTaxable))
                    add("Total SGST" to money(report.viewSgst))
                    add("Total CGST" to money(report.viewCgst))
                    if (report.hasIgst) add("Total IGST" to money(report.viewIgst))
                    if (report.hasVat) add("Total VAT" to money(report.viewVat))
                    add("Total Tax" to money(report.viewTax))
                    // As on the item view: what the bills carried besides their items.
                    addCharges(report)
                    add("Bills Total" to money(report.totalAmount))
                }
                ReportView.TABLE, ReportView.WAITER, ReportView.OPERATOR -> Unit
            }
        }

    /** The charges a bill carries on top of its items - left off where they are nothing. */
    private fun MutableList<Pair<String, String>>.addCharges(report: ShiftDayReportDao.Report) {
        if (report.totalServiceCharge > 0.005) add("Service Charge" to money(report.totalServiceCharge))
        if (report.totalOtherCharges > 0.005) add("Extra Charges" to money(report.totalOtherCharges))
        if (report.totalParcelCharge > 0.005) add("Parcel Charge" to money(report.totalParcelCharge))
        if (kotlin.math.abs(report.totalRoundOff) > 0.005) add("Round Off" to money(report.totalRoundOff))
    }

    override fun totalOf(report: ShiftDayReportDao.Report): Pair<String, String> =
        if (report.view.needsItems) "Items Total" to money(report.viewAmount)
        else "Total Amount" to money(report.totalAmount)

    override fun emptyMessage(
        report: ShiftDayReportDao.Report,
        fromDate: String,
        toDate: String
    ): Pair<String, String> = when {
        shifts.isEmpty() -> "No bills" to
            "No bills between ${pretty(fromDate)} and ${pretty(toDate)}. No shifts are set up, so each " +
                "day runs midnight to midnight - add the shop's shifts in Master › Shifts to report by them."
        else -> "No bills" to
            "${report.shiftFilter?.name ?: "No shift"} raised no bills on ${period(report)} " +
                "(${hours(report)})."
    }

    /** "2026-08-12" as "12-08-26" - how the F.DT / TO.DT line has always read. */
    private fun shortDate(date: String): String =
        pretty(date).let { it.take(6) + it.takeLast(2) }

    /**
     * The printed slip: a band for each business day's shift and its rows under it - the
     * bills, or the items, modes, tables or waiters they were totalled by - and every
     * shift's total in the summary at the foot.
     */
    override fun printContent(report: ShiftDayReportDao.Report): PeriodReportRenderer.Content {
        // (business date, shift) of every printed row, in order - what the bands are cut from.
        val keys: List<Pair<String, ShiftDao.Shift?>>
        val columns: List<String>
        val rows: List<List<String>>
        if (report.view == ReportView.BILL) {
            keys = report.lines.map { it.businessDate to it.shift }
            columns = listOf("TIME", "BILL", "MODE", "TOTAL")
            rows = report.lines.map {
                listOf(it.dateTime.drop(11).take(5), it.billNumber, it.payMode, money(it.netAmount))
            }
        } else {
            keys = report.groups.map { it.businessDate to it.shift }
            columns = when (report.view) {
                ReportView.ITEM -> listOf("ITEM", "QTY", "AMOUNT")
                ReportView.PAYMENT -> listOf("MODE", "BILLS", "AMOUNT")
                ReportView.TABLE -> listOf("TABLE", "BILLS", "AMOUNT")
                ReportView.TAX -> listOf("TAX", "TAXABLE", "TAX AMT", "AMOUNT")
                ReportView.OPERATOR -> listOf("OPERATOR", "BILLS", "AMOUNT")
                else -> listOf("WAITER", "BILLS", "AMOUNT")
            }
            rows = report.groups.map { g ->
                when (report.view) {
                    ReportView.TAX -> listOf(g.name, money(g.taxable), money(g.tax), money(g.amount))
                    else -> listOf(
                        if (report.view == ReportView.TABLE) tableName(g) else g.name,
                        if (report.view == ReportView.ITEM) quantity(g.quantity) else g.bills.toString(),
                        money(g.amount)
                    )
                }
            }
        }
        var lastBand: String? = null
        val bands = keys.map { (date, shift) ->
            val band = "${pretty(date)}  ${shiftName(shift).uppercase()}"
            if (band != lastBand) band.also { lastBand = it } else null
        }
        return PeriodReportRenderer.Content(
            title = TITLE,
            period = period(report),
            subtitle = "${report.billCount} bill(s)",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = "F.DT:${shortDate(report.fromDate)}" to "TO.DT:${shortDate(report.toDate)}",
            heading = listOf(
                "SHIFT" to (report.shiftFilter?.label ?: ALL_SHIFTS.uppercase()),
                "MODE" to report.view.label.uppercase(),
                "HOURS" to hours(report)
            ),
            columns = columns,
            rows = rows,
            bands = bands,
            // A bill's four short figures share the roll evenly; an item's name wants the room.
            evenColumns = report.view != ReportView.ITEM,
            // The item and waiter names are the shop's own, and go into the print language.
            nameColumns = when (report.view) {
                ReportView.ITEM, ReportView.WAITER, ReportView.OPERATOR -> setOf(0)
                else -> emptySet()
            },
            summary = summaryOf(report).map { (label, value) -> label.uppercase() to value },
            total = totalOf(report).let { (label, value) -> label.uppercase() to PrintType.grandTotal(value) },
            emptyNote = "No bills for this business day."
        )
    }

    companion object {
        /** Named once: the tile, the drawer entry and this screen all use it. */
        const val TITLE = "Shift Day Report"

        private const val ALL_SHIFTS = "All Shifts"

        private const val OUTSIDE_SHIFTS = "Outside shift hours"
    }
}
