package com.example.synergic_pos_offline.utils

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.ReturnDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The return slip is composed off-screen like every other receipt, so nothing in
 * the app would show it collapsing - it would just come off the roll blank.
 */
@RunWith(AndroidJUnit4::class)
class ReturnReceiptRendererTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

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

    private fun themed() = ContextThemeWrapper(context, R.style.Theme_Synergic_POS_Offline)

    private fun line(
        name: String, qty: Double, rate: Double,
        discount: Double, cgst: Double, sgst: Double, amount: Double
    ) = ReturnDao.ReturnLine(
        productId = 1L, billItemId = null, name = name, quantity = qty, rate = rate,
        cgstRate = 2.5, sgstRate = 2.5, vatRate = 0.0,
        gross = qty * rate, discount = discount, taxable = qty * rate - discount,
        cgst = cgst, sgst = sgst, vat = 0.0, amount = amount
    )

    /** An item-wise return: no bill behind it. */
    private val itemWise = ReturnDao.Result(
        id = 1L, returnNumber = "RT000012", dateTime = "2026-07-29 17:20:00",
        originalBillNumber = null,
        lines = listOf(line("Orange Juice 1L", 2.0, 90.0, 0.0, 4.5, 4.5, 189.0)),
        totalGross = 180.0, totalDiscount = 0.0,
        totalCgst = 4.5, totalSgst = 4.5, totalVat = 0.0, totalAmount = 189.0
    )

    /** A bill-wise return: names the bill it came off, and carries a discount. */
    private val billWise = itemWise.copy(
        returnNumber = "RT000013",
        originalBillNumber = "A-118",
        lines = listOf(
            line("Orange Juice 1L", 2.0, 90.0, 18.0, 4.05, 4.05, 170.10),
            line("Basmati Rice 5kg", 1.0, 1250.0, 0.0, 31.25, 31.25, 1312.50)
        ),
        totalGross = 1430.0, totalDiscount = 18.0,
        totalCgst = 35.30, totalSgst = 35.30, totalVat = 0.0, totalAmount = 1482.60
    )

    @Test
    fun rendersEachPaperWidthNonBlankAndSaves() {
        for ((label, dots) in listOf("58mm" to 384, "80mm" to 576)) {
            val bmp = onMain { ReturnReceiptRenderer(themed()).renderToBitmap(billWise, "ADMIN", dots) }
            assertNotNull("render returned null at $label", bmp)
            requireNotNull(bmp)

            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val ink = px.count { (it ushr 24) != 0 && (it and 0x00FFFFFF) != 0x00FFFFFF }

            File(context.filesDir, "return_$label.png").also { f ->
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            assertTrue("$label render is blank (no ink)", ink > 500)
            assertTrue("$label collapsed to ${bmp.width}x${bmp.height}", bmp.height > bmp.width)
        }
    }

    /** An item-wise slip has no bill to name, and must not print an empty line for one. */
    @Test
    fun anItemWiseSlipRendersWithoutAnOriginalBill() {
        val bmp = onMain { ReturnReceiptRenderer(themed()).renderToBitmap(itemWise, "ADMIN", 576) }
        assertNotNull("item-wise render returned null", bmp)
        requireNotNull(bmp)

        File(context.filesDir, "return_itemwise.png").also { f ->
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue("item-wise slip collapsed", bmp.height > bmp.width)
    }

    /** Like every receipt, it must not follow the device's text size. */
    @Test
    fun theSlipHoldsItsSizeAtAnyDeviceFontScale() {
        val sizes = listOf(1f, 2f).map { scale ->
            val ctx = ContextThemeWrapper(context, R.style.Theme_Synergic_POS_Offline).apply {
                applyOverrideConfiguration(Configuration().apply { fontScale = scale })
            }
            val bmp = onMain { ReturnReceiptRenderer(ctx).renderToBitmap(billWise, "ADMIN", 576) }
            requireNotNull(bmp)
            bmp.width to bmp.height
        }
        assertEquals("the return slip changed size with the device font scale", sizes[0], sizes[1])
    }
}
