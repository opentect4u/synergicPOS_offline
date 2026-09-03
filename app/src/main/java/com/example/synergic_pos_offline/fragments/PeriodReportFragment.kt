package com.example.synergic_pos_offline.fragments

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.CalendarGrain
import com.example.synergic_pos_offline.utils.PeriodReportPrinter
import com.example.synergic_pos_offline.utils.PeriodReportRenderer
import com.example.synergic_pos_offline.utils.ReportExport
import com.example.synergic_pos_offline.utils.ReportTable
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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

    // ---- What the range is made of -------------------------------------------

    /**
     * Whether the From / To fields ask for a date, a month or a year.
     *
     * Most reports run over dates and leave this alone. The calendar reports do not:
     * a month-wise report asked for with two calendars would make an operator pick a
     * day it then ignores, and would quietly turn "August" into "the 11th of August"
     * for anyone who read the field back.
     *
     * The field holds the stored form ([CalendarGrain.now]), so what is on screen is
     * exactly what is handed to [load] - and, since those forms sort in calendar
     * order as text, the From-after-To check stays one string comparison.
     */
    protected open val grain: CalendarGrain = CalendarGrain.DAY

    /** What the two fields are labelled, which follows [grain]. */
    private val rangeHints: Pair<String, String>
        get() = when (grain) {
            CalendarGrain.MINUTE -> "From date & time" to "To date & time"
            CalendarGrain.DAY -> "From date" to "To date"
            CalendarGrain.MONTH -> "From month" to "To month"
            CalendarGrain.YEAR -> "From year" to "To year"
        }

    /** What the range is called in a message - "dates", "months", "years". */
    private val rangeNoun: String
        get() = when (grain) {
            CalendarGrain.MINUTE -> "times"
            CalendarGrain.DAY -> "dates"
            CalendarGrain.MONTH -> "months"
            CalendarGrain.YEAR -> "years"
        }

    // ---- The optional filter -------------------------------------------------

    /**
     * The label on the dropdown above Generate, or null for a report that narrows by
     * nothing but its dates - which is most of them, and why the control is hidden
     * rather than merely empty.
     */
    protected open val filterHint: String? = null

    /** What that dropdown offers. The first entry is what the screen opens on. */
    protected open val filterOptions: List<String> = emptyList()

    /**
     * Whether the operator can type into the dropdown to narrow it.
     *
     * Off, it is a picker: a handful of fixed choices, and typing at them would only
     * invite entries that are not on the list. On, it is a search - for a list long
     * enough that scrolling it is worse than naming what you are after. The adapter
     * matches on any word of an entry, so a row reading "1  rahul01  RAHUL" is found
     * by the code, the login or the name alike.
     */
    protected open val filterSearchable: Boolean = false

    /**
     * What the dropdown currently reads - blank on a report that has no filter.
     *
     * Read inside [load], where a subclass turns it into whatever its query needs.
     * Held here rather than passed to [load] so a report that ignores the filter is
     * not made to declare a parameter it has no use for.
     */
    protected var filterChoice: String = ""
        private set

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
    private lateinit var btnPdf: MaterialButton
    private lateinit var btnExcel: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_period_report, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view

        etFrom = view.findViewById(R.id.etPeriodFrom)
        etTo = view.findViewById(R.id.etPeriodTo)
        btnPrint = view.findViewById(R.id.btnPeriodPrint)
        btnPdf = view.findViewById(R.id.btnPeriodPdf)
        btnExcel = view.findViewById(R.id.btnPeriodExcel)

        val (fromHint, toHint) = rangeHints
        view.findViewById<TextInputLayout>(R.id.tilPeriodFrom).hint = fromHint
        view.findViewById<TextInputLayout>(R.id.tilPeriodTo).hint = toHint

        // Opens on the current day / month / year, the range asked for far more often
        // than any other, so Generate works on the first tap rather than after two
        // pickers. A range of minutes opens at the start of today - see [openingFrom].
        etFrom.setText(grain.openingFrom())
        etTo.setText(grain.now())

        etFrom.setOnClickListener { pickPeriod(etFrom) }
        etTo.setOnClickListener { pickPeriod(etTo) }

        setUpFilter(view)

        view.findViewById<MaterialButton>(R.id.btnPeriodGenerate).setOnClickListener { generate() }
        btnPrint.setOnClickListener {
            report?.let { r ->
                PeriodReportPrinter.print(requireContext(), printContent(r), rowNoun) {
                    if (isAdded) toast(it)
                }
            }
        }
        // The same figures the screen is showing, as a file. Built from what was
        // generated, not read again - see [sheetOf].
        btnPdf.setOnClickListener { download(asPdf = true) }
        btnExcel.setOnClickListener { download(asPdf = false) }

        ThemeManager.applyTheme(view)
        // ThemeManager fills every MaterialButton; Print is the secondary action here
        // and keeps its outlined look.
        val accent = ThemeManager.getThemeColor(requireContext())
        listOf(btnPrint, btnPdf, btnExcel).forEach { b ->
            b.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            b.setTextColor(accent)
            b.strokeColor = ColorStateList.valueOf(accent)
        }
        // The file icons keep their own colours - red for PDF, green for the
        // spreadsheet - which is what makes them identifiable at a glance. Re-set
        // after the theme pass, which tints every button icon with the accent.
        btnPdf.iconTint = null
        btnExcel.iconTint = null
    }

    /**
     * Shows the dropdown and seeds it, on a report that declares one.
     *
     * The choice is only read on Generate, never live: changing it does not silently
     * rewrite a report already on the screen into one the operator did not ask for,
     * and the figures being printed stay the figures that were generated.
     */
    private fun setUpFilter(view: View) {
        val til = view.findViewById<TextInputLayout>(R.id.tilPeriodFilter)
        val hint = filterHint
        if (hint == null || filterOptions.isEmpty()) {
            til.visibility = View.GONE
            return
        }
        til.visibility = View.VISIBLE
        til.hint = hint

        filterChoice = filterOptions.first()
        val dropdown = view.findViewById<MaterialAutoCompleteTextView>(R.id.actPeriodFilter)
        dropdown.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, filterOptions)
        )
        dropdown.setText(filterChoice, false)

        if (filterSearchable) {
            dropdown.isFocusable = true
            dropdown.isFocusableInTouchMode = true
            dropdown.inputType = android.text.InputType.TYPE_CLASS_TEXT
            // One character, so the list narrows from the first keystroke rather than
            // after three - these lists are short, and waiting is worse than scrolling.
            dropdown.threshold = 1
            // A tap re-opens the whole list rather than leaving the operator to clear
            // the box first: they are usually switching to another operator, not
            // correcting a typo in this one.
            dropdown.setOnClickListener { dropdown.showDropDown() }
        }

        dropdown.setOnItemClickListener { _, _, position, _ ->
            // The adapter's own list once it has been filtered, not the full one -
            // position is an index into what is on screen.
            filterChoice = (dropdown.adapter.getItem(position) as? String) ?: filterChoice
        }
    }

    // ---- Generating ----------------------------------------------------------

    private fun generate() {
        val from = etFrom.text?.toString()?.trim().orEmpty()
        val to = etTo.text?.toString()?.trim().orEmpty()
        if (from.isEmpty() || to.isEmpty()) {
            toast("Pick both $rangeNoun")
            return
        }
        // yyyy / yyyy-MM / yyyy-MM-dd all sort in calendar order as text, so whichever
        // grain this report runs at, this is the whole check.
        if (from > to) {
            toast("The From value is after the To value")
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
        setOutputsEnabled(true)

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
        setOutputsEnabled(false)
    }

    /** Print and the two downloads live and die together: all three need a report. */
    private fun setOutputsEnabled(enabled: Boolean) {
        listOf(btnPrint, btnPdf, btnExcel).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.45f
        }
    }

    /**
     * The generated report as a file in Downloads.
     *
     * [sheetOf] is built from the columns and rows already on the screen, so what is
     * downloaded is what was generated - the same rule Print follows, for the same
     * reason: a sale landing between the two would otherwise put a figure in the file
     * that nobody ever saw.
     */
    private fun download(asPdf: Boolean) {
        val r = report ?: return
        val sheet = sheetOf(r)
        val saved = runCatching {
            if (asPdf) ReportExport.toPdf(requireContext(), sheet)
            else ReportExport.toExcel(requireContext(), sheet)
        }
        toast(
            saved.fold(
                onSuccess = { "Saved to $it" },
                onFailure = { "Could not save the ${if (asPdf) "PDF" else "spreadsheet"}" }
            )
        )
    }

    /** What the downloads are made of: this screen, as data. */
    private fun sheetOf(r: T) = ReportExport.Sheet(
        title = screenTitle,
        subtitle = headline(r),
        columns = columns.map { it.label },
        alignEnd = columns.map { it.alignEnd },
        rows = rows,
        summary = summaryOf(r) + listOf(totalOf(r))
    )

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

    /** Opens whichever picker [grain] calls for, seeded with what [field] holds. */
    private fun pickPeriod(field: TextInputEditText) = when (grain) {
        CalendarGrain.MINUTE -> pickMoment(field)
        CalendarGrain.DAY -> pickDate(field)
        CalendarGrain.MONTH -> pickMonth(field)
        CalendarGrain.YEAR -> pickYear(field)
    }

    /**
     * A calendar, then a clock. Writes back "yyyy-MM-dd HH:mm".
     *
     * Two dialogs in sequence rather than one control: Android has no combined picker,
     * and a date typed into a text field is a date typed wrongly.
     */
    private fun pickMoment(field: TextInputEditText) {
        val calendar = Calendar.getInstance()
        field.text?.toString()?.takeIf { it.isNotBlank() }?.let { current ->
            runCatching {
                val date = current.take(10).split("-")
                val time = current.drop(11).split(":")
                calendar.set(date[0].toInt(), date[1].toInt() - 1, date[2].toInt())
                calendar.set(Calendar.HOUR_OF_DAY, time[0].toInt())
                calendar.set(Calendar.MINUTE, time[1].toInt())
            }
        }
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                TimePickerDialog(
                    requireContext(),
                    { _, hour, minute ->
                        field.setText(
                            String.format(
                                Locale.US, "%04d-%02d-%02d %02d:%02d",
                                year, month + 1, day, hour, minute
                            )
                        )
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    // 24-hour, because the field holds 24-hour and a picker that
                    // disagreed with the box it fills would be read wrong at a glance.
                    true
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

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

    /**
     * A month and a year, side by side. Writes back "yyyy-MM".
     *
     * Two wheels rather than a calendar with its day ignored: a picker that offers a
     * choice the report will throw away invites the operator to believe it mattered.
     */
    private fun pickMonth(field: TextInputEditText) {
        val parts = (field.text?.toString().orEmpty() + "--").split("-")
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)

        val months = NumberPicker(requireContext()).apply {
            minValue = 1
            maxValue = 12
            displayedValues = MONTH_NAMES
            value = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 12)
                ?: (Calendar.getInstance().get(Calendar.MONTH) + 1)
            wrapSelectorWheel = false
        }
        val years = yearPicker(parts.getOrNull(0)?.toIntOrNull() ?: thisYear)

        showPicker("Pick a month", listOf(months, years)) {
            field.setText(String.format(Locale.US, "%04d-%02d", years.value, months.value))
        }
    }

    /** A year on its own. Writes back "yyyy". */
    private fun pickYear(field: TextInputEditText) {
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)
        val years = yearPicker(field.text?.toString()?.trim()?.toIntOrNull() ?: thisYear)
        showPicker("Pick a year", listOf(years)) {
            field.setText(years.value.toString())
        }
    }

    /**
     * A wheel of years ending at this one, opened on [selected].
     *
     * It does not run past the current year: a till cannot have billed in a year that
     * has not happened, and a report of it could only ever be empty.
     */
    private fun yearPicker(selected: Int): NumberPicker {
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)
        return NumberPicker(requireContext()).apply {
            minValue = thisYear - YEARS_OFFERED
            maxValue = thisYear
            value = selected.coerceIn(minValue, maxValue)
            wrapSelectorWheel = false
        }
    }

    /** The wheels in a row, under [title], with the pick applied on OK. */
    private fun showPicker(title: String, wheels: List<NumberPicker>, onPick: () -> Unit) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), 0)
            wheels.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(row)
            .setPositiveButton("OK") { _, _ -> onPick() }
            .setNegativeButton("Cancel", null)
            .show()
    }

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

        /** How far back the year wheels reach - long enough to outlast any till. */
        const val YEARS_OFFERED = 20

        /** The month wheel's labels, in the order [NumberPicker] wants them. */
        val MONTH_NAMES = arrayOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )
    }
}
