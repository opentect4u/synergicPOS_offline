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
            put("movement", movement())
            put("daily", lastSevenDays())
            put("alerts", alerts())
            put("renewal", renewal())
            // One band or the other, never both: the trade this till is in decides
            // which four figures mean anything. A grocery has no tables to fill and a
            // restaurant does not sell off the shelf, so the other four would be a row
            // of zeroes dressed up as a measurement.
            if (isRestaurant()) put("restaurant", restaurant()) else put("retail", retail())
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

    /**
     * What is selling and what is sitting - the fastest and slowest movers.
     *
     * ## Over a month, not over today
     *
     * A day cannot tell you what is slow. Half a shop's catalogue sells nothing on any
     * given Tuesday, and calling all of it slow-moving would be reporting the weather.
     * [MOVEMENT_DAYS] is long enough that an item which has genuinely stopped moving
     * stands apart from one that simply had a quiet morning, and short enough that it
     * is still news.
     *
     * ## Which items can be slow
     *
     * Only ones the shop is still holding. An item with nothing on the shelf has not
     * stopped selling - it has stopped being stocked, which is a different problem and
     * is what the stock alerts above are for. So where stock is tracked the slow list
     * is drawn from what is actually on hand; where it is not, every product is a
     * candidate, since there is no count to filter on.
     *
     * A product that sold nothing at all belongs at the top of the slow list rather
     * than being left out, which is why the sales are joined onto the master and not
     * the other way round.
     */
    private fun movement(): JSONObject {
        val from = dayOffset(-(MOVEMENT_DAYS - 1))
        val to = dayOffset(0)

        // Two reads rather than a correlated subquery per product: a catalogue runs to
        // thousands of rows and this is a dashboard, not a report.
        val soldByProduct = mutableMapOf<Long, Double>()
        query(
            """
            SELECT i.product_id, COALESCE(SUM(i.quantity), 0)
            FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} i
            JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = i.bill_id
            WHERE date(COALESCE(b.bill_date_time, b.bill_date)) BETWEEN '$from' AND '$to'
              AND ${BillDao.countableBillClause("b")}
            GROUP BY i.product_id
            """.trimIndent()
        ) { c -> soldByProduct[c.getLong(0)] = c.getDouble(1) }

        val onHand =
            "COALESCE((SELECT SUM(s.current_quantity) FROM ${DatabaseHelper.Tables.MD_BATCH_STOCK} s " +
                "WHERE s.product_id = p.id), 0)"
        data class Item(val id: Long, val name: String, val sold: Double, val stock: Double)
        val products = mutableListOf<Item>()
        query(
            """
            SELECT p.id, p.product_name, $onHand
            FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p
            WHERE p.product_name IS NOT NULL AND TRIM(p.product_name) <> ''
            """.trimIndent()
        ) { c ->
            products.add(
                Item(
                    id = c.getLong(0),
                    name = c.getString(1).orEmpty(),
                    sold = soldByProduct[c.getLong(0)] ?: 0.0,
                    stock = c.getDouble(2)
                )
            )
        }

        val stockTracked = GeneralSettingsDao.isStockEnabled(appContext)
        val fast = products.filter { it.sold > 0.0 }
            .sortedWith(compareByDescending<Item> { it.sold }.thenBy { it.name })
            .take(MOVEMENT_ROWS)
        // Slowest last in the array, so the chart reads worst-at-the-bottom the way
        // the fast half reads best-at-the-top.
        val slowPool = if (stockTracked) products.filter { it.stock > 0.0 } else products
        val slow = slowPool
            .filterNot { p -> fast.any { it.id == p.id } }
            .sortedWith(compareBy<Item> { it.sold }.thenBy { it.name })
            .take(MOVEMENT_ROWS)
            .reversed()

        fun rows(items: List<Item>) = JSONArray().apply {
            items.forEach {
                put(
                    JSONObject()
                        .put("label", it.name)
                        .put("value", it.sold)
                        .put("stock", StockDao.trim(it.stock))
                )
            }
        }
        return JSONObject()
            .put("days", MOVEMENT_DAYS)
            .put("fast", rows(fast))
            .put("slow", rows(slow))
            .put("stockTracked", stockTracked)
    }

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
    /**
     * Whether the registration is close enough to running out to say so, and how to
     * word it - see [com.example.synergic_pos_offline.utils.RenewalStatus].
     *
     * `show` is false almost every day of the year: the band only appears inside the
     * last month, and stays once the date has passed. Sent as an object either way so
     * the page has one shape to render rather than a key that may not be there.
     */
    private fun renewal(): JSONObject {
        val status = com.example.synergic_pos_offline.utils.RenewalStatus.of(appContext)
        if (status == null || !status.needsAttention) return JSONObject().put("show", false)
        return JSONObject()
            .put("show", true)
            // Red once it has expired or is inside the last week; amber before that, so
            // "there is time" and "this is about to stop" do not look the same.
            .put("urgent", status.expired || status.daysLeft <= 7)
            .put("headline", com.example.synergic_pos_offline.utils.RenewalStatus.headline(status))
            .put("detail", com.example.synergic_pos_offline.utils.RenewalStatus.detail(status))
    }

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

    // ---- The trade band ---------------------------------------------------------

    /*
     * Both bands below are deliberately narrow: every figure is read from a column
     * this app's own screens write. Three of the eight the design first asked for had
     * no source at all and are not here -
     *
     *   - inventory accuracy needs a physical stock-take, and nothing records one;
     *   - expiry needs md_batch_stock.expiry_date, which is only ever read (for
     *     earliest-first draw-down) and never written by any screen;
     *   - KOT time to prepare needs the kitchen to mark a ticket finished, and the
     *     only status a ticket is ever moved to is CLOSED, when the bill is printed.
     *
     * A tile whose column is never filled is worse than no tile: it reports zero and
     * reads as a fact about the shop.
     */

    /**
     * The four a counter asks about: what is not selling, what came in last, what is
     * in the drawer, and what has gone out on trust.
     *
     * Only the first of them needs stock to be switched on, and only to price what is
     * sitting there - the count itself comes off the bills.
     */
    private fun retail(): JSONObject {
        val from = dayOffset(-(MOVEMENT_DAYS - 1))
        val to = dayOffset(0)
        val today = dayOffset(0)
        val sold =
            "EXISTS(SELECT 1 FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} i " +
                "JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = i.bill_id " +
                "WHERE i.product_id = p.id " +
                "AND date(COALESCE(b.bill_date_time, b.bill_date)) BETWEEN '$from' AND '$to' " +
                "AND ${BillDao.countableBillClause("b")})"

        // Products nobody has bought in a month. Counted over the whole catalogue, not
        // only over what is in stock: an item that has neither sold nor been reordered
        // is exactly the one worth knowing about, and it holds no stock by definition.
        val unsold = query1(
            "SELECT COUNT(*) FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p WHERE NOT $sold"
        ).toInt()
        val products = query1("SELECT COUNT(*) FROM ${DatabaseHelper.Tables.MD_PRODUCTS}").toInt()

        // How much of that is money on a shelf rather than a line in a list.
        val onHandOf =
            "COALESCE((SELECT SUM(s.current_quantity) FROM ${DatabaseHelper.Tables.MD_BATCH_STOCK} s " +
                "WHERE s.product_id = p.id), 0)"
        // The selling price, from whichever of the two columns this till filled in -
        // see the note in DatabaseHelper.onOpen about the duplicate pair.
        val rateOf =
            "COALESCE((SELECT COALESCE(r.sell_price, r.sale_price, r.rate, 0) " +
                "FROM ${DatabaseHelper.Tables.MD_PRODUCT_RATES} r WHERE r.product_id = p.id " +
                "ORDER BY r.id ASC LIMIT 1), 0)"
        var unsoldStocked = 0
        var unsoldValue = 0.0
        query(
            "SELECT $onHandOf, $rateOf FROM ${DatabaseHelper.Tables.MD_PRODUCTS} p " +
                "WHERE NOT $sold AND $onHandOf > 0"
        ) { c ->
            unsoldStocked++
            unsoldValue += c.getDouble(0) * c.getDouble(1)
        }

        // The last bill off this till, whenever it was. Not restricted to today: on a
        // quiet morning "nothing yet" is the answer, and yesterday evening's last sale
        // is the more useful thing to show while waiting for the first.
        var lastAmount = 0.0
        var lastNumber = ""
        var lastMinutes = 0.0
        var hasLast = false
        var lastToday = false
        query(
            """
            SELECT COALESCE(b.net_amount, 0), COALESCE(b.bill_number, ''),
                   (julianday('now','localtime')
                    - julianday(COALESCE(b.bill_date_time, b.bill_date))) * 1440,
                   date(COALESCE(b.bill_date_time, b.bill_date))
            FROM ${DatabaseHelper.Tables.TD_BILLS} b
            WHERE ${BillDao.countableBillClause("b")}
            ORDER BY COALESCE(b.bill_date_time, b.bill_date) DESC, b.receipt_no DESC
            LIMIT 1
            """.trimIndent()
        ) { c ->
            hasLast = true
            lastAmount = c.getDouble(0)
            lastNumber = c.getString(1).orEmpty()
            lastMinutes = c.getDouble(2)
            lastToday = c.getString(3) == today
        }

        // Cash taken today, net of the change handed back - what the drawer is up by,
        // which is the question "cash in hand" asks. There is no opening float and no
        // record of money taken out, so this is takings and not a reconciled drawer.
        val cash = query1(
            """
            SELECT COALESCE(SUM(COALESCE(p.amount_paid, 0) - COALESCE(p.change_amount, 0)), 0)
            FROM ${DatabaseHelper.Tables.TD_PAYMENTS} p
            JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = p.bill_id
            WHERE date(COALESCE(b.bill_date_time, b.bill_date)) = '$today'
              AND UPPER(COALESCE(p.payment_mode, b.bill_type, '')) = 'CASH'
              AND ${BillDao.countableBillClause("b")}
            """.trimIndent()
        )
        val takings = query1(
            "SELECT COALESCE(SUM(b.net_amount), 0) FROM ${DatabaseHelper.Tables.TD_BILLS} b " +
                "WHERE date(COALESCE(b.bill_date_time, b.bill_date)) = '$today' " +
                "AND ${BillDao.countableBillClause("b")}"
        )

        // Sales that went out on trust today. One mode per bill - the first payment row
        // written against it - so a bill settled in two goes is still one sale and not
        // two, which counting the payment rows would make it.
        val modeOf =
            "UPPER(COALESCE((SELECT p.payment_mode FROM ${DatabaseHelper.Tables.TD_PAYMENTS} p " +
                "WHERE p.bill_id = b.receipt_no ORDER BY p.id ASC LIMIT 1), b.bill_type, ''))"
        var creditCount = 0
        var creditValue = 0.0
        query(
            """
            SELECT COUNT(*), COALESCE(SUM(b.net_amount), 0)
            FROM ${DatabaseHelper.Tables.TD_BILLS} b
            WHERE date(COALESCE(b.bill_date_time, b.bill_date)) = '$today'
              AND $modeOf = 'CREDIT'
              AND ${BillDao.countableBillClause("b")}
            """.trimIndent()
        ) { c ->
            creditCount = c.getInt(0)
            creditValue = c.getDouble(1)
        }
        // What is owed altogether, from the master record rather than from the bills:
        // md_customers.balance_amount is the figure a collection writes back down, so
        // it is the only one that falls when a customer pays.
        val owed = query1(
            "SELECT COALESCE(SUM(balance_amount), 0) FROM ${DatabaseHelper.Tables.MD_CUSTOMERS} " +
                "WHERE COALESCE(balance_amount, 0) > 0"
        )

        return JSONObject()
            .put("days", MOVEMENT_DAYS)
            .put("stockTracked", GeneralSettingsDao.isStockEnabled(appContext))
            .put("unsold", unsold)
            .put("products", products)
            .put("unsoldStocked", unsoldStocked)
            .put("unsoldValue", unsoldValue)
            .put("hasLast", hasLast)
            .put("lastAmount", lastAmount)
            .put("lastNumber", lastNumber)
            .put("lastMinutes", lastMinutes)
            .put("lastToday", lastToday)
            .put("cash", cash)
            .put("takings", takings)
            .put("creditCount", creditCount)
            .put("creditValue", creditValue)
            .put("bills", query1(
                "SELECT COUNT(*) FROM ${DatabaseHelper.Tables.TD_BILLS} b " +
                    "WHERE date(COALESCE(b.bill_date_time, b.bill_date)) = '$today' " +
                    "AND ${BillDao.countableBillClause("b")}"
            ).toInt())
            .put("owed", owed)
    }

    // ---- The restaurant band -----------------------------------------------------

    /**
     * The floor and the kitchen, mostly in the present tense - what a manager glancing
     * at this mid-service wants. Yesterday's service is what the reports are for.
     *
     * The one figure that is not measurable here is how long a finished meal took:
     * settling a table sets its status and nothing else, so there is no second
     * timestamp to subtract from. What can be said is how long the tables sitting
     * *now* have been sitting, and that is what the occupancy card says.
     */
    private fun restaurant(): JSONObject {
        val today = dayOffset(0)

        // A row of md_table describes a run of tables rather than one table, so the
        // total is the sum of the runs and not the count of the rows.
        val tables = query1(
            "SELECT COALESCE(SUM(CASE WHEN no_of_tables > 0 THEN no_of_tables ELSE 1 END), 0) " +
                "FROM ${DatabaseHelper.Tables.MD_TABLE}"
        ).toInt()
        // Counted by section AND code: table codes restart in every section, so
        // counting codes alone reads two rooms' table 1 as one occupied table.
        val occupied = query1(
            "SELECT COUNT(DISTINCT COALESCE(section, '') || '|' || table_code) " +
                "FROM ${DatabaseHelper.Tables.TD_RUNNING_ORDER} " +
                "WHERE status = 'RUNNING' AND table_code IS NOT NULL AND TRIM(table_code) <> ''"
        ).toInt()
        val seatedMinutes = query1(
            "SELECT COALESCE(AVG((julianday('now','localtime') - julianday(created_at)) * 1440), 0) " +
                "FROM ${DatabaseHelper.Tables.TD_RUNNING_ORDER} WHERE status = 'RUNNING'"
        )

        // How many times the room has been sat today. Every seating opens a running
        // order, so counting today's orders counts today's covers - including the ones
        // already settled, which is what makes this a measure of the day rather than
        // of this minute.
        val seatings = query1(
            "SELECT COUNT(*) FROM ${DatabaseHelper.Tables.TD_RUNNING_ORDER} " +
                "WHERE date(created_at) = '$today'"
        ).toInt()
        val turns = if (tables > 0) seatings.toDouble() / tables else 0.0

        // The kitchen queue. Age since raised, which is the honest reading: a ticket is
        // only ever CLOSED when the bill prints, so "prepared" is not a state this
        // database has.
        val openKots = query1(
            "SELECT COUNT(*) FROM ${DatabaseHelper.Tables.TD_KOT} " +
                "WHERE COALESCE(status, 'OPEN') NOT IN ('CLOSED', 'CANCELLED')"
        ).toInt()
        val oldestKot = query1(
            "SELECT COALESCE(MAX((julianday('now','localtime') - julianday(created_at)) * 1440), 0) " +
                "FROM ${DatabaseHelper.Tables.TD_KOT} " +
                "WHERE COALESCE(status, 'OPEN') NOT IN ('CLOSED', 'CANCELLED')"
        )

        // Dine in against take away, over today's takings. Both are written to the
        // bill by the restaurant screen, so this is a split of money and not a guess
        // from whether a table happened to be named.
        var dineIn = 0.0
        var takeAway = 0.0
        query(
            """
            SELECT COALESCE(b.order_type, ''), COALESCE(SUM(b.net_amount), 0)
            FROM ${DatabaseHelper.Tables.TD_BILLS} b
            WHERE date(COALESCE(b.bill_date_time, b.bill_date)) = '$today'
              AND ${BillDao.countableBillClause("b")}
            GROUP BY COALESCE(b.order_type, '')
            """.trimIndent()
        ) { c ->
            val type = c.getString(0).orEmpty().replace(" ", "").lowercase()
            // A bill with no order type came from a table, since only the take-away
            // path has to name itself.
            if (type.startsWith("take") || type.startsWith("parcel")) takeAway += c.getDouble(1)
            else dineIn += c.getDouble(1)
        }

        return JSONObject()
            .put("tables", tables)
            .put("occupied", occupied.coerceAtMost(if (tables > 0) tables else occupied))
            .put("seatedMinutes", seatedMinutes)
            .put("seatings", seatings)
            .put("turns", turns)
            .put("turnsTarget", TURNS_TARGET)
            .put("openKots", openKots)
            .put("oldestKot", oldestKot)
            .put("kotLate", KOT_LATE_MINUTES)
            .put("dineIn", dineIn)
            .put("takeAway", takeAway)
    }

    /** Which of the two bands this till gets. Read the same way the rest of the app reads it. */
    private fun isRestaurant(): Boolean =
        com.example.synergic_pos_offline.utils.SettingsCache.value(appContext, "G", "Mode") == "R"

    private fun query1(sql: String): Double = runCatching {
        helper.readableDatabase.rawQuery(sql, null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0) else 0.0
        }
    }.getOrDefault(0.0)

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
        /** Bars on the category chart before the rest become "Other". */
        const val TOP_ROWS = 6

        /** Products named in the alert band; the count above it reports them all. */
        const val ALERT_ROWS = 3

        /** How far back "moving" is measured - see [movement]. */
        const val MOVEMENT_DAYS = 30

        /** How many items each half of the fast/slow chart names. */
        const val MOVEMENT_ROWS = 5

        /** Turns a room is doing well to manage in a day, for the turns bar to sit against. */
        const val TURNS_TARGET = 3.0

        /** How long a ticket may sit before the kitchen queue card calls it late. */
        const val KOT_LATE_MINUTES = 15.0
    }
}
