package com.example.synergic_pos_offline.fragments

import com.example.synergic_pos_offline.database.StockDao
import com.example.synergic_pos_offline.database.WaiterDao
import com.example.synergic_pos_offline.database.WaiterWiseReportDao
import com.example.synergic_pos_offline.utils.PeriodReportRenderer

/**
 * Waiter Wise Report - one waiter's bills over a period, or every waiter's at once.
 * Restaurant only - see [ReportsFragment.isVisible], which gates the tile on
 * Restaurant mode the same way it gates KOT Cancel and the UDF reports.
 *
 * A date range and a waiter go in. Picking one waiter reads like the Operator
 * Billed Report reads an operator - one row per bill, what it carried, what it was
 * taxed, what came off it and what it came to - with the heading naming the waiter
 * instead of the operator. [ALL_LABEL] in the same dropdown, the way the
 * Payment-Wise Report offers one, drops the filter instead of narrowing it and
 * reads like the UDF-Wise Report instead - one row per waiter, summed across the
 * period - so the floor's whole night can be read as one report rather than one
 * waiter at a time.
 *
 * See [WaiterWiseReportDao] for which bills belong to a waiter - the bill's own
 * `waiter_id`, set once at order creation from the table it was opened on.
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
            ?: return WaiterWiseReportDao.Report(fromDate, toDate, null, allWaiters = false)
        return dao.between(fromDate, toDate, waiter)
    }

    override fun isEmpty(report: WaiterWiseReportDao.Report): Boolean = report.isEmpty

    override fun headline(report: WaiterWiseReportDao.Report): String {
        val who = if (report.allWaiters) "${report.waiterCount} waiter(s)" else report.waiter?.name.orEmpty()
        return "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}   •   " +
            "$who   •   ${report.billCount} bill(s)"
    }

    /**
     * One waiter picked: the Operator Billed Report's own columns - a bill each,
     * what it carried, what it was taxed, what came off it, what it came to.
     *
     * [ALL_LABEL] picked instead: the UDF-Wise Report's own columns, WAITER
     * standing in for UDF - one row per waiter, summed across the period.
     */
    // Evenly spaced rather than each fitted tight to its own header: WAITER (and
    // BILL, in the single-waiter table) is the one column holding text and keeps
    // the extra room text needs, but the figure columns beside it are all the
    // SAME width as one another - see [FIGURE_COL_DP] - so the row reads as one
    // even grid instead of the numbers bunching up wherever a header happened to
    // be short.
    override fun columnsFor(report: WaiterWiseReportDao.Report): List<Column> =
        if (report.allWaiters) listOf(
            Column("WAITER", 150, alignEnd = false),
            Column("BILLS", FIGURE_COL_DP, alignEnd = true),
            Column("TAX AMT", FIGURE_COL_DP, alignEnd = true),
            Column("DISC.", FIGURE_COL_DP, alignEnd = true),
            Column("BILL AMT", FIGURE_COL_DP, alignEnd = true)
        ) else listOf(
            Column("BILL", 150, alignEnd = true),
            Column("ITEMS", FIGURE_COL_DP, alignEnd = true),
            Column("TAX", FIGURE_COL_DP, alignEnd = true),
            Column("DISC", FIGURE_COL_DP, alignEnd = true),
            Column("TOTAL", FIGURE_COL_DP, alignEnd = true)
        )

    override fun rowsOf(report: WaiterWiseReportDao.Report): List<List<String>> =
        if (report.allWaiters) report.rows.map { row ->
            listOf(row.waiterName, row.bills.toString(), money(row.taxAmount), money(row.discount), money(row.billAmount))
        } else report.lines.map { line ->
            listOf(line.billNumber, money(line.items), money(line.tax), money(line.discount), money(line.total))
        }

    override fun summaryOf(report: WaiterWiseReportDao.Report): List<Pair<String, String>> =
        buildList {
            if (report.allWaiters) add("Total Waiters" to report.waiterCount.toString())
            add("Total Bills" to report.billCount.toString())
            if (!report.allWaiters) add("Total Items" to StockDao.trim(report.totalItems))
            // Each tax its own line rather than one blended figure - a GST return is
            // filed against SGST and CGST separately.
            add("Total SGST" to money(report.totalSgst))
            add("Total CGST" to money(report.totalCgst))
            if (report.hasIgst) add("Total IGST" to money(report.totalIgst))
            // VAT and the charges ALWAYS, zero or not. They were shown only where the
            // period actually carried one, which left the summary a different shape
            // from one waiter or period to the next - and a reader who cannot see the
            // row cannot tell "none was charged" from "this report does not total it".
            add("Total VAT" to money(report.totalVat))
            add("Total Disc." to money(report.totalDiscount))
            add("Service Charge" to money(report.totalServiceCharge))
            add("Extra Charges" to money(report.totalOtherCharges))
            add("Parcel Charge" to money(report.totalParcelCharge))
            // Last - the final adjustment the total below already includes.
            add("Round Off" to money(report.totalRoundOff))
        }

    /** The one figure the report is read for: what the waiter's tables took. */
    override fun totalOf(report: WaiterWiseReportDao.Report): Pair<String, String> =
        (if (report.allWaiters) "Bill Amount" else "Grand Total") to money(report.totalAmount)

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
     * The printed slip: the Operator Billed Report's own layout for one waiter
     * (WTR CODE / WTR NAME heading over a bill-per-line body), or the UDF-Wise
     * Report's own layout for [ALL_LABEL] (one row per waiter).
     */
    override fun printContent(report: WaiterWiseReportDao.Report): PeriodReportRenderer.Content =
        if (report.allWaiters) PeriodReportRenderer.Content(
            title = "Waiter Wise Report",
            period = "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}",
            subtitle = "${report.waiterCount} waiter(s)",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = "F.DT:${shortDate(report.fromDate)}" to "TO.DT:${shortDate(report.toDate)}",
            columns = listOf("WAITER", "BILLS", "TAX AMT", "DISC.", "BILL AMT"),
            // NOT evenColumns - WAITER holds a name, and forcing it to share an
            // equal fifth of the roll with four short figures is the printed
            // version of the same clutter the on-screen table had: the figures
            // sit needlessly wide while a long name is the one thing actually
            // fighting for room. Left at the default instead - the same one the
            // UDF-Wise Report's identically-shaped table already prints with -
            // each figure column is measured to its own content and WAITER takes
            // whatever is left, ellipsizing if a name genuinely does not fit
            // rather than shrinking every column's type size to make room for it.
            rows = rowsOf(report),
            summary = buildList {
                add("SGST AMOUNT :" to money(report.totalSgst))
                add("CGST AMOUNT :" to money(report.totalCgst))
                if (report.hasIgst) add("IGST AMOUNT :" to money(report.totalIgst))
                // The same lines the screen shows, on the same terms - see [summaryOf].
                add("VAT AMOUNT  :" to money(report.totalVat))
                add("DISC. AMOUNT:" to money(report.totalDiscount))
                add("SERVICE CHG :" to money(report.totalServiceCharge))
                add("EXTRA CHGS  :" to money(report.totalOtherCharges))
                add("PARCEL CHG  :" to money(report.totalParcelCharge))
                add("ROUND OFF   :" to money(report.totalRoundOff))
            },
            total = "TOTAL  :" to money(report.totalAmount),
            emptyNote = "No bills in this period."
        ) else PeriodReportRenderer.Content(
            title = "Waiter Wise Report",
            period = "${pretty(report.fromDate)}  to  ${pretty(report.toDate)}",
            subtitle = "${report.billCount} bill(s)",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = "F.DT:${shortDate(report.fromDate)}" to "TO.DT:${shortDate(report.toDate)}",
            heading = listOf(
                "WTR CODE:" to (report.waiter?.code ?: "-"),
                "WTR NAME:" to (report.waiter?.name?.uppercase() ?: "-")
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
                add("TOTAL VAT  " to money(report.totalVat))
                add("TOTAL DISC." to money(report.totalDiscount))
                add("SERVICE CHG" to money(report.totalServiceCharge))
                add("EXTRA CHGS " to money(report.totalOtherCharges))
                add("PARCEL CHG " to money(report.totalParcelCharge))
                add("ROUND OFF  " to money(report.totalRoundOff))
                add("GRAND TOTAL" to money(report.totalAmount))
            }.map { (label, value) -> "$label :" to value },
            emptyNote = "No bills for this waiter."
        )

    private companion object {
        /** The dropdown's own wording for "every waiter" - matches the Payment-Wise
         *  Report's own All entry. */
        const val ALL_LABEL = "All"

        /** Every figure column's width, in both tables - see [columnsFor]'s own note
         *  on why they are all one width rather than each fitted to its header. */
        const val FIGURE_COL_DP = 115
    }
}
