package com.example.synergic_pos_offline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The counter settlement dialog lists what is being paid for.
 *
 * QSR and take-away are settled in this dialog rather than on the checkout screen, so
 * until now the person handing money over saw a total and nothing else. The list is the
 * answer to "what am I paying for?" - so what matters is that the rows are actually
 * there, that a long ticket scrolls rather than pushing the buttons off the card, and
 * that the pay controls below it survive.
 *
 * Also writes the rendered dialog to the app's files dir so it can be looked at:
 *   adb exec-out run-as com.example.synergic_pos_offline cat files/quickpay-6.png > d.png
 */
@RunWith(AndroidJUnit4::class)
class QuickPayItemsLayoutTest {

    private val ctx
        get() = ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext,
            R.style.Theme_Synergic_POS_Offline
        )

    /** The dialog, with [items] lines in it, laid out as it would be on screen. */
    private fun render(items: List<Pair<String, String>>, name: String): View {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_quick_payment, null)
        val container = view.findViewById<LinearLayout>(R.id.llQpItems)
        assertNotNull("the dialog should have an item list", container)

        // Filled exactly as fillQuickPayItems does it.
        val inflater = LayoutInflater.from(container.context)
        items.forEach { (itemName, qty) ->
            val row = inflater.inflate(R.layout.item_quick_pay_line, container, false)
            row.findViewById<TextView>(R.id.tvQpLineName).text = itemName
            row.findViewById<TextView>(R.id.tvQpLineQty).text = "×$qty"
            container.addView(row)
        }
        // The cap the dialog itself applies - the real rule, not a copy of it.
        val scroll = view.findViewById<View>(R.id.svQpItems)
        scroll.layoutParams = scroll.layoutParams.apply {
            height = com.example.synergic_pos_offline.fragments.quickPayListHeight(
                items.size, scroll.resources.displayMetrics.density
            )
        }

        view.findViewById<TextView>(R.id.tvQpTitle).text = "QSR — Settlement"
        view.findViewById<TextView>(R.id.tvQpSubtitle).text = "Token #7"
        view.findViewById<TextView>(R.id.tvQpTotal).text = "₹ 740.00"

        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val bmp = Bitmap.createBitmap(
            view.measuredWidth.coerceAtLeast(1),
            view.measuredHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        ).apply { eraseColor(Color.WHITE) }
        view.draw(Canvas(bmp))
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "$name.png")
            .outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return view
    }

    private val ticket = listOf(
        "Chicken Biryani" to "2",
        "Butter Naan" to "4",
        "Masala Tea" to "1"
    )

    @Test
    fun everyLineOfTheOrderIsListedWithItsQuantity() {
        var view: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = render(ticket, "quickpay-3")
        }
        val container = view!!.findViewById<LinearLayout>(R.id.llQpItems)
        assertEquals("one row per line of the order", ticket.size, container.childCount)

        ticket.forEachIndexed { i, (itemName, qty) ->
            val row = container.getChildAt(i)
            assertEquals(itemName, row.findViewById<TextView>(R.id.tvQpLineName).text.toString())
            assertEquals("×$qty", row.findViewById<TextView>(R.id.tvQpLineQty).text.toString())
        }
    }

    @Test
    fun thePayControlsSurviveALongTicket() {
        // The reason the list scrolls. A counter order of thirty lines must not push the
        // pay tiles, the tendered box or the buttons off the bottom of the card - they
        // are the only way to take the money or to get out of the dialog.
        var view: View? = null
        val long = (1..30).map { "Item number $it with a fairly long name" to "$it" }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = render(long, "quickpay-30")
        }
        val card = view!!

        val screen = ctx.resources.displayMetrics.heightPixels
        assertTrue(
            "a 30-line ticket made the dialog ${card.measuredHeight}px tall " +
                "against a ${screen}px screen",
            card.measuredHeight <= screen
        )

        listOf(R.id.btnQpCash, R.id.btnFormPositive, R.id.btnFormNegative, R.id.tvQpTotal)
            .forEach { id ->
                val v = card.findViewById<View>(id)
                assertNotNull("a pay control is missing", v)
                assertTrue(
                    "a pay control was laid out past the bottom of the card",
                    v.bottom in 1..card.measuredHeight
                )
            }
    }

    @Test
    fun anEmptyListLeavesTheDialogUsable() {
        // Billing is blocked on an empty order ("Add items before billing"), so this is
        // defensive rather than reachable - but a dialog that cannot be dismissed is a
        // till that has to be force-stopped.
        var view: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = render(emptyList(), "quickpay-0")
        }
        assertEquals(0, view!!.findViewById<LinearLayout>(R.id.llQpItems).childCount)
        assertTrue(view!!.findViewById<View>(R.id.btnFormNegative).bottom > 0)
    }
}
