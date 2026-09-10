package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.ItemWiseReportDao
import com.example.synergic_pos_offline.database.StockDao
import com.example.synergic_pos_offline.utils.CalendarGrain
import com.example.synergic_pos_offline.utils.PeriodReportRenderer

/**
 * The Item Wise and Time Wise item sale reports: what sold, a line per item.
 *
 * One screen for both. They differ in a single thing - how the range is asked for -
 * and that is [grain], which already decides the pickers, the field labels and the
 * comparison the query makes. Everything else about them is the same report, so
 * writing it twice would only have created two places for the same columns and the
 * same totals to drift apart.
 *
 * The two subclasses below are the whole difference between them.
 */
abstract class ItemSaleReportFragment : PeriodReportFragment<ItemWiseReportDao.Report>() {

    /** What the slip is called - "ITEM-WISE SALE REPORT". */
    protected abstract val printTitle: String

    override val rowNoun = "items"

    private val dao: ItemWiseReportDao by lazy { ItemWiseReportDao(requireContext()) }

    override fun load(fromDate: String, toDate: String): ItemWiseReportDao.Report =
        dao.between(fromDate, toDate, grain)

    override fun isEmpty(report: ItemWiseReportDao.Report): Boolean = report.isEmpty

    override fun headline(report: ItemWiseReportDao.Report): String =
        "${grain.label(report.fromDate)}  to  ${grain.label(report.toDate)}   •   " +
            "${report.itemCount} item(s)"

    override fun columnsFor(report: ItemWiseReportDao.Report): List<Column> = listOf(
        Column("SL NO", 70, alignEnd = false),
        Column("ITEM NAME", 260, alignEnd = false),
        Column("QUANTITY", 100, alignEnd = true),
        Column("AMOUNT", 120, alignEnd = true),
        // SGST before CGST, as the slip has always set them.
        Column("SGST", 100, alignEnd = true),
        Column("CGST", 100, alignEnd = true)
    )

    override fun rowsOf(report: ItemWiseReportDao.Report): List<List<String>> =
        report.lines.map { line ->
            listOf(
                line.serial.toString(),
                line.name,
                StockDao.trim(line.quantity),
                money(line.amount),
                money(line.sgst),
                money(line.cgst)
            )
        }

    override fun summaryOf(report: ItemWiseReportDao.Report): List<Pair<String, String>> =
        buildList {
            add("Total Items" to report.itemCount.toString())
            add("Total Qty" to StockDao.trim(report.totalQuantity))
            // Named to match bill-wise's own summary line, which now holds the same
            // figure: what the goods were taxed on, across the period.
            add("Taxable Amount" to money(report.totalAmount))
            add("SGST Amount" to money(report.totalSgst))
            add("CGST Amount" to money(report.totalCgst))
            if (report.hasIgst) add("IGST Amount" to money(report.totalIgst))
            add("VAT Amount" to money(report.totalVat))
            // ALWAYS SHOWN, zero or not. These used to appear only
            // where the period actually carried one, on the reasoning that a shop which
            // never charges an Extra Charge should not read a row of zeroes saying so.
            // What that produced instead was a summary whose shape changed with the
            // period: a reader who knew the report had a Discount line found it absent
            // and could not tell "nothing was discounted" from "this report does not
            // total discounts", which is the worse of the two doubts to leave.
            add("Discount Amount" to money(report.totalDiscount))
            add("Service Charge" to money(report.totalServiceCharge))
            add("Extra Charges" to money(report.totalOtherCharges))
            add("Parcel Charge" to money(report.totalParcelCharge))
            // Last - the final adjustment before the total below it, not a figure left
            // dangling after it.
            add("Round Off Amount" to money(report.totalRoundOff))
        }

    /**
     * The one figure the report is read for - and the one bill-wise is read for too.
     *
     * THE NET, not the taxable value. The two reports headline a figure each under
     * near-identical names, and they were not the same figure: bill-wise's Total
     * Amount is what the period was settled for, this one's Total Amt was what the
     * items were taxed on - so a period reading 880.00 on one screen read 862.86 on
     * the other, and the difference was the tax the reader could see listed above it.
     *
     * Both now headline what the period came to. What was sold before tax is still
     * here, as Item Amount in the summary, where bill-wise states the same figure
     * under Bill Amount.
     */
    override fun totalOf(report: ItemWiseReportDao.Report): Pair<String, String> =
        "Total Amount" to money(report.totalNetAmount)

    /**
     * The printed slip, in the format these tills have always printed it.
     *
     * Five figures per item is more than a roll holds across at a readable size, so
     * each item takes two lines: its name and how much of it went on the first, and
     * the money on the second. That is the one shape in which an item name long
     * enough to be recognised and four columns of figures can share the same paper.
     */
    override fun printContent(report: ItemWiseReportDao.Report): PeriodReportRenderer.Content =
        PeriodReportRenderer.Content(
            title = printTitle,
            period = "${grain.label(report.fromDate)}  to  ${grain.label(report.toDate)}",
            subtitle = "${report.itemCount} item(s)",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = "F.DT:${shortDate(report.fromDate)}" to "TO.DT:${shortDate(report.toDate)}",
            // The item name, put into the print language as it is on a bill.
            nameColumns = setOf(0),
            columns = listOf("ITEM NAME", "QUANTITY"),
            rows = report.lines.map { listOf(it.name, StockDao.trim(it.quantity)) },
            // The leading blank is what holds the money under the name above it
            // rather than out at the left margin.
            columns2 = listOf("", "AMOUNT", "SGST", "CGST"),
            rows2 = report.lines.map { listOf("", money(it.amount), money(it.sgst), money(it.cgst)) },
            // The same labels, in the same order, as the bill-wise slip prints - the
            // two reports state the same figures and should not make the reader
            // translate between two vocabularies to see that.
            summary = buildList {
                add("Total Qty" to StockDao.trim(report.totalQuantity))
                add("Taxable Amount" to money(report.totalAmount))
                add("SGST Amount" to money(report.totalSgst))
                add("CGST Amount" to money(report.totalCgst))
                if (report.hasIgst) add("IGST Amount" to money(report.totalIgst))
                add("VAT Amount" to money(report.totalVat))
                // The same lines the screen shows, in the same order and on the same
                // terms - always, zero or not. A slip that omitted what the screen
                // above it listed would be read as the two disagreeing.
                add("Discount Amount" to money(report.totalDiscount))
                add("Service Charge" to money(report.totalServiceCharge))
                add("Extra Charges" to money(report.totalOtherCharges))
                add("Parcel Charge" to money(report.totalParcelCharge))
                // Last before the total, not after it - the final adjustment the
                // total itself already includes, not a figure left dangling below it.
                add("Round Off Amount" to money(report.totalRoundOff))
                add("Total Amount" to money(report.totalNetAmount))
            }.map { (label, value) -> label.uppercase().padEnd(16) + " :" to value },
            emptyNote = "Nothing was sold in this period."
        )

    /**
     * The range as the F.DT / TO.DT line states it - "12-08-26", and the clock with
     * it where the report was asked for by the minute.
     */
    private fun shortDate(value: String): String {
        val day = pretty(value.take(10)).let { it.take(6) + it.takeLast(2) }
        return if (grain == CalendarGrain.MINUTE) "$day ${value.drop(11)}" else day
    }

    override fun emptyMessage(
        report: ItemWiseReportDao.Report,
        fromDate: String,
        toDate: String
    ): Pair<String, String> = "Nothing sold in this period" to
        "No items were billed between ${grain.label(fromDate)} and ${grain.label(toDate)}."
}

/** Item Wise Sale Report - a date range in, a line per item out. */
class ItemWiseReportFragment : ItemSaleReportFragment() {
    override val screenTitle = "Item Wise Report"
    override val printTitle = "Item-Wise Sale Report"
    override val grain = CalendarGrain.DAY
}

/**
 * Time Wise Item Sale Report - the same reading of the books, asked for by the
 * minute rather than by the day.
 *
 * The report that answers what sold over a lunch service, or between two shifts,
 * where a whole day is too blunt to see it.
 */
class TimeWiseItemReportFragment : ItemSaleReportFragment() {
    override val screenTitle = "Time Wise Item Report"
    override val printTitle = "Time-Wise Item Sale Report"
    override val grain = CalendarGrain.MINUTE
}
