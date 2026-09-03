package com.example.synergic_pos_offline.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.CustomerDao
import com.example.synergic_pos_offline.database.CustomerLedgerDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The ledger block is shared between the report screen and this dialog, and it
 * sizes its movements list by weight. A weighted child needs a bounded parent, so
 * this lays the dialog out at a real window size and checks the list actually got
 * height rather than collapsing to nothing behind the buttons.
 */
@RunWith(AndroidJUnit4::class)
class CustomerLedgerDialogLayoutTest {

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

    private val ledger = CustomerLedgerDao.Ledger(
        customer = CustomerDao.Customer(
            id = 1L, name = "Ramesh Kumar", address = "", phone = "9876543210",
            gstin = "19AAACR1234F1ZQ", creditEnabled = true, creditLimit = 6500.0,
            balance = 3300.0
        ),
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
    fun theMovementsListGetsHeightInsideTheDialog() {
        val themed = ContextThemeWrapper(context, R.style.Theme_Synergic_POS_Offline)
        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * 0.92f).toInt()
        val height = (metrics.heightPixels * 0.86f).toInt()

        val report = onMain {
            val view = LayoutInflater.from(themed).inflate(R.layout.dialog_customer_ledger, null)
            CustomerLedgerView.bind(view, ledger)

            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)

            val rv = view.findViewById<RecyclerView>(R.id.rvLedger)
            val print = view.findViewById<View>(R.id.btnLedgerDialogPrint)

            // Saved for eyeballing.
            if (view.width > 0 && view.height > 0) {
                val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                Canvas(bmp).also { it.drawColor(Color.WHITE) }.let { view.draw(it) }
                FileOutputStream(File(context.filesDir, "ledger_dialog.png")).use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }

            Triple(rv.height, rv.adapter?.itemCount ?: -1, print.height)
        }

        val (listHeight, itemCount, printHeight) = report
        assertTrue("the movements list collapsed to ${listHeight}px", listHeight > 100)
        assertEquals("every movement should be bound", 4, itemCount)
        assertTrue("the Print button was squeezed out (${printHeight}px)", printHeight > 0)
    }
}
