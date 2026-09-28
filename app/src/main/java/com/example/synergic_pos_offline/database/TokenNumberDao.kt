package com.example.synergic_pos_offline.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hands out take-away token numbers under Bill Settings ▸ Token Numbering.
 *
 * Built the same way bill numbering is (see [BillDao.nextBillSequence]) and for the
 * same reasons: the next token continues from the HIGHEST one already used in the
 * current reset period, so a cancelled or deleted order cannot hand its number to a
 * later one, and only an empty period falls back to a starting point.
 *
 * Where it differs from a bill is the period it counts in. A bill number runs on by
 * default because it is an accounting record; a token resets daily because it is a
 * label shouted across a counter and is finished with by the next morning.
 *
 * WHAT COUNTS AS USED is both the orders still open and the ones already settled.
 * Settled ones matter: a counter that took token 1, served it and started the next
 * order expects 2, not 1 again. The open orders live in td_running_order and the
 * settled ones in td_bills, so both are asked.
 */
class TokenNumberDao(context: Context) {

    private val helper = DatabaseHelper.getInstance(context)
    private val settingsDao = BillSettingsDao(context)

    /**
     * The code the next take-away order should carry, e.g. "TA-7" - or "TA-TK7" once a
     * prefix is configured.
     *
     * The "TA-" marker is NOT the configurable prefix and is never dropped. It is how
     * every screen in the app knows a running order is a counter token rather than a
     * table: the order list, the bill's table line, the printer's own label. The
     * shop's prefix sits after it, and what is shown to anyone - "Token #TK7" - is
     * this code with the marker stripped.
     */
    fun nextCode(excludeOrderId: Long? = null): String {
        val s = settingsDao.load()
        val prefix = if (s.tokenNoCharEnabled) s.tokenNoCharPrefix.take(3) else ""
        return "$MARKER$prefix${nextSequence(s, excludeOrderId)}"
    }

    /**
     * The counter the next token should carry within the current reset period.
     *
     * [excludeOrderId] leaves one running order out of the count - the empty token
     * being renumbered. Without it an order would be compared against itself and the
     * number would climb by one every time the counter looked at it.
     *
     * START NO. IS A FLOOR, not a fallback. It applies in every reset mode, on every
     * token, rather than only when the period happens to be empty - which is how bill
     * numbering treats it and which made this control do nothing at all in the mode
     * tokens default to. Set it to 100 and the counter issues 101 next, today and
     * every day after; leave it at 0, the default, and a fresh period starts at 1.
     */
    fun nextSequence(
        s: BillSettingsDao.BillSettings = settingsDao.load(),
        excludeOrderId: Long? = null
    ): Int {
        val db = helper.readableDatabase
        val now = today()
        val used = listOfNotNull(
            maxTokenIn(
                db, DatabaseHelper.Tables.TD_RUNNING_ORDER, "table_code", "created_at", s, now,
                excludeId = excludeOrderId
            ),
            maxBillToken(db, s, now)
        ).maxOrNull()
        return maxOf(used ?: 0, s.startTokenNo) + 1
    }

    /** The date prefix of the current reset period, or null when tokens never reset. */
    private fun periodPrefix(s: BillSettingsDao.BillSettings, nowDate: String): String? =
        when (s.tokenResetMode) {
            BillSettingsDao.ResetMode.DAILY -> nowDate
            BillSettingsDao.ResetMode.MONTHLY -> nowDate.take(7)
            BillSettingsDao.ResetMode.YEARLY -> nowDate.take(4)
            BillSettingsDao.ResetMode.CONTINUE -> null
        }

    /**
     * The highest token number in [table] within the reset period.
     *
     * WORKED OUT IN SQL, NOT BY READING EVERY CODE BACK. It used to fetch every
     * token in the period and pick the number off each in Kotlin - fine for "open
     * orders plus today's bills", which is what it was written for. But under Monthly
     * or Continue reset the period is every bill this month or ever: at a lakh and a
     * half bills that was a lakh and a half strings read on the main thread on every
     * Take Away or QSR tap, and the mode switch sat frozen for it.
     *
     * The trailing digits are cut off in SQL: `rtrim(code, digits)` is the code with
     * its number removed, so what follows it is the number - whatever marker and
     * prefix went before, which is exactly what [sequenceOf] reads. MAX() then runs
     * inside SQLite, over the (bill_date, table_number) index for bills.
     */
    private fun maxTokenIn(
        db: SQLiteDatabase, table: String, codeCol: String, dateCol: String,
        s: BillSettingsDao.BillSettings, nowDate: String, excludeId: Long? = null,
        /** Only rows after this receipt number - see [maxBillToken]. */
        afterReceipt: Long? = null
    ): Int? {
        // Dates are compared by prefix so the one expression works for a plain date
        // (bill_date) and a date-time (created_at) alike.
        //
        // As a range on the text ("2026-09" <= "2026-09-14 10:02:11" < "2026-09~")
        // rather than substr(): the same rows, but td_bills can answer a range from
        // its bill_date index instead of reading every bill ever written.
        val prefix = periodPrefix(s, nowDate)
        val code = "trim($codeCol)"
        val where = StringBuilder("$codeCol LIKE '$MARKER%' AND $code GLOB '*[0-9]'")
        val args = mutableListOf<String>()
        // Looking only past [afterReceipt], the unary + keeps SQLite off the date index
        // so it walks the few newest receipt numbers instead of the whole period.
        val dateExpr = if (afterReceipt != null) "+$dateCol" else dateCol
        prefix?.let {
            where.append(" AND $dateExpr >= ? AND $dateExpr < ?")
            args.add(it); args.add("$it~")
        }
        excludeId?.let { where.append(" AND id <> ?"); args.add(it.toString()) }
        afterReceipt?.let { where.append(" AND receipt_no > ?"); args.add(it.toString()) }
        val number = "CAST(substr($code, length(rtrim($code, '0123456789')) + 1) AS INTEGER)"
        return runCatching {
            db.rawQuery(
                "SELECT MAX($number) FROM $table WHERE $where",
                args.toTypedArray().takeIf { it.isNotEmpty() }
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }
        }.getOrNull()
    }

    /**
     * The highest token among the settled bills in the reset period, remembered
     * between calls.
     *
     * Bills are only ever added at the end - a new receipt number - so once the
     * period's highest token is known, the next ask only has to look at bills written
     * since. Switching Take Away ↔ QSR ↔ Dine In over and over, which is all day at a
     * counter, then costs one "any new bills?" look instead of a pass over the month.
     *
     * Read afresh whenever that shortcut cannot be trusted: a new period or a changed
     * reset setting, and a bill book whose last receipt number went BACKWARDS - a
     * restore, or bills erased - since then what was remembered may count bills that
     * are gone. A bill deleted from the middle leaves the remembered highest in place,
     * which is the rule anyway: a deleted order's token is never handed out again.
     */
    private fun maxBillToken(db: SQLiteDatabase, s: BillSettingsDao.BillSettings, nowDate: String): Int? {
        val key = "${s.tokenResetMode}:${periodPrefix(s, nowDate)}"
        val top = runCatching {
            db.rawQuery("SELECT COALESCE(MAX(receipt_no), 0) FROM ${DatabaseHelper.Tables.TD_BILLS}", null)
                .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }.getOrDefault(-1L)
        val bills = DatabaseHelper.Tables.TD_BILLS
        synchronized(TokenNumberDao) {
            val held = billCache
            if (held != null && held.key == key && top >= 0 && held.watermark <= top) {
                if (held.watermark == top) return held.max
                val newer = maxTokenIn(db, bills, "table_number", "bill_date", s, nowDate, afterReceipt = held.watermark)
                val max = listOfNotNull(held.max, newer).maxOrNull()
                billCache = BillCache(key, top, max)
                return max
            }
            val max = maxTokenIn(db, bills, "table_number", "bill_date", s, nowDate)
            billCache = if (top >= 0) BillCache(key, top, max) else null
            return max
        }
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    companion object {
        /** Marks a running order as a counter token. Not the shop's prefix - see [nextCode]. */
        const val MARKER = "TA-"

        /** What [maxBillToken] last found, and how far through the bills it looked. */
        private data class BillCache(val key: String, val watermark: Long, val max: Int?)

        @Volatile private var billCache: BillCache? = null

        /** The digits on the end of a token code, whatever marker and prefix precede them. */
        private val TRAILING_DIGITS = Regex("(\\d+)$")

        /** The number in a token code ("TA-TK7" → 7), or null if it carries none. */
        fun sequenceOf(code: String?): Int? {
            val c = code?.trim().orEmpty()
            if (!c.startsWith(MARKER, ignoreCase = true)) return null
            return TRAILING_DIGITS.find(c)?.groupValues?.get(1)?.toIntOrNull()
        }
    }
}
