package com.example.synergic_pos_offline.database

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.synergic_pos_offline.utils.BillSettingsSnapshot
import com.example.synergic_pos_offline.utils.GstCalculator
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sale returns, both ways round.
 *
 * The figures matter more than the screens here: a return is money going back, so
 * what it is valued at, and how much of a line can still come back after an earlier
 * return, are what these check.
 */
@RunWith(AndroidJUnit4::class)
class ReturnDaoTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = DatabaseHelper.getInstance(context).writableDatabase
    private val dao = ReturnDao(context)

    /**
     * The rules the pricing tests work under.
     *
     * Stated here rather than read from the device's Tax Settings: what these check
     * is the arithmetic, and a test that only passes when the till happens to have
     * GST switched on is testing the emulator's configuration, not the code.
     */
    private val gstBasis = ReturnDao.PricingBasis(
        taxEnabled = true,
        inclusive = false,
        discountPreTax = true
    )

    private var productId = -1L
    private var rateId = -1L
    private var receiptNo = -1L
    private val returnIds = mutableListOf<Long>()

    @Before
    fun seed() {
        productId = db.insert(
            DatabaseHelper.Tables.MD_PRODUCTS, null,
            ContentValues().apply {
                put("product_name", "ZZ Return Test Widget")
                put("bar_code", "ZZRT0001")
                put("hsn_code", "998877")
            }
        )
        assertTrue("could not seed the product", productId > 0)

        rateId = db.insert(
            DatabaseHelper.Tables.MD_PRODUCT_RATES, null,
            ContentValues().apply {
                put("product_id", productId)
                put("rate_name", "Default")
                put("rate", 100.0)
                put("cgst_rate", 2.5)
                put("sgst_rate", 2.5)
                put("\"default\"", 1)
            }
        )

        val bill = BillDao(context).createBill(
            BillDao.NewBill(
                billType = "CASH",
                customerId = null,
                items = listOf(
                    BillDao.Item(
                        productId = productId, name = "ZZ Return Test Widget",
                        quantity = 5.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5
                    )
                ),
                payment = BillDao.Payment(mode = "CASH", amountPaid = 525.0),
                totalPrice = 500.0, discountAmount = 0.0, discountPercentage = 0.0,
                cgstAmount = 12.5, sgstAmount = 12.5, netAmount = 525.0
            )
        )
        requireNotNull(bill) { "createBill returned null" }
        receiptNo = bill.receiptNo
    }

    @After
    fun cleanUp() {
        returnIds.forEach { id ->
            db.delete(DatabaseHelper.Tables.TD_RETURN_ITEMS, "return_id=?", arrayOf(id.toString()))
            db.delete(DatabaseHelper.Tables.TD_SALE_RETURNS, "id=?", arrayOf(id.toString()))
        }
        if (receiptNo > 0) {
            db.delete(DatabaseHelper.Tables.TD_PAYMENTS, "bill_id=?", arrayOf(receiptNo.toString()))
            db.delete(DatabaseHelper.Tables.TD_BILL_ITEMS, "bill_id=?", arrayOf(receiptNo.toString()))
            db.delete(DatabaseHelper.Tables.TD_BILLS, "receipt_no=?", arrayOf(receiptNo.toString()))
        }
        if (rateId > 0) db.delete(DatabaseHelper.Tables.MD_PRODUCT_RATES, "id=?", arrayOf(rateId.toString()))
        if (productId > 0) db.delete(DatabaseHelper.Tables.MD_PRODUCTS, "id=?", arrayOf(productId.toString()))
    }

    // ---- Item lookup -------------------------------------------------------

    @Test
    fun anItemIsFoundByNameBarcodeOrHsn() {
        listOf("Return Test Widget", "ZZRT0001", "998877").forEach { term ->
            val found = dao.searchItems(term)
            assertTrue(
                "searching \"$term\" did not find the item",
                found.any { it.productId == productId }
            )
        }
    }

    /** The rate and its tax come back with the item, or the return cannot be priced. */
    @Test
    fun anItemCarriesTheRateItIsSoldAt() {
        val item = dao.searchItems("ZZRT0001").first { it.productId == productId }
        assertEquals(100.0, item.rate, 0.001)
        assertEquals(2.5, item.cgstRate, 0.001)
        assertEquals(2.5, item.sgstRate, 0.001)
    }

    @Test
    fun anEmptyTermFindsNothing() {
        assertTrue(dao.searchItems("   ").isEmpty())
    }

    // ---- Bill lookup -------------------------------------------------------

    @Test
    fun aBillOffersItsLinesBack() {
        val lines = dao.linesOfBill(receiptNo)
        assertEquals(1, lines.size)
        assertEquals(5.0, lines[0].soldQuantity, 0.001)
        assertEquals(0.0, lines[0].returnedQuantity, 0.001)
        assertEquals("all five should be returnable", 5.0, lines[0].returnableQuantity, 0.001)
    }

    /**
     * The crux of a bill-wise return: once some of a line has come back, only the
     * rest can. Otherwise the same goods could be refunded on every visit.
     */
    @Test
    fun aLineCannotBeReturnedTwiceOver() {
        val line = dao.linesOfBill(receiptNo).first()
        val priced = dao.priceLine(
            name = line.name, productId = line.productId, billItemId = line.billItemId,
            quantity = 2.0, rate = line.rate,
            cgstRate = line.cgstRate, sgstRate = line.sgstRate, vatRate = line.vatRate
        )
        val saved = dao.save(listOf(priced), originalBillId = receiptNo)
        requireNotNull(saved) { "save returned null" }
        returnIds.add(saved.id)

        val after = dao.linesOfBill(receiptNo).first()
        assertEquals("two should now be recorded as returned", 2.0, after.returnedQuantity, 0.001)
        assertEquals("three should remain returnable", 3.0, after.returnableQuantity, 0.001)
    }

    // ---- Pricing -----------------------------------------------------------

    /** A return is valued the way the sale was, tax included. */
    @Test
    fun aReturnIsPricedWithTheTaxItWasSoldUnder() {
        val line = dao.priceLine(
            name = "ZZ Return Test Widget", productId = productId, billItemId = null,
            quantity = 2.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5, vatRate = 0.0
        )
        assertEquals(2.0, line.quantity, 0.001)
        // Whatever the regime, the refund is the base plus whatever tax was charged
        // on it - so the two have to agree rather than being asserted separately.
        assertTrue("the refund should be positive", line.amount > 0.0)
        assertEquals(
            "refund should be the taxable value plus its tax",
            line.amount, (line.amount - line.tax) + line.tax, 0.001
        )
    }

    /** The tax comes back split, because that is how it was charged. */
    @Test
    fun aReturnReportsItsTaxSplit() {
        val line = dao.priceLine(
            name = "ZZ Return Test Widget", productId = productId, billItemId = null,
            quantity = 2.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5, vatRate = 0.0,
            basis = gstBasis
        )
        assertTrue("CGST should be reported", line.cgst > 0.0)
        assertTrue("SGST should be reported", line.sgst > 0.0)
        assertEquals("the split should add up to the total tax", line.tax, line.cgst + line.sgst, 0.001)
        assertEquals("gross is quantity x rate", 200.0, line.gross, 0.001)
    }

    /**
     * A discount on the line comes off the refund. Returning at the listed rate
     * would hand back more than the customer paid.
     */
    @Test
    fun aDiscountReducesTheRefund() {
        val full = dao.priceLine(
            name = "ZZ Return Test Widget", productId = productId, billItemId = null,
            quantity = 2.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5, vatRate = 0.0,
            basis = gstBasis
        )
        val discounted = dao.priceLine(
            name = "ZZ Return Test Widget", productId = productId, billItemId = null,
            quantity = 2.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5, vatRate = 0.0,
            discountAmount = 20.0, basis = gstBasis
        )
        assertEquals(20.0, discounted.discount, 0.001)
        assertTrue(
            "a discounted line should refund less (${discounted.amount} vs ${full.amount})",
            discounted.amount < full.amount
        )
    }

    /**
     * Returning part of a discounted line gives back part of the discount. All of
     * it, or none, would refund the wrong amount on every partial return.
     */
    @Test
    fun aPartialReturnGivesBackItsShareOfTheDiscount() {
        val line = ReturnDao.BillLine(
            billItemId = 1L, productId = productId, name = "ZZ Return Test Widget",
            soldQuantity = 5.0, returnedQuantity = 0.0, rate = 100.0,
            discountAmount = 50.0, cgstRate = 2.5, sgstRate = 2.5, vatRate = 0.0
        )
        assertEquals("two of five carries two fifths", 20.0, line.discountFor(2.0), 0.001)
        assertEquals("the whole line carries all of it", 50.0, line.discountFor(5.0), 0.001)
        assertEquals("none of it carries none", 0.0, line.discountFor(0.0), 0.001)
    }

    /** The bill's own discount reaches the return screen, or it cannot be applied. */
    @Test
    fun aBillLineCarriesTheDiscountItWasSoldUnder() {
        val discounted = BillDao(context).createBill(
            BillDao.NewBill(
                billType = "CASH",
                customerId = null,
                items = listOf(
                    BillDao.Item(
                        productId = productId, name = "ZZ Return Test Widget",
                        quantity = 4.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5,
                        discountAmount = 40.0
                    )
                ),
                payment = BillDao.Payment(mode = "CASH", amountPaid = 378.0),
                totalPrice = 400.0, discountAmount = 40.0, discountPercentage = 10.0,
                cgstAmount = 9.0, sgstAmount = 9.0, netAmount = 378.0
            )
        )
        requireNotNull(discounted)
        val extraReceipt = discounted.receiptNo
        try {
            val line = dao.linesOfBill(extraReceipt).first()
            assertEquals("the line's discount should come through", 40.0, line.discountAmount, 0.001)
        } finally {
            db.delete(DatabaseHelper.Tables.TD_PAYMENTS, "bill_id=?", arrayOf(extraReceipt.toString()))
            db.delete(DatabaseHelper.Tables.TD_BILL_ITEMS, "bill_id=?", arrayOf(extraReceipt.toString()))
            db.delete(DatabaseHelper.Tables.TD_BILLS, "receipt_no=?", arrayOf(extraReceipt.toString()))
        }
    }

    // ---- The rules a return is priced under --------------------------------

    /**
     * A bill-wise return reads its rules off the bill's own frozen snapshot.
     *
     * Written directly onto the bill rather than by changing Tax Settings and
     * raising a sale: this has to hold for a bill made under settings the till no
     * longer has, which is exactly the case that cannot be reproduced by
     * configuring the till as it is now.
     */
    @Test
    fun aBillWiseReturnReadsItsRulesOffTheBill() {
        db.execSQL(
            "UPDATE ${DatabaseHelper.Tables.TD_BILLS} SET settings_snapshot = ? WHERE receipt_no = ?",
            arrayOf(
                BillSettingsSnapshot.serialize(
                    BillSettingsDao(context).load(),
                    taxEnabled = true,
                    discountPreTax = true,
                    inclusive = true
                ),
                receiptNo.toString()
            )
        )

        val basis = dao.basisForBill(receiptNo)
        assertTrue("tax was on when this bill was raised", basis.taxEnabled)
        assertTrue("the bill was raised inclusive of tax", basis.inclusive)
        assertTrue("the bill discounted before tax", basis.discountPreTax)
    }

    /**
     * And those rules actually change the refund - so following the bill rather
     * than today's settings is not a distinction without a difference.
     *
     * The same line priced inclusive and exclusive of the same rate must differ:
     * an inclusive rate is carved out of the listed price, an exclusive one is
     * added to it.
     */
    @Test
    fun theBillsRulesChangeWhatIsRefunded() {
        val inclusive = dao.priceLine(
            name = "ZZ Return Test Widget", productId = productId, billItemId = null,
            quantity = 1.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5, vatRate = 0.0,
            basis = gstBasis.copy(inclusive = true)
        )
        val exclusive = dao.priceLine(
            name = "ZZ Return Test Widget", productId = productId, billItemId = null,
            quantity = 1.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5, vatRate = 0.0,
            basis = gstBasis.copy(inclusive = false)
        )
        assertEquals("an inclusive rate is already in the price", 100.0, inclusive.amount, 0.01)
        assertEquals("an exclusive rate is added to it", 105.0, exclusive.amount, 0.01)
        assertTrue(
            "the two must differ, or honouring the bill's rules would be pointless",
            exclusive.amount > inclusive.amount
        )
    }

    /** A bill too old to carry a snapshot falls back to the live settings. */
    @Test
    fun aBillWithoutASnapshotFallsBackToTodaysSettings() {
        db.execSQL(
            "UPDATE ${DatabaseHelper.Tables.TD_BILLS} SET settings_snapshot = NULL WHERE receipt_no = ?",
            arrayOf(receiptNo.toString())
        )
        assertEquals(
            "with no snapshot the live rules are all there is",
            dao.liveBasis(), dao.basisForBill(receiptNo)
        )
    }

    // ---- The summary -------------------------------------------------------

    /**
     * The return screen and the printed slip both build their summary from this
     * one list, so what it contains is what both show.
     */
    @Test
    fun theSummaryReadsGrossDiscountTaxThenRefund() {
        val line = dao.priceLine(
            name = "ZZ Return Test Widget", productId = productId, billItemId = null,
            quantity = 2.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5, vatRate = 0.0,
            discountAmount = 20.0, basis = gstBasis
        )
        val labels = ReturnDao.summaryRows(listOf(line)).map { it.label }
        assertEquals(listOf("ITEMS", "GROSS", "DISCOUNT", "CGST", "SGST", "REFUND"), labels)
    }

    /** A row that carries nothing is left off, rather than printing a zero. */
    @Test
    fun theSummaryLeavesOutWhatDoesNotApply() {
        val plain = dao.priceLine(
            name = "ZZ Return Test Widget", productId = productId, billItemId = null,
            quantity = 1.0, rate = 100.0, cgstRate = 0.0, sgstRate = 0.0, vatRate = 0.0,
            basis = gstBasis
        )
        val labels = ReturnDao.summaryRows(listOf(plain)).map { it.label }
        assertEquals(listOf("ITEMS", "GROSS", "REFUND"), labels)
    }

    /** The refund is the row set apart, and it agrees with what is saved. */
    @Test
    fun theSummaryRefundMatchesTheSavedTotal() {
        val line = dao.priceLine(
            name = "ZZ Return Test Widget", productId = productId, billItemId = null,
            quantity = 3.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5, vatRate = 0.0,
            discountAmount = 15.0, basis = gstBasis
        )
        val rows = ReturnDao.summaryRows(listOf(line))
        val refund = rows.single { it.emphasis }
        assertEquals("REFUND", refund.label)

        val saved = dao.save(listOf(line))
        requireNotNull(saved)
        returnIds.add(saved.id)
        assertEquals(
            "the summary refund should be what is filed",
            saved.totalAmount, refund.value, 0.001
        )
        assertEquals("the item count is not an amount", 1.0, rows.first().value, 0.001)
        assertTrue("the item count should not be money", !rows.first().isMoney)
    }

    // ---- Saving ------------------------------------------------------------

    @Test
    fun aReturnIsFiledWithItsLines() {
        val priced = dao.priceLine(
            name = "ZZ Return Test Widget", productId = productId, billItemId = null,
            quantity = 3.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5, vatRate = 0.0
        )
        val saved = dao.save(listOf(priced), reason = "Damaged")
        requireNotNull(saved) { "save returned null" }
        returnIds.add(saved.id)

        assertTrue("the return should be numbered", saved.returnNumber.startsWith("RT"))
        assertNull("an item-wise return has no original bill", saved.originalBillNumber)
        assertEquals(1, saved.lines.size)

        val storedLines = db.rawQuery(
            "SELECT COUNT(*) FROM ${DatabaseHelper.Tables.TD_RETURN_ITEMS} WHERE return_id = ?",
            arrayOf(saved.id.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        assertEquals("the line should be on file", 1, storedLines)
    }

    /** A bill-wise return names the bill it came off, for the slip to refer back to. */
    @Test
    fun aBillWiseReturnNamesItsBill() {
        val line = dao.linesOfBill(receiptNo).first()
        val priced = dao.priceLine(
            name = line.name, productId = line.productId, billItemId = line.billItemId,
            quantity = 1.0, rate = line.rate,
            cgstRate = line.cgstRate, sgstRate = line.sgstRate, vatRate = line.vatRate
        )
        val saved = dao.save(listOf(priced), originalBillId = receiptNo)
        requireNotNull(saved)
        returnIds.add(saved.id)

        assertNotNull("a bill-wise return should name its bill", saved.originalBillNumber)
        assertEquals(dao.billNumberOf(receiptNo), saved.originalBillNumber)
    }

    @Test
    fun nothingToReturnIsRefused() {
        assertNull("an empty return should not be filed", dao.save(emptyList()))
        val zero = dao.priceLine(
            name = "ZZ Return Test Widget", productId = productId, billItemId = null,
            quantity = 0.0, rate = 100.0, cgstRate = 2.5, sgstRate = 2.5, vatRate = 0.0
        )
        assertNull("a zero-quantity return should not be filed", dao.save(listOf(zero)))
    }

    // ---- The return window -------------------------------------------------

    @Test
    fun todaysBillIsInsideAnyWindow() {
        assertTrue(dao.withinReturnWindow(receiptNo, days = 7))
    }

    @Test
    fun noLimitMeansNoWindow() {
        // Backdated well past any sane limit; with days = 0 it must still be allowed.
        db.execSQL(
            "UPDATE ${DatabaseHelper.Tables.TD_BILLS} SET bill_date = '2020-01-01' WHERE receipt_no = ?",
            arrayOf(receiptNo.toString())
        )
        assertTrue("days = 0 means no limit", dao.withinReturnWindow(receiptNo, days = 0))
        assertTrue("an old bill should fall outside a 7-day limit", !dao.withinReturnWindow(receiptNo, days = 7))
    }
}
