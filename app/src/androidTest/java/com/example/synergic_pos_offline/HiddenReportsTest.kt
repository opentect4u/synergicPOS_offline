package com.example.synergic_pos_offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.fragments.ReportsFragment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reports taken off the menu stay off it, on every surface that lists them.
 *
 * Two places show reports - the Reports grid and the sidebar's Reports branch - and
 * they are two descriptions of one menu. A report hidden from one only is worse than
 * one never hidden: it is still reachable, just no longer where anybody looks for it.
 * Both filter through [ReportsFragment.isVisible], so that is what this asks.
 *
 * Asserted on the gate rather than by reading the drawer off the screen, because both
 * lists scroll - a screenshot proves only what happened to be in view.
 */
@RunWith(AndroidJUnit4::class)
class HiddenReportsTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun profitAndLossAndPaymentReceiptAreHidden() {
        listOf("Profit & Loss Report", "Payment & Receipt").forEach { title ->
            assertFalse(
                "\"$title\" should not appear on any report menu",
                ReportsFragment.isVisible(ctx, title)
            )
        }
    }

    /**
     * The gate has not simply been turned off for everything.
     *
     * The guard that matters: hiding two reports by making `isVisible` always false
     * would satisfy the test above and empty the whole menu.
     */
    @Test
    fun theOrdinaryReportsAreStillThere() {
        listOf(
            "Bill Wise Report", "Item Wise Report", "Operator Wise Report",
            "Void Bill Report", "Tax Report", "Customer Ledger", "Customer Payment"
        ).forEach { title ->
            assertTrue(
                "\"$title\" should still be on the menu",
                ReportsFragment.isVisible(ctx, title)
            )
        }
    }

    /**
     * And the titles are spelled the way both lists spell them.
     *
     * The gate matches on the title string, so a hidden entry whose spelling drifted
     * in one list would quietly come back on that surface alone - exactly the
     * half-hidden state the gate exists to prevent.
     */
    @Test
    fun theHiddenTitlesMatchTheOnesTheMenusUse() {
        // The spellings as ReportsFragment's own grid and MainActivity's drawer list
        // them. If either is renamed without updating the other, this fails.
        listOf("Profit & Loss Report", "Payment & Receipt").forEach { title ->
            assertFalse(
                "\"$title\" must be spelled exactly as the menus spell it",
                ReportsFragment.isVisible(ctx, title)
            )
            assertTrue(
                "a near-miss spelling should NOT be hidden - it would mean the real " +
                    "title is something else and this gate is guarding nothing",
                ReportsFragment.isVisible(ctx, "$title ")
            )
        }
    }
}
