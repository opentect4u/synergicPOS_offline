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
 * ## Shown for ONLINE, and only then
 *
 * Three conditions: Online is the chosen payment mode, a UPI ID is set up in Bill
 * Settings, and there is something to pay. It briefly appeared for every mode; that
 * put a scan-to-pay code in front of a customer paying cash, on a screen the counter
 * turns round to them, which is an invitation to pay a bill that is being settled
 * another way. Choosing Online is the customer saying they are paying by phone, and
 * that is the moment the code is wanted.
 *
 * NOT THE PRINT SWITCH, though. "UPI QR on bill" decides what goes on PAPER - a shop
 * turns it off to save paper and clutter on every slip, and neither of those is a
 * reason to withhold the code from somebody who has just said they want to scan it.
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
     * [bind] runs on every totals refresh - once per keystroke in the tendered box -
     * so a full settings query behind each one is a query per character typed. The
     * Online gate keeps most of those away, but a customer paying online types into
     * that box too, and the cache costs nothing to read.
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
     * [online] is the caller's answer to whether Online is the chosen mode, so this
     * stays out of the business of knowing how each till names its payment methods -
     * they do not agree ("Online" here, Method.ONLINE there).
     *
     * Safe to call on every totals refresh - which is what all three screens do, since
     * the amount is what the code is for. The URI it last drew is kept on the
     * ImageView, so a redraw that would produce the same code re-encodes nothing.
     */
    fun bind(root: View, amount: Double, online: Boolean) {
        val block = root.findViewById<View>(R.id.llCheckoutUpiQr) ?: return
        val target = root.findViewById<ImageView>(R.id.ivCheckoutUpiQr) ?: return

        if (!online || amount <= 0.0) {
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
