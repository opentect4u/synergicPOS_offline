package com.example.synergic_pos_offline.lanreport

import android.content.Context
import android.util.Log
import com.example.synergic_pos_offline.database.BillWiseReportDao
import com.example.synergic_pos_offline.database.CategoryWiseReportDao
import com.example.synergic_pos_offline.database.DashboardDao
import com.example.synergic_pos_offline.database.ItemWiseReportDao
import com.example.synergic_pos_offline.database.PaymentWiseReportDao
import com.example.synergic_pos_offline.fragments.RestaurantOrdersFragment
import com.example.synergic_pos_offline.utils.CalendarGrain
import com.example.synergic_pos_offline.utils.PaymentModeSetting
import com.example.synergic_pos_offline.utils.SettingsCache
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The Mobile Reports server: answers phones on the shop network with the same
 * figures the till's own report screens show, as JSON.
 *
 * READ ONLY, with one exception. Every route reads through the existing report classes
 * and only GET is accepted - except `POST /api/tables/settle`, the mobile app's Table
 * Settlement, which confirms payment for a billed dine-in table through the till's own
 * settlement code (Restaurant mode only, PIN required like everything else).
 *
 * Routes (port [LanPairing.PORT]):
 *  - `GET /api/ping` - no PIN; lets a phone check it has found a till.
 *  - `GET /api/dashboard` - today's figures, from [DashboardDao.snapshot].
 *  - `GET /api/reports/bill-wise?from=yyyy-MM-dd&to=yyyy-MM-dd`
 *  - `GET /api/reports/item-wise?from=&to=`
 *  - `GET /api/reports/payment-wise?from=&to=[&mode=CASH]`
 *  - `GET /api/reports/category-wise?from=&to=`
 *  - `GET /api/catalog` - every report this till's Reports screen shows, and whether
 *    it is in Restaurant mode (api 2)
 *  - `GET /api/report?id=&from=&to=[&filter=][&mode=]` - any of them, as a table (api 2)
 *  - `GET /api/tables/billed` - dine-in tables billed and awaiting payment (Restaurant)
 *  - `GET /api/tables/checkout?orderId=` - a billed table's checkout: lines, totals, modes
 *  - `GET /api/tables/preview?orderId=&mode=...` - the Receipt Preview, as a PNG
 *  - `POST /api/tables/settle` (orderId, amount, mode, tendered, splitCash/Online/Card,
 *    creditPhone) - confirm payment, free the table
 *
 * Every route but ping needs the PIN, as the `X-POS-PIN` header or a `pin` parameter.
 * Dates default to today. Ten wrong PINs from one address lock it out for a minute,
 * so the PIN cannot simply be guessed through.
 */
class ReportServer(context: Context) : NanoHTTPD(LanPairing.PORT) {

    private val app = context.applicationContext

    private class Strikes(var count: Int, var since: Long)
    private val failures = ConcurrentHashMap<String, Strikes>()

    override fun serve(session: IHTTPSession): Response {
        val response = try {
            route(session)
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${session.uri}", e)
            error(Response.Status.INTERNAL_ERROR, "Could not read the report: ${e.message}")
        }
        // A phone app reads this directly, but a browser test page may too.
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Headers", "X-POS-PIN")
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun route(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
        val path = session.uri.trimEnd('/').ifEmpty { "/" }
        // GET everywhere, except the one route that changes anything - see [settleTable].
        val allowed = if (path == "/api/tables/settle") Method.POST else Method.GET
        if (session.method != allowed) {
            return error(Response.Status.METHOD_NOT_ALLOWED, "Only ${allowed.name} is supported here")
        }
        if (path == "/api/ping") return json(ping())

        val client = session.remoteIpAddress.orEmpty()
        if (isLockedOut(client)) {
            return error(Response.Status.lookup(429) ?: Response.Status.FORBIDDEN, "Too many wrong PINs - wait a minute")
        }
        val pin = session.headers["x-pos-pin"] ?: session.parameters["pin"]?.firstOrNull()
        if (!LanPairing.pinMatches(app, pin)) {
            strike(client)
            return error(Response.Status.UNAUTHORIZED, "Wrong or missing PIN")
        }
        failures.remove(client)

        // Nobody signed in to the till: the phones it was serving are cut off until
        // somebody is - the phone takes this as "disconnected" (see loggedOut).
        if (!com.example.synergic_pos_offline.utils.SessionManager.isSignedIn(app)) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "application/json; charset=utf-8",
                JSONObject().put("error", "The POS user logged out. Log in on the POS, then connect again.")
                    .put("loggedOut", true).toString()
            )
        }

        if (path == "/api/dashboard") return json(DashboardDao(app).snapshot())
        if (path == "/api/catalog") return json(catalog())
        if (path == "/api/report") return report(session)
        if (path == "/api/tables/billed") return billedTables()
        if (path == "/api/tables/checkout") return tableCheckout(session)
        if (path == "/api/tables/preview") return tablePreview(session)
        if (path == "/api/tables/settle") return settleTable(session)

        val (from, to) = range(session) ?: return error(
            Response.Status.BAD_REQUEST,
            "Dates must be yyyy-MM-dd, and from must not be after to"
        )
        return when (path) {
            "/api/reports/bill-wise" -> json(billWise(from, to))
            "/api/reports/item-wise" -> json(itemWise(from, to))
            "/api/reports/payment-wise" -> json(
                paymentWise(from, to, session.parameters["mode"]?.firstOrNull()?.trim()?.uppercase())
            )
            "/api/reports/category-wise" -> json(categoryWise(from, to))
            else -> error(Response.Status.NOT_FOUND, "No such report: $path")
        }
    }

    // ---- Reports -------------------------------------------------------------

    private fun ping() = JSONObject()
        .put("app", "SynergicPOS")
        .put("api", API_VERSION)
        // Where the live table events are - see EventSocketServer.
        .put("eventsPort", EventSocketServer.PORT)
        .put("signedIn", com.example.synergic_pos_offline.utils.SessionManager.isSignedIn(app))
        .put("version", runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull().orEmpty())

    private fun billWise(from: String, to: String): JSONObject {
        val r = BillWiseReportDao(app).between(from, to)
        val lines = JSONArray()
        r.lines.forEach {
            lines.put(
                JSONObject()
                    .put("billNumber", it.billNumber).put("date", it.date).put("payMode", it.payMode)
                    .put("mrp", it.mrp).put("cgst", it.cgst).put("sgst", it.sgst).put("igst", it.igst)
                    .put("vat", it.vat).put("discount", it.discount).put("roundOff", it.roundOff)
                    .put("serviceCharge", it.serviceCharge).put("otherCharges", it.otherCharges)
                    .put("parcelCharge", it.parcelCharge).put("netAmount", it.netAmount)
            )
        }
        return period(from, to).put("lines", lines).put(
            "totals", JSONObject()
                .put("billCount", r.billCount).put("mrp", r.totalMrp).put("cgst", r.totalCgst)
                .put("sgst", r.totalSgst).put("igst", r.totalIgst).put("vat", r.totalVat)
                .put("discount", r.totalDiscount).put("roundOff", r.totalRoundOff)
                .put("serviceCharge", r.totalServiceCharge).put("otherCharges", r.totalOtherCharges)
                .put("parcelCharge", r.totalParcelCharge).put("amount", r.totalAmount)
        )
    }

    private fun itemWise(from: String, to: String): JSONObject {
        val r = ItemWiseReportDao(app).between(from, to)
        val lines = JSONArray()
        r.lines.forEach {
            lines.put(
                JSONObject()
                    .put("serial", it.serial).put("name", it.name).put("quantity", it.quantity)
                    .put("amount", it.amount).put("sgst", it.sgst).put("cgst", it.cgst)
                    .put("igst", it.igst).put("vat", it.vat)
            )
        }
        return period(from, to).put("lines", lines).put(
            "totals", JSONObject()
                .put("itemCount", r.itemCount).put("quantity", r.totalQuantity).put("amount", r.totalAmount)
                .put("sgst", r.totalSgst).put("cgst", r.totalCgst).put("igst", r.totalIgst)
                .put("vat", r.totalVat).put("discount", r.totalDiscount)
                .put("serviceCharge", r.totalServiceCharge).put("otherCharges", r.totalOtherCharges)
                .put("parcelCharge", r.totalParcelCharge).put("roundOff", r.totalRoundOff)
                .put("netAmount", r.totalNetAmount)
        )
    }

    private fun paymentWise(from: String, to: String, mode: String?): JSONObject {
        val dao = PaymentWiseReportDao(app)
        val r = if (mode.isNullOrBlank()) dao.between(from, to) else dao.between(from, to, mode)
        val lines = JSONArray()
        r.lines.forEach {
            lines.put(JSONObject().put("mode", it.mode).put("billCount", it.billCount).put("paidAmount", it.paidAmount))
        }
        return period(from, to).put("mode", r.mode).put("lines", lines).put(
            "totals", JSONObject()
                .put("modeCount", r.modeCount).put("bills", r.totalBills).put("paid", r.totalPaid)
                .put("credit", r.creditAmount).put("collected", r.collectedAmount)
        )
    }

    private fun categoryWise(from: String, to: String): JSONObject {
        val r = CategoryWiseReportDao(app).between(from, to)
        val lines = JSONArray()
        r.lines.forEach {
            lines.put(JSONObject().put("category", it.category).put("quantity", it.quantity).put("amount", it.amount))
        }
        return period(from, to).put("lines", lines).put(
            "totals", JSONObject()
                .put("categoryCount", r.categoryCount).put("quantity", r.totalQuantity).put("amount", r.totalAmount)
        )
    }

    // ---- Every report on the till (api 2) -------------------------------------

    /**
     * The reports this till shows on its Reports screen, in its order - and whether it
     * runs in Restaurant mode, which decides the restaurant-only menus on the phone
     * (Table Settlement), by the same setting the till's own menus go by.
     */
    private fun catalog(): JSONObject {
        val reports = JSONArray()
        MobileReports.visible(app).forEach {
            reports.put(JSONObject().put("id", it.id).put("title", it.title))
        }
        val restaurant = SettingsCache.value(app, "G", "Mode") == "R"
        return JSONObject().put("reports", reports).put("restaurant", restaurant)
    }

    /**
     * `GET /api/report?id=&from=&to=[&filter=][&mode=]` - any report in [catalog], run
     * by the till's own report screen (see [HeadlessReport]), as a table.
     *
     * from/to are `yyyy-MM-dd`, or `yyyy-MM-dd HH:mm` for a report asked for by the
     * minute; they are cut to the report's own grain (a month, a year) here. The answer
     * carries what the report can be narrowed by, so the phone can offer the choices.
     */
    private fun report(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull().orEmpty()
        val entry = MobileReports.find(app, id)
            ?: return error(Response.Status.NOT_FOUND, "No such report on this till: $id")
        val today = format().format(Date())
        val fromRaw = session.parameters["from"]?.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: today
        val toRaw = session.parameters["to"]?.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: today
        if (!isMoment(fromRaw) || !isMoment(toRaw) || fromRaw > toRaw) {
            return error(Response.Status.BAD_REQUEST, "Dates must be yyyy-MM-dd, and from must not be after to")
        }
        val filter = session.parameters["filter"]?.firstOrNull()?.takeIf { it.isNotBlank() }
        val mode = session.parameters["mode"]?.firstOrNull()?.takeIf { it.isNotBlank() }

        val screen = MobileReports.instantiate(entry)
        val spec = screen.headlessSpec(app)
        val (from, to) = when (spec.grain) {
            CalendarGrain.MINUTE ->
                (if (fromRaw.length >= 16) fromRaw.take(16) else "${fromRaw.take(10)} 00:00") to
                    (if (toRaw.length >= 16) toRaw.take(16) else "${toRaw.take(10)} 23:59")
            else -> fromRaw.take(spec.grain.storedLength) to toRaw.take(spec.grain.storedLength)
        }
        val result = screen.runHeadless(app, from, to, filter, mode)
        val sheet = result.sheet

        val body = JSONObject()
            .put("id", entry.id).put("title", entry.title)
            .put("grain", spec.grain.name).put("from", from).put("to", to)
            .put("subtitle", sheet.subtitle)
            .put("columns", JSONArray(sheet.columns))
            .put("alignEnd", JSONArray(sheet.alignEnd))
            .put("rows", JSONArray().also { rows -> sheet.rows.forEach { rows.put(JSONArray(it)) } })
            .put("summary", JSONArray().also { s ->
                sheet.summary.forEach { (label, value) -> s.put(JSONObject().put("label", label).put("value", value)) }
            })
        spec.filterHint?.let {
            body.put("filter", JSONObject().put("hint", it).put("options", JSONArray(spec.filterOptions))
                .put("searchable", spec.filterSearchable).put("value", result.filterValue))
        }
        spec.modeHint?.let {
            body.put("mode", JSONObject().put("hint", it).put("options", JSONArray(spec.modeOptions))
                .put("value", result.modeValue))
        }
        result.emptyTitle?.let {
            body.put("empty", JSONObject().put("title", it).put("hint", result.emptyHint.orEmpty()))
        }
        return json(body)
    }

    // ---- Table Settlement (Restaurant mode) -----------------------------------

    private fun isRestaurant() = SettingsCache.value(app, "G", "Mode") == "R"

    /** The payment tiles this till's dine-in checkout shows. */
    private fun settleModes(): List<String> = MobileReports.onMain { RestaurantOrdersFragment() }.headlessModes(app)

    /** A billed table's order id from the request, or null. */
    private fun orderIdOf(session: IHTTPSession): Long? =
        session.parameters["orderId"]?.firstOrNull()?.toLongOrNull()

    /**
     * How the phone says the table was paid, from the request: `mode`, `tendered`,
     * `splitCash` / `splitOnline` / `splitCard`, `creditPhone`.
     */
    private fun paymentOf(session: IHTTPSession): RestaurantOrdersFragment.HeadlessPayment? {
        val p = session.parameters
        fun num(key: String) = p[key]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
        val mode = p["mode"]?.firstOrNull().orEmpty()
            .let { m -> settleModes().firstOrNull { it.equals(m, ignoreCase = true) } } ?: return null
        return RestaurantOrdersFragment.HeadlessPayment(
            mode = mode,
            tendered = num("tendered"),
            split = mapOf("CASH" to num("splitCash"), "ONLINE" to num("splitOnline"), "CARD" to num("splitCard"))
                .filterValues { it > 0.001 },
            creditPhone = p["creditPhone"]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    /** `GET /api/tables/checkout?orderId=` - the till's checkout screen, as data. */
    private fun tableCheckout(session: IHTTPSession): Response {
        if (!isRestaurant()) return error(Response.Status.FORBIDDEN, "Table Settlement is only for Restaurant mode")
        val orderId = orderIdOf(session) ?: return error(Response.Status.BAD_REQUEST, "orderId is missing")
        return try {
            json(MobileReports.onMain { RestaurantOrdersFragment() }.headlessCheckout(app, orderId))
        } catch (e: IllegalStateException) {
            error(Response.Status.CONFLICT, e.message ?: "This table cannot be paid")
        }
    }

    /**
     * `GET /api/tables/preview?orderId=&mode=...` - the checkout's Receipt Preview, as
     * a PNG of the slip. Nothing is written.
     */
    private fun tablePreview(session: IHTTPSession): Response {
        if (!isRestaurant()) return error(Response.Status.FORBIDDEN, "Table Settlement is only for Restaurant mode")
        val orderId = orderIdOf(session) ?: return error(Response.Status.BAD_REQUEST, "orderId is missing")
        val payment = paymentOf(session)
            ?: return error(Response.Status.BAD_REQUEST, "Pick a payment mode: ${settleModes().joinToString()}")
        return try {
            // Drawn on the main thread, where the renderer's views expect to be laid out.
            val bitmap = MobileReports.onMain {
                listOfNotNull(RestaurantOrdersFragment().headlessPreview(app, orderId, payment))
            }.firstOrNull() ?: return error(Response.Status.INTERNAL_ERROR, "Could not render the receipt")
            val bytes = java.io.ByteArrayOutputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            bitmap.recycle()
            newFixedLengthResponse(Response.Status.OK, "image/png", java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
        } catch (e: IllegalStateException) {
            error(Response.Status.CONFLICT, e.message ?: "This table cannot be paid")
        }
    }

    /** `GET /api/tables/billed` - dine-in tables whose bill is printed, awaiting payment. */
    private fun billedTables(): Response {
        if (!isRestaurant()) return error(Response.Status.FORBIDDEN, "Table Settlement is only for Restaurant mode")
        val tables = JSONArray()
        MobileReports.onMain { RestaurantOrdersFragment() }.headlessBilledTables(app).forEach {
            tables.put(
                JSONObject().put("orderId", it.orderId).put("table", it.table).put("section", it.section)
                    .put("amount", it.amount).put("time", it.time).put("items", it.itemCount)
                    .put("billNumber", it.billNumber)
            )
        }
        return json(JSONObject().put("tables", tables).put("modes", JSONArray(settleModes())))
    }

    /**
     * `POST /api/tables/settle` (form: orderId, mode, amount) - confirms payment for one
     * billed dine-in table and frees it.
     *
     * THE ONE ROUTE THAT WRITES. It does exactly what the till's own Confirm Payment
     * does, through the same code (RestaurantOrdersFragment.headlessSettle): the bill is
     * saved under the number its slip was printed with, the order closed and the table
     * freed. `amount` is what the phone showed; if what is owed has changed since, it
     * is refused rather than settled for a figure nobody saw.
     */
    private fun settleTable(session: IHTTPSession): Response {
        if (!isRestaurant()) return error(Response.Status.FORBIDDEN, "Table Settlement is only for Restaurant mode")
        session.parseBody(HashMap())
        val orderId = orderIdOf(session)
            ?: return error(Response.Status.BAD_REQUEST, "Which table? orderId is missing")
        val payment = paymentOf(session)
            ?: return error(Response.Status.BAD_REQUEST, "Pick a payment mode: ${settleModes().joinToString()}")
        val amount = session.parameters["amount"]?.firstOrNull()?.toDoubleOrNull()
            ?: return error(Response.Status.BAD_REQUEST, "amount is missing")
        return try {
            val clientId = session.parameters["clientId"]?.firstOrNull()?.trim().orEmpty().take(64)
            val billNo = MobileReports.onMain { RestaurantOrdersFragment() }
                .headlessSettle(app, orderId, payment, amount, clientId)
            Log.i(TAG, "Table settled from Mobile Reports: order $orderId, ${payment.mode}, bill $billNo")
            json(JSONObject().put("ok", true).put("billNumber", billNo))
        } catch (e: IllegalStateException) {
            // Already settled, amount changed, not billed - nothing was written.
            error(Response.Status.CONFLICT, e.message ?: "Could not settle this table")
        }
    }

    /** `yyyy-MM-dd`, optionally followed by ` HH:mm`. */
    private fun isMoment(s: String): Boolean {
        if (!isDate(s.take(10))) return false
        if (s.length == 10) return true
        return s.length == 16 && s[10] == ' ' && Regex("([01]\\d|2[0-3]):[0-5]\\d").matches(s.substring(11))
    }

    // ---- Helpers -------------------------------------------------------------

    private fun period(from: String, to: String) = JSONObject().put("from", from).put("to", to)

    /** The from/to dates asked for, today where not given; null when they are not valid. */
    private fun range(session: IHTTPSession): Pair<String, String>? {
        val today = format().format(Date())
        val from = session.parameters["from"]?.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: today
        val to = session.parameters["to"]?.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: today
        if (!isDate(from) || !isDate(to) || from > to) return null
        return from to to
    }

    private fun format() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }

    private fun isDate(s: String): Boolean =
        s.length == 10 && runCatching { format().parse(s) }.getOrNull() != null

    private fun isLockedOut(client: String): Boolean {
        val s = failures[client] ?: return false
        if (System.currentTimeMillis() - s.since > LOCKOUT_MS) { failures.remove(client); return false }
        return s.count >= MAX_FAILURES
    }

    private fun strike(client: String) {
        val now = System.currentTimeMillis()
        failures.compute(client) { _, s ->
            if (s == null || now - s.since > LOCKOUT_MS) Strikes(1, now) else s.apply { count++ }
        }
    }

    private fun json(body: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body.toString())

    private fun error(status: Response.IStatus, message: String): Response =
        newFixedLengthResponse(
            status, "application/json; charset=utf-8",
            JSONObject().put("error", message).toString()
        )

    companion object {
        private const val TAG = "ReportServer"
        /** Bumped when a route or a field changes, so the phone app can tell. */
        const val API_VERSION = 2
        private const val MAX_FAILURES = 10
        private const val LOCKOUT_MS = 60_000L
    }
}
