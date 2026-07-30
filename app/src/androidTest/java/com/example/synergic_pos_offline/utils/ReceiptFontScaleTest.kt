package com.example.synergic_pos_offline.utils

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.BillDao
import com.example.synergic_pos_offline.database.CustomerDao
import com.example.synergic_pos_offline.database.CustomerLedgerDao
import com.example.synergic_pos_offline.database.DatabaseHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * A receipt is laid out against a fixed paper width, so its text must not follow
 * the device's font size: a tablet set to large text would otherwise print a bill
 * whose columns no longer fit the roll.
 *
 * Each renderer is driven from a context pretending the device is set to double and
 * to half text, and the slip has to come out byte-for-byte the size it does at
 * normal text.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptFontScaleTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private var receiptNo = -1L

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

    /** The app's context as it would be on a device set to [scale] text size. */
    private fun atFontScale(scale: Float): Context =
        ContextThemeWrapper(context, R.style.Theme_Synergic_POS_Offline).apply {
            applyOverrideConfiguration(Configuration().apply { fontScale = scale })
        }

    /** The scales a device can realistically be set to, either side of normal. */
    private val scales = listOf(0.85f, 1f, 1.3f, 2f)

    @Before
    fun makeABill() {
        val result = BillDao(context).createBill(
            BillDao.NewBill(
                billType = "CASH",
                customerId = null,
                items = listOf(
                    BillDao.Item(productId = null, name = "Test item one", quantity = 2.0, rate = 250.0),
                    BillDao.Item(productId = null, name = "Test item two", quantity = 1.0, rate = 125.0)
                ),
                payment = BillDao.Payment(mode = "CASH", amountPaid = 625.0),
                totalPrice = 625.0, discountAmount = 0.0, discountPercentage = 0.0,
                cgstAmount = 0.0, sgstAmount = 0.0, netAmount = 625.0
            )
        )
        requireNotNull(result) { "createBill returned null" }
        receiptNo = result.receiptNo
    }

    @After
    fun cleanUp() {
        val db = DatabaseHelper.getInstance(context).writableDatabase
        if (receiptNo > 0) {
            db.delete(DatabaseHelper.Tables.TD_PAYMENTS, "bill_id=?", arrayOf(receiptNo.toString()))
            db.delete(DatabaseHelper.Tables.TD_BILL_ITEMS, "bill_id=?", arrayOf(receiptNo.toString()))
            db.delete(DatabaseHelper.Tables.TD_BILLS, "receipt_no=?", arrayOf(receiptNo.toString()))
        }
    }

    /** Renders at every scale and asserts the size never moves. */
    private fun assertSizeHolds(label: String, render: (Context) -> Bitmap?) {
        val sizes = scales.map { scale ->
            val bmp = onMain { render(atFontScale(scale)) }
            assertNotNull("$label render returned null at font scale $scale", bmp)
            requireNotNull(bmp)
            if (scale == 2f) {
                File(context.filesDir, "fontscale_${label}_2x.png").also { f ->
                    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            scale to (bmp.width to bmp.height)
        }

        val normal = sizes.first { it.first == 1f }.second
        sizes.forEach { (scale, size) ->
            assertEquals(
                "$label changed size at font scale $scale: $size vs $normal at normal text",
                normal, size
            )
        }
        assertTrue("$label collapsed", normal.second > normal.first)
    }

    /**
     * Guards the three tests below from passing for the wrong reason.
     *
     * They all assert that something does *not* change with the font scale, which is
     * exactly what a broken harness would report too. This checks the opposite
     * directly: an `sp`-sized view built from these contexts really does grow, so a
     * receipt that holds its size is holding it against a scale that was applied.
     */
    @Test
    fun theHarnessReallyChangesTheFontScale() {
        val heights = listOf(1f, 2f).map { scale ->
            onMain {
                val text = android.widget.TextView(atFontScale(scale)).apply {
                    text = "Receipt"
                    textSize = 13f
                }
                text.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
                    android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
                )
                text.measuredHeight
            }
        }
        assertTrue(
            "the test harness does not actually change the font scale ($heights)",
            heights[1] > heights[0]
        )
    }

    @Test
    fun theBillHoldsItsSizeAtAnyDeviceFontScale() {
        assertSizeHolds("bill") { ctx -> BillReceiptRenderer(ctx).renderToBitmap(receiptNo, 576) }
    }

    @Test
    fun thePaymentReceiptHoldsItsSizeAtAnyDeviceFontScale() {
        val receipt = PaymentReceiptRenderer.Receipt(
            receiptNumber = "AP000042", dateTime = "2026-07-29 14:35:07", cashier = "ADMIN",
            customerName = "Ramesh Kumar", customerPhone = "9876543210", customerGstin = "",
            previousDue = 2500.0, amountPaid = 1500.0, totalDue = 1000.0,
            totalPaid = 8200.0, creditLimit = 6500.0, mode = "CASH"
        )
        assertSizeHolds("payment") { ctx -> PaymentReceiptRenderer(ctx).renderToBitmap(receipt, 576) }
    }

    @Test
    fun theLedgerHoldsItsSizeAtAnyDeviceFontScale() {
        val ledger = CustomerLedgerDao.Ledger(
            customer = CustomerDao.Customer(
                id = 1L, name = "Ramesh Kumar", address = "", phone = "9876543210",
                gstin = "", creditEnabled = true, creditLimit = 6500.0, balance = 3300.0
            ),
            fromDate = "2026-07-01", toDate = "2026-07-31", opening = 1200.0,
            entries = listOf(
                CustomerLedgerDao.Entry("2026-07-04", "Credit sale", "Bill A-118", 0.0, 2500.0, 3700.0),
                CustomerLedgerDao.Entry("2026-07-12", "Payment received", "", 1500.0, 0.0, 2200.0)
            ),
            totalIn = 1500.0, totalOut = 2500.0, closing = 2200.0
        )
        assertSizeHolds("ledger") { ctx -> LedgerReceiptRenderer(ctx).renderToBitmap(ledger, "ADMIN", 576) }
    }
}
