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
import com.example.synergic_pos_offline.database.CustomerPaymentReportDao
import com.example.synergic_pos_offline.utils.BusyDialog
import com.example.synergic_pos_offline.utils.CustomerPaymentReportRenderer
import com.example.synergic_pos_offline.utils.PrinterSetup
import com.example.synergic_pos_offline.utils.ReportDownloads
import com.example.synergic_pos_offline.utils.ReportExport
import com.example.synergic_pos_offline.utils.ReportSummaryFold
import com.example.synergic_pos_offline.utils.ReportTable
import com.example.synergic_pos_offline.utils.ThemeManager
import com.example.synergic_pos_offline.utils.ThermalPrinter
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Customer Payment Report - every due-collection of a period, block by block, with
 * the total collected. What is on screen and what prints come from one generated
 * [CustomerPaymentReportDao.Report], so Print sends exactly what was reviewed.
 */
class CustomerPaymentReportFragment : Fragment(), TitledScreen {

    override val screenTitle = "Customer Payment Report"

    private val dao: CustomerPaymentReportDao by lazy { CustomerPaymentReportDao(requireContext()) }
    private var report: CustomerPaymentReportDao.Report? = null

    private lateinit var root: View

    /** What each column is drawn at - the declared minimums until [ReportTable] stretches them. */
    private var columnPx: IntArray = IntArray(0)
    private lateinit var etFrom: TextInputEditText
    private lateinit var etTo: TextInputEditText
    private lateinit var btnPrint: MaterialButton

    /** The PDF and Excel buttons beside Print - see [ReportDownloads]. */
    private lateinit var downloads: ReportDownloads

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_customer_payment_report, container, false)

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
        btnPrint.setOnClickListener { report?.let { printReport(it) } }

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

        ReportSummaryFold.wire(view)
    }

    // ---- Generating ----------------------------------------------------------

    private fun generate() {
        val from = etFrom.text?.toString()?.trim().orEmpty()
        val to = etTo.text?.toString()?.trim().orEmpty()
        if (from.isEmpty() || to.isEmpty()) { toast("Pick both dates"); return }
        if (from > to) { toast("The From date is after the To date"); return }

        BusyDialog.run(this, "Generating report…") {
            val result = dao.between(from, to)
            BusyDialog.onMain(this) {
                report = result.takeUnless { it.isEmpty }

                if (result.isEmpty) {
                    showEmpty(
                        "No payments in this period",
                        "No customer payment was collected between ${pretty(from)} and ${pretty(to)}."
                    )
                    return@onMain
                }
                bind(result)
            }
        }
    }

    private fun bind(r: CustomerPaymentReportDao.Report) {
        root.findViewById<View>(R.id.llReportEmpty).visibility = View.GONE
        root.findViewById<View>(R.id.llReportResult).visibility = View.VISIBLE
        btnPrint.isEnabled = true
        downloads.setEnabled(true)

        root.findViewById<TextView>(R.id.tvReportPeriod).text =
            "${pretty(r.fromDate)}  to  ${pretty(r.toDate)}   •   ${r.entries.size} payment(s)"

        // Fills the card where there is room to spare and scrolls sideways where there
        // is not - the same table the other period reports draw.
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

    private fun drawTable(r: CustomerPaymentReportDao.Report) {
        val header = root.findViewById<LinearLayout>(R.id.llReportHeader)
        header.removeAllViews()
        header.addView(tableRow(COLUMNS.map { it.label }, index = -1))

        val rows = root.findViewById<RecyclerView>(R.id.rvReportRows)
        rows.layoutParams = rows.layoutParams.apply {
            width = columnPx.sum() + dp(ROW_PADDING_DP) * 2
        }
        rows.layoutManager = LinearLayoutManager(requireContext())
        rows.adapter = EntryAdapter(r.entries)

        val summary = root.findViewById<LinearLayout>(R.id.llReportSummary)
        summary.removeAllViews()
        summary.addView(summaryRow("Total Payments", r.entries.size.toString()))
        summary.addView(summaryRow("Total Paid", money(r.totalPaid), emphasised = true))
        ReportSummaryFold.setTotal(root, money(r.totalPaid))
        ReportSummaryFold.collapse(root)
    }

    private fun cellsOf(e: CustomerPaymentReportDao.Entry): List<String> = listOf(
        e.customerId.toString(),
        e.customerName.uppercase(),
        dateTime(e.paymentDateTime),
        e.billNo,
        money(e.paidAmount),
        money(e.balanceAmount)
    )

    /**
     * The screen as a downloadable table - one row per payment, where the card stacks
     * each one over four lines to fit its width.
     */
    private fun sheetOf(r: CustomerPaymentReportDao.Report) = ReportExport.Sheet(
        title = screenTitle,
        subtitle = "${pretty(r.fromDate)}  to  ${pretty(r.toDate)}   •   ${r.entries.size} payment(s)",
        columns = listOf("CUST ID", "CUSTOMER", "DATE & TIME", "BILL NO", "PAID AMT", "BALANCE AMT"),
        alignEnd = listOf(false, false, false, false, true, true),
        rows = r.entries.map { cellsOf(it) },
        summary = listOf(
            "Payments" to r.entries.size.toString(),
            "Total Paid" to money(r.totalPaid)
        )
    )

    private fun showEmpty(title: String, hint: String) {
        root.findViewById<View>(R.id.llReportResult).visibility = View.GONE
        root.findViewById<View>(R.id.llReportEmpty).visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.tvReportEmptyTitle).text = title
        root.findViewById<TextView>(R.id.tvReportEmptyHint).text = hint
        btnPrint.isEnabled = false
        downloads.setEnabled(false)
    }

    // ---- Printing ------------------------------------------------------------

    private fun printReport(r: CustomerPaymentReportDao.Report) {
        val config = ThermalPrinter.configForPurpose(requireContext(), "BILL")
            ?: ThermalPrinter.savedConfig(requireContext())
        if (config == null) {
            PrinterSetup.show(requireContext()) { saved -> sendToPrinter(r, saved) }
            return
        }
        sendToPrinter(r, config)
    }

    private fun sendToPrinter(r: CustomerPaymentReportDao.Report, config: ThermalPrinter.Config) {
        val ctx = context ?: return
        val bitmap = CustomerPaymentReportRenderer(ctx).renderToBitmap(r, config.paperDots)
        if (bitmap == null) { toast("Could not render the report"); return }
        ThermalPrinter.print(ctx, bitmap, config) { outcome ->
            if (!isAdded) return@print
            toast(
                when (outcome) {
                    is ThermalPrinter.Result.Success -> "Printed"
                    is ThermalPrinter.Result.Sent -> "Sent to printer"
                    is ThermalPrinter.Result.Failure -> "Print failed: ${outcome.message}"
                }
            )
        }
    }

    // ---- Table ---------------------------------------------------------------

    private data class Column(val label: String, val widthDp: Int, val alignEnd: Boolean)

    private inner class EntryAdapter(
        private val entries: List<CustomerPaymentReportDao.Entry>
    ) : RecyclerView.Adapter<EntryAdapter.Holder>() {

        inner class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(tableRow(COLUMNS.map { "" }, index = 0) as LinearLayout)

        override fun onBindViewHolder(holder: Holder, position: Int) {
            cellsOf(entries[position]).forEachIndexed { i, value ->
                (holder.row.getChildAt(i) as? TextView)?.text = value
            }
            holder.row.setBackgroundColor(
                if (position % 2 == 1) Color.parseColor("#FFFFFF") else Color.parseColor("#F7F8FA")
            )
        }

        override fun getItemCount(): Int = entries.size
    }

    /** One row - the header when [index] is negative - built from the one column list. */
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

    /** A "Label ........ value" line of the summary card. */
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

    private fun dateTime(value: String): String = runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(value)!!
        SimpleDateFormat("dd-MM-yy HH:mm:ss", Locale.US).format(parsed)
    }.getOrDefault(value)

    private fun money(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) =
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

    private companion object {
        /** Side padding on every row, header included - and on the list holding them. */
        const val ROW_PADDING_DP = 6

        val COLUMNS = listOf(
            Column("CUST ID", 90, alignEnd = false),
            Column("CUSTOMER", 160, alignEnd = false),
            Column("DATE & TIME", 150, alignEnd = false),
            Column("BILL NO", 120, alignEnd = false),
            Column("PAID AMT", 110, alignEnd = true),
            Column("BALANCE AMT", 120, alignEnd = true)
        )
    }
}
