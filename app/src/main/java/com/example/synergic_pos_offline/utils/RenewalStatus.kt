package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.DatabaseHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * How long this installation has left to run, read from the registration's
 * `registration_upto`.
 *
 * The till is licensed for a period, and the shop needs telling **before** that period
 * is out rather than on the morning it stops - so the dashboard warns from a month
 * ahead, which is enough time to arrange a renewal without it becoming background
 * noise the whole year.
 */
object RenewalStatus {

    /**
     * How far ahead the warning starts, in days.
     *
     * A month, taken as 30 days: the shop is told at least a month early whatever the
     * length of the month it lands in, which is the promise that matters.
     */
    const val WARN_DAYS = 30

    /**
     * Where the renewal stands.
     *
     * [daysLeft] is counted in whole days, so a renewal later today reads as "today"
     * and not as expired because the stored time was this morning. Negative once the
     * date has passed.
     */
    data class Status(val date: Date, val prettyDate: String, val daysLeft: Int) {
        val expired: Boolean get() = daysLeft < 0
        /** Inside the warning window - the dashboard shows its alert from here. */
        val nearingRenewal: Boolean get() = daysLeft in 0..WARN_DAYS
        /** Worth putting in front of the operator at all: due soon, or already past. */
        val needsAttention: Boolean get() = expired || nearingRenewal
    }

    /** The registered store's renewal date, or null when none is stored or readable. */
    fun of(context: Context): Status? = runCatching {
        val stored = DatabaseHelper.getInstance(context).readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("registration_upto"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        } ?: return null

        val date = parse(stored) ?: return null
        Status(
            date = date,
            prettyDate = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(date),
            daysLeft = ((startOfDay(date) - startOfDay(Date())) / 86_400_000L).toInt()
        )
    }.getOrNull()

    /** How the alert puts it - the shortest true sentence for where the date sits. */
    fun headline(status: Status): String = when {
        status.daysLeft < 0 -> "Registration expired"
        status.daysLeft == 0 -> "Registration expires today"
        status.daysLeft == 1 -> "Registration expires tomorrow"
        else -> "Registration expires in ${status.daysLeft} days"
    }

    /** The line under it: the date itself, and what to do about it. */
    fun detail(status: Status): String = when {
        status.daysLeft < 0 ->
            "Valid until ${status.prettyDate} — ${-status.daysLeft} day${plural(-status.daysLeft)} ago. " +
                "Contact support to renew."
        else ->
            "Valid until ${status.prettyDate}. Contact support to renew before it runs out."
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    private fun parse(stored: String): Date? =
        listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd").firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.US).parse(stored.trim()) }.getOrNull()
        }

    private fun startOfDay(date: Date): Long = Calendar.getInstance().run {
        time = date
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        timeInMillis
    }
}
