package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.BillWiseReportDao

/**
 * Sends a generated Bill Wise Report to the printer.
 *
 * Takes the report it is given rather than reading the period again: the operator
 * is printing what is on the screen in front of them, and a sale landing between
 * pressing Generate and pressing Print would otherwise put a bill on the paper that
 * was never in the report they checked.
 */
object BillWiseReportPrinter {

    /**
     * How many bills will be sent to a roll in one job.
     *
     * A bill takes two printed lines, so this is already about four metres of paper;
     * a month of a busy till would be hundreds of metres, which is not a report
     * anybody wants and not one a printer should be handed. It is also the point
     * past which the slip stops being a bitmap a tablet can hold - the whole report
     * is rendered to a single image before any of it is sent - and running out of
     * memory mid-job would be a worse answer than this one.
     */
    const val MAX_PRINTABLE_BILLS = 150

    /** @param result told what happened, so a caller can toast it wherever it is */
    fun print(context: Context, report: BillWiseReportDao.Report, result: (String) -> Unit) {
        if (report.billCount > MAX_PRINTABLE_BILLS) {
            result(
                "${report.billCount} bills is too many to print at once - " +
                    "narrow the date range to $MAX_PRINTABLE_BILLS or fewer"
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
        report: BillWiseReportDao.Report,
        config: ThermalPrinter.Config,
        result: (String) -> Unit
    ) {
        val printedBy = SessionManager.currentUser?.userId?.uppercase() ?: "---"
        val capture = BillWiseReportRenderer(context)
            .renderToBitmap(report, printedBy, config.paperDots)
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
