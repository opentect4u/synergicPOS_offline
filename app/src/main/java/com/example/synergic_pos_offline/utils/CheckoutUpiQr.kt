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
 * Shown only while Online is the chosen mode, and only when a UPI ID has been set
 * up in Bill Settings. It does not consult the "UPI QR on bill" switch: that one
 * decides what is *printed*, and a code on screen costs no paper.
 */
object CheckoutUpiQr {

    /** Side of the drawn code, matching ivCheckoutUpiQr in the layout. */
    private const val QR_DP = 150

    /**
     * Draws the code for [amount], or hides the block.
     *
     * [online] is the caller's answer to which mode is selected, so this stays out
     * of the business of knowing how each till names its payment methods.
     *
     * Safe to call on every totals refresh - which is what both screens do, since
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
        // Read only once Online is chosen, so the settings table is not queried on
        // every keystroke of a cash sale.
        val settings = runCatching { BillSettingsDao(root.context).load() }.getOrNull()
        val vpa = settings?.upiId.orEmpty()
        if (settings == null || !UpiQr.isValidVpa(vpa)) {
            block.visibility = View.GONE
            return
        }

        val uri = UpiQr.payUri(vpa, settings.upiPayeeName, amount)
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
