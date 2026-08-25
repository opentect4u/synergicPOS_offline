package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.BillDeleteDao
import com.example.synergic_pos_offline.database.BillSettingsDao
import com.example.synergic_pos_offline.utils.BillPrinter
import com.example.synergic_pos_offline.utils.BillReceiptRenderer
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.PrinterSetup
import com.example.synergic_pos_offline.utils.ReceiptContext
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

    /**
     * Whether this bill is being shown as a second copy of one already issued, and
     * so heads its preview and its print "DUPLICATE BILL". Set by Bill history; a
     * reprint of the sale just completed is not a duplicate of anything yet.
     */
    private val duplicate: Boolean get() = arguments?.getBoolean(ARG_DUPLICATE) == true

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = LayoutInflater
        // Inflated at a standard font scale, like the print - otherwise the preview
        // would grow and shrink with the device's text size while the paper did not,
        // and the two would stop being the same slip. See [ReceiptContext].
        .from(ReceiptContext.standardFontScale(requireContext()))
        // Whichever layout the till's Print Template is set to, so the bill screen
        // shows the slip that will come out of the printer - not a Standard one.
        .inflate(BillReceiptRenderer.layoutFor(requireContext()), container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Header fallbacks from arguments.
        val args = arguments
        args?.getString(ARG_BILL_NO)?.let { view.findViewById<TextView>(R.id.tvBillNo).text = "BILL NO: $it" }
        args?.getString(ARG_NAME)?.let { view.findViewById<TextView>(R.id.tvName).text = "NAME  : $it" }
        args?.getString(ARG_DATE)?.let { view.findViewById<TextView>(R.id.tvDate).text = it }
        // The time is the one header field with a setting behind it, so the fallback
        // has to obey it too: populate() below re-decides this from the bill's own
        // snapshot, but it only runs when there is a receipt to read, and a fallback
        // that ignored the setting would be a way round it on the path where it does
        // not. See BillSettingsDao.BillSettings.timeOnBill.
        val showTime = BillSettingsDao(requireContext()).load().timeOnBill
        view.findViewById<TextView>(R.id.tvTime).apply {
            val fallback = args?.getString(ARG_TIME).orEmpty()
            if (showTime) {
                if (fallback.isNotEmpty()) text = fallback
            } else {
                visibility = View.GONE
            }
        }
        // The grand total is no longer a static field - it is one line of the summary
        // block that populate() builds below, so there is nothing to pre-fill here.

        val receiptNo = args?.getLong(ARG_RECEIPT_NO, -1L) ?: -1L
        if (receiptNo > 0) {
            BillReceiptRenderer(requireContext()).populate(view, receiptNo, duplicate = duplicate)
        }

        view.findViewById<MaterialButton>(R.id.btnPrintBill).apply {
            backgroundTintList = ColorStateList.valueOf(ThemeManager.getThemeColor(requireContext()))
            setOnClickListener { printReceipt(view, receiptNo) }
        }

        view.findViewById<MaterialButton>(R.id.btnDeleteBill).apply {
            // Outlined and in the warning colour: it sits beside Print, and the two
            // must not be reachable by the same absent-minded tap.
            val danger = ContextCompat.getColor(requireContext(), R.color.menu_delete_icon)
            setTextColor(danger)
            strokeColor = ColorStateList.valueOf(danger)
            // Nothing to delete on a bill that was never saved, and nothing to do on
            // one already deleted - it still opens from Bill History's Cancelled list,
            // and a second Delete there could only fail.
            val gone = receiptNo > 0 &&
                BillDeleteDao(requireContext()).isDeleted(receiptNo)
            isEnabled = receiptNo > 0 && !gone
            visibility = if (gone) View.GONE else View.VISIBLE
            setOnClickListener { confirmDelete(receiptNo) }
        }
    }

    /**
     * Asks before deleting, and says what deleting means.
     *
     * Destructive and not undoable from the app, so it is worth a sentence: the bill
     * leaves the sales figures entirely and can afterwards only be found under
     * Cancelled bills in Bill History, and on the Void Bill Report.
     */
    private fun confirmDelete(receiptNo: Long) {
        val billNo = arguments?.getString(ARG_BILL_NO).orEmpty().ifBlank { "this bill" }
        DialogUtils.showConfirm(
            context = requireContext(),
            title = "Delete bill $billNo?",
            message = "It will be taken out of every sales report and total. You will " +
                "still find it under Cancelled bills in Bill History and on the Void " +
                "Bill Report.",
            positiveText = "Delete",
            destructive = true,
            onConfirm = { deleteBill(receiptNo) }
        )
    }

    private fun deleteBill(receiptNo: Long) {
        val outcome = BillDeleteDao(requireContext()).delete(receiptNo)
        if (!outcome.deleted) {
            // The reason, in a dialog rather than a toast: it names the document
            // standing in the way and what to do about it, which is more than a
            // message that disappears in two seconds can carry.
            DialogUtils.showSuccess(
                context = requireContext(),
                title = "Bill not deleted",
                message = outcome.reason ?: "The bill could not be deleted.",
                buttonText = "OK"
            ) {}
            return
        }
        toast("Bill deleted")
        // Back to wherever this was opened from - Bill History reloads on resume, so
        // the bill moves to the Cancelled list without anything else being asked.
        parentFragmentManager.popBackStack()
    }

    /**
     * Prints the receipt exactly as shown, and records that it happened.
     *
     * The print button is hidden from the capture: it floats over the receipt on
     * screen, and would otherwise be drawn onto the paper.
     */
    private fun printReceipt(root: View, receiptNo: Long) {
        val card = root.findViewById<View>(R.id.cardReceipt)
        val button = root.findViewById<View>(R.id.llBillActions)

        // The button floats over the receipt, so it would otherwise be captured
        // onto the paper. Restored right after: both captures are synchronous.
        button.visibility = View.GONE
        // Prefer the BILL slot in md_printer (its paper width scales the print);
        // fall back to the legacy saved config only if it is unset.
        val config = ThermalPrinter.configForPurpose(requireContext(), "BILL")
            ?: ThermalPrinter.savedConfig(requireContext())
        if (config == null) {
            // No printer set up yet - ask for it, then print once it is saved.
            button.visibility = View.VISIBLE
            showPrinterSetup(card, receiptNo)
            return
        }
        // Rendered off-screen at the paper's own width, not captured from the on-screen
        // card: the card here is laid out at the device's screen width regardless of
        // paper size, so capturing it and shrinking to fit would print 58mm as a
        // miniature of 80mm instead of at full-size wrapped text.
        val capture = BillReceiptRenderer(requireContext())
            .renderToBitmap(receiptNo, config.paperDots, duplicate = duplicate)
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
        toast("Printing to ${config.description}…")
        // Bill Settings' "Two Copy" toggle. Two separate jobs, and with the toggle on
        // they are two DIFFERENT slips - the original, then one stamped DUPLICATE.
        // See BillPrinter.copiesFor.
        //
        // [capture] is what this screen already rendered, so it is used as the first
        // copy rather than rendering the same thing again; only the second is new.
        // Falls back to it entirely when there is no saved bill to re-render from -
        // the preview of a sale that has not been written has no receipt number.
        val copies = if (receiptNo > 0) {
            BillPrinter.copiesFor(requireContext(), receiptNo, config.paperDots, duplicate)
                .ifEmpty { listOf(capture) }
        } else {
            val twoCopy = BillSettingsDao(requireContext()).load().twoCopyBill
            if (twoCopy) listOf(capture, capture) else listOf(capture)
        }
        ThermalPrinter.printSequence(requireContext(), copies, config) { result ->
            // The fragment may be gone by the time the printer answers.
            if (!isAdded) return@printSequence
            when (result) {
                is ThermalPrinter.Result.Success -> {
                    toast("Printed")
                    if (receiptNo > 0) BillReceiptRenderer.recordPrint(requireContext(), receiptNo, duplicate)
                }
                // The printer took the receipt but does not report back, so say what
                // is actually known rather than claiming paper came out.
                is ThermalPrinter.Result.Sent -> {
                    toast("Sent to printer")
                    if (receiptNo > 0) BillReceiptRenderer.recordPrint(requireContext(), receiptNo, duplicate)
                }
                is ThermalPrinter.Result.Failure -> showPrintFailed(result.message, receiptNo, config)
            }
        }
    }

    /**
     * Offers a way out when the printer cannot be reached: correct the address, or
     * fall back to the system print dialog so the sale is not held up by hardware.
     *
     * What to go and check depends on how the printer is attached, so [config] is
     * passed in rather than the advice being the same either way - "on the same
     * network" is no help to someone whose printer is on a cable.
     */
    private fun showPrintFailed(message: String, receiptNo: Long, config: ThermalPrinter.Config) {
        val card = view?.findViewById<View>(R.id.cardReceipt) ?: return
        val checkThis = if (config.isUsb) {
            "Check the printer is powered on and its USB cable is properly connected"
        } else {
            "Check the printer is powered on and on the same network"
        }
        DialogUtils.showConfirm(
            context = requireContext(),
            title = "Printer not reachable",
            message = "$message\n\n$checkThis, or print another way.",
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
            if (receiptNo > 0) BillReceiptRenderer.recordPrint(requireContext(), receiptNo, duplicate)
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
            // The printer is saved after the dialog closes, by when the operator may
            // have left this screen; use the current context and bail rather than
            // crash on requireContext().
            val ctx = context ?: return@show
            val capture = BillReceiptRenderer(ctx)
                .renderToBitmap(receiptNo, config.paperDots, duplicate = duplicate)
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
        private const val ARG_DUPLICATE = "duplicate"

        /**
         * Opens the receipt. When [receiptNo] > 0 the bill is loaded live from the
         * database; the remaining values act as a header fallback.
         *
         * [duplicate] heads the bill "DUPLICATE BILL", on screen and on paper - what
         * Bill history opens, since the customer already has the original.
         */
        fun newInstance(
            billNo: String, name: String, date: String, time: String, total: String,
            receiptNo: Long = -1L,
            duplicate: Boolean = false
        ): BillFragment = BillFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_RECEIPT_NO, receiptNo)
                putBoolean(ARG_DUPLICATE, duplicate)
                putString(ARG_BILL_NO, billNo)
                putString(ARG_NAME, name)
                putString(ARG_DATE, date)
                putString(ARG_TIME, time)
                putString(ARG_TOTAL, total)
            }
        }
    }
}
