package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.BillSettingsDao

/**
 * Prints a saved bill without one being on screen.
 *
 * The bill screen has its own print path, with recovery UI for a printer that
 * cannot be reached; this is the plain version, for printing straight from a list.
 * It still goes through the same renderer, honours the same "Two Copy" setting and
 * records the print the same way, so a slip that comes off here is the one the
 * bill screen would have produced.
 */
object BillPrinter {

    /**
     * @param duplicate marks the slip as a copy of one already issued - see
     *        [BillReceiptRenderer.populate]
     * @param report told what happened, so a caller can toast it wherever it is
     */
    fun print(context: Context, receiptNo: Long, duplicate: Boolean, report: (String) -> Unit) {
        if (receiptNo <= 0) {
            report("This bill cannot be printed")
            return
        }
        // The BILL slot in md_printer is the source of truth (its paper width scales
        // the print); fall back to the legacy saved config only if it is unset.
        val config = ThermalPrinter.configForPurpose(context, "BILL")
            ?: ThermalPrinter.savedConfig(context)
        if (config == null) {
            PrinterSetup.show(context) { saved -> send(context, receiptNo, duplicate, saved, report) }
            return
        }
        send(context, receiptNo, duplicate, config, report)
    }

    private fun send(
        context: Context,
        receiptNo: Long,
        duplicate: Boolean,
        config: ThermalPrinter.Config,
        report: (String) -> Unit
    ) {
        // Rendered off-screen at the paper's own width rather than captured from a
        // view: there is no view here, and a capture scaled to fit would print 58mm
        // as a miniature of 80mm instead of at full size with more wrapping.
        val capture = BillReceiptRenderer(context)
            .renderToBitmap(receiptNo, config.paperDots, duplicate = duplicate)
        if (capture == null) {
            report("Could not render the receipt")
            return
        }
        val copies = if (BillSettingsDao(context).load().twoCopyBill) 2 else 1
        ThermalPrinter.printCopies(context, capture, config, copies) { result ->
            when (result) {
                is ThermalPrinter.Result.Success -> {
                    report("Printed")
                    BillReceiptRenderer.recordPrint(context, receiptNo)
                }
                // The printer took the receipt but does not report back, so say what
                // is actually known rather than claiming paper came out.
                is ThermalPrinter.Result.Sent -> {
                    report("Sent to printer")
                    BillReceiptRenderer.recordPrint(context, receiptNo)
                }
                is ThermalPrinter.Result.Failure -> report("Print failed: ${result.message}")
            }
        }
    }
}
