package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.StockReportDao

/**
 * Sends a generated Stock Report to the printer.
 *
 * Takes the report it is given rather than reading the shelf again: the operator is
 * printing the count they just looked at, and stock moves with every sale.
 */
object StockReportPrinter {

    /**
     * How many item lines will be sent to a roll in one job.
     *
     * A line per item, and the whole report is rendered to a single bitmap before
     * any of it is sent - a catalogue of thousands would be metres of paper and more
     * memory than a till has.
     */
    const val MAX_PRINTABLE_ITEMS = 300

    /** @param result told what happened, so a caller can toast it wherever it is */
    fun print(context: Context, report: StockReportDao.Report, result: (String) -> Unit) {
        if (report.itemCount > MAX_PRINTABLE_ITEMS) {
            result(
                "${report.itemCount} items is too many to print at once - " +
                    "$MAX_PRINTABLE_ITEMS is the most this will send to a roll"
            )
            return
        }
        // The BILL slot is the source of truth (its paper width scales the print);
        // the legacy saved config is the fallback, as everywhere else that prints.
        val config = ThermalPrinter.configForPurpose(context, "BILL")
            ?: ThermalPrinter.savedConfig(context)
        if (config == null) {
            PrinterSetup.show(context) { saved -> send(context, report, saved, result) }
            return
        }
        send(context, report, config, result)
    }

    private fun send(
        context: Context,
        report: StockReportDao.Report,
        config: ThermalPrinter.Config,
        result: (String) -> Unit
    ) {
        val printedBy = SessionManager.currentUser?.userId?.uppercase() ?: "---"
        val capture = StockReportRenderer(context).renderToBitmap(report, printedBy, config.paperDots)
        if (capture == null) {
            result("Could not render the report")
            return
        }
        ThermalPrinter.print(context, capture, config) { outcome ->
            result(
                when (outcome) {
                    is ThermalPrinter.Result.Success -> "Printed"
                    is ThermalPrinter.Result.Sent -> "Sent to printer"
                    is ThermalPrinter.Result.Failure -> "Print failed: ${outcome.message}"
                }
            )
        }
    }
}
