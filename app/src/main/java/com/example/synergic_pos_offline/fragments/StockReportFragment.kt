package com.example.synergic_pos_offline.fragments

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
import com.example.synergic_pos_offline.database.StockDao
import com.example.synergic_pos_offline.database.StockReportDao
import com.example.synergic_pos_offline.utils.StockReportPrinter
import com.example.synergic_pos_offline.utils.ReportTable
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stock Report - every item and what is on the shelf.
 *
 * The Bill Wise and Item Wise reports' twin, less the date range: stock is a
 * standing count rather than a period, so there is nothing to bound and the card
 * holds only Generate and Print.
 *
 * Reachable only while stock is tracked; the tile and the menu entry are both
 * dropped otherwise, since there is no count to report on.
 */
class StockReportFragment : Fragment(), TitledScreen {

    override val screenTitle = "Stock Report"

    private val dao: StockReportDao by lazy { StockReportDao(requireContext()) }

    /** The generated report, held so Print sends exactly what is on screen. */
    private var report: StockReportDao.Report? = null

    private lateinit var root: View

    /**
     * What each column is actually drawn at - the declared minimums until the card
     * has been laid out and [ReportTable] can share out whatever room is spare.
     */
    private var columnPx: IntArray = IntArray(0)
    private lateinit var btnPrint: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_stock_report, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view

        btnPrint = view.findViewById(R.id.btnPrintStockReport)
        view.findViewById<MaterialButton>(R.id.btnGenerate).setOnClickListener { generate() }
        btnPrint.setOnClickListener {
            report?.let { r -> StockReportPrinter.print(requireContext(), r) { if (isAdded) toast(it) } }
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
        // Stamped when it is read, not when it is printed: a stock figure is only
        // true as of a moment, and the next sale moves it.
        val takenAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val result = dao.current(takenAt)
        report = result.takeUnless { it.isEmpty }

        if (result.isEmpty) {
            showEmpty("No stock to report", "There are no products on this till yet.")
            return
        }
        bind(result)
    }

    private fun bind(r: StockReportDao.Report) {
        root.findViewById<View>(R.id.llStockReportEmpty).visibility = View.GONE
        root.findViewById<View>(R.id.llStockReportResult).visibility = View.VISIBLE
        btnPrint.isEnabled = true

        root.findViewById<TextView>(R.id.tvStockReportTaken).text =
            "As at ${pretty(r.takenAt)}   •   ${r.itemCount} item(s)"

        // The table fills the card where there is room to spare, and keeps its
        // declared widths and scrolls where there is not - see [ReportTable]. The
        // card's width is only known once it has been laid out, so the table is
        // built at its minimums first and stretched when that width arrives.
        columnPx = COLUMNS.map { dp(it.widthDp) }.toIntArray()
        drawTable(r)
        root.findViewById<View>(R.id.hsvStockReportTable).let { table ->
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
    private fun drawTable(r: StockReportDao.Report) {
        val header = root.findViewById<LinearLayout>(R.id.llStockReportHeader)
        header.removeAllViews()
        header.addView(tableRow(COLUMNS.map { it.label }, index = -1))

        val rows = root.findViewById<RecyclerView>(R.id.rvStockReportRows)
        rows.layoutParams = rows.layoutParams.apply {
            width = columnPx.sum() + dp(ROW_PADDING_DP) * 2
        }
        rows.layoutManager = LinearLayoutManager(requireContext())
        rows.adapter = LineAdapter(r.lines)

        val summary = root.findViewById<LinearLayout>(R.id.llStockReportSummary)
        summary.removeAllViews()
        summary.addView(summaryRow("Total Items", r.itemCount.toString()))
        summary.addView(summaryRow("Out of Stock", r.outOfStock.toString()))
        summary.addView(summaryRow("Total Quantity", StockDao.trim(r.totalQuantity), emphasised = true))
    }

    private fun showEmpty(title: String, hint: String) {
        root.findViewById<View>(R.id.llStockReportResult).visibility = View.GONE
        root.findViewById<View>(R.id.llStockReportEmpty).visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.tvStockReportEmptyTitle).text = title
        root.findViewById<TextView>(R.id.tvStockReportEmptyHint).text = hint
        btnPrint.isEnabled = false
    }

    // ---- Table ---------------------------------------------------------------

    /** One column of the on-screen table: what it is called and how wide it sits. */
    private data class Column(val label: String, val widthDp: Int, val alignEnd: Boolean)

    /** The rows of the report, recycled - a catalogue is a long list. */
    private inner class LineAdapter(
        private val lines: List<StockReportDao.Line>
    ) : RecyclerView.Adapter<LineAdapter.Holder>() {

        inner class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(tableRow(COLUMNS.map { "" }, index = 0) as LinearLayout)

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val line = lines[position]
            listOf(line.serial.toString(), line.name, StockDao.trim(line.quantity))
                .forEachIndexed { i, value ->
                    (holder.row.getChildAt(i) as? TextView)?.text = value
                }
            // An item that has run out is the one a stock report is opened to find,
            // so it is marked rather than left to be spotted among the rest.
            (holder.row.getChildAt(2) as? TextView)?.setTextColor(
                if (line.quantity <= 0.0) Color.parseColor("#B3261E")
                else resources.getColor(R.color.text_main, null)
            )
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

    /** "yyyy-MM-dd HH:mm:ss" as "dd-MM-yyyy hh:mm a". */
    private fun pretty(value: String): String = runCatching {
        SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.US)
            .format(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(value)!!)
    }.getOrDefault(value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** Side padding on every row, header included - and on the list holding them. */
        const val ROW_PADDING_DP = 6

        /**
         * The table, left to right. The item name takes by far the most room: it is
         * what the report is about, and the only column with no fixed length.
         */
        val COLUMNS = listOf(
            Column("SL NO", 70, alignEnd = false),
            Column("ITEM", 300, alignEnd = false),
            Column("QTY IN STOCK", 130, alignEnd = true)
        )
    }
}
