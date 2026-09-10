package com.example.synergic_pos_offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.CalendarReportDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.OperatorWiseReportDao
import com.example.synergic_pos_offline.database.WaiterWiseReportDao
import com.example.synergic_pos_offline.utils.CalendarGrain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The report summaries total the right column.
 *
 * These reports state VAT, round off and the extra charges next to each other in a
 * summary, and every one of those figures is read out of a query by POSITION. A
 * column added in the middle of a SELECT shifts every index after it, and the failure
 * that produces is silent and plausible: the report still draws, the labels are still
 * there, and one of the amounts is quietly another amount. A reader has no way to
 * catch that by looking.
 *
 * So each figure planted here is DIFFERENT and none is a round number - a report that
 * printed round off where the parcel charge belongs would still pass against a row of
 * matching values.
 */
@RunWith(AndroidJUnit4::class)
class ReportSummaryTotalsTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = DatabaseHelper.getInstance(ctx).writableDatabase

    private val planted = mutableListOf<Long>()

    /** Deliberately all different, and none of them round. */
    private companion object {
        const val VAT = 11.11
        const val DISCOUNT = 22.22
        const val SERVICE = 33.33
        const val OTHER = 44.44        // the total of the two extra charges...
        const val PARCEL = 4.04        // ...of which this is the parcel's share
        const val ROUND_OFF = 0.37
        const val NET = 555.55
        const val CGST = 6.06
        const val SGST = 7.07
    }

    @After
    fun clearUpAfterItself() {
        planted.forEach { runCatching { db.execSQL("DELETE FROM td_bills WHERE receipt_no = $it") } }
        planted.clear()
    }

    @Test
    fun theDailyReportTotalsRoundOffAndVat() {
        val today = day(Date())
        plantBill(today)

        val report = CalendarReportDao(ctx).between(today, today, CalendarGrain.DAY)

        assertEquals("VAT", VAT, report.totalVat, 0.005)
        assertEquals("round off", ROUND_OFF, report.totalRoundOff, 0.005)
        // The neighbours either side of the new column, so a shift shows up here even
        // if round off itself happened to land on a plausible figure.
        assertEquals("parcel charge", PARCEL, report.totalParcelCharge, 0.005)
        assertEquals("total amount", NET, report.totalAmount, 0.005)
        assertEquals("extra charges", OTHER - PARCEL, report.totalOtherCharges, 0.005)
        assertEquals("discount", DISCOUNT, report.totalDiscount, 0.005)
        assertEquals("service charge", SERVICE, report.totalServiceCharge, 0.005)
        assertEquals("CGST", CGST, report.totalCgst, 0.005)
        assertEquals("SGST", SGST, report.totalSgst, 0.005)
    }

    @Test
    fun theWaiterWiseReportTotalsRoundOffAndVat() {
        val today = day(Date())
        plantBill(today, waiterId = someWaiter())

        // Null waiter is the "All" reading, which totals a different query from the
        // one-waiter reading - both had a round off column added, so both are asked.
        val all = WaiterWiseReportDao(ctx).between(today, today, null)
        assertEquals("VAT, all waiters", VAT, all.totalVat, 0.005)
        assertEquals("round off, all waiters", ROUND_OFF, all.totalRoundOff, 0.005)
        assertEquals("parcel charge, all waiters", PARCEL, all.totalParcelCharge, 0.005)
        assertEquals("extra charges, all waiters", OTHER - PARCEL, all.totalOtherCharges, 0.005)
        assertEquals("total amount, all waiters", NET, all.totalAmount, 0.005)
    }

    @Test
    fun theOperatorWiseReportTotalsVat() {
        val today = day(Date())
        plantBill(today)

        val report = OperatorWiseReportDao(ctx).between(today, today)
        assertEquals("VAT", VAT, report.totalVat, 0.005)
        // The column VAT was added after - it must not have taken the amount's place.
        assertEquals("total amount", NET, report.totalAmount, 0.005)
    }

    // ---- Helpers -------------------------------------------------------------------

    /** One completed bill carrying a different figure in every column. */
    private fun plantBill(date: String, waiterId: Long? = null): Long {
        db.execSQL(
            "INSERT INTO td_bills (bill_number, bill_date, bill_date_time, bill_type, " +
                "bill_status, is_voided, waiter_id, tot_cgst_amount, tot_sgst_amount, " +
                "tot_igst_amount, tot_vat_amount, tot_discount_amount, service_charge_amount, " +
                "tot_other_charges_amount, parcel_charge_amount, tot_round_off_amount, " +
                "net_amount) VALUES ('SUMMARY-TEST', ?, ?, 'CASH', 'COMPLETED', 0, ?, " +
                "$CGST, $SGST, 0, $VAT, $DISCOUNT, $SERVICE, $OTHER, $PARCEL, $ROUND_OFF, $NET)",
            arrayOf(date, "$date 10:00:00", waiterId)
        )
        var id = 0L
        db.rawQuery("SELECT last_insert_rowid()", null).use { c -> if (c.moveToFirst()) id = c.getLong(0) }
        planted.add(id)
        return id
    }

    /**
     * Any waiter this till knows, made if it knows none.
     *
     * The all-waiters query only counts bills that name one, so a device whose waiter
     * master is empty would otherwise read an empty report and pass every assertion
     * against nothing.
     */
    private fun someWaiter(): Long {
        db.rawQuery("SELECT id FROM md_waiters LIMIT 1", null).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        db.execSQL("INSERT INTO md_waiters (waiter_name) VALUES ('Roll-over test waiter')")
        db.rawQuery("SELECT last_insert_rowid()", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    private fun day(date: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
}
