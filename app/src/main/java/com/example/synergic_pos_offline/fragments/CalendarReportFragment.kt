package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.CalendarReportDao
import com.example.synergic_pos_offline.utils.CalendarGrain
import com.example.synergic_pos_offline.utils.PeriodReportRenderer

/**
 * The Day Wise, Month Wise and Year Wise reports: takings totalled by the calendar.
 *
 * One screen for the three of them. They differ in a single thing - how wide a row
 * is - and that is [grain], which is already what decides the pickers, the field
 * labels, the grouping and how a period reads. Everything else about them is the
 * same report, so writing it three times would only have created three places for
 * the same wording and the same columns to drift apart.
 *
 * The three subclasses below are the whole difference between them.
 */
abstract class CalendarReportFragment : PeriodReportFragment<CalendarReportDao.Report>() {

    /** What one row is - "Day", "Month", "Year". Heads the first column. */
    protected abstract val periodLabel: String

    private val dao: CalendarReportDao by lazy { CalendarReportDao(requireContext()) }

    override val rowNoun: String get() = periodLabel.lowercase() + "s"

    override fun load(fromDate: String, toDate: String): CalendarReportDao.Report =
        dao.between(fromDate, toDate, grain)

    override fun isEmpty(report: CalendarReportDao.Report): Boolean = report.isEmpty

    override fun headline(report: CalendarReportDao.Report): String =
        "${grain.label(report.fromPeriod)}  to  ${grain.label(report.toPeriod)}   •   " +
            "${report.periodCount} ${rowNoun}   •   ${report.totalBills} bill(s)"

    override fun columnsFor(report: CalendarReportDao.Report): List<Column> = listOf(
        Column(periodLabel.uppercase(), 160, alignEnd = false),
        Column("TOTAL BILLS", 130, alignEnd = true),
        Column("TOTAL AMOUNT", 150, alignEnd = true)
    )

    override fun rowsOf(report: CalendarReportDao.Report): List<List<String>> =
        report.lines.map { line ->
            listOf(
                grain.label(line.period),
                line.billCount.toString(),
                money(line.totalAmount)
            )
        }

    override fun summaryOf(report: CalendarReportDao.Report): List<Pair<String, String>> =
        buildList {
            add("${periodLabel}s With Sales" to report.periodCount.toString())
            add("Total Bills" to report.totalBills.toString())
            // Worth naming only when there is more than one to compare against.
            if (report.periodCount > 1) {
                report.busiest?.let {
                    add("Best $periodLabel" to "${grain.label(it.period)}  ${money(it.totalAmount)}")
                }
            }
            // Each tax its own line rather than one blended figure - a GST return is
            // filed against SGST and CGST separately, and IGST/VAT earn their place
            // only where a bill in the range actually carried one.
            add("Total SGST" to money(report.totalSgst))
            add("Total CGST" to money(report.totalCgst))
            if (report.hasIgst) add("Total IGST" to money(report.totalIgst))
            if (report.hasVat) add("Total VAT" to money(report.totalVat))
            // Shown only where the range actually carried one - a shop that never
            // charges Service or an Extra Charge should not read a zero row saying so.
            if (report.totalServiceCharge > 0.005) add("Service Charge" to money(report.totalServiceCharge))
            if (report.totalOtherCharges > 0.005) add("Extra Charges" to money(report.totalOtherCharges))
        }

    /** The one figure the report is read for. */
    override fun totalOf(report: CalendarReportDao.Report): Pair<String, String> =
        "Total Amount" to money(report.totalAmount)

    /** The slip's own name - "DAILY REPORT", "MONTH - WISE REPORT". */
    protected abstract val printTitle: String

    /** How a period is banded on the slip - "DAY : 07-08-2026". */
    protected abstract fun band(period: String): String

    /**
     * Whether the slip repeats its range on an F.DT / TO.DT line under the head.
     *
     * The daily slip carries one; the month and year slips do not, because their
     * bands already spell the range out - a month report of one month would state
     * "08-2026" twice over, once as a range and once as the only band under it.
     */
    protected open val showsRangeLine: Boolean = true

    /** What the discount column is headed. Abbreviated where the band is wider. */
    protected open val discountHeader: String = "DISCOUNT"

    /**
     * The printed report, in the format these tills have always printed.
     *
     * The period is a band across the slip rather than a first column: every figure
     * under it belongs to that day, and setting the date beside a single row of
     * figures would say it twice and cost the width the figures need. See
     * [PeriodReportRenderer.Style.CLASSIC].
     */
    override fun printContent(report: CalendarReportDao.Report): PeriodReportRenderer.Content =
        PeriodReportRenderer.Content(
            title = printTitle,
            period = "${grain.label(report.fromPeriod)}  to  ${grain.label(report.toPeriod)}",
            subtitle = "${report.periodCount} $rowNoun · ${report.totalBills} bill(s)",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = if (!showsRangeLine) null else
                "F.DT:${shortDate(report.fromPeriod)}" to "TO.DT:${shortDate(report.toPeriod)}",
            columns = listOf("BILLS", "TAX", discountHeader, "AMOUNT"),
            bands = report.lines.map { band(it.period) },
            rows = report.lines.map { line ->
                listOf(
                    line.billCount.toString(),
                    money(line.totalTax),
                    money(line.totalDiscount),
                    money(line.totalAmount)
                )
            },
            summary = buildList {
                add("TOTAL BILLS :" to report.totalBills.toString())
                add("TOTAL SGST  :" to money(report.totalSgst))
                add("TOTAL CGST  :" to money(report.totalCgst))
                if (report.hasIgst) add("TOTAL IGST  :" to money(report.totalIgst))
                if (report.hasVat) add("TOTAL VAT   :" to money(report.totalVat))
                add("TOTAL DISC. :" to money(report.totalDiscount))
                if (report.totalServiceCharge > 0.005) add("SERVICE CHG :" to money(report.totalServiceCharge))
                if (report.totalOtherCharges > 0.005) add("EXTRA CHGS  :" to money(report.totalOtherCharges))
            },
            total = "TOTAL AMOUNT:" to money(report.totalAmount),
            emptyNote = "No bills in this range."
        )

    /**
     * A stored period as the head line states it - "01-08-26", "08-26", "2026".
     *
     * Two-digit years, which is how the F.DT / TO.DT line has always read: it is a
     * range being confirmed at a glance, not a date being recorded.
     */
    private fun shortDate(period: String): String = when (grain) {
        CalendarGrain.MONTH -> grain.label(period)
        CalendarGrain.YEAR -> period
        // Day, and the minute grain no calendar report uses - both read as a date.
        else -> grain.label(period).take(10).let { it.take(6) + it.takeLast(2) }
    }

    override fun emptyMessage(
        report: CalendarReportDao.Report,
        fromDate: String,
        toDate: String
    ): Pair<String, String> = "No bills in this range" to
        "Nothing was billed between ${grain.label(fromDate)} and ${grain.label(toDate)}."
}

/** Day Wise Report - a line per day, oldest first. Prints as the DAILY REPORT. */
class DayWiseReportFragment : CalendarReportFragment() {
    override val screenTitle = "Day-Wise Report"
    override val periodLabel = "Day"
    override val grain = CalendarGrain.DAY
    override val printTitle = "DAILY REPORT"
    override fun band(period: String) = "DAY : ${grain.label(period)}"
}

/** Month Wise Report - a line per month, asked for as a range of months. */
class MonthWiseReportFragment : CalendarReportFragment() {
    override val screenTitle = "Month Wise Report"
    override val periodLabel = "Month"
    override val grain = CalendarGrain.MONTH
    override val printTitle = "MONTH - WISE REPORT"
    override val showsRangeLine = false
    override val discountHeader = "DISC."

    // "08-2026", not "Aug 2026": the band on this slip has always been numeric, and
    // a month read as a number sorts by eye down a roll of them.
    override fun band(period: String) = "MONTH :" + period.let {
        val parts = it.split("-")
        if (parts.size >= 2) "${parts[1]}-${parts[0]}" else it
    }
}

/** Year Wise Report - a line per year, asked for as a range of years. */
class YearWiseReportFragment : CalendarReportFragment() {
    override val screenTitle = "Year Wise Report"
    override val periodLabel = "Year"
    override val grain = CalendarGrain.YEAR
    override val printTitle = "YEAR - WISE REPORT"
    override val showsRangeLine = false
    override val discountHeader = "DISC."
    override fun band(period: String) = "YEAR :$period"
}
