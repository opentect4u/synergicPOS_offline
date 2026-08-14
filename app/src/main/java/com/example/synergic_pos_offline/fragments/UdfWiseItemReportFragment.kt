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
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.UdfWiseItemReportDao
import com.example.synergic_pos_offline.utils.PrinterSetup
import com.example.synergic_pos_offline.utils.ThemeManager
import com.example.synergic_pos_offline.utils.ThermalPrinter
import com.example.synergic_pos_offline.utils.UdfWiseItemReportRenderer
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * UDF-Wise Item Report - the restaurant items of a period grouped by UDF (the table),
 * each product with its quantity and amount, a subtotal per UDF and a grand total.
 * Restaurant-only. Screen and print come from one generated report.
 */
class UdfWiseItemReportFragment : Fragment(), TitledScreen {

    override val screenTitle = "UDF Wise Item Report"

    private val dao: UdfWiseItemReportDao by lazy { UdfWiseItemReportDao(requireContext()) }
    private var report: UdfWiseItemReportDao.Report? = null

    private lateinit var root: View
    private lateinit var etFrom: TextInputEditText
    private lateinit var etTo: TextInputEditText
    private lateinit var btnPrint: MaterialButton

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
    }

    private fun generate() {
        val from = etFrom.text?.toString()?.trim().orEmpty()
        val to = etTo.text?.toString()?.trim().orEmpty()
        if (from.isEmpty() || to.isEmpty()) { toast("Pick both dates"); return }
        if (from > to) { toast("The From date is after the To date"); return }

        val result = dao.between(from, to)
        report = result.takeUnless { it.isEmpty }
        if (result.isEmpty) {
            showEmpty("No items in this period", "No restaurant item was billed between ${pretty(from)} and ${pretty(to)}.")
            return
        }
        bind(result)
    }

    private fun bind(r: UdfWiseItemReportDao.Report) {
        root.findViewById<View>(R.id.llReportEmpty).visibility = View.GONE
        root.findViewById<View>(R.id.llReportResult).visibility = View.VISIBLE
        btnPrint.isEnabled = true

        root.findViewById<TextView>(R.id.tvReportPeriod).text =
            "${pretty(r.fromDate)}  to  ${pretty(r.toDate)}   •   ${r.groups.size} UDF(s)"

        val container = root.findViewById<LinearLayout>(R.id.llCpRows)
        container.removeAllViews()
        container.addView(itemRow("NAME", "QTY", "AMOUNT", bold = true))
        r.groups.forEach { g ->
            container.addView(divider())
            container.addView(band("UDF NO: ${g.udf}"))
            g.items.forEach { container.addView(itemRow(it.name, qtyFmt(it.qty), money(it.amount), bold = false)) }
            container.addView(spread("QTY : ${qtyFmt(g.qty)}", "AMT : ${money(g.amount)}"))
        }
        container.addView(divider())
        container.addView(spread("TOTAL QTY : ${qtyFmt(r.totalQty)}", "TOTAL AMT : ${money(r.totalAmount)}", bold = true))
    }

    private fun showEmpty(title: String, hint: String) {
        root.findViewById<View>(R.id.llReportResult).visibility = View.GONE
        root.findViewById<View>(R.id.llReportEmpty).visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.tvReportEmptyTitle).text = title
        root.findViewById<TextView>(R.id.tvReportEmptyHint).text = hint
        btnPrint.isEnabled = false
    }

    private fun printReport(r: UdfWiseItemReportDao.Report) {
        val config = ThermalPrinter.configForPurpose(requireContext(), "BILL")
            ?: ThermalPrinter.savedConfig(requireContext())
        if (config == null) {
            PrinterSetup.show(requireContext()) { saved -> sendToPrinter(r, saved) }
            return
        }
        sendToPrinter(r, config)
    }

    private fun sendToPrinter(r: UdfWiseItemReportDao.Report, config: ThermalPrinter.Config) {
        val ctx = context ?: return
        val bitmap = UdfWiseItemReportRenderer(ctx).renderToBitmap(r, config.paperDots)
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

    private fun itemRow(name: String, qty: String, amount: String, bold: Boolean): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
            addView(cell(name, Gravity.START, 3f, bold))
            addView(cell(qty, Gravity.END, 1.1f, bold))
            addView(cell(amount, Gravity.END, 1.5f, bold))
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

    private fun band(text: String): View = TextView(requireContext()).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(resources.getColor(R.color.text_secondary, null))
        setPadding(0, dp(4), 0, dp(2))
    }

    private fun spread(left: String, right: String, bold: Boolean = false): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
            addView(TextView(requireContext()).apply {
                text = left
                textSize = if (bold) 14f else 13f
                setTypeface(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(resources.getColor(R.color.text_main, null))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            addView(TextView(requireContext()).apply {
                text = right
                textSize = if (bold) 14f else 13f
                gravity = Gravity.END
                setTypeface(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
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
