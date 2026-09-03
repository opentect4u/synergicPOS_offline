package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.TaxReportDao
import com.example.synergic_pos_offline.utils.PeriodReportRenderer
import java.util.Locale

/**
 * Tax Report - what was taxed over a period, at what rate, and what that came to.
 *
 * A line per tax and slab, which is the shape a return is filed in. The screen behind
 * the Tax Report tile, and the report a filing is made from.
 *
 * Everything a date-range report does comes from [PeriodReportFragment]; what is here
 * is only what makes this a tax report.
 */
class TaxReportFragment : PeriodReportFragment<TaxReportDao.Report>() {

    override val screenTitle = "Tax Report"

    override val rowNoun = "slabs"

    private val dao: TaxReportDao by lazy { TaxReportDao(requireContext()) }

    override fun load(fromDate: String, toDate: String): TaxReportDao.Report =
        dao.between(fromDate, toDate)

    override fun isEmpty(report: TaxReportDao.Report): Boolean = report.isEmpty

    override fun headline(report: TaxReportDao.Report): String =
        "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}   •   " +
            "${report.slabCount} slab(s)"

    override fun columnsFor(report: TaxReportDao.Report): List<Column> = listOf(
        Column("TAX", 100, alignEnd = false),
        Column("AMOUNT", 150, alignEnd = true),
        Column("GST%", 100, alignEnd = true),
        Column("TAX AMOUNT", 150, alignEnd = true)
    )

    override fun rowsOf(report: TaxReportDao.Report): List<List<String>> =
        report.lines.map { line ->
            listOf(line.tax, money(line.amount), percent(line.rate), money(line.taxAmount))
        }

    override fun summaryOf(report: TaxReportDao.Report): List<Pair<String, String>> =
        buildList {
            add("Tax Slabs" to report.slabCount.toString())
            // A flat pair of totals, not a slab of their own - a charge is not
            // sold at a rate to file against, so it does not belong among the
            // rows above. Shown only where the period actually carried one.
            if (report.charges.service > 0.005) add("Service Charge" to money(report.charges.service))
            if (report.charges.other > 0.005) add("Extra Charges" to money(report.charges.other))
        }

    /** The one figure the report is read for. */
    override fun totalOf(report: TaxReportDao.Report): Pair<String, String> =
        "Total" to money(report.totalTax)

    /**
     * The printed slip, in the format these tills have always printed it.
     *
     * Each tax names itself on a line of its own and its slabs follow underneath, so
     * a shop running three rates reads three figures under one heading rather than
     * repeating "SGST" down the page. That is why the rows are a mixture of one-cell
     * headings and four-cell figures.
     */
    override fun printContent(report: TaxReportDao.Report): PeriodReportRenderer.Content {
        val rows = mutableListOf<List<String>>()
        var heading: String? = null
        report.lines.forEach { line ->
            if (line.tax != heading) {
                heading = line.tax
                rows.add(listOf("${line.tax.padEnd(TAX_LABEL_WIDTH)}:"))
            }
            rows.add(listOf("", money(line.amount), percent(line.rate), money(line.taxAmount)))
        }

        return PeriodReportRenderer.Content(
            title = "Tax Report",
            period = "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}",
            subtitle = "${report.slabCount} slab(s)",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = "F.DT:${shortDate(report.fromDate)}" to "TO.DT:${shortDate(report.toDate)}",
            columns = listOf("", "AMOUNT", "GST%", "TAX AMOUNT"),
            evenColumns = true,
            rows = rows,
            footerRow = listOf("TOTAL :", "", "", money(report.totalTax)),
            summary = buildList {
                if (report.charges.service > 0.005) add("SERVICE CHG :" to money(report.charges.service))
                if (report.charges.other > 0.005) add("EXTRA CHGS  :" to money(report.charges.other))
            },
            emptyNote = "No tax was charged in this period."
        )
    }

    /** "2.5" as "2.50" - a rate reads as a rate, to the same places as the money. */
    private fun percent(rate: Double): String = String.format(Locale.US, "%.2f", rate)

    /** "2026-08-12" as "12-08-26" - how the F.DT / TO.DT line has always read. */
    private fun shortDate(date: String): String =
        pretty(date).let { it.take(6) + it.takeLast(2) }

    override fun emptyMessage(
        report: TaxReportDao.Report,
        fromDate: String,
        toDate: String
    ): Pair<String, String> = "No tax in this period" to
        "Nothing taxable was billed between ${pretty(fromDate)} and ${pretty(toDate)}."

    private companion object {
        /** "SGST" and "CGST" are four; padding to this lines their colons up. */
        const val TAX_LABEL_WIDTH = 5
    }
}
