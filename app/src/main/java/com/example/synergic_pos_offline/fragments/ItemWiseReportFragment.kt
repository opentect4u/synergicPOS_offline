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
import com.example.synergic_pos_offline.database.ItemWiseReportDao
import com.example.synergic_pos_offline.database.StockDao
import com.example.synergic_pos_offline.utils.ItemWiseReportPrinter
import com.example.synergic_pos_offline.utils.ReportTable
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Item Wise Report - what sold over a period, a line per item.
 *
 * The Bill Wise Report's twin, down to the date range, the table, the summary and
 * the Print button; the difference is which way the books are read. See
 * [ItemWiseReportDao].
 *
 * Screen and printout are rendered from one [ItemWiseReportDao.Report], generated
 * when Generate is pressed, so printing sends what was looked at rather than a
 * fresh query that a sale could have changed underneath.
 */
class ItemWiseReportFragment : Fragment(), TitledScreen {

    override val screenTitle = "Item Wise Report"

    private val dao: ItemWiseReportDao by lazy { ItemWiseReportDao(requireContext()) }

    /** The generated report, held so Print sends exactly what was generated. */
    private var report: ItemWiseReportDao.Report? = null

    private lateinit var root: View

    /**
     * What each column is actually drawn at - the declared minimums until the card
     * has been laid out and [ReportTable] can share out whatever room is spare.
     */
    private var columnPx: IntArray = IntArray(0)
    private lateinit var etFrom: TextInputEditText
    private lateinit var etTo: TextInputEditText
    private lateinit var btnPrint: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_item_wise_report, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view

        etFrom = view.findViewById(R.id.etFrom)
        etTo = view.findViewById(R.id.etTo)
        btnPrint = view.findViewById(R.id.btnPrintItemReport)

        // Opens on today, the range asked for far more often than any other.
        val today = Calendar.getInstance().time
        etFrom.setText(iso(today))
        etTo.setText(iso(today))

        etFrom.setOnClickListener { pickDate(etFrom) }
        etTo.setOnClickListener { pickDate(etTo) }

        view.findViewById<MaterialButton>(R.id.btnGenerate).setOnClickListener { generate() }
        btnPrint.setOnClickListener {
            report?.let { r -> ItemWiseReportPrinter.print(requireContext(), r) { if (isAdded) toast(it) } }
        }

        ThemeManager.applyTheme(view)
        // ThemeManager fills every MaterialButton; Print is the secondary action.
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
                "Nothing sold in this period",
                "No items were billed between ${pretty(from)} and ${pretty(to)}."
            )
            return
        }
        bind(result)
    }

    private fun bind(r: ItemWiseReportDao.Report) {
        root.findViewById<View>(R.id.llItemReportEmpty).visibility = View.GONE
        root.findViewById<View>(R.id.llItemReportResult).visibility = View.VISIBLE
        btnPrint.isEnabled = true

        root.findViewById<TextView>(R.id.tvItemReportPeriod).text =
            "${pretty(r.fromDate)}  to  ${pretty(r.toDate)}   •   ${r.itemCount} item(s)"

        // The table fills the card where there is room to spare, and keeps its
        // declared widths and scrolls where there is not - see [ReportTable]. The
        // card's width is only known once it has been laid out, so the table is
        // built at its minimums first and stretched when that width arrives.
        columnPx = COLUMNS.map { dp(it.widthDp) }.toIntArray()
        drawTable(r)
        root.findViewById<View>(R.id.hsvItemReportTable).let { table ->
            table.post {
                if (!isAdded) return@post
                val available = table.width - dp(ROW_PADDING_DP) * 2
                val stretched = ReportTable.stretch(COLUMNS.map { dp(it.widthDp) }.toIntArray(), available)
                if (!stretched.contentEquals(columnPx)) {
                    columnPx = stretched
                    drawTable(r)
                }
            }
        }
    }

    /** Lays the header and rows out at whatever [columnPx] currently says. */
    private fun drawTable(r: ItemWiseReportDao.Report) {
        val header = root.findViewById<LinearLayout>(R.id.llItemReportHeader)
        header.removeAllViews()
        header.addView(tableRow(COLUMNS.map { it.label }, index = -1))

        // The list is as wide as one row, so it and the header scroll sideways
        // together - a header that sat still would label the wrong figures.
        val rows = root.findViewById<RecyclerView>(R.id.rvItemReportRows)
        rows.layoutParams = rows.layoutParams.apply {
            width = columnPx.sum() + dp(ROW_PADDING_DP) * 2
        }
        rows.layoutManager = LinearLayoutManager(requireContext())
        rows.adapter = LineAdapter(r.lines)

        val summary = root.findViewById<LinearLayout>(R.id.llItemReportSummary)
        summary.removeAllViews()
        summary.addView(summaryRow("Total Items", r.itemCount.toString()))
        summary.addView(summaryRow("Total Quantity", StockDao.trim(r.totalQuantity)))
        summary.addView(summaryRow("Total Price", money(r.totalPrice), emphasised = true))
    }

    private fun showEmpty(title: String, hint: String) {
        root.findViewById<View>(R.id.llItemReportResult).visibility = View.GONE
        root.findViewById<View>(R.id.llItemReportEmpty).visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.tvItemReportEmptyTitle).text = title
        root.findViewById<TextView>(R.id.tvItemReportEmptyHint).text = hint
        btnPrint.isEnabled = false
    }

    // ---- Table ---------------------------------------------------------------

    /** One column of the on-screen table: what it is called and how wide it sits. */
    private data class Column(val label: String, val widthDp: Int, val alignEnd: Boolean)

    /** The rows of the report, recycled - a long period is a long list of items. */
    private inner class LineAdapter(
        private val lines: List<ItemWiseReportDao.Line>
    ) : RecyclerView.Adapter<LineAdapter.Holder>() {

        inner class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(tableRow(COLUMNS.map { "" }, index = 0) as LinearLayout)

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val line = lines[position]
            listOf(
                line.serial.toString(),
                line.name,
                StockDao.trim(line.quantity),
                money(line.price)
            ).forEachIndexed { i, value ->
                (holder.row.getChildAt(i) as? TextView)?.text = value
            }
            // Zebra by where the row sits now: a recycled row carries the shade of
            // whichever row it used to be.
            holder.row.setBackgroundColor(
                if (position % 2 == 1) Color.parseColor("#FFFFFF") else Color.parseColor("#F7F8FA")
            )
        }

        override fun getItemCount(): Int = lines.size
    }

    /**
     * Builds one row - the header when [index] is negative - from the one column
     * list, so a heading cannot end up over the wrong figures.
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
                    layoutParams = LinearLayout.LayoutParams(columnPx[i], -2)
                    text = values.getOrNull(i).orEmpty()
                    textSize = 12f
                    // maxLines, never isSingleLine - the latter also turns on
                    // horizontal scrolling, which prints the cell blank.
                    maxLines = 1
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
         * The table, left to right - the four columns the report was asked for.
         *
         * ITEM takes by far the most room: it is the column the report is about, and
         * the only one whose content has no fixed length.
         */
        val COLUMNS = listOf(
            Column("SL NO", 70, alignEnd = false),
            Column("ITEM", 260, alignEnd = false),
            Column("QTY", 100, alignEnd = true),
            Column("PRICE", 120, alignEnd = true)
        )
    }
}
