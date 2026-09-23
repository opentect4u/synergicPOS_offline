package com.example.synergic_pos_offline

import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The product popup still inflates, with the scale stream added to its foot.
 *
 * A layout is not checked by the compiler: an attribute the platform rejects builds
 * perfectly and throws when the dialog opens - which, for this layout, is the moment a
 * cashier taps a product. So the whole thing is inflated here, and every view the dialog
 * reaches for is looked up, the existing ones as much as the new.
 */
@RunWith(AndroidJUnit4::class)
class ProductEntryLayoutTest {

    private fun inflate(): View {
        val context = ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext,
            R.style.Theme_Synergic_POS_Offline
        )
        return LayoutInflater.from(context).inflate(R.layout.dialog_product_entry, null)
    }

    @Test
    fun theWholePopupInflatesWithEverythingTheDialogBinds() {
        val view = inflate()
        listOf(
            R.id.etQty, R.id.etRate, R.id.tvLineAmount, R.id.tvTaxable,
            R.id.tilRateSelect, R.id.actRateSelect, R.id.rowItemDiscount,
            R.id.btnDialogCancel, R.id.btnDialogAdd,
            // The scale row: the live weight and the button that moves it into the
            // quantity. The raw stream that briefly sat below this has moved to General
            // Settings, beside the Starting and Ending Points it exists to help set.
            R.id.llScaleWeight, R.id.etScaleWeight, R.id.btnUseScaleWeight
        ).forEach {
            assertNotNull("a view the dialog binds is missing", view.findViewById<View>(it))
        }
    }

    @Test
    fun theScaleRowStartsHiddenAndStaysOutOfTheWayOfTheQuantityBox() {
        val view = inflate()
        // Hidden until a scale is actually connected, so a till with no scale sees no
        // trace of it.
        assertEquals(View.GONE, view.findViewById<View>(R.id.llScaleWeight).visibility)

        // The weight box is a display, not an input. It must not take the focus on first
        // layout, or the quantity box - the one field this popup exists to fill - opens
        // without the keyboard.
        assertEquals(false, view.findViewById<View>(R.id.etScaleWeight).isFocusable)

        // The quantity box is still the one that can take input.
        assertEquals(true, view.findViewById<View>(R.id.etQty).isFocusable)
    }
}
