package com.example.synergic_pos_offline

import android.view.LayoutInflater
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.BillSettingsDao
import com.example.synergic_pos_offline.utils.BillReceiptRenderer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "UPI QR on bill" decides whether the code goes on the paper - on every bill.
 *
 * One switch, one meaning. It used to depend on the payment mode as well and only on
 * a restaurant bill, so a shop that turned it on got a code on a grocery cash sale
 * and nothing on a restaurant one, with nothing on screen to explain the difference.
 *
 * The grocery and restaurant tills print through the SAME renderer, so this is really
 * one gate - but that is the claim worth pinning, because the two have drifted apart
 * here before and the symptom is a code on a slip that has already been paid.
 */
@RunWith(AndroidJUnit4::class)
class BillUpiQrSwitchTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var previous: Boolean = false

    @Before
    fun rememberTheSetting() {
        previous = BillSettingsDao(ctx).load().upiQrEnabled
    }

    /** The shop's own switch is put back - this flips a real setting. */
    @After
    fun putItBack() = setSwitch(previous)

    @Test
    fun theSwitchOffKeepsTheCodeOffEveryBill() {
        setSwitch(false)
        // Both formats a shop can be on, because the block lives in each layout and a
        // gate that only holds for one of them is a gate that does not hold.
        listOf(R.layout.fragment_bill, R.layout.fragment_bill_classic).forEach { layout ->
            assertEquals(
                "with the switch OFF no bill may carry the code (layout $layout)",
                View.GONE, qrVisibility(layout)
            )
        }
    }

    /**
     * And with it on the code is there - otherwise the test above passes against a
     * renderer that simply never draws it, which would be the same bug the other way.
     *
     * Needs a UPI ID set up on the till; without one there is nothing to encode and
     * the block stays hidden whatever the switch says. Skipped rather than failed on
     * a till that has not set one up.
     */
    @Test
    fun theSwitchOnPutsTheCodeOnTheBill() {
        if (!com.example.synergic_pos_offline.utils.UpiQr.isValidVpa(
                BillSettingsDao(ctx).load().upiId
            )
        ) return
        setSwitch(true)
        assertEquals(
            "with the switch ON the code belongs on the bill",
            View.VISIBLE, qrVisibility(R.layout.fragment_bill)
        )
    }

    // ---- Helpers -------------------------------------------------------------------

    /** Renders a one-line draft bill and reports what the QR block did. */
    private fun qrVisibility(layout: Int): Int {
        var result = -1
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val themed = androidx.appcompat.view.ContextThemeWrapper(
                ctx, com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar
            )
            val view = LayoutInflater.from(themed).inflate(layout, null, false)
            BillReceiptRenderer(themed).populate(view, 0L, draft = draft())
            result = view.findViewById<View>(R.id.llUpiQr)?.visibility ?: -1
        }
        return result
    }

    /** A bill with something to pay - the code is never drawn for a nil total. */
    private fun draft() = BillReceiptRenderer.Draft(
        billNumber = "QR-TEST",
        dateTime = "2026-09-11 12:00:00",
        cashier = "TEST",
        customer = BillReceiptRenderer.Draft.Customer(name = "Walk-in"),
        items = listOf(
            BillReceiptRenderer.Draft.Item(name = "QR test item", quantity = 1.0, rate = 100.0)
        ),
        discount = 0.0,
        roundOff = 0.0,
        netAmount = 100.0,
        paymentModes = listOf("Cash")
    )

    private fun setSwitch(on: Boolean) {
        val dao = BillSettingsDao(ctx)
        dao.save(dao.load().copy(upiQrEnabled = on))
        com.example.synergic_pos_offline.utils.SettingsCache.storeFromDb(ctx)
    }
}
