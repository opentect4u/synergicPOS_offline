package com.example.synergic_pos_offline.utils

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * How wide one step of a report's range is - a minute, a day, a month or a year.
 *
 * Bills are stamped `yyyy-MM-dd HH:mm:ss`, which is why this is a *length* rather
 * than a date format: the first sixteen characters of that are the minute, the first
 * ten the day, the first seven the month and the first four the year. So one
 * comparison serves every report - `substr` to [storedLength] - and, because those
 * prefixes sort in calendar order as text, so does one range check. No date
 * arithmetic anywhere, and nothing to get wrong about month lengths or leap years.
 *
 * The stored form is also what the From / To fields hold, so what is typed into a
 * query is exactly what is on the screen.
 */
enum class CalendarGrain(val storedLength: Int) {

    MINUTE(16),
    DAY(10),
    MONTH(7),
    YEAR(4);

    /** Now, truncated to this grain: "2026-08-12 14:03", "2026-08-12", "2026-08", "2026". */
    fun now(): String = SimpleDateFormat(STORED, Locale.US)
        .format(Calendar.getInstance().time)
        .take(storedLength)

    /**
     * Where a range of this grain sensibly opens.
     *
     * The same as [now] for the grains that name a whole period - today, this month,
     * this year all contain the present moment. A range of *minutes* does not: opened
     * at the present minute it would be empty until the next sale, so it opens at the
     * start of today instead and runs to now.
     */
    fun openingFrom(): String =
        if (this == MINUTE) now().take(11) + "00:00" else now()

    /**
     * A stored value as it reads on a report: "12-08-2026 14:03", "12-08-2026",
     * "Aug 2026", "2026".
     *
     * A value that cannot be parsed is handed back untouched rather than blanked - a
     * row of a report should show whatever the books actually hold, even where that
     * is something this did not expect.
     */
    fun label(stored: String): String = runCatching {
        when (this) {
            MINUTE -> reformat(stored, "yyyy-MM-dd HH:mm", "dd-MM-yyyy HH:mm")
            DAY -> reformat(stored, "yyyy-MM-dd", "dd-MM-yyyy")
            MONTH -> reformat(stored, "yyyy-MM", "MMM yyyy")
            YEAR -> stored.take(4)
        }
    }.getOrDefault(stored)

    private fun reformat(value: String, from: String, to: String): String =
        SimpleDateFormat(to, Locale.US)
            .format(SimpleDateFormat(from, Locale.US).parse(value.take(storedLength))!!)

    private companion object {
        /** The longest form, which every grain is a prefix of. */
        const val STORED = "yyyy-MM-dd HH:mm"
    }
}
