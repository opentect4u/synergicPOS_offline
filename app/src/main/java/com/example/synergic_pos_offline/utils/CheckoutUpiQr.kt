package com.example.synergic_pos_offline.utils

import android.graphics.Rect
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.BillSettingsDao
import java.util.Locale

/**
 * The scan-to-pay code on a checkout screen - view_checkout_upi_qr.xml, included
 * just above the confirm button on both the grocery and the restaurant tills.
 *
 * The counter and the printed bill want the same thing for different reasons. The
 * bill's code ([BillReceiptRenderer.renderUpiQr]) is a record the customer walks
 * out with; this one is live, redrawn as the total moves, so the screen can be
 * turned around and scanned before the sale is confirmed. Both are built the same
 * way and pay the same place - see [UpiQr] for why the amount has to be in the
 * code rather than typed by whoever scans it.
 *
 * ## Shown whenever there is a code to show
 *
 * One condition: a UPI ID set up in Bill Settings, and something to pay. Neither the
 * payment mode nor the "UPI QR on bill" switch is consulted.
 *
 * NOT THE PRINT SWITCH. That one decides what goes on PAPER. A shop turns it off to
 * save paper and clutter on every slip, and neither of those is a reason to take the
 * code off a screen - the details behind it are set up and in use either way.
 *
 * NOT THE MODE. It used to appear only once Online was picked, which is the wrong way
 * round: the code is how a customer DECIDES to pay by phone. Hidden until they had
 * already said so, it was only ever offered to somebody who had announced they did not
 * need it - and a customer reaching for their phone made the counter change the mode
 * first and turn the screen round second.
 */
object CheckoutUpiQr {

    /** Side of the drawn code, matching ivCheckoutUpiQr in the layout. */
    private const val QR_DP = 150

    // The two Bill Settings rows this needs, spelled as BillSettingsDao writes them.
    // Bill settings are stored under setting_type 'B'.
    private const val KEY_UPI_ID = "Bill Upi Id"
    private const val KEY_UPI_PAYEE_NAME = "Bill Upi Payee Name"

    /**
     * One bill setting: the login cache first, the database only if it has no answer.
     *
     * The settings table used to be read outright here, and that was affordable while
     * the code appeared for ONE payment mode - the read was skipped on every cash
     * sale. It is drawn for every sale now, and [bind] runs on each totals refresh,
     * which is once per keystroke in the tendered box. A full settings query behind
     * each of those is a query per character typed.
     *
     * Same two-step every other hot setting uses - see GeneralSettingsDao.isStockEnabled.
     * Blank and missing both come back null, so "not set up" is one case to the caller.
     */
    private fun setting(root: View, key: String): String? {
        SettingsCache.value(root.context, "B", key)?.takeIf { it.isNotBlank() }?.let { return it }
        val s = runCatching { BillSettingsDao(root.context).load() }.getOrNull() ?: return null
        return when (key) {
            KEY_UPI_ID -> s.upiId
            KEY_UPI_PAYEE_NAME -> s.upiPayeeName
            else -> ""
        }.takeIf { it.isNotBlank() }
    }

    /**
     * Draws the code for [amount], or hides the block.
     *
     * Safe to call on every totals refresh - which is what all three screens do, since
     * the amount is what the code is for. The URI it last drew is kept on the
     * ImageView, so a redraw that would produce the same code re-encodes nothing.
     */
    fun bind(root: View, amount: Double) {
        val block = root.findViewById<View>(R.id.llCheckoutUpiQr) ?: return
        val target = root.findViewById<ImageView>(R.id.ivCheckoutUpiQr) ?: return

        if (amount <= 0.0) {
            block.visibility = View.GONE
            return
        }
        val vpa = setting(root, KEY_UPI_ID)
        if (vpa == null || !UpiQr.isValidVpa(vpa)) {
            block.visibility = View.GONE
            return
        }

        val uri = UpiQr.payUri(vpa, setting(root, KEY_UPI_PAYEE_NAME).orEmpty(), amount)
        if (target.getTag(R.id.ivCheckoutUpiQr) != uri) {
            val px = (QR_DP * root.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val bitmap = UpiQr.bitmap(uri, px)
            if (bitmap == null) {
                block.visibility = View.GONE
                return
            }
            target.setImageBitmap(bitmap)
            target.setTag(R.id.ivCheckoutUpiQr, uri)
        }

        root.findViewById<TextView>(R.id.tvCheckoutUpiAmount)?.text =
            "₹ " + String.format(Locale.US, "%.2f", BillRounding.toPaise(amount))

        val wasHidden = block.visibility != View.VISIBLE
        block.visibility = View.VISIBLE
        // Only on the way in, never on a redraw: the code sits at the foot of a
        // column that usually has to scroll to reach it, and an operator who turns
        // the screen to a customer should not have to go looking for it first.
        // Scrolling again on every totals refresh would instead fight whoever is
        // reading the lines above it.
        if (wasHidden) block.post { block.requestRectangleOnScreen(Rect(0, 0, block.width, block.height), false) }
    }
}
