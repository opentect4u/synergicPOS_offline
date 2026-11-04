package com.example.synergic_pos_offline.database

import android.content.Context
import com.example.synergic_pos_offline.utils.BillRounding
import com.example.synergic_pos_offline.utils.GstCalculator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The Shift Day Report: a business day's bills, shift by shift, where the day is the
 * one the shop's SHIFTS make rather than the one the calendar does.
 *
 * ## The business day
 *
 * A restaurant open 10:00 to 22:00 with a night shift from 22:00 to 06:00 does not
 * close at midnight. The bill rung up at 01:30 on the 11th belongs to the night of the
 * 10th - it is in the 10th's cash-up, it was served by the 10th's night staff - and a
 * report that cut the day at midnight would split one night's takings across two dates.
 *
 * So a business date begins when the day's first shift begins: the earliest start time
 * on the shift master. With shifts of 10:00-22:00 and 22:00-06:00, the 10th runs from
 * 10:00 on the 10th to 10:00 on the 11th, which takes in the whole night shift ending at
 * 06:00. A bill in the quiet hours between the last shift's end and the next day's start
 * (06:00 to 10:00 here) still belongs to the day it closes, and is listed as outside
 * shift hours rather than dropped. A till with no shifts on the master has a plain
 * midnight-to-midnight day.
 *
 * ## Which shift a bill belongs to
 *
 * The one whose hours cover the time it was settled - [ShiftDao.Shift.coversNow], the
 * same test that decides whether a user may sign in. That is the clock, not the person,
 * and it is the deliberate opposite of [ShiftWiseReportDao], which counts a bill under
 * the shift its operator is on. The two answer different questions: that one is "what
 * did this team take", this one is "what came in during each part of the day". Both are
 * kept, and neither changes the other.
 *
 * ## Where the figures come from
 *
 * Off each bill's own stored totals, read the way [ShiftWiseReportDao] and the Bill Wise
 * Report read them, so a bill appearing on all three reads the same on all three.
 */
class ShiftDayReportDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val appContext = context.applicationContext

    /**
     * How the report's bills are laid out - one row per bill, or totalled by what was
     * sold, how it was paid, where it was served or who served it. Every view groups
     * within each business date's shift, so a shift's figures can be read off any of them.
     */
    enum class View(val label: String) {
        BILL("Bill Wise"),
        ITEM("Item Wise"),
        PAYMENT("Payment Wise"),
        TABLE("Table Wise"),
        WAITER("Waiter Wise"),
        TAX("Tax Wise"),
        OPERATOR("Operator Wise");

        /** Whether this view is built from the bills' item lines rather than the bills. */
        val needsItems: Boolean get() = this == ITEM || this == TAX
    }

    /** One bill on the report. */
    data class Line(
        /** td_bills.receipt_no - what the bill's items and tenders hang off. */
        val receiptNo: Long,
        /** The business date it is counted under, yyyy-MM-dd - not always the date it was rung up. */
        val businessDate: String,
        /** The shift its settle time falls in, or null for one outside every shift's hours. */
        val shift: ShiftDao.Shift?,
        val billNumber: String,
        /** "Dine In", "Take Away", "QSR" - blank on a grocery bill. */
        val orderType: String,
        /** The table code for a dine-in bill, the "TA-n" token for a counter one. */
        val table: String,
        /** The table's section; blank for a counter bill. */
        val section: String,
        /** The waiter's name, blank where the bill had none. */
        val waiter: String,
        /** yyyy-MM-dd HH:mm:ss, as stored. */
        val dateTime: String,
        val operator: String,
        val payMode: String,
        val mrp: Double,
        val cgst: Double,
        val sgst: Double,
        val igst: Double,
        val vat: Double,
        val discount: Double,
        val roundOff: Double,
        val serviceCharge: Double,
        val otherCharges: Double,
        val parcelCharge: Double,
        val netAmount: Double,
        val regime: GstCalculator.TaxRegime
    ) {
        val isVat: Boolean get() = regime == GstCalculator.TaxRegime.VAT
    }

    /**
     * One line of a bill's items, as sold: its own total, tax and discount inside it,
     * and the tax booked on it - read as stored, the way [TaxReportDao] reads it.
     */
    data class ItemSale(
        val receiptNo: Long,
        val name: String,
        val quantity: Double,
        val amount: Double,
        /** CGST + SGST + IGST rate, as a percentage. */
        val gstRate: Double = 0.0,
        val vatRate: Double = 0.0,
        val cgst: Double = 0.0,
        val sgst: Double = 0.0,
        val igst: Double = 0.0,
        val vat: Double = 0.0
    ) {
        val tax: Double get() = cgst + sgst + igst + vat

        /** The line's total less the tax on it - the taxable base however the price was quoted. */
        val taxable: Double get() = amount - tax

        /** "GST 5%", "VAT 12.5%", "No Tax" - the slab the tax view totals the line under. */
        val slab: String
            get() = listOfNotNull(
                gstRate.takeIf { it > 0.0 || cgst + sgst + igst > 0.0 }?.let { "GST ${percent(it)}" },
                vatRate.takeIf { it > 0.0 || vat > 0.0 }?.let { "VAT ${percent(it)}" }
            ).joinToString(" + ").ifBlank { NO_TAX }
    }

    /** What one tender of a bill kept in the drawer - tendered less change. */
    data class Tender(val receiptNo: Long, val mode: String, val amount: Double)

    /**
     * One row of a grouped view: a business date's shift and the thing totalled within
     * it - an item, a payment mode, a table, a waiter.
     */
    data class Group(
        val businessDate: String,
        val shift: ShiftDao.Shift?,
        /** The item, mode, waiter - or for a table, its section (or the counter's order type). */
        val name: String,
        /** The table code under [name]'s section; blank for every other view. */
        val detail: String,
        /** Distinct bills behind the row. */
        val bills: Int,
        /** Quantity sold - the item view only. */
        val quantity: Double,
        val amount: Double,
        /** The tax view's figures; zero on every other view. */
        val taxable: Double = 0.0,
        val cgst: Double = 0.0,
        val sgst: Double = 0.0,
        val igst: Double = 0.0,
        val vat: Double = 0.0,
        /** The tax view's combined rate, so slabs list lowest first rather than as text sorts. */
        val rate: Double = 0.0
    ) {
        val tax: Double get() = BillRounding.toPaise(cgst + sgst + igst + vat)
    }

    /** One shift's share of the period, for the totals under the table. */
    data class ShiftTotal(val shift: ShiftDao.Shift?, val bills: Int, val amount: Double)

    /**
     * The whole report. Totalled from the listed lines rather than by a second query, so
     * the summary and the rows above it agree by construction.
     */
    data class Report(
        val fromDate: String,
        val toDate: String,
        /** "10:00" - when each business date begins. "00:00" on a till without shifts. */
        val dayStart: String,
        /** "06:00" with [endsNextDay] - when the last shift of a business date ends. */
        val dayEnd: String,
        val endsNextDay: Boolean,
        /** The shift reported on, or null for all of them. */
        val shiftFilter: ShiftDao.Shift?,
        val shifts: List<ShiftDao.Shift>,
        val lines: List<Line>,
        /** How the report was asked to be laid out - held so Print matches the screen. */
        val view: View = View.BILL,
        /** The bills' item lines; read only for [View.ITEM] and [View.TAX]. */
        val items: List<ItemSale> = emptyList(),
        /** The bills' tenders; read only for [View.PAYMENT]. */
        val tenders: List<Tender> = emptyList()
    ) {
        val billCount: Int get() = lines.size
        val totalMrp: Double get() = total { it.mrp }
        val totalCgst: Double get() = total { it.cgst }
        val totalSgst: Double get() = total { it.sgst }
        val totalIgst: Double get() = total { it.igst }
        val totalVat: Double get() = total { it.vat }
        val totalDiscount: Double get() = total { it.discount }
        val totalServiceCharge: Double get() = total { it.serviceCharge }
        val totalOtherCharges: Double get() = total { it.otherCharges }
        val totalParcelCharge: Double get() = total { it.parcelCharge }
        val totalRoundOff: Double get() = total { it.roundOff }
        val totalAmount: Double get() = total { it.netAmount }

        val hasVat: Boolean get() = lines.any { it.isVat || it.vat > 0.0 }
        val hasIgst: Boolean get() = lines.any { it.igst > 0.0 }
        val isEmpty: Boolean get() = lines.isEmpty()

        /**
         * Each shift's bills and takings, in the order the shifts run through the day,
         * every shift listed even where it took nothing - a shift that billed nothing is
         * a figure the cash-up wants to see, not a line to leave out. Bills outside all
         * shift hours come last, and only where there were any.
         */
        val byShift: List<ShiftTotal>
            get() {
                val shown = shiftFilter?.let { listOf(it) } ?: shifts
                val out = shown.map { s ->
                    val mine = lines.filter { it.shift?.id == s.id }
                    ShiftTotal(s, mine.size, BillRounding.toPaise(mine.sumOf { it.netAmount }))
                }.toMutableList()
                val outside = lines.filter { it.shift == null }
                if (outside.isNotEmpty()) {
                    out.add(ShiftTotal(null, outside.size, BillRounding.toPaise(outside.sumOf { it.netAmount })))
                }
                return out
            }

        /** Quantity across every item line - the item view's own total. */
        val totalQuantity: Double get() = groups.sumOf { it.quantity }

        /** The tax view's totals, from its own rows - so they agree with the rows above them. */
        val viewTaxable: Double get() = BillRounding.toPaise(groups.sumOf { it.taxable })
        val viewCgst: Double get() = BillRounding.toPaise(groups.sumOf { it.cgst })
        val viewSgst: Double get() = BillRounding.toPaise(groups.sumOf { it.sgst })
        val viewIgst: Double get() = BillRounding.toPaise(groups.sumOf { it.igst })
        val viewVat: Double get() = BillRounding.toPaise(groups.sumOf { it.vat })
        val viewTax: Double get() = BillRounding.toPaise(groups.sumOf { it.tax })

        /** What the rows of [view] add up to - the items' own total on the item view. */
        val viewAmount: Double get() = BillRounding.toPaise(groups.sumOf { it.amount })

        /**
         * [view]'s rows: each business date's shifts in day order, and within a shift the
         * biggest figure first - except tables, which read in floor order.
         *
         * Empty on the bill view, which lists [lines] as they are.
         */
        val groups: List<Group> by lazy {
            val byReceipt = lines.associateBy { it.receiptNo }
            // One bill's contribution to a row: the row it lands in and what it adds.
            // [item] is the item line behind it, on the tax view - where the tax figures come from.
            data class Part(
                val line: Line, val name: String, val detail: String, val qty: Double, val amount: Double,
                val item: ItemSale? = null
            )
            val parts: List<Part> = when (view) {
                View.BILL -> emptyList()
                View.ITEM -> items.mapNotNull { i ->
                    byReceipt[i.receiptNo]?.let { Part(it, i.name, "", i.quantity, i.amount) }
                }
                // The tenders, plus whatever of the bill no tender covered - a credit
                // sale's amount, owed rather than taken - so the modes add up to the bills.
                View.PAYMENT -> lines.flatMap { line ->
                    val mine = tenders.filter { it.receiptNo == line.receiptNo && kotlin.math.abs(it.amount) > 0.005 }
                    val due = BillRounding.toPaise(line.netAmount - mine.sumOf { it.amount })
                    mine.map { Part(line, it.mode, "", 0.0, it.amount) } +
                        (if (due > 0.005) listOf(Part(line, CREDIT, "", 0.0, due)) else emptyList())
                }
                View.TABLE -> lines.map { line ->
                    if (line.section.isNotBlank()) Part(line, line.section, line.table, 0.0, line.netAmount)
                    else Part(line, line.orderType.ifBlank { COUNTER }, "", 0.0, line.netAmount)
                }
                View.WAITER -> lines.map { Part(it, it.waiter.ifBlank { NO_WAITER }, "", 0.0, it.netAmount) }
                // By the item lines, not the bill: one bill can carry dishes at two rates.
                View.TAX -> items.mapNotNull { i ->
                    byReceipt[i.receiptNo]?.let { Part(it, i.slab, "", 0.0, i.amount, i) }
                }
                View.OPERATOR -> lines.map { Part(it, it.operator, "", 0.0, it.netAmount) }
            }
            val shiftOrder = shifts.mapIndexed { i, s -> s.id to i }.toMap()
            parts.groupBy { Triple(it.line.businessDate, it.line.shift?.id, it.name to it.detail) }
                .map { (key, ps) ->
                    Group(
                        businessDate = key.first,
                        shift = ps.first().line.shift,
                        name = key.third.first,
                        detail = key.third.second,
                        bills = ps.map { it.line.receiptNo }.distinct().size,
                        quantity = ps.sumOf { it.qty },
                        amount = BillRounding.toPaise(ps.sumOf { it.amount }),
                        taxable = BillRounding.toPaise(ps.sumOf { it.item?.taxable ?: 0.0 }),
                        cgst = BillRounding.toPaise(ps.sumOf { it.item?.cgst ?: 0.0 }),
                        sgst = BillRounding.toPaise(ps.sumOf { it.item?.sgst ?: 0.0 }),
                        igst = BillRounding.toPaise(ps.sumOf { it.item?.igst ?: 0.0 }),
                        vat = BillRounding.toPaise(ps.sumOf { it.item?.vat ?: 0.0 }),
                        rate = ps.first().item?.let { it.gstRate + it.vatRate } ?: 0.0
                    )
                }
                .sortedWith(
                    compareBy<Group>({ it.businessDate }, { it.shift?.let { s -> shiftOrder[s.id] } ?: Int.MAX_VALUE })
                        .then(
                            when (view) {
                                View.TABLE -> compareBy<Group>(
                                    // Sections first, counter orders after them.
                                    { if (it.detail.isBlank()) 1 else 0 }, { it.name.lowercase() },
                                    { it.detail.toIntOrNull() ?: Int.MAX_VALUE }, { it.detail }
                                )
                                // Slabs lowest rate first, as a return lists them.
                                View.TAX -> compareBy<Group>({ it.rate }, { it.name })
                                else -> compareByDescending<Group> { it.amount }.thenBy { it.name.lowercase() }
                            }
                        )
                )
        }

        /**
         * Each shift's share as [view] counts it - the same shifts as [byShift], with the
         * amount its rows add up to (the items' total on the item view).
         */
        val byShiftInView: List<ShiftTotal>
            get() = if (view == View.BILL) byShift else byShift.map { t ->
                val mine = groups.filter { it.shift?.id == t.shift?.id }
                t.copy(amount = BillRounding.toPaise(mine.sumOf { it.amount }))
            }

        /** Each payment mode's total over the whole period - the payment view's summary. */
        val byMode: List<Pair<String, Double>>
            get() = groups.groupBy { it.name }
                .map { (mode, gs) -> mode to BillRounding.toPaise(gs.sumOf { it.amount }) }
                .sortedByDescending { it.second }

        private fun total(pick: (Line) -> Double): Double =
            BillRounding.toPaise(lines.sumOf { pick(it) })
    }

    /**
     * The shift master, in the order the shifts run through a business day - the one
     * that opens the day first. The master's own order is the order they were typed in,
     * which need not be that.
     */
    fun shifts(): List<ShiftDao.Shift> {
        val all = ShiftDao(appContext).getAll()
        val start = dayStartMinutes(all)
        return all.sortedBy { s -> ShiftDao.minutesOf(s.fromTime)?.let { (it - start + DAY) % DAY } ?: DAY }
    }

    /**
     * Every completed bill whose settle time falls inside the business dates [fromDate]
     * to [toDate] inclusive, both yyyy-MM-dd, oldest first - optionally only those of
     * [only] shift.
     *
     * Voided and cancelled bills are left out, as on every other sales report. The
     * bills' item lines are read only for [View.ITEM] and [View.TAX], their tenders only
     * for [View.PAYMENT] - the other views need neither.
     */
    fun between(fromDate: String, toDate: String, only: ShiftDao.Shift?, view: View = View.BILL): Report {
        val shifts = shifts()
        val startMin = dayStartMinutes(shifts)
        val (endMin, endsNextDay) = dayEnd(shifts, startMin)
        val startClock = clock(startMin)

        // The window, as stored date-times: the first business date's opening up to the
        // opening of the day after the last one. Compared as text, which is safe because
        // bill_date_time is always written yyyy-MM-dd HH:mm:ss.
        val windowFrom = "$fromDate $startClock:00"
        val windowTo = "${plusDays(toDate, 1)} $startClock:00"

        val store = currentStoreId()
        val storeClause = if (store != null) "AND b.store_id = ?" else ""
        // Which bills are in the window - shared by the bill query and the item and
        // tender queries, so all three read exactly the same bills.
        val billWhere = """
            COALESCE(b.bill_date_time, substr(b.bill_date, 1, 10) || ' 00:00:00') >= ?
              AND COALESCE(b.bill_date_time, substr(b.bill_date, 1, 10) || ' 00:00:00') < ?
              AND COALESCE(b.is_voided, 0) = 0
              AND COALESCE(b.bill_status, 'COMPLETED') <> 'CANCELLED'
              $storeClause
        """.trimIndent()
        val sql = """
            SELECT b.bill_number,
                   COALESCE(b.bill_date_time, substr(b.bill_date, 1, 10) || ' 00:00:00'),
                   COALESCE(u.user_name, u.user_id, ''),
                   -- Every mode the bill was settled in - see ShiftWiseReportDao.
                   COALESCE(
                       (SELECT GROUP_CONCAT(mode, ' + ') FROM (
                           SELECT p.payment_mode AS mode FROM ${DatabaseHelper.Tables.TD_PAYMENTS} p
                            WHERE p.bill_id = b.receipt_no AND p.payment_mode IS NOT NULL
                            ORDER BY p.id ASC)),
                       b.bill_type, ''),
                   COALESCE(b.tot_price, 0), COALESCE(b.tot_cgst_amount, 0),
                   COALESCE(b.tot_sgst_amount, 0), COALESCE(b.tot_igst_amount, 0),
                   COALESCE(b.tot_vat_amount, 0),
                   COALESCE(b.tot_discount_amount, 0), COALESCE(b.tot_round_off_amount, 0),
                   COALESCE(b.service_charge_amount, 0), COALESCE(b.tot_other_charges_amount, 0),
                   COALESCE(b.parcel_charge_amount, 0),
                   COALESCE(b.net_amount, 0),
                   b.receipt_no, COALESCE(b.order_type, ''), COALESCE(b.table_number, ''),
                   COALESCE(b.table_section, ''), COALESCE(w.waiter_name, '')
            FROM ${DatabaseHelper.Tables.TD_BILLS} b
            LEFT JOIN ${DatabaseHelper.Tables.MD_USERS} u ON u.id = b.operator_id
            LEFT JOIN ${DatabaseHelper.Tables.MD_WAITERS} w ON w.id = b.waiter_id
            WHERE $billWhere
            ORDER BY COALESCE(b.bill_date_time, b.bill_date) ASC, b.receipt_no ASC
        """.trimIndent()
        val args = mutableListOf(windowFrom, windowTo).apply { if (store != null) add(store.toString()) }

        val lines = mutableListOf<Line>()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val dateTime = c.getString(1).orEmpty()
                val minutes = minutesOfDateTime(dateTime)
                // The first shift, in day order, whose hours cover the settle time.
                val shift = minutes?.let { m -> shifts.firstOrNull { it.hasHours && it.coversNow(m) } }
                if (only != null && shift?.id != only.id) continue
                val cgst = c.getDouble(5)
                val sgst = c.getDouble(6)
                val igst = c.getDouble(7)
                val vat = c.getDouble(8)
                lines.add(
                    Line(
                        receiptNo = c.getLong(15),
                        businessDate = businessDate(dateTime, startMin),
                        shift = shift,
                        billNumber = c.getString(0).orEmpty().ifBlank { "-" },
                        orderType = c.getString(16).orEmpty(),
                        table = c.getString(17).orEmpty(),
                        section = c.getString(18).orEmpty(),
                        waiter = c.getString(19).orEmpty(),
                        dateTime = dateTime,
                        operator = c.getString(2).orEmpty().ifBlank { "-" },
                        payMode = c.getString(3).orEmpty().ifBlank { "-" }.uppercase(),
                        mrp = c.getDouble(4),
                        cgst = cgst,
                        sgst = sgst,
                        igst = igst,
                        vat = vat,
                        discount = c.getDouble(9),
                        roundOff = c.getDouble(10),
                        serviceCharge = c.getDouble(11),
                        otherCharges = (c.getDouble(12) - c.getDouble(13)).coerceAtLeast(0.0),
                        parcelCharge = c.getDouble(13),
                        netAmount = c.getDouble(14),
                        regime = BillWiseReportDao.regimeOf(cgst + sgst + igst, vat)
                    )
                )
            }
        }

        // Rows of bills that the shift filter dropped are read too, and simply find no
        // bill to land on - see [Report.groups].
        val items = if (!view.needsItems) emptyList() else buildList {
            helper.readableDatabase.rawQuery(
                """
                SELECT i.bill_id, COALESCE(NULLIF(TRIM(i.product_name), ''), '-'),
                       COALESCE(i.quantity, 0), COALESCE(i.item_total, 0),
                       COALESCE(i.cgst_rate, 0) + COALESCE(i.sgst_rate, 0) + COALESCE(i.igst_rate, 0),
                       COALESCE(i.vat_rate, 0),
                       COALESCE(i.cgst_amount, 0), COALESCE(i.sgst_amount, 0),
                       COALESCE(i.igst_amount, 0), COALESCE(i.vat_amount, 0)
                FROM ${DatabaseHelper.Tables.TD_BILL_ITEMS} i
                JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = i.bill_id
                WHERE $billWhere
                """.trimIndent(), args.toTypedArray()
            ).use { c ->
                while (c.moveToNext()) add(
                    ItemSale(
                        receiptNo = c.getLong(0), name = c.getString(1),
                        quantity = c.getDouble(2), amount = c.getDouble(3),
                        gstRate = c.getDouble(4), vatRate = c.getDouble(5),
                        cgst = c.getDouble(6), sgst = c.getDouble(7),
                        igst = c.getDouble(8), vat = c.getDouble(9)
                    )
                )
            }
        }
        // What stayed in the drawer - tendered less change, as the Payment-Wise Report counts it.
        val tenders = if (view != View.PAYMENT) emptyList() else buildList {
            helper.readableDatabase.rawQuery(
                """
                SELECT p.bill_id, UPPER(TRIM(COALESCE(p.payment_mode, b.bill_type, '-'))),
                       COALESCE(p.amount_paid, 0) - COALESCE(p.change_amount, 0)
                FROM ${DatabaseHelper.Tables.TD_PAYMENTS} p
                JOIN ${DatabaseHelper.Tables.TD_BILLS} b ON b.receipt_no = p.bill_id
                WHERE $billWhere
                ORDER BY p.id ASC
                """.trimIndent(), args.toTypedArray()
            ).use { c ->
                while (c.moveToNext()) add(Tender(c.getLong(0), c.getString(1), c.getDouble(2)))
            }
        }
        return Report(
            fromDate, toDate, startClock, clock(endMin), endsNextDay, only, shifts, lines,
            view, items, tenders
        )
    }

    private fun currentStoreId(): Int? =
        com.example.synergic_pos_offline.utils.SessionManager.currentUser?.storeId

    companion object {
        private const val DAY = 24 * 60

        /** The payment view's row for what a bill's tenders left owing. */
        const val CREDIT = "CREDIT"

        /** The table view's row for bills with neither a section nor an order type. */
        const val COUNTER = "Counter"

        /** The waiter view's row for bills nobody was assigned to. */
        const val NO_WAITER = "No waiter"

        /** The tax view's row for item lines that carried no tax. */
        const val NO_TAX = "No Tax"

        /** 5.0 as "5%", 2.5 as "2.5%". */
        private fun percent(rate: Double): String =
            (if (rate % 1.0 == 0.0) rate.toInt().toString() else "%.2f".format(Locale.US, rate).trimEnd('0').trimEnd('.')) + "%"

        /** A shift with both clock faces readable - one that actually bounds a part of the day. */
        private val ShiftDao.Shift.hasHours: Boolean
            get() = ShiftDao.minutesOf(fromTime) != null && ShiftDao.minutesOf(toTime) != null

        /** When a business day begins: the earliest shift start, or midnight without shifts. */
        fun dayStartMinutes(shifts: List<ShiftDao.Shift>): Int =
            shifts.filter { it.hasHours }.mapNotNull { ShiftDao.minutesOf(it.fromTime) }.minOrNull() ?: 0

        /**
         * When the last shift of a business day ends, and whether that is on the next
         * calendar day. Measured from [startMin] so a night shift ending at 06:00 reads
         * as the day's END, not as six in the morning before it began.
         */
        fun dayEnd(shifts: List<ShiftDao.Shift>, startMin: Int): Pair<Int, Boolean> {
            val ends = shifts.filter { it.hasHours }.map { s ->
                val from = ShiftDao.minutesOf(s.fromTime)!!
                val to = ShiftDao.minutesOf(s.toTime)!!
                val offset = (from - startMin + DAY) % DAY
                val length = ((to - from + DAY) % DAY).let { if (it == 0) DAY else it }
                offset + length
            }
            val last = ends.maxOrNull() ?: DAY
            return ((startMin + last) % DAY) to (startMin + last >= DAY)
        }

        /** The business date a settle time belongs to: its own date, less the day's opening hours. */
        fun businessDate(dateTime: String, startMin: Int): String = runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val cal = Calendar.getInstance().apply { time = fmt.parse(dateTime)!! }
            cal.add(Calendar.MINUTE, -startMin)
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        }.getOrDefault(dateTime.take(10))

        private fun minutesOfDateTime(dateTime: String): Int? =
            ShiftDao.minutesOf(dateTime.drop(11).take(5))

        private fun clock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

        private fun plusDays(date: String, days: Int): String = runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val cal = Calendar.getInstance().apply { time = fmt.parse(date)!! }
            cal.add(Calendar.DAY_OF_MONTH, days)
            fmt.format(cal.time)
        }.getOrDefault(date)
    }
}
