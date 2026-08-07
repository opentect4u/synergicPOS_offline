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
import com.example.synergic_pos_offline.database.BillWiseReportDao
import com.example.synergic_pos_offline.utils.BillWiseReportPrinter
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Bill Wise Report - every bill of a period, what each was made of, and the totals
 * of the whole period.
 *
 * The same screen in Restaurant and in Grocery. A settled restaurant order is
 * persisted as a bill through the same path a grocery sale takes, so there is one
 * set of books to report on and this reads it - see [BillWiseReportDao].
 *
 * What is on screen and what comes out of the printer are rendered from one
 * [BillWiseReportDao.Report], generated once when Generate is pressed. Printing does
 * not re-query: an operator prints what they were looking at, and a sale landing
 * between the two would otherwise put a figure on the paper that was never on the
 * screen.
 */
class BillWiseReportFragment : Fragment(), TitledScreen {

    override val screenTitle = "Bill Wise Report"

    private val dao: BillWiseReportDao by lazy { BillWiseReportDao(requireContext()) }

    /** The generated report, held so Print sends exactly what was generated. */
    private var report: BillWiseReportDao.Report? = null

    private lateinit var root: View
    private lateinit var etFrom: TextInputEditText
    private lateinit var etTo: TextInputEditText
    private lateinit var btnPrint: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_bill_wise_report, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view

        etFrom = view.findViewById(R.id.etFrom)
        etTo = view.findViewById(R.id.etTo)
        btnPrint = view.findViewById(R.id.btnPrintReport)

        // Opens on today, the range asked for far more often than any other, so
        // Generate works on the first tap rather than after two calendars.
        val today = Calendar.getInstance().time
        etFrom.setText(iso(today))
        etTo.setText(iso(today))

        etFrom.setOnClickListener { pickDate(etFrom) }
        etTo.setOnClickListener { pickDate(etTo) }

        view.findViewById<MaterialButton>(R.id.btnGenerate).setOnClickListener { generate() }
        btnPrint.setOnClickListener {
            report?.let { r -> BillWiseReportPrinter.print(requireContext(), r) { if (isAdded) toast(it) } }
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

        val result = dao.between(from, to)
        report = result.takeUnless { it.isEmpty }

        if (result.isEmpty) {
            showEmpty(
                "No bills in this period",
                "Nothing was billed between ${pretty(from)} and ${pretty(to)}."
            )
            return
        }
        bind(result)
    }

    private fun bind(r: BillWiseReportDao.Report) {
        root.findViewById<View>(R.id.llReportEmpty).visibility = View.GONE
        root.findViewById<View>(R.id.llReportResult).visibility = View.VISIBLE
        btnPrint.isEnabled = true

        root.findViewById<TextView>(R.id.tvReportPeriod).text =
            "${pretty(r.fromDate)}  to  ${pretty(r.toDate)}   •   ${r.billCount} bill(s)"

        val header = root.findViewById<LinearLayout>(R.id.llReportHeader)
        header.removeAllViews()
        header.addView(tableRow(COLUMNS.map { it.label }, index = -1))

        // The list is as wide as one row, not as wide as the screen: it and the
        // header scroll sideways together inside the one HorizontalScrollView, and
        // a header that sat still over rows that moved would label the wrong ones.
        // The row's own side padding counts - leave it out and the list is narrower
        // than its rows, which shifts every figure out from under its heading.
        val rows = root.findViewById<RecyclerView>(R.id.rvReportRows)
        rows.layoutParams = rows.layoutParams.apply {
            width = COLUMNS.sumOf { dp(it.widthDp) } + dp(ROW_PADDING_DP) * 2
        }
        rows.layoutManager = LinearLayoutManager(requireContext())
        rows.adapter = LineAdapter(r.lines)

        val summary = root.findViewById<LinearLayout>(R.id.llReportSummary)
        summary.removeAllViews()
        // Read straight off the report, in the order they add up: the parts, then
        // the adjustment, then what the parts came to.
        listOf(
            "Total Bills" to r.billCount.toString(),
            "Total MRP" to money(r.totalMrp),
            "Total CGST" to money(r.totalCgst),
            "Total SGST" to money(r.totalSgst),
            "Total IGST" to money(r.totalIgst),
            "Total Discount" to money(r.totalDiscount),
            "Total Round Off" to money(r.totalRoundOff)
        ).forEach { (label, value) -> summary.addView(summaryRow(label, value)) }
        summary.addView(summaryRow("Total Bill Amount", money(r.totalAmount), emphasised = true))
    }

    private fun showEmpty(title: String, hint: String) {
        root.findViewById<View>(R.id.llReportResult).visibility = View.GONE
        root.findViewById<View>(R.id.llReportEmpty).visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.tvReportEmptyTitle).text = title
        root.findViewById<TextView>(R.id.tvReportEmptyHint).text = hint
        btnPrint.isEnabled = false
    }

    // ---- Table ---------------------------------------------------------------

    /** One column of the on-screen table: what it is called and how wide it sits. */
    private data class Column(val label: String, val widthDp: Int, val alignEnd: Boolean)

    /**
     * The rows of the report.
     *
     * Each row is the same nine cells in the same nine widths as the header, built
     * by [tableRow] so there is one description of the table rather than two that
     * have to be kept in step.
     */
    private inner class LineAdapter(
        private val lines: List<BillWiseReportDao.Line>
    ) : RecyclerView.Adapter<LineAdapter.Holder>() {

        inner class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(tableRow(COLUMNS.map { "" }, index = 0) as LinearLayout)

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val line = lines[position]
            val values = listOf(
                line.billNumber,
                pretty(line.date),
                line.payMode,
                money(line.mrp),
                money(line.cgst),
                money(line.sgst),
                money(line.igst),
                money(line.discount),
                money(line.netAmount)
            )
            values.forEachIndexed { i, value ->
                (holder.row.getChildAt(i) as? TextView)?.text = value
            }
            // Zebra by where the row sits now, not by where it was built: a recycled
            // row carries the shade of whichever row it used to be.
            holder.row.setBackgroundColor(
                if (position % 2 == 1) Color.parseColor("#FFFFFF") else Color.parseColor("#F7F8FA")
            )
        }

        override fun getItemCount(): Int = lines.size
    }

    /**
     * Builds one row - the header when [index] is negative, otherwise a data row -
     * from the one column list, so a heading cannot end up over the wrong figures.
     *
     * Every column is a fixed width rather than weighted: the row scrolls sideways,
     * where a weighted column has no width to divide up, and the money columns have
     * to line up down the page to be read as a column at all. The shade [index]
     * picks is only the row's starting one - a recycled row is re-shaded for the
     * position it lands on, see [LineAdapter].
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
            COLUMNS.forEachIndexed { i, column ->
                addView(TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(column.widthDp), -2)
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
            setPadding(0, dp(5), 0, dp(5))
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                text = label
                textSize = if (emphasised) 15f else 13f
                setTypeface(Typeface.MONOSPACE, if (emphasised) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(resources.getColor(R.color.text_secondary, null))
            })
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2)
                text = value
                textSize = if (emphasised) 16f else 13f
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
    private fun pretty(value: String): String = runCatching {
        SimpleDateFormat("dd-MM-yyyy", Locale.US)
            .format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value.take(10))!!)
    }.getOrDefault(value)

    private fun money(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** Side padding on every row, header included - and on the list holding them. */
        const val ROW_PADDING_DP = 6

        /**
         * The table, left to right.
         *
         * DATE is not one of the figures a bill-wise report is about, but a range
         * covering more than one day is unreadable without it - every row would say
         * what it came to and none would say when.
         */
        val COLUMNS = listOf(
            Column("BILL NO", 120, alignEnd = false),
            Column("DATE", 100, alignEnd = false),
            Column("PAY MODE", 90, alignEnd = false),
            Column("TOTAL MRP", 100, alignEnd = true),
            Column("CGST", 90, alignEnd = true),
            Column("SGST", 90, alignEnd = true),
            Column("IGST", 90, alignEnd = true),
            Column("DISCOUNT", 100, alignEnd = true),
            Column("TOTAL AMOUNT", 120, alignEnd = true)
        )
    }
}
