package com.example.synergic_pos_offline.fragments

import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.PeriodReportPrinter
import com.example.synergic_pos_offline.utils.PeriodReportRenderer
import com.example.synergic_pos_offline.utils.ReportTable
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The shape every date-range report on this till takes: two dates and a Generate
 * button, a table that scrolls sideways, a block of totals under it, and a Print
 * that sends exactly what is on the screen.
 *
 * That shape was written out once per report before this existed - the same date
 * pickers, the same column stretching, the same zebra striping, the same
 * "generate once, print what was generated" rule copied into each. A rule copied
 * four times is four rules, and they drift.
 *
 * A subclass supplies what its report *is*: how to read it ([load]), what its
 * columns are called ([columnsFor]), its rows as text ([rowsOf]), how it totals
 * ([summaryOf] and [totalOf]), and what it prints ([printContent]). Everything a
 * report does rather than says is here.
 *
 * [T] is the subclass's own report type - typically its DAO's `Report`. It is held
 * between Generate and Print, so an operator prints what they were looking at: a
 * sale landing between the two would otherwise put a figure on the paper that was
 * never on the screen.
 */
abstract class PeriodReportFragment<T : Any> : Fragment(), TitledScreen {

    /** One column of the on-screen table: what it is called and how wide it sits. */
    protected data class Column(val label: String, val widthDp: Int, val alignEnd: Boolean)

    // ---- What a report has to say for itself ---------------------------------

    /** Reads the period. Called on Generate, once, with both dates as `yyyy-MM-dd`. */
    protected abstract fun load(fromDate: String, toDate: String): T

    /** Whether [report] has anything in it - an empty one is shown, not tabulated. */
    protected abstract fun isEmpty(report: T): Boolean

    /** The line above the table: the period and what it holds. */
    protected abstract fun headline(report: T): String

    /**
     * The columns [report] needs. Read per report rather than declared once, so a
     * column that only applies to some periods (VAT, IGST) can be left off the rest
     * rather than printing as a stripe of zeroes.
     */
    protected abstract fun columnsFor(report: T): List<Column>

    /** Every row, already formatted, in the order [columnsFor] lists the columns. */
    protected abstract fun rowsOf(report: T): List<List<String>>

    /** The totals under the table, in the order they add up. */
    protected abstract fun summaryOf(report: T): List<Pair<String, String>>

    /** The one figure the report is read for, set apart under [summaryOf]. */
    protected abstract fun totalOf(report: T): Pair<String, String>

    /** [report] as receipt paper - see [PeriodReportRenderer.Content]. */
    protected abstract fun printContent(report: T): PeriodReportRenderer.Content

    /** Title and hint shown when the period turned up nothing. */
    protected abstract fun emptyMessage(
        report: T,
        fromDate: String,
        toDate: String
    ): Pair<String, String>

    /** What a row is called, for the message when there are too many to print. */
    protected open val rowNoun: String = "rows"

    // ---- State ---------------------------------------------------------------

    /** The generated report, held so Print sends exactly what was generated. */
    private var report: T? = null

    /** [rowsOf] the held report, laid out once rather than per bind. */
    private var rows: List<List<String>> = emptyList()

    private lateinit var root: View

    /**
     * What each column is actually drawn at - the declared minimums until the card
     * has been laid out and [ReportTable] can share out whatever room is spare.
     */
    private var columnPx: IntArray = IntArray(0)

    /** The columns actually drawn, as [columnsFor] settled them for this report. */
    private var columns: List<Column> = emptyList()

    private lateinit var etFrom: TextInputEditText
    private lateinit var etTo: TextInputEditText
    private lateinit var btnPrint: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_period_report, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view

        etFrom = view.findViewById(R.id.etPeriodFrom)
        etTo = view.findViewById(R.id.etPeriodTo)
        btnPrint = view.findViewById(R.id.btnPeriodPrint)

        // Opens on today, the range asked for far more often than any other, so
        // Generate works on the first tap rather than after two calendars.
        val today = Calendar.getInstance().time
        etFrom.setText(iso(today))
        etTo.setText(iso(today))

        etFrom.setOnClickListener { pickDate(etFrom) }
        etTo.setOnClickListener { pickDate(etTo) }

        view.findViewById<MaterialButton>(R.id.btnPeriodGenerate).setOnClickListener { generate() }
        btnPrint.setOnClickListener {
            report?.let { r ->
                PeriodReportPrinter.print(requireContext(), printContent(r), rowNoun) {
                    if (isAdded) toast(it)
                }
            }
        }

        ThemeManager.applyTheme(view)
        // ThemeManager fills every MaterialButton; Print is the secondary action here
        // and keeps its outlined look.
        val accent = ThemeManager.getThemeColor(requireContext())
        btnPrint.apply {
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(accent)
            strokeColor = ColorStateList.valueOf(accent)
        }
    }

    // ---- Generating ----------------------------------------------------------

    private fun generate() {
        val from = etFrom.text?.toString()?.trim().orEmpty()
        val to = etTo.text?.toString()?.trim().orEmpty()
        if (from.isEmpty() || to.isEmpty()) {
            toast("Pick both dates")
            return
        }
        // ISO dates sort lexicographically, so this is the whole check.
        if (from > to) {
            toast("The From date is after the To date")
            return
        }

        val result = load(from, to)
        report = result.takeUnless { isEmpty(it) }

        if (isEmpty(result)) {
            val (title, hint) = emptyMessage(result, from, to)
            showEmpty(title, hint)
            return
        }
        bind(result)
    }

    private fun bind(r: T) {
        root.findViewById<View>(R.id.llPeriodEmpty).visibility = View.GONE
        root.findViewById<View>(R.id.llPeriodResult).visibility = View.VISIBLE
        btnPrint.isEnabled = true

        root.findViewById<TextView>(R.id.tvPeriodHeadline).text = headline(r)

        // The table fills the card where there is room to spare, and keeps its
        // declared widths and scrolls where there is not - see [ReportTable]. The
        // card's width is only known once it has been laid out, so the table is
        // built at its minimums first and stretched when that width arrives.
        columns = columnsFor(r)
        rows = rowsOf(r)
        columnPx = columns.map { dp(it.widthDp) }.toIntArray()
        drawTable(r)
        root.findViewById<View>(R.id.hsvPeriodTable).let { table ->
            table.post {
                if (!isAdded) return@post
                val available = table.width - dp(ROW_PADDING_DP) * 2
                val stretched = ReportTable.stretch(
                    columns.map { dp(it.widthDp) }.toIntArray(), available
                )
                if (!stretched.contentEquals(columnPx)) {
                    columnPx = stretched
                    drawTable(r)
                }
            }
        }
    }

    /** Lays the header and rows out at whatever [columnPx] currently says. */
    private fun drawTable(r: T) {
        val header = root.findViewById<LinearLayout>(R.id.llPeriodHeader)
        header.removeAllViews()
        header.addView(tableRow(columns.map { it.label }, index = -1))

        // The list is as wide as one row, not as wide as the screen: it and the
        // header scroll sideways together inside the one HorizontalScrollView, and
        // a header that sat still over rows that moved would label the wrong ones.
        // The row's own side padding counts - leave it out and the list is narrower
        // than its rows, which shifts every figure out from under its heading.
        val list = root.findViewById<RecyclerView>(R.id.rvPeriodRows)
        list.layoutParams = list.layoutParams.apply {
            width = columnPx.sum() + dp(ROW_PADDING_DP) * 2
        }
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = RowAdapter()

        val summary = root.findViewById<LinearLayout>(R.id.llPeriodSummary)
        summary.removeAllViews()
        summaryOf(r).forEach { (label, value) -> summary.addView(summaryRow(label, value)) }
        val (totalLabel, totalValue) = totalOf(r)
        summary.addView(summaryRow(totalLabel, totalValue, emphasised = true))
    }

    private fun showEmpty(title: String, hint: String) {
        root.findViewById<View>(R.id.llPeriodResult).visibility = View.GONE
        root.findViewById<View>(R.id.llPeriodEmpty).visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.tvPeriodEmptyTitle).text = title
        root.findViewById<TextView>(R.id.tvPeriodEmptyHint).text = hint
        btnPrint.isEnabled = false
    }

    // ---- Table ---------------------------------------------------------------

    /**
     * The rows of the report.
     *
     * Each row is the same cells in the same widths as the header, built by
     * [tableRow] so there is one description of the table rather than two that have
     * to be kept in step.
     */
    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {

        inner class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(tableRow(columns.map { "" }, index = 0) as LinearLayout)

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val cells = rows[position]
            columns.indices.forEach { i ->
                (holder.row.getChildAt(i) as? TextView)?.text = cells.getOrNull(i).orEmpty()
            }
            // Zebra by where the row sits now, not by where it was built: a recycled
            // row carries the shade of whichever row it used to be.
            holder.row.setBackgroundColor(
                if (position % 2 == 1) Color.parseColor("#FFFFFF") else Color.parseColor("#F7F8FA")
            )
        }

        override fun getItemCount(): Int = rows.size
    }

    /**
     * Builds one row - the header when [index] is negative, otherwise a data row -
     * from the one column list, so a heading cannot end up over the wrong figures.
     *
     * Every column is a fixed width rather than weighted: the row scrolls sideways,
     * where a weighted column has no width to divide up, and the money columns have
     * to line up down the page to be read as a column at all. The shade [index]
     * picks is only the row's starting one - a recycled row is re-shaded for the
     * position it lands on, see [RowAdapter].
     */
    private fun tableRow(values: List<String>, index: Int): View {
        val header = index < 0
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ROW_PADDING_DP), dp(10), dp(ROW_PADDING_DP), dp(10))
            setBackgroundColor(
                when {
                    header -> Color.parseColor("#ECEFF1")
                    index % 2 == 1 -> Color.parseColor("#FFFFFF")
                    else -> Color.parseColor("#F7F8FA")
                }
            )
            columns.forEachIndexed { i, column ->
                addView(TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(columnPx.getOrElse(i) { -2 }, -2)
                    text = values.getOrNull(i).orEmpty()
                    textSize = 12f
                    maxLines = 1
                    // A long bill number reads as shortened rather than as sliced
                    // through the middle of a digit.
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = if (column.alignEnd) Gravity.END else Gravity.START
                    setPadding(dp(8), 0, dp(8), 0)
                    setTypeface(Typeface.MONOSPACE, if (header) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(
                        resources.getColor(
                            if (header) R.color.text_secondary else R.color.text_main, null
                        )
                    )
                })
            }
        }
    }

    /** A "Label ................ value" line of the summary card. */
    private fun summaryRow(label: String, value: String, emphasised: Boolean = false): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                text = label
                textSize = if (emphasised) 13f else 11.5f
                setTypeface(Typeface.MONOSPACE, if (emphasised) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(resources.getColor(R.color.text_secondary, null))
            })
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2)
                text = value
                textSize = if (emphasised) 14f else 11.5f
                gravity = Gravity.END
                setTypeface(Typeface.MONOSPACE, if (emphasised) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(resources.getColor(R.color.text_main, null))
            })
        }

    // ---- Small helpers -------------------------------------------------------

    /** Opens a calendar seeded with whatever [field] holds, and writes the pick back. */
    private fun pickDate(field: TextInputEditText) {
        val calendar = Calendar.getInstance()
        field.text?.toString()?.takeIf { it.isNotBlank() }?.let { current ->
            runCatching {
                val parts = current.split("-")
                calendar.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
            }
        }
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                field.setText(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun iso(date: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

    /** "yyyy-MM-dd" as "dd-MM-yyyy", which is how a date is read on a bill here. */
    protected fun pretty(value: String): String = runCatching {
        SimpleDateFormat("dd-MM-yyyy", Locale.US)
            .format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value.take(10))!!)
    }.getOrDefault(value)

    protected fun money(value: Double): String = String.format(Locale.US, "%.2f", value)

    /** A quantity trimmed of a needless ".00" - "3" not "3.00", but "2.50" kept. */
    protected fun quantity(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.2f", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** Side padding on every row, header included - and on the list holding them. */
        const val ROW_PADDING_DP = 6
    }
}
