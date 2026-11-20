package com.example.synergic_pos_offline.lanreport

import android.content.Context
import com.example.synergic_pos_offline.utils.CalendarGrain
import com.example.synergic_pos_offline.utils.ReportExport

/**
 * A report screen that the Mobile Reports server can also run without its screen.
 *
 * The phone is shown the same report the till shows - same query, same columns, same
 * totals - because it is produced by the same code: the screen's own load, columns,
 * rows and summary, run off-screen with the server's context. Nothing is re-written
 * for the phone, so the two cannot drift apart.
 *
 * READ ONLY, like everything the server does.
 */
interface HeadlessReport {

    /** What the phone has to ask for before it can run the report. */
    data class Spec(
        /** Whether the range is in days, months, years or minutes. */
        val grain: CalendarGrain = CalendarGrain.DAY,
        /** The label of the dropdown narrowing the report, or null for none. */
        val filterHint: String? = null,
        val filterOptions: List<String> = emptyList(),
        /** A long list (items, customers) the phone should let the user search. */
        val filterSearchable: Boolean = false,
        /** The label of the dropdown changing what a row is, or null for none. */
        val modeHint: String? = null,
        val modeOptions: List<String> = emptyList()
    )

    /** The report as run, or why it came out empty. */
    data class Result(
        val sheet: ReportExport.Sheet,
        /** The filter and mode the report actually ran with - blank where none. */
        val filterValue: String = "",
        val modeValue: String = "",
        /** Set when there was nothing to report: what the till's screen would say. */
        val emptyTitle: String? = null,
        val emptyHint: String? = null
    ) {
        companion object {
            fun empty(title: String, emptyTitle: String, emptyHint: String, filter: String = "", mode: String = "") =
                Result(
                    ReportExport.Sheet(title, "", emptyList(), emptyList(), emptyList()),
                    filter, mode, emptyTitle, emptyHint
                )
        }
    }

    /** What this report needs asked - see [Spec]. May read the database (for its lists). */
    fun headlessSpec(context: Context): Spec

    /**
     * Runs the report. [from] and [to] are already in [Spec.grain]'s stored form;
     * [filter] and [mode] are one of the spec's options, or null for its default.
     */
    fun runHeadless(context: Context, from: String, to: String, filter: String?, mode: String?): Result
}
