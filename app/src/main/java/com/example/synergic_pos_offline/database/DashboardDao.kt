package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.StockAlerts
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Every figure the dashboard draws, read in one pass and handed over as JSON.
 *
 * ## Why JSON, and why one object
 *
 * The dashboard is an HTML page - see `assets/dashboard/index.html` - so what it needs
 * is data rather than views. Gathering it all here means the page is drawn once from
 * one consistent reading of the books: a snapshot taken across nine separate queries
 * run from nine places could show a sales total that disagrees with the hourly chart
 * beneath it, because a sale landed between the two.
 *
 * ## What counts as a sale
 *
 * [BillDao.countableBillClause] decides, everywhere. A voided bill never counted and a
 * bill that has come back on a sale return stops counting, which is the same rule the
 * reports apply - so today's dashboard and a report run over today agree, and an
 * operator checking one against the other is not left wondering which lied.
 *
 * ## Dates
 *
 * Local time throughout, because a shop's day is the day it is standing in. Bills
 * carry both `bill_date_time` and `bill_date`; the first is used where the hour
 * matters and COALESCE covers the rows that only ever had the date.
 */
class DashboardDao(context: Context) {

    private val appContext = context.applicationContext
    private val helper = DatabaseHelper.getInstance(context)

    /** Everything the page draws, ready to hand to it. */
    fun snapshot(): JSONObject {
        val db = helper.readableDatabase

        fun num(sql: String): Double = runCatching {
            db.rawQuery(sql, null).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0) else 0.0
            }
        }.getOrDefault(0.0)

        val today = dayOffset(0)
        val yesterday = dayOffset(-1)
        val weekAgo = dayOffset(-6)

        // Sales, and the same day last time, so the change line means something.
        val salesToday = num(salesOn(today))
        val salesYesterday = num(salesOn(yesterday))
        val billsToday = num(countOn(today)).toInt()
        val billsWeek = num(countBetween(weekAgo, today)).toInt()

        val collected = num(
            """
            SELECT COALESCE(SUM(p.amount_paid), 0) FROM ${DatabaseHelper.Tables.TD_PAYMENTS} p
            JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = p.bill_id
            WHERE date(COALESCE(b.bill_date_time, b.bill_date)) = '$today'
              AND ${BillDao.countableBillClause("b")}
            """.trimIndent()
        )

        val customers = num(
            "SELECT COUNT(DISTINCT customer_id) FROM ${DatabaseHelper.Tables.TD_BILLS} " +
                "WHERE ${dayIs(today)} AND customer_id IS NOT NULL AND ${BillDao.countableBillClause()}"
        ).toInt()
        val newMembers = num(
            "SELECT COUNT(*) FROM ${DatabaseHelper.Tables.MD_CUSTOMERS} WHERE date(created_at) = '$today'"
        ).toInt()
        val repeat = num(
            "SELECT COUNT(DISTINCT customer_id) FROM ${DatabaseHelper.Tables.TD_BILLS} " +
                "WHERE ${dayIs(today)} AND customer_id IS NOT NULL AND ${BillDao.countableBillClause()} " +
                "AND customer_id IN (SELECT customer_id FROM ${DatabaseHelper.Tables.TD_BILLS} " +
                "WHERE date(COALESCE(bill_date_time, bill_date)) < '$today' AND customer_id IS NOT NULL " +
                "AND ${BillDao.countableBillClause()})"
        ).toInt()

        return JSONObject().apply {
            put("date", SimpleDateFormat("d MMMM yyyy", Locale.UK).format(Date()))
            put("currency", "₹")

            put("salesToday", salesToday)
            put("salesChange", percentChange(salesToday, salesYesterday))
            put("bills", billsToday)
            put("avgBill", if (billsToday > 0) salesToday / billsToday else 0.0)
            // Against the daily average of the week behind it, which is the only
            // "normal" a till has to compare a day with.
            put("billsChange", percentChange(billsToday.toDouble(), billsWeek / 7.0))

            put("collected", collected)
            put("pending", (salesToday - collected).coerceAtLeast(0.0))
            put(
                "collectedPct",
                if (salesToday > 0) (collected / salesToday * 100).coerceIn(0.0, 100.0) else 0.0
            )

            put("customers", customers)
            put("newMembers", newMembers)
            put("repeat", repeat)

            put("hourly", hourly(today))
            put("payments", payments(today))
            put("categories", topCategories(today))
            put("staff", staff(today))
            put("daily", lastSevenDays())
            put("alerts", alerts())
        }
    }

    // ---- The series ----------------------------------------------------------

    /**
     * Today's takings by hour, over the hours the shop actually traded.
     *
     * Empty hours between the first and last sale are kept - a quiet two o'clock is
     * part of the shape of the day - but the hours before the shop opened and after it
     * shut are left off rather than drawn as a flat line along the bottom.
     */
    private fun hourly(day: String): JSONArray {
        val byHour = sortedMapOf<Int, Double>()
        query(
            """
            SELECT CAST(strftime('%H', COALESCE(bill_date_time, bill_date)) AS INTEGER),
                   COALESCE(SUM(net_amount), 0)
            FROM ${DatabaseHelper.Tables.TD_BILLS}
            WHERE ${dayIs(day)} AND ${BillDao.countableBillClause()}
            GROUP BY 1 ORDER BY 1
            """.trimIndent()
        ) { c -> byHour[c.getInt(0)] = c.getDouble(1) }

        val out = JSONArray()
        if (byHour.isEmpty()) return out
        for (hour in byHour.firstKey()..byHour.lastKey()) {
            out.put(
                JSONObject()
                    .put("label", hourLabel(hour))
                    .put("value", byHour[hour] ?: 0.0)
            )
        }
        return out
    }

    /**
     * What was collected, by how it was taken.
     *
     * UPI and ONLINE are read together as one slice: both settle over the same rails,
     * and splitting them makes two thin wedges nobody can compare.
     */
    private fun payments(day: String): JSONArray {
        val byMode = linkedMapOf("Cash" to 0.0, "Card" to 0.0, "UPI / Online" to 0.0, "Credit" to 0.0)
        query(
            """
            SELECT UPPER(COALESCE(p.payment_mode, b.bill_type, '')), COALESCE(SUM(p.amount_paid), 0)
            FROM ${DatabaseHelper.Tables.TD_PAYMENTS} p
            JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = p.bill_id
            WHERE date(COALESCE(b.bill_date_time, b.bill_date)) = '$day'
              AND ${BillDao.countableBillClause("b")}
            GROUP BY 1
            """.trimIndent()
        ) { c ->
            val slice = when (c.getString(0).orEmpty()) {
                "CASH" -> "Cash"
                "CARD" -> "Card"
                "UPI", "ONLINE" -> "UPI / Online"
                "CREDIT" -> "Credit"
                else -> "Cash"
            }
            byMode[slice] = (byMode[slice] ?: 0.0) + c.getDouble(1)
        }
        val out = JSONArray()
        byMode.filterValues { it > 0.0 }.forEach { (label, value) ->
            out.put(JSONObject().put("label", label).put("value", value))
        }
        return out
    }

    /** Today's takings by department, biggest first, the long tail clubbed as Other. */
    private fun topCategories(day: String): JSONArray = topOf(
        """
        SELECT COALESCE(NULLIF(TRIM(c.category_name), ''), 'Uncategorised'),
               COALESCE(SUM(i.item_total), 0)
        FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} i
        JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = i.bill_id
        LEFT JOIN ${DatabaseHelper.Tables.MD_PRODUCTS} p ON p.id = i.product_id
        LEFT JOIN ${DatabaseHelper.Tables.MD_CATEGORY} c ON c.id = p.category_id
        WHERE date(COALESCE(b.bill_date_time, b.bill_date)) = '$day'
          AND ${BillDao.countableBillClause("b")}
        GROUP BY 1 ORDER BY 2 DESC
        """.trimIndent()
    )

    /** Today's takings by whoever rang them up. */
    private fun staff(day: String): JSONArray = topOf(
        """
        SELECT COALESCE(NULLIF(TRIM(u.user_name), ''), u.user_id, 'Unknown'),
               COALESCE(SUM(b.net_amount), 0)
        FROM ${DatabaseHelper.Tables.TD_BILLS} b
        LEFT JOIN ${DatabaseHelper.Tables.MD_USERS} u ON u.id = b.operator_id
        WHERE ${dayIs(day, "b")} AND ${BillDao.countableBillClause("b")}
        GROUP BY 1 ORDER BY 2 DESC
        """.trimIndent()
    )

    /**
     * The top [TOP_ROWS] of a label/value query, with everything below them added up
     * as one "Other" bar.
     *
     * A chart of thirty departments is a chart of none of them, and dropping the tail
     * silently would leave the bars adding up to less than the day's takings with
     * nothing on the page to say why.
     */
    private fun topOf(sql: String): JSONArray {
        val rows = mutableListOf<Pair<String, Double>>()
        query(sql) { c -> rows.add(c.getString(0).orEmpty() to c.getDouble(1)) }
        val out = JSONArray()
        rows.take(TOP_ROWS).forEach {
            out.put(JSONObject().put("label", it.first).put("value", it.second))
        }
        val tail = rows.drop(TOP_ROWS).sumOf { it.second }
        if (tail > 0.0) out.put(JSONObject().put("label", "Other").put("value", tail))
        return out
    }

    /** The week behind, oldest first, with the quiet days drawn as the zeroes they are. */
    private fun lastSevenDays(): JSONArray {
        val byDay = mutableMapOf<String, Double>()
        query(
            """
            SELECT date(COALESCE(bill_date_time, bill_date)), COALESCE(SUM(net_amount), 0)
            FROM ${DatabaseHelper.Tables.TD_BILLS}
            WHERE date(COALESCE(bill_date_time, bill_date)) BETWEEN '${dayOffset(-6)}' AND '${dayOffset(0)}'
              AND ${BillDao.countableBillClause()}
            GROUP BY 1
            """.trimIndent()
        ) { c -> byDay[c.getString(0).orEmpty()] = c.getDouble(1) }

        val out = JSONArray()
        val label = SimpleDateFormat("EEE", Locale.UK)
        val stored = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        for (offset in -6..0) {
            val date = dateOffset(offset)
            val key = stored.format(date)
            out.put(
                JSONObject()
                    .put("label", label.format(date))
                    .put("value", byDay[key] ?: 0.0)
            )
        }
        return out
    }

    /**
     * What is out or running low, for the band across the head of the page.
     *
     * Read through [StockAlerts] rather than queried again here, so the dashboard, the
     * Low Stock Report and the sale screen's badges cannot come to disagree about what
     * "low" means - there is one rule and it lives there.
     */
    private fun alerts(): JSONObject {
        val summary = StockAlerts.undismissed(appContext, StockAlerts.find(appContext))
        val items = JSONArray()
        summary.items.take(ALERT_ROWS).forEach {
            items.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("quantity", StockDao.trim(it.quantity))
                    .put("out", it.isOut)
            )
        }
        return JSONObject()
            .put("total", summary.total)
            .put("shown", items)
            .put("enabled", StockAlerts.enabled(appContext))
    }

    // ---- Plumbing --------------------------------------------------------------

    private fun query(sql: String, read: (android.database.Cursor) -> Unit) {
        runCatching {
            helper.readableDatabase.rawQuery(sql, null).use { c -> while (c.moveToNext()) read(c) }
        }
    }

    private fun salesOn(day: String) =
        "SELECT COALESCE(SUM(net_amount), 0) FROM ${DatabaseHelper.Tables.TD_BILLS} " +
            "WHERE ${dayIs(day)} AND ${BillDao.countableBillClause()}"

    private fun countOn(day: String) =
        "SELECT COUNT(*) FROM ${DatabaseHelper.Tables.TD_BILLS} " +
            "WHERE ${dayIs(day)} AND ${BillDao.countableBillClause()}"

    private fun countBetween(from: String, to: String) =
        "SELECT COUNT(*) FROM ${DatabaseHelper.Tables.TD_BILLS} " +
            "WHERE date(COALESCE(bill_date_time, bill_date)) BETWEEN '$from' AND '$to' " +
            "AND ${BillDao.countableBillClause()}"

    private fun dayIs(day: String, alias: String = "") = alias.let { a ->
        val p = if (a.isEmpty()) "" else "$a."
        "date(COALESCE(${p}bill_date_time, ${p}bill_date)) = '$day'"
    }

    /**
     * The change from [previous] to [now], as a percentage.
     *
     * Null where there is nothing to compare against: a first day of trading has no
     * "yesterday", and reporting that as +100% would be inventing a comparison.
     */
    private fun percentChange(now: Double, previous: Double): Any =
        if (previous <= 0.0) JSONObject.NULL else (now - previous) / previous * 100.0

    private fun dayOffset(days: Int): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(dateOffset(days))

    private fun dateOffset(days: Int): Date = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, days)
    }.time

    /** "1 PM" - the hour as a person says it, which is how the axis reads. */
    private fun hourLabel(hour: Int): String = when {
        hour == 0 -> "12 AM"
        hour < 12 -> "$hour AM"
        hour == 12 -> "12 PM"
        else -> "${hour - 12} PM"
    }

    private companion object {
        /** Bars on a category or staff chart before the rest become "Other". */
        const val TOP_ROWS = 6

        /** Products named in the alert band; the count above it reports them all. */
        const val ALERT_ROWS = 3
    }
}
