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
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.CustomerItemWiseReportDao
import com.example.synergic_pos_offline.utils.CustomerItemWiseReportRenderer
import com.example.synergic_pos_offline.utils.PrinterSetup
import com.example.synergic_pos_offline.utils.ReportDownloads
import com.example.synergic_pos_offline.utils.ReportExport
import com.example.synergic_pos_offline.utils.ThemeManager
import com.example.synergic_pos_offline.utils.ThermalPrinter
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Customer Item-Wise Report - a chosen customer's items over a period, each product
 * with quantity, amount and tax, and the period totals. Screen and print come from
 * one generated report.
 */
class CustomerItemWiseReportFragment : Fragment(), TitledScreen {

    override val screenTitle = "Customer Item Wise RPT"

    private val dao: CustomerItemWiseReportDao by lazy { CustomerItemWiseReportDao(requireContext()) }
    private var report: CustomerItemWiseReportDao.Report? = null
    private var customers: List<CustomerItemWiseReportDao.Customer> = emptyList()
    private var selectedCustomerId: Long? = null

    private lateinit var root: View
    private lateinit var actCustomer: MaterialAutoCompleteTextView
    private lateinit var etFrom: TextInputEditText
    private lateinit var etTo: TextInputEditText
    private lateinit var btnPrint: MaterialButton

    /** The PDF and Excel buttons beside Print - see [ReportDownloads]. */
    private lateinit var downloads: ReportDownloads

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_customer_itemwise_report, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view

        actCustomer = view.findViewById(R.id.actCustomer)
        etFrom = view.findViewById(R.id.etFrom)
        etTo = view.findViewById(R.id.etTo)
        btnPrint = view.findViewById(R.id.btnPrintReport)

        customers = dao.customers()
        val labels = customers.map { c -> if (c.phone.isNotBlank()) "${c.name} - ${c.phone}" else c.name }
        actCustomer.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels))
        actCustomer.setOnItemClickListener { _, _, position, _ ->
            selectedCustomerId = customers.getOrNull(position)?.id
        }

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
    }

    private fun generate() {
        val customerId = selectedCustomerId
        if (customerId == null) { toast("Pick a customer"); return }
        val from = etFrom.text?.toString()?.trim().orEmpty()
        val to = etTo.text?.toString()?.trim().orEmpty()
        if (from.isEmpty() || to.isEmpty()) { toast("Pick both dates"); return }
        if (from > to) { toast("The From date is after the To date"); return }

        val result = dao.between(customerId, from, to)
        report = result.takeUnless { it.isEmpty }
        if (result.isEmpty) {
            showEmpty("No items in this period", "${result.customerName.ifBlank { "This customer" }} bought nothing between ${pretty(from)} and ${pretty(to)}.")
            return
        }
        bind(result)
    }

    private fun bind(r: CustomerItemWiseReportDao.Report) {
        root.findViewById<View>(R.id.llReportEmpty).visibility = View.GONE
        root.findViewById<View>(R.id.llReportResult).visibility = View.VISIBLE
        btnPrint.isEnabled = true
        downloads.setEnabled(true)

        root.findViewById<TextView>(R.id.tvReportPeriod).text =
            "${r.customerName}   •   ${pretty(r.fromDate)} to ${pretty(r.toDate)}"

        val container = root.findViewById<LinearLayout>(R.id.llCiRows)
        container.removeAllViews()
        container.addView(twoCol("ITEM NAME", "QUANTITY", bold = true))
        container.addView(threeCol("AMOUNT", "SGST", "CGST", bold = true))
        r.items.forEach { item ->
            container.addView(divider())
            container.addView(twoCol(item.name, qtyFmt(item.qty), bold = false))
            container.addView(threeCol(money(item.amount), money(item.sgst), money(item.cgst), bold = false))
        }
        container.addView(divider())
        container.addView(total("TOTAL QTY :", qtyFmt(r.totalQty)))
        container.addView(total("TOTAL SGST:", money(r.totalSgst)))
        container.addView(total("TOTAL CGST:", money(r.totalCgst)))
        if (r.totalServiceCharge > 0.005) container.addView(total("SERVICE CHG:", money(r.totalServiceCharge)))
        if (r.totalOtherCharges > 0.005) container.addView(total("EXTRA CHGS:", money(r.totalOtherCharges)))
        container.addView(total("TOTAL AMT :", money(r.totalAmount)))
    }

    /**
     * The screen as a downloadable table.
     *
     * On glass each item is two stacked lines, because the card is narrow; a
     * spreadsheet has no such trouble, so the same figures go out as one row per item
     * with a column each. Nothing is added or dropped - it is the same report, laid
     * out for the page it is going to.
     */
    private fun sheetOf(r: CustomerItemWiseReportDao.Report) = ReportExport.Sheet(
        title = screenTitle,
        subtitle = "${r.customerName}   •   ${pretty(r.fromDate)} to ${pretty(r.toDate)}",
        columns = listOf("ITEM NAME", "QUANTITY", "AMOUNT", "SGST", "CGST"),
        alignEnd = listOf(false, true, true, true, true),
        rows = r.items.map {
            listOf(it.name, qtyFmt(it.qty), money(it.amount), money(it.sgst), money(it.cgst))
        },
        summary = buildList {
            add("Total Qty" to qtyFmt(r.totalQty))
            add("Total SGST" to money(r.totalSgst))
            add("Total CGST" to money(r.totalCgst))
            if (r.totalServiceCharge > 0.005) add("Service Charge" to money(r.totalServiceCharge))
            if (r.totalOtherCharges > 0.005) add("Extra Charges" to money(r.totalOtherCharges))
            add("Total Amount" to money(r.totalAmount))
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

    private fun printReport(r: CustomerItemWiseReportDao.Report) {
        val config = ThermalPrinter.configForPurpose(requireContext(), "BILL")
            ?: ThermalPrinter.savedConfig(requireContext())
        if (config == null) {
            PrinterSetup.show(requireContext()) { saved -> sendToPrinter(r, saved) }
            return
        }
        sendToPrinter(r, config)
    }

    private fun sendToPrinter(r: CustomerItemWiseReportDao.Report, config: ThermalPrinter.Config) {
        val ctx = context ?: return
        val bitmap = CustomerItemWiseReportRenderer(ctx).renderToBitmap(r, config.paperDots)
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

    // ---- On-screen blocks ----------------------------------------------------

    private fun twoCol(left: String, right: String, bold: Boolean): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
            addView(cell(left, Gravity.START, 1f, bold))
            addView(cell(right, Gravity.END, 1f, bold))
        }

    private fun threeCol(a: String, b: String, c: String, bold: Boolean): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
            addView(cell(a, Gravity.START, 1f, bold))
            addView(cell(b, Gravity.CENTER, 1f, bold))
            addView(cell(c, Gravity.END, 1f, bold))
        }

    private fun cell(text: String, gravity: Int, weight: Float, bold: Boolean): View =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            this.gravity = gravity
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(resources.getColor(R.color.text_main, null))
            layoutParams = LinearLayout.LayoutParams(0, -2, weight)
        }

    private fun total(label: String, value: String): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
            addView(TextView(requireContext()).apply {
                text = label
                textSize = 14f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(resources.getColor(R.color.text_secondary, null))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            addView(TextView(requireContext()).apply {
                text = value
                textSize = 14f
                gravity = Gravity.END
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(resources.getColor(R.color.text_main, null))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
        }

    private fun divider(): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            .also { it.topMargin = dp(6); it.bottomMargin = dp(6) }
        setBackgroundColor(Color.parseColor("#D8DCE0"))
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
    private fun qtyFmt(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
}
