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
     * The slips one press of Print should produce, in the order they come out.
     *
     * WITH TWO COPY OFF this is one slip, captioned as [duplicate] says.
     *
     * WITH TWO COPY ON an ORIGINAL is TWO DIFFERENT RENDERS, not one render sent
     * twice: the first carries the bill's own number and caption, the second is
     * stamped DUPLICATE. That is what the two copies are for - one goes to the
     * customer and one stays with the shop - and a pair of identical originals is two
     * documents that each claim to be the bill. Which is a real problem on a return:
     * the copy the shop kept is indistinguishable from the one the customer brings
     * back.
     *
     * ## A DUPLICATE IS ONE SLIP, whatever Two Copy says
     *
     * The setting is about the sale, not about the printer. A sale produces a pair
     * because there are two parties to it and each keeps one; that pairing happens
     * once, when the bill is issued, and the shop's half is already in the shop.
     *
     * A reprint from Bill History is somebody asking for another copy of a bill that
     * was issued long ago. They asked for A copy. Handing back two put a second
     * unaccounted DUPLICATE of the same sale into circulation every time anybody
     * reprinted anything - and spent twice the paper doing it.
     *
     * Rendering twice costs a second pass over the same bill, which is a fraction of
     * what the printer then spends putting it on paper.
     */
    fun copiesFor(
        context: Context,
        receiptNo: Long,
        paperDots: Int,
        duplicate: Boolean
    ): List<android.graphics.Bitmap> {
        val renderer = BillReceiptRenderer(context)

        // A sale mixing VAT-rated and GST-rated goods still prints as ONE slip - see
        // BillReceiptRenderer.populate()'s own demarcation, "BILL NO: NA" partway
        // down the item table, each half carrying its own summary and one grand
        // total over both at the foot of the whole thing. Not cut into two separate
        // pieces of paper: they are one sale, rung up at one counter at one moment.
        val first = renderer.renderToBitmap(receiptNo, paperDots, duplicate = duplicate)
            ?: return emptyList()
        // A duplicate is one slip and stops here: Two Copy pairs a SALE, and this sale
        // was paired when it was rung up. See the note above.
        val twoCopy = !duplicate && BillSettingsDao(context).load().twoCopyBill
        val bill = if (!twoCopy) listOf(first) else {
            // The shop's copy. Falls back to a second of the first if it cannot be
            // rendered: two copies unmarked is a smaller failure than one copy where
            // the shop was told to expect two.
            val second = renderer.renderToBitmap(receiptNo, paperDots, duplicate = true) ?: first
            listOf(first, second)
        }
        // The counter coupons, after every copy of the bill. Last because they are
        // what gets torn off and handed out: the customer keeps the bill and walks
        // the coupons round the counters, so the coupons want to be the loose end of
        // the run rather than buried between two copies of the same document.
        //
        // Added here rather than at each print button because this is the one place
        // that answers "what should one press of Print produce" - see the callers.
        return bill + CouponPrinter.couponsFor(context, receiptNo, paperDots)
    }

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
        val copies = copiesFor(context, receiptNo, config.paperDots, duplicate)
        if (copies.isEmpty()) {
            report("Could not render the receipt")
            return
        }
        ThermalPrinter.printSequence(context, copies, config) { result ->
            when (result) {
                is ThermalPrinter.Result.Success -> {
                    report("Printed")
                    BillReceiptRenderer.recordPrint(context, receiptNo, duplicate)
                }
                // The printer took the receipt but does not report back, so say what
                // is actually known rather than claiming paper came out.
                is ThermalPrinter.Result.Sent -> {
                    report("Sent to printer")
                    BillReceiptRenderer.recordPrint(context, receiptNo, duplicate)
                }
                is ThermalPrinter.Result.Failure -> report("Print failed: ${result.message}")
            }
        }
    }
}
