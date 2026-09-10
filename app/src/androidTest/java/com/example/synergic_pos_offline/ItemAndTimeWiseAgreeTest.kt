package com.example.synergic_pos_offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.ItemWiseReportDao
import com.example.synergic_pos_offline.utils.CalendarGrain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Item Wise and Time Wise item reports state the same figures for the same sales.
 *
 * They are ONE report asked for two ways - the same screen, the same query, differing
 * only in whether the range is a pair of dates or a pair of date-and-times. So over a
 * window that covers the whole day, every total must agree to the paisa. Anything else
 * is the reader being told two different things about one afternoon's trading.
 *
 * ## What this is pinning
 *
 * The item lines were always read at the grain the range was asked at. The charges -
 * service, extra, parcel and ROUND OFF - and the whole-bill discount were not: they
 * belong to a bill rather than to any one item on it, so they come from their own
 * queries, and those cut the bill's moment to ten characters, a DATE, whatever bounds
 * they were handed.
 *
 * With a date range that agreed. With `2026-09-09 00:00` it did not merely blur - it
 * matched NOTHING, because a string that is a prefix of another sorts before it, so
 * `'2026-09-09' >= '2026-09-09 00:00'` is false. Every charge and the discount came
 * back zero on the Time Wise report, silently, and its Total Amount with them.
 */
@RunWith(AndroidJUnit4::class)
class ItemAndTimeWiseAgreeTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = DatabaseHelper.getInstance(ctx).writableDatabase

    private val planted = mutableListOf<Long>()

    /** All different, none round - so a figure landing in the wrong slot still shows. */
    private companion object {
        const val ROUND_OFF = -0.37
        const val SERVICE = 33.33
        const val OTHER = 44.44      // both extra charges together...
        const val PARCEL = 4.04      // ...of which this is the parcel's share
        const val DISCOUNT = 22.22
        const val ITEM_TOTAL = 118.0
        const val CGST = 9.0
        const val SGST = 9.0
    }

    @After
    fun clearUpAfterItself() {
        planted.forEach { id ->
            runCatching { db.execSQL("DELETE FROM td_bill_items WHERE bill_id = $id") }
            runCatching { db.execSQL("DELETE FROM td_bills WHERE receipt_no = $id") }
        }
        planted.clear()
    }

    @Test
    fun theTwoReportsAgreeOverTheSameDay() {
        val today = day(Date())
        plantBillWithOneLine(today, at = "$today 14:02:03")

        val dao = ItemWiseReportDao(ctx)
        val itemWise = dao.between(today, today, CalendarGrain.DAY)
        // The whole of the same day, asked for by the minute - what the Time Wise
        // screen sends when its two pickers span the day.
        val timeWise = dao.between("$today 00:00", "$today 23:59", CalendarGrain.MINUTE)

        assertEquals("the same day should hold the same items", itemWise.itemCount, timeWise.itemCount)

        // The figure the mismatch was reported against.
        assertEquals("ROUND OFF", itemWise.totalRoundOff, timeWise.totalRoundOff, 0.005)
        // Everything else off the same two queries, which failed the same way.
        assertEquals("service charge", itemWise.totalServiceCharge, timeWise.totalServiceCharge, 0.005)
        assertEquals("extra charges", itemWise.totalOtherCharges, timeWise.totalOtherCharges, 0.005)
        assertEquals("parcel charge", itemWise.totalParcelCharge, timeWise.totalParcelCharge, 0.005)
        assertEquals("discount", itemWise.totalDiscount, timeWise.totalDiscount, 0.005)
        // And the headline, which is built from all of them.
        assertEquals("total amount", itemWise.totalNetAmount, timeWise.totalNetAmount, 0.005)
        assertEquals("taxable amount", itemWise.totalAmount, timeWise.totalAmount, 0.005)
        assertEquals("VAT", itemWise.totalVat, timeWise.totalVat, 0.005)
    }

    /**
     * The figures are the planted ones, not merely equal to each other.
     *
     * Two reports that both read zero would satisfy the test above perfectly, and
     * zero is exactly what the broken one returned - so what the charges actually
     * come to is asserted here as well.
     */
    @Test
    fun theTimeWiseReportReadsTheRealCharges() {
        val today = day(Date())
        plantBillWithOneLine(today, at = "$today 14:02:03")

        val timeWise = ItemWiseReportDao(ctx)
            .between("$today 00:00", "$today 23:59", CalendarGrain.MINUTE)

        assertEquals("round off", ROUND_OFF, timeWise.totalRoundOff, 0.005)
        assertEquals("service charge", SERVICE, timeWise.totalServiceCharge, 0.005)
        assertEquals("extra charges", OTHER - PARCEL, timeWise.totalOtherCharges, 0.005)
        assertEquals("parcel charge", PARCEL, timeWise.totalParcelCharge, 0.005)
        assertEquals("discount", DISCOUNT, timeWise.totalDiscount, 0.005)
    }

    /**
     * A minute range that excludes the sale still excludes it.
     *
     * The point of the Time Wise report is that a narrower window shows less. A fix
     * that simply widened every comparison back to the day would pass both tests
     * above and quietly break the one thing this report is for.
     */
    @Test
    fun aWindowThatMissesTheSaleReportsNothing() {
        val today = day(Date())
        plantBillWithOneLine(today, at = "$today 14:02:03")

        val morning = ItemWiseReportDao(ctx)
            .between("$today 08:00", "$today 09:00", CalendarGrain.MINUTE)

        assertEquals("no item sold in that hour", 0, morning.itemCount)
        assertEquals("and so no round off", 0.0, morning.totalRoundOff, 0.005)
        assertEquals("and no charges", 0.0, morning.totalServiceCharge, 0.005)
        assertEquals("and no discount", 0.0, morning.totalDiscount, 0.005)
    }

    // ---- Helpers -------------------------------------------------------------------

    /** One completed, non-MRP bill with a single line, sold at [at]. */
    private fun plantBillWithOneLine(date: String, at: String): Long {
        db.execSQL(
            "INSERT INTO td_bills (bill_number, bill_date, bill_date_time, bill_type, " +
                "bill_status, is_voided, is_mrp_billing, tot_cgst_amount, tot_sgst_amount, " +
                "tot_vat_amount, tot_discount_amount, service_charge_amount, " +
                "tot_other_charges_amount, parcel_charge_amount, tot_round_off_amount, " +
                "net_amount) VALUES ('AGREE-TEST', ?, ?, 'CASH', 'COMPLETED', 0, 0, " +
                "$CGST, $SGST, 0, $DISCOUNT, $SERVICE, $OTHER, $PARCEL, $ROUND_OFF, 0)",
            arrayOf(date, at)
        )
        var id = 0L
        db.rawQuery("SELECT last_insert_rowid()", null).use { c -> if (c.moveToFirst()) id = c.getLong(0) }
        planted.add(id)
        db.execSQL(
            "INSERT INTO td_bill_items (bill_id, product_id, product_name, quantity, rate, " +
                "item_subtotal, item_total, cgst_amount, sgst_amount) " +
                "VALUES ($id, NULL, 'Agreement test line', 1, 100, 100, $ITEM_TOTAL, $CGST, $SGST)"
        )
        return id
    }

    private fun day(date: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
}
