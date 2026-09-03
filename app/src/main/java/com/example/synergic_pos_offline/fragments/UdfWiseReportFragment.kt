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
import com.example.synergic_pos_offline.database.UdfWiseReportDao
import com.example.synergic_pos_offline.utils.PeriodReportPrinter
import com.example.synergic_pos_offline.utils.PeriodReportRenderer
import com.example.synergic_pos_offline.utils.ReportDownloads
import com.example.synergic_pos_offline.utils.ReportExport
import com.example.synergic_pos_offline.utils.ReportTable
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * UDF-Wise Report - the restaurant bills of a period grouped by UDF ("<section>-
 * <table>", e.g. "AC-1", "BAR-2"), each with its bill count and tax / discount /
 * bill totals. Restaurant-only. Screen and print come from one generated report,
 * so Print sends what was reviewed.
 */
class UdfWiseReportFragment : Fragment(), TitledScreen {

    override val screenTitle = "UDF-Wise Report"

    private val dao: UdfWiseReportDao by lazy { UdfWiseReportDao(requireContext()) }
    private var report: UdfWiseReportDao.Report? = null

    private lateinit var root: View
    private lateinit var etFrom: TextInputEditText
    private lateinit var etTo: TextInputEditText
    private lateinit var btnPrint: MaterialButton

    /** The PDF and Excel buttons beside Print - see [ReportDownloads]. */
    private lateinit var downloads: ReportDownloads

    private var columnPx: IntArray = IntArray(0)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_bill_wise_report, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view

        etFrom = view.findViewById(R.id.etFrom)
        etTo = view.findViewById(R.id.etTo)
        btnPrint = view.findViewById(R.id.btnPrintReport)

        val today = Calendar.getInstance().time
        etFrom.setText(iso(today))
        etTo.setText(iso(today))
        etFrom.setOnClickListener { pickDate(etFrom) }
        etTo.setOnClickListener { pickDate(etTo) }

        view.findViewById<MaterialButton>(R.id.btnGenerate).setOnClickListener { generate() }
        btnPrint.setOnClickListener {
            report?.let { r ->
                PeriodReportPrinter.print(requireContext(), printContent(r), "groups") {
                    if (isAdded) toast(it)
                }
            }
        }

        ThemeManager.applyTheme(view)
        val accent = ThemeManager.getThemeColor(requireContext())
        btnPrint.apply {
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(accent)
            strokeColor = ColorStateList.valueOf(accent)
        }
        // The same figures the screen is showing, as a file in Downloads. Built from
        // the held report, so a download is what was generated.
        downloads = ReportDownloads.wire(
            view, requireContext(), accent, { if (isAdded) toast(it) }
        ) { report?.let { sheetOf(it) } }
    }

    // ---- Generating ----------------------------------------------------------

    private fun generate() {
        val from = etFrom.text?.toString()?.trim().orEmpty()
        val to = etTo.text?.toString()?.trim().orEmpty()
        if (from.isEmpty() || to.isEmpty()) { toast("Pick both dates"); return }
        if (from > to) { toast("The From date is after the To date"); return }

        val result = dao.between(from, to)
        report = result.takeUnless { it.isEmpty }
        if (result.isEmpty) {
            showEmpty(
                "No bills in this period",
                "No restaurant bill was raised between ${pretty(from)} and ${pretty(to)}."
            )
            return
        }
        bind(result)
    }

    private fun bind(r: UdfWiseReportDao.Report) {
        root.findViewById<View>(R.id.llReportEmpty).visibility = View.GONE
        root.findViewById<View>(R.id.llReportResult).visibility = View.VISIBLE
        btnPrint.isEnabled = true
        downloads.setEnabled(true)

        root.findViewById<TextView>(R.id.tvReportPeriod).text =
            "${pretty(r.fromDate)}  to  ${pretty(r.toDate)}   •   ${r.rows.size} group(s)"

        columnPx = COLUMNS.map { dp(it.widthDp) }.toIntArray()
        drawTable(r)
        root.findViewById<View>(R.id.hsvReportTable).let { table ->
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

    private fun drawTable(r: UdfWiseReportDao.Report) {
        val header = root.findViewById<LinearLayout>(R.id.llReportHeader)
        header.removeAllViews()
        header.addView(tableRow(COLUMNS.map { it.label }, headerRow = true))

        val rows = root.findViewById<RecyclerView>(R.id.rvReportRows)
        rows.layoutParams = rows.layoutParams.apply { width = columnPx.sum() + dp(ROW_PADDING_DP) * 2 }
        rows.layoutManager = LinearLayoutManager(requireContext())
        rows.adapter = RowAdapter(r.rows)

        val summary = root.findViewById<LinearLayout>(R.id.llReportSummary)
        summary.removeAllViews()
        summary.addView(summaryRow("Total Groups", r.rows.size.toString()))
        summary.addView(summaryRow("Total Bills", r.totalBills.toString()))
        // Each tax its own line rather than one blended figure - a GST return is
        // filed against SGST and CGST separately, and IGST/VAT earn their place
        // only where a bill in the range actually carried one.
        summary.addView(summaryRow("SGST Amount", money(r.totalSgst)))
        summary.addView(summaryRow("CGST Amount", money(r.totalCgst)))
        if (r.hasIgst) summary.addView(summaryRow("IGST Amount", money(r.totalIgst)))
        if (r.hasVat) summary.addView(summaryRow("VAT Amount", money(r.totalVat)))
        summary.addView(summaryRow("Discount Amount", money(r.totalDiscount)))
        // Shown only where the period actually carried one - a shop that never
        // charges Service or an Extra Charge should not read a zero row saying so.
        if (r.totalServiceCharge > 0.005) summary.addView(summaryRow("Service Charge", money(r.totalServiceCharge)))
        if (r.totalOtherCharges > 0.005) summary.addView(summaryRow("Extra Charges", money(r.totalOtherCharges)))
        summary.addView(summaryRow("Bill Amount", money(r.totalBillAmount), emphasised = true))
    }

    /** The screen as a downloadable table: the columns and rows it is drawing. */
    private fun sheetOf(r: UdfWiseReportDao.Report) = ReportExport.Sheet(
        title = screenTitle,
        subtitle = "${pretty(r.fromDate)}  to  ${pretty(r.toDate)}   •   ${r.rows.size} group(s)",
        columns = COLUMNS.map { it.label },
        alignEnd = COLUMNS.map { it.alignEnd },
        rows = r.rows.map {
            listOf(it.udf, it.bills.toString(), money(it.taxAmount), money(it.discount), money(it.billAmount))
        },
        summary = buildList {
            add("Total Groups" to r.rows.size.toString())
            add("Total Bills" to r.totalBills.toString())
            add("SGST Amount" to money(r.totalSgst))
            add("CGST Amount" to money(r.totalCgst))
            if (r.hasIgst) add("IGST Amount" to money(r.totalIgst))
            if (r.hasVat) add("VAT Amount" to money(r.totalVat))
            add("Discount Amount" to money(r.totalDiscount))
            if (r.totalServiceCharge > 0.005) add("Service Charge" to money(r.totalServiceCharge))
            if (r.totalOtherCharges > 0.005) add("Extra Charges" to money(r.totalOtherCharges))
            add("Bill Amount" to money(r.totalBillAmount))
        }
    )

    private fun showEmpty(title: String, hint: String) {
        root.findViewById<View>(R.id.llReportResult).visibility = View.GONE
        root.findViewById<View>(R.id.llReportEmpty).visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.tvReportEmptyTitle).text = title
        root.findViewById<TextView>(R.id.tvReportEmptyHint).text = hint
        btnPrint.isEnabled = false
        downloads.setEnabled(false)
    }

    // ---- Print ---------------------------------------------------------------

    private fun printContent(r: UdfWiseReportDao.Report): PeriodReportRenderer.Content =
        PeriodReportRenderer.Content(
            title = "UDF-Wise Report",
            period = "${pretty(r.fromDate)}  to  ${pretty(r.toDate)}",
            subtitle = "${r.rows.size} group(s)",
            style = PeriodReportRenderer.Style.CLASSIC,
            range = "F.DT:${shortDate(r.fromDate)}" to "TO.DT:${shortDate(r.toDate)}",
            columns = listOf("UDF", "BILLS", "TAX AMT", "DISC.", "BILL AMT"),
            rows = r.rows.map { row ->
                listOf(row.udf, row.bills.toString(), money(row.taxAmount), money(row.discount), money(row.billAmount))
            },
            summary = buildList {
                add("SGST AMOUNT :" to money(r.totalSgst))
                add("CGST AMOUNT :" to money(r.totalCgst))
                if (r.hasIgst) add("IGST AMOUNT :" to money(r.totalIgst))
                if (r.hasVat) add("VAT AMOUNT  :" to money(r.totalVat))
                if (r.totalServiceCharge > 0.005) add("SERVICE CHG :" to money(r.totalServiceCharge))
                if (r.totalOtherCharges > 0.005) add("EXTRA CHGS  :" to money(r.totalOtherCharges))
            },
            total = "TOTAL  :" to money(r.totalBillAmount),
            emptyNote = "No bills in this period."
        )

    private fun shortDate(date: String): String = pretty(date).let { it.take(6) + it.takeLast(2) }

    // ---- On-screen table -----------------------------------------------------

    private data class Column(val label: String, val widthDp: Int, val alignEnd: Boolean)

    private inner class RowAdapter(
        private val rows: List<UdfWiseReportDao.Row>
    ) : RecyclerView.Adapter<RowAdapter.Holder>() {
        inner class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(tableRow(COLUMNS.map { "" }, headerRow = false) as LinearLayout)

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val r = rows[position]
            val cells = listOf(r.udf, r.bills.toString(), money(r.taxAmount), money(r.discount), money(r.billAmount))
            cells.forEachIndexed { i, v -> (holder.row.getChildAt(i) as? TextView)?.text = v }
            holder.row.setBackgroundColor(
                if (position % 2 == 1) Color.parseColor("#FFFFFF") else Color.parseColor("#F7F8FA")
            )
        }

        override fun getItemCount(): Int = rows.size
    }

    private fun tableRow(values: List<String>, headerRow: Boolean): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ROW_PADDING_DP), dp(10), dp(ROW_PADDING_DP), dp(10))
            setBackgroundColor(if (headerRow) Color.parseColor("#ECEFF1") else Color.parseColor("#F7F8FA"))
            COLUMNS.forEachIndexed { i, column ->
                addView(TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(columnPx[i], -2)
                    text = values.getOrNull(i).orEmpty()
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = if (column.alignEnd) Gravity.END else Gravity.START
                    setPadding(dp(8), 0, dp(8), 0)
                    setTypeface(Typeface.MONOSPACE, if (headerRow) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(resources.getColor(if (headerRow) R.color.text_secondary else R.color.text_main, null))
                })
            }
        }

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

    // ---- Helpers -------------------------------------------------------------

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

    private fun pretty(value: String): String = runCatching {
        SimpleDateFormat("dd-MM-yyyy", Locale.US)
            .format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value.take(10))!!)
    }.getOrDefault(value)

    private fun money(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val ROW_PADDING_DP = 6
        val COLUMNS = listOf(
            Column("UDF", 90, alignEnd = false),
            Column("BILLS", 70, alignEnd = true),
            Column("TAX AMT", 100, alignEnd = true),
            Column("DISC.", 90, alignEnd = true),
            Column("BILL AMT", 110, alignEnd = true)
        )
    }
}
