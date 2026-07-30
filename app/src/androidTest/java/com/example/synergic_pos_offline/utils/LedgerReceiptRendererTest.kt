package com.example.synergic_pos_offline.utils

import android.graphics.Bitmap
import android.util.Log
import android.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.CustomerDao
import com.example.synergic_pos_offline.database.CustomerLedgerDao
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The printed ledger is composed off-screen, so nothing in the app would show it
 * collapsing - it would just come off the roll blank. This measures it and counts
 * the ink.
 */
@RunWith(AndroidJUnit4::class)
class LedgerReceiptRendererTest {

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

    private val customer = CustomerDao.Customer(
        id = 1L,
        name = "Ramesh Kumar",
        address = "12 Market Road",
        phone = "9876543210",
        gstin = "19AAACR1234F1ZQ",
        creditEnabled = true,
        creditLimit = 6500.0,
        balance = 3300.0
    )

    private val ledger = CustomerLedgerDao.Ledger(
        customer = customer,
        fromDate = "2026-07-01",
        toDate = "2026-07-31",
        opening = 1200.0,
        entries = listOf(
            CustomerLedgerDao.Entry("2026-07-04", "Credit sale", "Bill A-118", 0.0, 2500.0, 3700.0),
            CustomerLedgerDao.Entry("2026-07-12", "Payment received", "", 1500.0, 0.0, 2200.0),
            CustomerLedgerDao.Entry("2026-07-21", "Credit sale", "Bill A-143", 0.0, 1600.0, 3800.0),
            CustomerLedgerDao.Entry("2026-07-28", "Payment received", "", 500.0, 0.0, 3300.0)
        ),
        totalIn = 2000.0,
        totalOut = 4100.0,
        closing = 3300.0
    )

    @Test
    fun rendersEachPaperWidthNonBlankAndSaves() {
        val themed = ContextThemeWrapper(context, R.style.Theme_Synergic_POS_Offline)
        val report = StringBuilder()

        for ((label, dots) in listOf("58mm" to 384, "80mm" to 576)) {
            val bmp = onMain { LedgerReceiptRenderer(themed).renderToBitmap(ledger, "ADMIN", dots) }
            assertNotNull("render returned null at $label", bmp)
            requireNotNull(bmp)

            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val ink = px.count { (it ushr 24) != 0 && (it and 0x00FFFFFF) != 0x00FFFFFF }

            File(context.filesDir, "ledger_$label.png").also { f ->
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            report.append("$label -> ${bmp.width}x${bmp.height}, inkPixels=$ink\n")

            assertTrue("$label render is blank (no ink)", ink > 500)
            assertTrue("$label collapsed to ${bmp.width}x${bmp.height}", bmp.height > bmp.width)
        }

        Log.i("RENDERCHECK", "\n$report")
    }

    /**
     * The narrowest paper with the widest figures - where a money column too small
     * for its number makes Android break the number in half. Saved for eyeballing
     * because a wrap is a layout fault, not something an assertion sees.
     */
    @Test
    fun rendersWideFiguresOnNarrowPaper() {
        val themed = ContextThemeWrapper(context, R.style.Theme_Synergic_POS_Offline)
        val big = ledger.copy(
            opening = 84500.0,
            entries = listOf(
                CustomerLedgerDao.Entry("2026-07-04", "Credit sale", "Bill A-118", 0.0, 125000.0, 209500.0),
                CustomerLedgerDao.Entry("2026-07-12", "Payment received", "", 118750.0, 0.0, 90750.0)
            ),
            totalIn = 118750.0,
            totalOut = 125000.0,
            closing = 90750.0
        )

        val bmp = onMain { LedgerReceiptRenderer(themed).renderToBitmap(big, "ADMIN", 384) }
        assertNotNull("wide-figure render returned null", bmp)
        requireNotNull(bmp)

        File(context.filesDir, "ledger_wide_58mm.png").also { f ->
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue("wide figures collapsed to ${bmp.width}x${bmp.height}", bmp.height > bmp.width)
    }

    /** An account with no movement in the period still has to state its balances. */
    @Test
    fun rendersAnEmptyPeriod() {
        val themed = ContextThemeWrapper(context, R.style.Theme_Synergic_POS_Offline)
        val quiet = ledger.copy(entries = emptyList(), totalIn = 0.0, totalOut = 0.0, closing = 1200.0)

        val bmp = onMain { LedgerReceiptRenderer(themed).renderToBitmap(quiet, "ADMIN", 576) }
        assertNotNull("empty-period render returned null", bmp)
        requireNotNull(bmp)

        File(context.filesDir, "ledger_empty.png").also { f ->
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue("empty period collapsed to ${bmp.width}x${bmp.height}", bmp.height > bmp.width)
    }
}
