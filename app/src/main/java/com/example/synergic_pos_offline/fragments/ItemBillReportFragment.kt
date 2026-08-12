package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.ItemBillReportDao
import com.example.synergic_pos_offline.database.StockDao
import com.example.synergic_pos_offline.utils.PeriodReportRenderer

/**
 * Item Bill Report - one item's bills over a period, a line each.
 *
 * The Item Wise Report says which item to look at; this is the looking. A date range
 * and an item go in, and every bill it appeared on comes out with how much of it
 * went, at what rate, and what that came to.
 *
 * Everything a date-range report does comes from [PeriodReportFragment], including
 * the item picker - what is here is only what makes this one item's own report.
 */
class ItemBillReportFragment : PeriodReportFragment<ItemBillReportDao.Report>() {

    override val screenTitle = "Item Bill Report"

    override val rowNoun = "bills"

    override val filterHint = "Item"

    /**
     * Typed at rather than scrolled: a master of a few hundred lines is no list to
     * page through, and whoever is running the report knows the name, the barcode or
     * the HSN code. Each entry carries all three so any of them finds it.
     */
    override val filterSearchable = true

    private val dao: ItemBillReportDao by lazy { ItemBillReportDao(requireContext()) }

    /** Read once: the product master does not change while a report is being run. */
    private val items: List<ItemBillReportDao.Item> by lazy { dao.items() }

    override val filterOptions: List<String> by lazy { items.map { it.label } }

    override fun load(fromDate: String, toDate: String): ItemBillReportDao.Report {
        val item = items.firstOrNull { it.label == filterChoice }
            // Nothing matched what is in the box - a typed fragment left unpicked, or
            // a till with no products at all. An empty report says so rather than
            // quietly reporting on whichever item happened to be first.
            ?: return ItemBillReportDao.Report(fromDate, toDate, null, emptyList())
        return dao.between(fromDate, toDate, item)
    }

    override fun isEmpty(report: ItemBillReportDao.Report): Boolean = report.isEmpty

    override fun headline(report: ItemBillReportDao.Report): String =
        "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}   •   " +
            "${report.item?.name.orEmpty()}   •   ${report.billCount} bill(s)"

    override fun columnsFor(report: ItemBillReportDao.Report): List<Column> = listOf(
        Column("BILL", 140, alignEnd = true),
        Column("QTY", 120, alignEnd = true),
        Column("RATE", 130, alignEnd = true),
        Column("AMOUNT", 150, alignEnd = true)
    )

    override fun rowsOf(report: ItemBillReportDao.Report): List<List<String>> =
        report.lines.map { line ->
            listOf(
                line.billNumber,
                money(line.quantity),
                money(line.rate),
                money(line.amount)
            )
        }

    override fun summaryOf(report: ItemBillReportDao.Report): List<Pair<String, String>> =
        listOf(
            "Total Qty" to money(report.totalQuantity),
            "Total Rate" to money(report.totalRate)
        )

    /** The one figure the report is read for. */
    override fun totalOf(report: ItemBillReportDao.Report): Pair<String, String> =
        "Total Amt" to money(report.totalAmount)

    /**
     * The printed slip, in the format these tills have always printed it: the item
     * named over the table, then a line per bill.
     *
     * Four columns of figures with no name among them, so the bill number is set to
     * the right with the rest - it reads as a figure, and a column that started at
     * the left would break the run of them.
     */
    override fun printContent(report: ItemBillReportDao.Report): PeriodReportRenderer.Content =
        PeriodReportRenderer.Content(
            title = "Item-Bill Report",
            period = "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}",
            subtitle = "${report.billCount} bill(s)",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = "F.DT:${shortDate(report.fromDate)}" to "TO.DT:${shortDate(report.toDate)}",
            // The item's name sits over the quantity column, above the heads.
            columnsAbove = listOf("", report.item?.name?.uppercase().orEmpty(), "", ""),
            columns = listOf("BILL", "QTY", "RATE", "AMOUNT"),
            evenColumns = true,
            alignFirstColumnEnd = true,
            rows = rowsOf(report),
            summary = listOf(
                "TOTAL QTY " to money(report.totalQuantity),
                "TOTAL RATE" to money(report.totalRate),
                "TOTAL AMT " to money(report.totalAmount)
            ).map { (label, value) -> "$label:" to value },
            emptyNote = "This item sold nothing in this period."
        )

    /** "2026-08-12" as "12-08-26" - how the F.DT / TO.DT line has always read. */
    private fun shortDate(date: String): String =
        pretty(date).let { it.take(6) + it.takeLast(2) }

    override fun emptyMessage(
        report: ItemBillReportDao.Report,
        fromDate: String,
        toDate: String
    ): Pair<String, String> = when {
        items.isEmpty() -> "No products on this till" to
            "Add products under Master → Products before running this report."

        report.item == null -> "Pick an item" to
            "Choose one from the list before generating - typing a name is not the " +
                "same as picking it."

        else -> "No bills in this period" to
            "${report.item.name} sold nothing between " +
                "${pretty(fromDate)} and ${pretty(toDate)}."
    }
}
