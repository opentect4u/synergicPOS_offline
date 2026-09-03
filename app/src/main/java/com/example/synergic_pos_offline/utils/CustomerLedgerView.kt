package com.example.synergic_pos_offline.utils

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.CustomerLedgerDao
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Paints and prints a generated [CustomerLedgerDao.Ledger].
 *
 * The same statement is reachable two ways - the Customer Ledger report and the
 * per-customer dialog on the Customers master - and both show
 * `R.layout.view_customer_ledger_result`. Keeping the binding and the print here
 * means the two screens cannot end up reporting the same account differently.
 */
object CustomerLedgerView {

    /**
     * Fills an inflated (or included) `view_customer_ledger_result` from [ledger].
     *
     * [root] may be the included block itself or any ancestor of it.
     */
    fun bind(root: View, ledger: CustomerLedgerDao.Ledger) {
        val customer = ledger.customer
        root.findViewById<TextView>(R.id.tvLedgerHeading).text =
            customer.name.trim().ifEmpty { "Unnamed customer" }
        root.findViewById<TextView>(R.id.tvLedgerSubheading).text =
            "${customer.phone.ifBlank { "No phone" }}  ·  ${pretty(ledger.fromDate)} to ${pretty(ledger.toDate)}"

        root.findViewById<TextView>(R.id.tvOpening).text = money(ledger.opening)
        root.findViewById<TextView>(R.id.tvTotalIn).text = money(ledger.totalIn)
        root.findViewById<TextView>(R.id.tvTotalOut).text = money(ledger.totalOut)
        root.findViewById<TextView>(R.id.tvClosing).text = money(ledger.closing)

        val rv = root.findViewById<RecyclerView>(R.id.rvLedger)
        if (rv.layoutManager == null) rv.layoutManager = LinearLayoutManager(root.context)
        rv.adapter = EntryAdapter(ledger.entries)

        root.findViewById<View>(R.id.tvNoMovements).visibility =
            if (ledger.entries.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Sends the statement to the BILL printer - the one a sale receipt goes to.
     *
     * @param report told what happened, so a caller can toast it wherever it is
     */
    fun print(context: Context, ledger: CustomerLedgerDao.Ledger, report: (String) -> Unit) {
        val config = ThermalPrinter.configForPurpose(context, "BILL")
            ?: ThermalPrinter.savedConfig(context)
        if (config == null) {
            PrinterSetup.show(context) { saved -> send(context, ledger, saved, report) }
            return
        }
        send(context, ledger, config, report)
    }

    private fun send(
        context: Context,
        ledger: CustomerLedgerDao.Ledger,
        config: ThermalPrinter.Config,
        report: (String) -> Unit
    ) {
        val printedBy = SessionManager.currentUser?.userId?.uppercase() ?: "---"
        val capture = LedgerReceiptRenderer(context).renderToBitmap(ledger, printedBy, config.paperDots)
        if (capture == null) {
            report("Could not render the ledger")
            return
        }
        ThermalPrinter.print(context, capture, config) { result ->
            report(
                when (result) {
                    is ThermalPrinter.Result.Success -> "Printed"
                    is ThermalPrinter.Result.Sent -> "Sent to printer"
                    is ThermalPrinter.Result.Failure -> "Print failed: ${result.message}"
                }
            )
        }
    }

    // ---- Rows --------------------------------------------------------------

    private class EntryAdapter(
        private val entries: List<CustomerLedgerDao.Entry>
    ) : RecyclerView.Adapter<EntryAdapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val particulars: TextView = view.findViewById(R.id.tvRowParticulars)
            val meta: TextView = view.findViewById(R.id.tvRowMeta)
            val moneyIn: TextView = view.findViewById(R.id.tvRowIn)
            val moneyOut: TextView = view.findViewById(R.id.tvRowOut)
            val balance: TextView = view.findViewById(R.id.tvRowBalance)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_ledger_row, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = entries[position]
            holder.particulars.text = entry.particulars
            holder.meta.text = listOf(pretty(entry.date), entry.reference)
                .filter { it.isNotBlank() }.joinToString("  ·  ")
            // A dash rather than 0.00 in the column this line did not move, so the
            // eye runs down the one that did.
            holder.moneyIn.text = if (entry.`in` > 0.0) money(entry.`in`) else "-"
            holder.moneyOut.text = if (entry.out > 0.0) money(entry.out) else "-"
            holder.balance.text = money(entry.balance)
        }

        override fun getItemCount() = entries.size
    }

    // ---- Formatting --------------------------------------------------------

    fun money(v: Double): String = "₹ " + String.format(Locale.US, "%.2f", v)

    /** "yyyy-MM-dd" (or a full timestamp) shown as "dd-MM-yyyy". */
    fun pretty(value: String): String = runCatching {
        SimpleDateFormat("dd-MM-yyyy", Locale.US)
            .format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value.take(10))!!)
    }.getOrDefault(value)
}
