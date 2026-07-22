package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.BillReceiptRenderer
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.PrinterSetup
import com.example.synergic_pos_offline.utils.ThermalPrinter
import com.example.synergic_pos_offline.utils.ReceiptPrinter
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton

/**
 * Read-only bill / receipt preview, styled like a thermal print-out. When a
 * receipt number is supplied it is populated live from the bill tables by
 * [BillReceiptRenderer]; otherwise it falls back to the header values passed in
 * the arguments.
 */
class BillFragment : Fragment(), TitledScreen {

    override val screenTitle = "Bill"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_bill, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Header fallbacks from arguments.
        val args = arguments
        args?.getString(ARG_BILL_NO)?.let { view.findViewById<TextView>(R.id.tvBillNo).text = "BILL NO: $it" }
        args?.getString(ARG_NAME)?.let { view.findViewById<TextView>(R.id.tvName).text = "NAME  : $it" }
        args?.getString(ARG_DATE)?.let { view.findViewById<TextView>(R.id.tvDate).text = it }
        args?.getString(ARG_TIME)?.let { view.findViewById<TextView>(R.id.tvTime).text = it }
        args?.getString(ARG_TOTAL)?.let { view.findViewById<TextView>(R.id.tvBillGrandTotal).text = it }

        val receiptNo = args?.getLong(ARG_RECEIPT_NO, -1L) ?: -1L
        if (receiptNo > 0) {
            BillReceiptRenderer(requireContext()).populate(view, receiptNo)
        }

        view.findViewById<MaterialButton>(R.id.btnPrintBill).apply {
            backgroundTintList = ColorStateList.valueOf(ThemeManager.getThemeColor(requireContext()))
            setOnClickListener { printReceipt(view, receiptNo) }
        }
    }

    /**
     * Prints the receipt exactly as shown, and records that it happened.
     *
     * The print button is hidden from the capture: it floats over the receipt on
     * screen, and would otherwise be drawn onto the paper.
     */
    private fun printReceipt(root: View, receiptNo: Long) {
        val card = root.findViewById<View>(R.id.cardReceipt)
        val button = root.findViewById<View>(R.id.btnPrintBill)

        // The button floats over the receipt, so it would otherwise be captured
        // onto the paper. Restored right after: both captures are synchronous.
        button.visibility = View.GONE
        val config = ThermalPrinter.savedConfig(requireContext())
        if (config == null) {
            // No printer set up yet - ask for it, then print once it is saved.
            button.visibility = View.VISIBLE
            showPrinterSetup(card, receiptNo)
            return
        }
        val capture = ReceiptPrinter.capture(card)
        button.visibility = View.VISIBLE

        if (capture == null) {
            toast("Could not render the receipt")
            return
        }
        sendToThermalPrinter(capture, config, receiptNo)
    }

    private fun sendToThermalPrinter(
        capture: android.graphics.Bitmap,
        config: ThermalPrinter.Config,
        receiptNo: Long
    ) {
        toast("Printing to ${config.ip}…")
        ThermalPrinter.print(requireContext(), capture, config) { result ->
            // The fragment may be gone by the time the printer answers.
            if (!isAdded) return@print
            when (result) {
                is ThermalPrinter.Result.Success -> {
                    toast("Printed")
                    if (receiptNo > 0) BillReceiptRenderer.recordPrint(requireContext(), receiptNo)
                }
                // The printer took the receipt but does not report back, so say what
                // is actually known rather than claiming paper came out.
                is ThermalPrinter.Result.Sent -> {
                    toast("Sent to printer")
                    if (receiptNo > 0) BillReceiptRenderer.recordPrint(requireContext(), receiptNo)
                }
                is ThermalPrinter.Result.Failure -> showPrintFailed(result.message, receiptNo)
            }
        }
    }

    /**
     * Offers a way out when the printer cannot be reached: correct the address, or
     * fall back to the system print dialog so the sale is not held up by hardware.
     */
    private fun showPrintFailed(message: String, receiptNo: Long) {
        val card = view?.findViewById<View>(R.id.cardReceipt) ?: return
        DialogUtils.showConfirm(
            context = requireContext(),
            title = "Printer not reachable",
            message = "$message\n\nCheck the printer is powered on and on the same " +
                "network, or print another way.",
            positiveText = "Printer settings",
            negativeText = "Use system print",
            iconRes = android.R.drawable.ic_dialog_alert,
            destructive = false,
            onConfirm = { showPrinterSetup(card, receiptNo) },
            onCancel = { systemPrint(card, receiptNo) }
        )
    }

    /** Android's own print stack: any configured printer, or Save as PDF. */
    private fun systemPrint(card: View, receiptNo: Long) {
        val billNumber = (view?.findViewById<TextView>(R.id.tvBillNo)?.text ?: "")
            .toString().removePrefix("BILL NO:").trim().ifEmpty { "receipt" }
        ReceiptPrinter.print(requireContext(), card, billNumber) {
            if (receiptNo > 0) BillReceiptRenderer.recordPrint(requireContext(), receiptNo)
        }
    }

    /**
     * Asks for the printer's address on the WiFi network, then prints.
     *
     * The printer's own IP is what is wanted here - most ESC/POS units show it on a
     * self-test slip held down at power-on - and 9100 is the near-universal port.
     */
    private fun showPrinterSetup(card: View, receiptNo: Long) {
        PrinterSetup.show(requireContext()) { config ->
            val capture = ReceiptPrinter.capture(card)
            if (capture == null) toast("Could not render the receipt")
            else sendToThermalPrinter(capture, config, receiptNo)
        }
    }

    private fun toast(message: String) =
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val ARG_RECEIPT_NO = "receipt_no"
        private const val ARG_BILL_NO = "bill_no"
        private const val ARG_NAME = "name"
        private const val ARG_DATE = "date"
        private const val ARG_TIME = "time"
        private const val ARG_TOTAL = "total"

        /**
         * Opens the receipt. When [receiptNo] > 0 the bill is loaded live from the
         * database; the remaining values act as a header fallback.
         */
        fun newInstance(
            billNo: String, name: String, date: String, time: String, total: String,
            receiptNo: Long = -1L
        ): BillFragment = BillFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_RECEIPT_NO, receiptNo)
                putString(ARG_BILL_NO, billNo)
                putString(ARG_NAME, name)
                putString(ARG_DATE, date)
                putString(ARG_TIME, time)
                putString(ARG_TOTAL, total)
            }
        }
    }
}
