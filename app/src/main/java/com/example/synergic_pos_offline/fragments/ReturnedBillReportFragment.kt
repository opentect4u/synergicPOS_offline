package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.database.ReturnedBillReportDao
import com.example.synergic_pos_offline.utils.PeriodReportRenderer

/**
 * Returned Bill Report - the bill wise report read over returns: every bill-wise
 * return of a period, the bill it came off, what came back and the tax that came
 * back with it.
 *
 * Only bill-wise returns are on it, because only they have a bill to be reported
 * against - see [ReturnedBillReportDao]. Where a till is set to item-wise returns
 * this report will stay empty however many goods come back, so the empty state says
 * so rather than leaving the operator to wonder whether the returns were saved.
 *
 * Everything a date-range report does comes from [PeriodReportFragment]; what is
 * here is only what makes this report a return report.
 */
class ReturnedBillReportFragment : PeriodReportFragment<ReturnedBillReportDao.Report>() {

    override val screenTitle = "Returned Bill Report"

    override val rowNoun = "returns"

    private val dao: ReturnedBillReportDao by lazy { ReturnedBillReportDao(requireContext()) }

    override fun load(fromDate: String, toDate: String): ReturnedBillReportDao.Report =
        dao.between(fromDate, toDate)

    override fun isEmpty(report: ReturnedBillReportDao.Report): Boolean = report.isEmpty

    override fun headline(report: ReturnedBillReportDao.Report): String =
        "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}   •   " +
            "${report.returnCount} return(s)"

    /**
     * The return, the bill behind it, what came back, and what it cost to take back.
     *
     * IGST, VAT and DISCOUNT are only columns where the period holds them: a shop
     * that never sells inter-state or never discounts would otherwise read stripes of
     * 0.00 across the report, pushing the columns that matter off the right edge.
     */
    override fun columnsFor(report: ReturnedBillReportDao.Report): List<Column> = buildList {
        add(Column("RETURN NO", 120, alignEnd = false))
        add(Column("DATE", 100, alignEnd = false))
        add(Column("BILL NO", 120, alignEnd = false))
        add(Column("QTY", 70, alignEnd = true))
        add(Column("GROSS", 100, alignEnd = true))
        add(Column("CGST", 90, alignEnd = true))
        add(Column("SGST", 90, alignEnd = true))
        if (report.hasIgst) add(Column("IGST", 90, alignEnd = true))
        if (report.hasVat) add(Column("VAT", 90, alignEnd = true))
        if (report.hasDiscount) add(Column("DISCOUNT", 100, alignEnd = true))
        add(Column("REFUND", 120, alignEnd = true))
    }

    override fun rowsOf(report: ReturnedBillReportDao.Report): List<List<String>> =
        report.lines.map { line ->
            buildList {
                add(line.returnNumber)
                add(pretty(line.returnDate))
                add(line.billNumber)
                add(quantity(line.quantity))
                add(money(line.gross))
                // A tax that did not apply to the bill this came off reads as a dash
                // rather than 0.00: zero says it was charged and came to nothing, a
                // dash says it was never in play.
                add(if (line.isVat) "-" else money(line.cgst))
                add(if (line.isVat) "-" else money(line.sgst))
                if (report.hasIgst) add(if (line.isVat) "-" else money(line.igst))
                if (report.hasVat) add(if (line.isVat) money(line.vat) else "-")
                if (report.hasDiscount) add(money(line.discount))
                add(money(line.refund))
            }
        }

    override fun summaryOf(report: ReturnedBillReportDao.Report): List<Pair<String, String>> =
        buildList {
            add("Total Returns" to report.returnCount.toString())
            add("Lines Returned" to report.totalLines.toString())
            add("Total Quantity" to quantity(report.totalQuantity))
            add("Total Gross" to money(report.totalGross))
            if (report.hasDiscount) add("Total Discount" to money(report.totalDiscount))
            add("Total CGST" to money(report.totalCgst))
            add("Total SGST" to money(report.totalSgst))
            add("Total IGST" to money(report.totalIgst))
            if (report.hasVat) add("Total VAT" to money(report.totalVat))
            add("Total Tax Reversed" to money(report.totalTax))
        }

    /** The one figure the report is read for: what went back over the counter. */
    override fun totalOf(report: ReturnedBillReportDao.Report): Pair<String, String> =
        "Total Refunded" to money(report.totalRefund)

    /**
     * The printed report.
     *
     * A roll fits about four columns, so the return, its bill and the refund go on
     * the first line and the tax behind that refund goes on a second under it -
     * labelled, so it can be read without a heading of its own to look up.
     */
    override fun printContent(report: ReturnedBillReportDao.Report): PeriodReportRenderer.Content =
        PeriodReportRenderer.Content(
            title = "Returned Bill Report",
            period = "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}",
            subtitle = "${report.returnCount} return(s)",
            columns = listOf("RETURN NO", "QTY", "GROSS", "REFUND"),
            rows = report.lines.map { line ->
                listOf(
                    line.returnNumber,
                    quantity(line.quantity),
                    money(line.gross),
                    money(line.refund)
                )
            },
            details = report.lines.map { line ->
                buildString {
                    append("  BILL ${line.billNumber}  ${pretty(line.returnDate)}")
                    if (line.isVat) {
                        append("  VAT ${money(line.vat)}")
                    } else {
                        append("  CGST ${money(line.cgst)}  SGST ${money(line.sgst)}")
                        if (report.hasIgst) append("  IGST ${money(line.igst)}")
                    }
                    if (report.hasDiscount) append("  DISC ${money(line.discount)}")
                }
            },
            summary = summaryOf(report).map { (label, value) -> label.uppercase() to value },
            total = totalOf(report).let { (label, value) -> label.uppercase() to value },
            emptyNote = "No returns in this period."
        )

    /**
     * Why the report is empty, which is a different answer depending on how the till
     * is set up.
     *
     * A shop taking item-wise returns can hand back goods all day without a single
     * row ever appearing here, and "no returns in this period" would send someone
     * looking for lost records. So where the period holds item-wise returns, or the
     * till is set to take them that way, the hint says which it is.
     */
    override fun emptyMessage(
        report: ReturnedBillReportDao.Report,
        fromDate: String,
        toDate: String
    ): Pair<String, String> {
        val period = "between ${pretty(fromDate)} and ${pretty(toDate)}"
        val settings = GeneralSettingsDao(requireContext()).load()

        return when {
            report.itemWiseCount > 0 -> "No bill-wise returns in this period" to
                "${report.itemWiseCount} item-wise return(s) were taken $period. Those are " +
                    "taken from the item with no bill behind them, so they cannot be reported " +
                    "against one."

            !settings.saleReturn -> "No returns in this period" to
                "Nothing was returned $period. Sale Return is switched off, so no returns " +
                    "can be taken - turn it on under Settings → General Settings."

            settings.returnMode == GeneralSettingsDao.ReturnMode.ITEM_WISE ->
                "No bill-wise returns in this period" to
                    "Nothing was returned against a bill $period. This till is set to " +
                        "item-wise returns, which have no bill behind them - switch Return " +
                        "Mode to Bill-wise under Settings → General Settings to fill this report."

            else -> "No returns in this period" to "Nothing was returned $period."
        }
    }
}
