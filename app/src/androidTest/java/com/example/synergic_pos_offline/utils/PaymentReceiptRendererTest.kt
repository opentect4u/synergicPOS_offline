package com.example.synergic_pos_offline.utils

import android.graphics.Bitmap
import android.util.Log
import android.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The payment slip is rendered entirely off-screen, so nothing on any screen would
 * reveal it collapsing - it would simply reach the printer as blank paper. This
 * measures it and counts the ink, the same way [BillReceiptRendererTest] does for a
 * bill.
 */
@RunWith(AndroidJUnit4::class)
class PaymentReceiptRendererTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** Runs on the main thread, as the app does; view inflation is not thread-safe. */
    private fun <T> onMain(block: () -> T): T {
        var out: T? = null
        var err: Throwable? = null
        instrumentation.runOnMainSync {
            try { out = block() } catch (t: Throwable) { err = t }
        }
        err?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private val sample = PaymentReceiptRenderer.Receipt(
        receiptNumber = "AP000042",
        dateTime = "2026-07-29 14:35:07",
        cashier = "ADMIN",
        customerName = "Ramesh Kumar",
        customerPhone = "9876543210",
        customerGstin = "19AAACR1234F1ZQ",
        previousDue = 2500.0,
        amountPaid = 1500.0,
        totalDue = 1000.0,
        totalPaid = 8200.0,
        creditLimit = 6500.0,
        mode = "CASH"
    )

    /**
     * Paying past the due carries the balance below zero. The figures print exactly
     * as the arithmetic leaves them, so the slip has to stay legible with a negative
     * total due on it rather than collapsing or clipping the minus sign.
     */
    @Test
    fun rendersAnOverpaymentWithANegativeDue() {
        val themed = ContextThemeWrapper(context, R.style.Theme_Synergic_POS_Offline)
        val overpaid = sample.copy(
            receiptNumber = "AP000043",
            previousDue = 2500.0,
            amountPaid = 4000.0,
            totalDue = -1500.0,
            totalPaid = 10700.0,
            creditLimit = 9000.0
        )

        val bmp = onMain { PaymentReceiptRenderer(themed).renderToBitmap(overpaid, 576) }
        assertNotNull("overpayment render returned null", bmp)
        requireNotNull(bmp)

        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val ink = px.count { (it ushr 24) != 0 && (it and 0x00FFFFFF) != 0x00FFFFFF }

        File(context.filesDir, "payment_receipt_overpaid.png").also { f ->
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        Log.i("RENDERCHECK", "overpaid -> ${bmp.width}x${bmp.height}, inkPixels=$ink")

        assertTrue("overpayment render is blank (no ink)", ink > 500)
        assertTrue("overpayment collapsed to ${bmp.width}x${bmp.height}", bmp.height > bmp.width)
    }

    @Test
    fun rendersEachPaperWidthNonBlankAndSaves() {
        val themed = ContextThemeWrapper(context, R.style.Theme_Synergic_POS_Offline)
        val dir = context.filesDir
        val report = StringBuilder()

        for ((label, dots) in listOf("58mm" to 384, "80mm" to 576)) {
            val bmp = onMain { PaymentReceiptRenderer(themed).renderToBitmap(sample, dots) }
            assertNotNull("render returned null at $label", bmp)
            requireNotNull(bmp)

            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val ink = px.count { (it ushr 24) != 0 && (it and 0x00FFFFFF) != 0x00FFFFFF }

            File(dir, "payment_receipt_$label.png").also { f ->
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            report.append("$label -> ${bmp.width}x${bmp.height}, inkPixels=$ink\n")

            assertTrue("$label render is blank (no ink)", ink > 500)
            assertTrue("$label collapsed to ${bmp.width}x${bmp.height}", bmp.height > bmp.width)
        }

        File(dir, "payment_receipt_dims.txt").writeText(report.toString())
        Log.i("RENDERCHECK", "\n$report")
    }
}
