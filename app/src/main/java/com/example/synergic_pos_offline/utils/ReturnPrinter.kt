package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.ReturnDao

/**
 * Sends a saved return to the printer.
 *
 * Both return screens end the same way - the goods are back, the return is filed,
 * and the customer wants the slip - so the printing lives here rather than being
 * written out twice.
 */
object ReturnPrinter {

    /** @param report told what happened, so a caller can toast it wherever it is */
    fun print(context: Context, result: ReturnDao.Result, report: (String) -> Unit) {
        // The BILL slot in md_printer is the source of truth (its paper width scales
        // the print); fall back to the legacy saved config only if it is unset.
        val config = ThermalPrinter.configForPurpose(context, "BILL")
            ?: ThermalPrinter.savedConfig(context)
        if (config == null) {
            PrinterSetup.show(context) { saved -> send(context, result, saved, report) }
            return
        }
        send(context, result, config, report)
    }

    private fun send(
        context: Context,
        result: ReturnDao.Result,
        config: ThermalPrinter.Config,
        report: (String) -> Unit
    ) {
        val cashier = SessionManager.currentUser?.userId?.uppercase() ?: "---"
        val capture = ReturnReceiptRenderer(context)
            .renderToBitmap(result, cashier, config.paperDots)
        if (capture == null) {
            report("Could not render the return receipt")
            return
        }
        ThermalPrinter.print(context, capture, config) { outcome ->
            report(
                when (outcome) {
                    is ThermalPrinter.Result.Success -> "Printed"
                    is ThermalPrinter.Result.Sent -> "Sent to printer"
                    is ThermalPrinter.Result.Failure -> "Print failed: ${outcome.message}"
                }
            )
        }
    }
}
