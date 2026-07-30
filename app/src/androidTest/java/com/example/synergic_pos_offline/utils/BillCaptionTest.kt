package com.example.synergic_pos_offline.utils

import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.BillDao
import com.example.synergic_pos_offline.database.BillHeaderFooterDao.FontSize
import com.example.synergic_pos_offline.database.CaptionDao
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
 * Captions head the slip according to what it is.
 *
 * BILL and DUPLICATE are alternatives - a slip is either an original or a copy of
 * one, never captioned as both - while CREDIT answers a separate question and joins
 * whichever of the two applies. A disabled caption prints nowhere.
 */
@RunWith(AndroidJUnit4::class)
class BillCaptionTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db = DatabaseHelper.getInstance(context).writableDatabase
    private val captions = CaptionDao(context)

    private var cashReceipt = -1L
    private var creditReceipt = -1L
    private val captionKeys = mutableListOf<String>()

    private companion object {
        // Deliberately unlikely wording: these have to be tellable apart from
        // whatever captions the device under test already has configured.
        const val BILL_TEXT = "ZZ-TEST BILL CAPTION"
        const val CREDIT_TEXT = "ZZ-TEST CREDIT CAPTION"
        const val DUPLICATE_TEXT = "ZZ-TEST DUPLICATE CAPTION"
    }

    private val seeded = setOf(BILL_TEXT, CREDIT_TEXT, DUPLICATE_TEXT)

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

    private fun bill(type: String, paid: Double): Long {
        val result = BillDao(context).createBill(
            BillDao.NewBill(
                billType = type,
                customerId = null,
                items = listOf(BillDao.Item(productId = null, name = "Test item", quantity = 2.0, rate = 250.0)),
                payment = BillDao.Payment(mode = type, amountPaid = paid),
                totalPrice = 500.0, discountAmount = 0.0, discountPercentage = 0.0,
                cgstAmount = 0.0, sgstAmount = 0.0, netAmount = 500.0
            )
        )
        requireNotNull(result) { "createBill returned null" }
        return result.receiptNo
    }

    @Before
    fun seed() {
        cashReceipt = bill("CASH", 500.0)
        creditReceipt = bill("CREDIT", 0.0)

        listOf(
            CaptionDao.Type.BILL to BILL_TEXT,
            CaptionDao.Type.CREDIT to CREDIT_TEXT,
            CaptionDao.Type.DUPLICATE to DUPLICATE_TEXT
        ).forEach { (type, text) ->
            captions.insert(type, text, FontSize.BIG, bold = true, enabled = true)
                ?.let { captionKeys.add(it) }
        }
        assertEquals("could not seed the captions", 3, captionKeys.size)
    }

    @After
    fun cleanUp() {
        captions.delete(captionKeys)
        listOf(cashReceipt, creditReceipt).filter { it > 0 }.forEach { no ->
            db.delete(DatabaseHelper.Tables.TD_CUSTOMER_LEDGER, "bill_id=?", arrayOf(no.toString()))
            db.delete(DatabaseHelper.Tables.TD_PAYMENTS, "bill_id=?", arrayOf(no.toString()))
            db.delete(DatabaseHelper.Tables.TD_BILL_ITEMS, "bill_id=?", arrayOf(no.toString()))
            db.delete(DatabaseHelper.Tables.TD_BILLS, "receipt_no=?", arrayOf(no.toString()))
        }
    }

    /**
     * The captions this test seeded, in the order they printed.
     *
     * Filtered to its own rows on purpose: a real till has captions of its own, and
     * a test that asserted on the whole block would pass on a clean device and fail
     * on every configured one. Order within the filtered list is still checked, so
     * the stacking order is not lost.
     */
    private fun printed(receiptNo: Long, duplicate: Boolean): List<String> = onMain {
        val view = LayoutInflater.from(themed()).inflate(R.layout.fragment_bill, null, false)
        BillReceiptRenderer(themed()).populate(view, receiptNo, duplicate = duplicate)
        val container = view.findViewById<LinearLayout>(R.id.llBillCaptions)
        (0 until container.childCount)
            .map { (container.getChildAt(it) as TextView).text.toString() }
            .filter { it in seeded }
    }

    @Test
    fun anOrdinaryBillCarriesOnlyTheBillCaption() {
        assertEquals(listOf(BILL_TEXT), printed(cashReceipt, duplicate = false))
    }

    @Test
    fun aCreditBillAddsTheCreditCaption() {
        assertEquals(listOf(BILL_TEXT, CREDIT_TEXT), printed(creditReceipt, duplicate = false))
    }

    /** The duplicate caption replaces the bill caption; it does not join it. */
    @Test
    fun aReprintSwapsTheBillCaptionForTheDuplicateOne() {
        assertEquals(listOf(DUPLICATE_TEXT), printed(cashReceipt, duplicate = true))
    }

    /** Credit still applies to a copy - it is about the sale, not about the slip. */
    @Test
    fun aCreditReprintCarriesDuplicateAndCredit() {
        assertEquals(
            listOf(DUPLICATE_TEXT, CREDIT_TEXT),
            printed(creditReceipt, duplicate = true)
        )
    }

    /** And an original is never captioned as a duplicate. */
    @Test
    fun anOriginalNeverCarriesTheDuplicateCaption() {
        assertTrue(
            "an original carried the duplicate caption",
            DUPLICATE_TEXT !in printed(creditReceipt, duplicate = false)
        )
    }

    @Test
    fun aDisabledCaptionPrintsNowhere() {
        captionKeys.forEach { captions.setEnabled(it, false) }
        assertTrue("disabled captions still printed", printed(creditReceipt, duplicate = true).isEmpty())
    }

    /** Saved for eyeballing: the captions have to head the slip, above the store name. */
    @Test
    fun rendersACaptionedSlipToABitmap() {
        val bmp = onMain {
            BillReceiptRenderer(themed()).renderToBitmap(creditReceipt, 576, duplicate = true)
        }
        assertNotNull("captioned render returned null", bmp)
        requireNotNull(bmp)

        File(context.filesDir, "captioned_bill.png").also { f ->
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue("captioned slip collapsed to ${bmp.width}x${bmp.height}", bmp.height > bmp.width)
    }
}
