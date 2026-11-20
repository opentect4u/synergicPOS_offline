package com.example.synergic_pos_offline.lanreport

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.synergic_pos_offline.fragments.BillWiseReportFragment
import com.example.synergic_pos_offline.fragments.CategoryWiseReportFragment
import com.example.synergic_pos_offline.fragments.CustomerItemWiseReportFragment
import com.example.synergic_pos_offline.fragments.CustomerLedgerFragment
import com.example.synergic_pos_offline.fragments.CustomerPaymentReportFragment
import com.example.synergic_pos_offline.fragments.DayWiseReportFragment
import com.example.synergic_pos_offline.fragments.DuplicateReportFragment
import com.example.synergic_pos_offline.fragments.ItemBillReportFragment
import com.example.synergic_pos_offline.fragments.ItemWiseReportFragment
import com.example.synergic_pos_offline.fragments.KotCancelReportFragment
import com.example.synergic_pos_offline.fragments.LowStockReportFragment
import com.example.synergic_pos_offline.fragments.MonthWiseReportFragment
import com.example.synergic_pos_offline.fragments.OperatorBilledReportFragment
import com.example.synergic_pos_offline.fragments.OperatorWiseReportFragment
import com.example.synergic_pos_offline.fragments.PaymentWiseReportFragment
import com.example.synergic_pos_offline.fragments.ProfitLossReportFragment
import com.example.synergic_pos_offline.fragments.ReportsFragment
import com.example.synergic_pos_offline.fragments.ReturnedBillReportFragment
import com.example.synergic_pos_offline.fragments.ShiftDayReportFragment
import com.example.synergic_pos_offline.fragments.ShiftWiseReportFragment
import com.example.synergic_pos_offline.fragments.StockReportFragment
import com.example.synergic_pos_offline.fragments.TaxReportFragment
import com.example.synergic_pos_offline.fragments.TimeWiseItemReportFragment
import com.example.synergic_pos_offline.fragments.UdfWiseItemReportFragment
import com.example.synergic_pos_offline.fragments.UdfWiseReportFragment
import com.example.synergic_pos_offline.fragments.UnsoldProductReportFragment
import com.example.synergic_pos_offline.fragments.VoidBillReportFragment
import com.example.synergic_pos_offline.fragments.WaiterWiseReportFragment
import com.example.synergic_pos_offline.fragments.YearWiseReportFragment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Every report the till's Reports screen offers, as the phone app lists them.
 *
 * In the same order as [ReportsFragment]'s grid, under the same titles - and filtered
 * by the same [ReportsFragment.isVisible] rule, so a report hidden on this till (the
 * restaurant-only ones on a grocery till, stock reports with stock off, the HIDDEN
 * ones) is hidden on the phone too.
 */
object MobileReports {

    class Entry(val id: String, val title: String, val make: () -> HeadlessReport)

    private fun entry(title: String, make: () -> HeadlessReport) =
        Entry(title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'), title, make)

    val ALL: List<Entry> = listOf(
        entry("Bill Wise Report") { BillWiseReportFragment() },
        entry("Item Wise Report") { ItemWiseReportFragment() },
        entry("Operator Wise Report") { OperatorWiseReportFragment() },
        entry("Void Bill Report") { VoidBillReportFragment() },
        entry("Tax Report") { TaxReportFragment() },
        entry("Duplicate Bill Report") { DuplicateReportFragment() },
        entry("Stock Report") { StockReportFragment() },
        entry("Item Bill Report") { ItemBillReportFragment() },
        entry("Returned Bill Report") { ReturnedBillReportFragment() },
        entry("UDF-Wise Report") { UdfWiseReportFragment() },
        entry("Payment-Wise Report") { PaymentWiseReportFragment() },
        entry("Unsold Product Report") { UnsoldProductReportFragment() },
        entry("Low Stock Report") { LowStockReportFragment() },
        entry("Opr Bill Report") { OperatorBilledReportFragment() },
        entry("Shift Wise Report") { ShiftWiseReportFragment() },
        entry(ShiftDayReportFragment.TITLE) { ShiftDayReportFragment() },
        entry("Category/Dept Wise Bill Report") { CategoryWiseReportFragment() },
        entry("Customer Payment") { CustomerPaymentReportFragment() },
        entry("Customer Ledger") { CustomerLedgerFragment() },
        entry("Profit & Loss Report") { ProfitLossReportFragment() },
        entry("KOT Cancel Report") { KotCancelReportFragment() },
        entry("Day-Wise Report") { DayWiseReportFragment() },
        entry("Month Wise Report") { MonthWiseReportFragment() },
        entry("Year Wise Report") { YearWiseReportFragment() },
        entry("UDF Wise Item Report") { UdfWiseItemReportFragment() },
        entry("Customer Item Wise RPT") { CustomerItemWiseReportFragment() },
        entry("Time Wise Item Report") { TimeWiseItemReportFragment() },
        entry("Waiter Wise Report") { WaiterWiseReportFragment() }
    )

    /** The reports this till shows, in its own order. */
    fun visible(context: Context): List<Entry> = ALL.filter { ReportsFragment.isVisible(context, it.title) }

    /** [id]'s report, if it is one this till shows. */
    fun find(context: Context, id: String): Entry? = visible(context).firstOrNull { it.id == id }

    /**
     * A fresh, unattached instance of [entry]'s screen to run off-screen.
     *
     * Made on the main thread, where a Fragment expects to be constructed; the report
     * itself is then run on the server's own thread, as Generate runs it off the main
     * thread on the till.
     */
    fun instantiate(entry: Entry): HeadlessReport = onMain { entry.make() }

    /**
     * Runs [make] on the main thread and hands back what it made - for constructing a
     * screen (a Fragment) from the server's own thread, where it expects to be made.
     */
    fun <T : Any> onMain(make: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return make()
        var made: T? = null
        var failure: Throwable? = null
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try { made = make() } catch (t: Throwable) { failure = t } finally { done.countDown() }
        }
        if (!done.await(10, TimeUnit.SECONDS)) throw IllegalStateException("The till is busy - try again")
        failure?.let { throw it }
        return made!!
    }
}
