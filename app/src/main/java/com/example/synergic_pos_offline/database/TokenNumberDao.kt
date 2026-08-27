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
            maxTokenIn(db, DatabaseHelper.Tables.TD_BILLS, "table_number", "bill_date", s, now)
        ).maxOrNull()
        return maxOf(used ?: 0, s.startTokenNo) + 1
    }

    /**
     * The highest token number in [table] within the reset period.
     *
     * The number is picked out in Kotlin rather than SQL because a token code is text
     * with a marker and an optional prefix in front of it - "TA-TK7" - and SQLite has
     * no way to pull the digits off the end of that. The rows are few: the ones open
     * now plus the ones billed today.
     */
    private fun maxTokenIn(
        db: SQLiteDatabase, table: String, codeCol: String, dateCol: String,
        s: BillSettingsDao.BillSettings, nowDate: String, excludeId: Long? = null
    ): Int? {
        // Dates are compared by prefix so the one expression works for a plain date
        // (bill_date) and a date-time (created_at) alike.
        val period = when (s.tokenResetMode) {
            BillSettingsDao.ResetMode.DAILY -> "substr($dateCol, 1, 10) = ?" to nowDate
            BillSettingsDao.ResetMode.MONTHLY -> "substr($dateCol, 1, 7) = ?" to nowDate.take(7)
            BillSettingsDao.ResetMode.YEARLY -> "substr($dateCol, 1, 4) = ?" to nowDate.take(4)
            BillSettingsDao.ResetMode.CONTINUE -> null
        }
        val where = StringBuilder("$codeCol LIKE '$MARKER%'")
        val args = mutableListOf<String>()
        period?.let { where.append(" AND ").append(it.first); args.add(it.second) }
        excludeId?.let { where.append(" AND id <> ?"); args.add(it.toString()) }
        return runCatching {
            db.rawQuery(
                "SELECT $codeCol FROM $table WHERE $where",
                args.toTypedArray().takeIf { it.isNotEmpty() }
            ).use { c ->
                var max: Int? = null
                while (c.moveToNext()) {
                    sequenceOf(c.getString(0))?.let { n -> if (max == null || n > max!!) max = n }
                }
                max
            }
        }.getOrNull()
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    companion object {
        /** Marks a running order as a counter token. Not the shop's prefix - see [nextCode]. */
        const val MARKER = "TA-"

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
