package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.database.StockDao
import com.example.synergic_pos_offline.database.StockReportDao
import com.example.synergic_pos_offline.utils.PeriodReportRenderer

/**
 * Low Stock Report - the Stock Report, narrowed to what needs restocking.
 *
 * The same three figures per item over the same date range, and the same slip at the
 * end of it; the only difference is which items are listed. That is deliberate rather
 * than lazy: an operator who has read one of these has read both, and a "low stock"
 * report that arranged the same numbers differently would be a second thing to learn
 * for no gain.
 *
 * What is on the shelf **now** is what decides whether an item is listed, not what it
 * did over the period. An item that sold steadily all month and is empty this morning
 * is exactly what this report is opened to find; one that sold nothing and is still
 * fully stocked is not, however quiet its month was. The dates therefore shape the
 * PUR.STOCK and SLD.STK. columns - the story of how it got here - and not the list.
 *
 * The threshold is General Settings' Alert Quantity, the same figure the sale
 * screen's badges and the dashboard's alerts use. There is one rule about what "low"
 * means on this till, and this is not the place to invent a second.
 */
class LowStockReportFragment : PeriodReportFragment<StockReportDao.Report>() {

    override val screenTitle = "Low Stock Report"

    override val rowNoun = "items"

    private val dao: StockReportDao by lazy { StockReportDao(requireContext()) }

    /**
     * What counts as low here - 0 when Stock Alert is off, which leaves only the
     * items that are actually empty.
     *
     * Read per run rather than held: the setting can be changed between two runs of
     * the report, and the second should answer the question as it stands.
     */
    private fun alertQty(): Double {
        val settings = GeneralSettingsDao(requireContext()).load()
        return if (settings.stockFlag && settings.stockAlert) settings.stockAlertQty.toDouble() else 0.0
    }

    /**
     * The stock report over the period, with everything comfortably stocked removed.
     *
     * Filtered here rather than in a query of its own so the two reports cannot come
     * to disagree about what an item's three figures are - there is one reading of
     * the stock, and this is a view of it.
     */
    override fun load(fromDate: String, toDate: String): StockReportDao.Report {
        val threshold = alertQty()
        val full = dao.between(fromDate, toDate)
        return full.copy(
            lines = full.lines.filter { it.current <= 0.0 || (threshold > 0.0 && it.current <= threshold) }
        )
    }

    override fun isEmpty(report: StockReportDao.Report): Boolean = report.isEmpty

    override fun headline(report: StockReportDao.Report): String =
        "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}   •   " +
            "${report.itemCount} item(s)   •   ${report.outOfStock} out of stock"

    override fun columnsFor(report: StockReportDao.Report): List<Column> = listOf(
        Column("SL NO", 70, alignEnd = false),
        Column("ITEM NAME", 260, alignEnd = false),
        Column("PUR.STOCK", 120, alignEnd = true),
        Column("SLD.STK.", 120, alignEnd = true),
        Column("C.STOCK", 120, alignEnd = true)
    )

    /**
     * Emptiest first, so the shelf that is already bare is at the top of the page.
     *
     * The Stock Report lists the master in its own order, which is right for a report
     * read end to end. This one is read from the top and acted on, and the order is
     * the difference between a list and a worklist.
     */
    override fun rowsOf(report: StockReportDao.Report): List<List<String>> =
        report.lines
            .sortedWith(compareBy({ it.current }, { it.name }))
            .mapIndexed { index, line ->
                listOf(
                    (index + 1).toString(),
                    line.name,
                    StockDao.trim(line.purchased),
                    StockDao.trim(line.sold),
                    StockDao.trim(line.current)
                )
            }

    override fun summaryOf(report: StockReportDao.Report): List<Pair<String, String>> =
        listOf(
            "Needs Attention" to report.itemCount.toString(),
            "Out Of Stock" to report.outOfStock.toString(),
            "Running Low" to (report.itemCount - report.outOfStock).toString(),
            "Alert Quantity" to StockDao.trim(alertQty())
        )

    /** What is left across everything listed - how much of a re-order this is. */
    override fun totalOf(report: StockReportDao.Report): Pair<String, String> =
        "Total In Stock" to StockDao.trim(report.totalCurrent)

    /**
     * Says which of the two reasons there is nothing to report, because they call
     * for opposite responses: a stocked shop needs no action, and a till with the
     * alert switched off is not answering the question at all.
     */
    override fun emptyMessage(
        report: StockReportDao.Report,
        fromDate: String,
        toDate: String
    ): Pair<String, String> =
        if (alertQty() <= 0.0) {
            "No alert quantity set" to
                "Nothing is out of stock. To list items that are merely running low, " +
                    "switch on Stock Alert and set an Alert Quantity in General Settings."
        } else {
            "Stock is fine" to
                "Nothing is out of stock or at or below the alert quantity of " +
                    "${StockDao.trim(alertQty())}."
        }

    /** "2026-08-12" as "12-08-26" - how the F.DT / TO.DT line has always read. */
    private fun shortDate(date: String): String =
        pretty(date).let { it.take(6) + it.takeLast(2) }

    override fun printContent(report: StockReportDao.Report): PeriodReportRenderer.Content =
        PeriodReportRenderer.Content(
            title = "Low Stock Report",
            period = "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}",
            subtitle = "${report.itemCount} item(s)",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = "F.DT:${shortDate(report.fromDate)}" to "TO.DT:${shortDate(report.toDate)}",
            // The item name, put into the print language as it is on a bill.
            nameColumns = setOf(0),
            columns = listOf("ITEM NAME"),
            rows = rowsOf(report).map { listOf(it[1]) },
            columns2 = listOf("", "PUR.STOCK", "SLD.STK.", "C.STOCK"),
            rows2 = rowsOf(report).map { listOf("", it[2], it[3], it[4]) },
            ruleBetweenRows = true,
            evenColumns = true,
            summary = summaryOf(report).map { (label, value) -> label.uppercase() to value },
            total = totalOf(report).let { (label, value) -> label.uppercase() to value },
            emptyNote = "Nothing is running low."
        )
}
