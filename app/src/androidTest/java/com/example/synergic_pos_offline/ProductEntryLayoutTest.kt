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
            // The scale row, unchanged...
            R.id.llScaleWeight, R.id.etScaleWeight, R.id.btnUseScaleWeight,
            // ...and the raw stream added below it.
            R.id.llScaleStream, R.id.tvScaleStream
        ).forEach {
            assertNotNull("a view the dialog binds is missing", view.findViewById<View>(it))
        }
    }

    @Test
    fun theStreamStartsHiddenAndStaysOutOfTheWayOfTheQuantityBox() {
        val view = inflate()
        // Hidden until a scale is actually connected - the dialog turns it on alongside
        // the weight row, and a till with no scale should see no trace of it.
        assertEquals(View.GONE, view.findViewById<View>(R.id.llScaleStream).visibility)

        // Not focusable, deliberately. A focusable view added to this dialog can take
        // the focus on first layout and leave the quantity box without the keyboard,
        // which would break the one flow this change was told to leave alone.
        val stream = view.findViewById<View>(R.id.tvScaleStream)
        assertEquals(false, stream.isFocusable)

        // The quantity box is still the one that can take input.
        assertEquals(true, view.findViewById<View>(R.id.etQty).isFocusable)
    }
}
