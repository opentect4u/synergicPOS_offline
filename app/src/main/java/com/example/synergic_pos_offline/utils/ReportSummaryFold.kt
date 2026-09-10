package com.example.synergic_pos_offline.utils

import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import com.example.synergic_pos_offline.R

/**
 * Folds a report's on-screen summary card down to its Total line, unfolding it
 * to the full breakdown on a tap - the same fold [PeriodReportFragment] wires
 * for the reports built on that shared screen, for the handful that draw their
 * own instead: [fragment_bill_wise_report.xml]'s own `llReportSummaryHeader` /
 * `tvReportSummaryTotal` / `btnToggleReportSummary` / `llReportSummary`.
 *
 * A report is read for its Total far more often than for the lines that add up
 * to it, so that is what stays on screen without a tap, and the breakdown is a
 * tap away rather than pushing the table down every time a report is generated.
 */
object ReportSummaryFold {

    /** Wires the header row and its chevron to fold/unfold [root]'s summary.
     *  Call once, from `onViewCreated` - before any report has set a total. */
    fun wire(root: View) {
        val toggle = View.OnClickListener { toggle(root) }
        root.findViewById<View>(R.id.llReportSummaryHeader).setOnClickListener(toggle)
        root.findViewById<ImageButton>(R.id.btnToggleReportSummary).setOnClickListener(toggle)
        setExpanded(root, expanded = false, animate = false)
    }

    /** Sets the header's own total text - call every time the summary is rebuilt,
     *  alongside [collapse]. */
    fun setTotal(root: View, text: String) {
        root.findViewById<TextView>(R.id.tvReportSummaryTotal).text = text
    }

    /** Folds the summary shut without animating - every report freshly
     *  generated starts folded, whatever the last one was left open to. */
    fun collapse(root: View) = setExpanded(root, expanded = false, animate = false)

    private fun toggle(root: View) {
        val detail = root.findViewById<View>(R.id.llReportSummary)
        setExpanded(root, detail.visibility != View.VISIBLE)
    }

    private fun setExpanded(root: View, expanded: Boolean, animate: Boolean = true) {
        val detail = root.findViewById<View>(R.id.llReportSummary)
        if (animate) {
            TransitionManager.beginDelayedTransition(
                detail.parent as ViewGroup,
                AutoTransition().apply { duration = 160 }
            )
        }
        detail.visibility = if (expanded) View.VISIBLE else View.GONE
        root.findViewById<ImageButton>(R.id.btnToggleReportSummary)
            .setImageResource(if (expanded) R.drawable.ic_expand_more else R.drawable.ic_expand_less)
        // The header's own total shows only while the detail is folded shut - open,
        // the same figure is already the last line of the detail below it, and
        // showing it twice would read as two totals to reconcile rather than one.
        root.findViewById<TextView>(R.id.tvReportSummaryTotal).visibility =
            if (expanded) View.GONE else View.VISIBLE
    }
}
