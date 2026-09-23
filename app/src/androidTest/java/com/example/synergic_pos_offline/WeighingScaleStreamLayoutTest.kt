package com.example.synergic_pos_offline

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scale's raw stream now lives in General Settings, not in the product popup.
 *
 * It is a SETUP aid: it exists so whoever is configuring the till can see what the scale
 * actually sends and count the Starting and Ending Points off it. That belongs beside
 * those two fields, not in front of the person selling.
 *
 * That it is GONE from the popup needs no assertion here: `llScaleStream` and
 * `tvScaleStream` no longer exist, so anything still referring to them fails to compile.
 * What is worth checking is that the popup kept the parts that were not diagnostic - the
 * live weight and its Use button - which is the last test below.
 */
@RunWith(AndroidJUnit4::class)
class WeighingScaleStreamLayoutTest {

    private fun inflate(layout: Int): View {
        val ctx = ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext,
            R.style.Theme_Synergic_POS_Offline
        )
        return LayoutInflater.from(ctx).inflate(layout, null)
    }

    @Test
    fun generalSettingsCarriesTheStreamAndItsRuler() {
        val view = inflate(R.layout.fragment_general_settings)
        listOf(
            R.id.llWeighingScaleStream,
            R.id.tvWeighingScaleStream,
            R.id.tvWeighingScaleRuler,
            R.id.tvWeighingScaleStreamSub
        ).forEach {
            assertNotNull("a view the settings screen fills in is missing", view.findViewById<View>(it))
        }
    }

    @Test
    fun theStreamSitsBelowTheStartAndEndPointsItExistsToHelpSet() {
        // Position is the point of this change. A ruler for counting characters is only
        // any use next to the fields the count goes into.
        val view = inflate(R.layout.fragment_general_settings)
        val endPoint = view.findViewById<View>(R.id.llWeighingScaleEndPoint)
        val stream = view.findViewById<View>(R.id.llWeighingScaleStream)

        val parent = endPoint.parent
        assertEquals("the stream should be in the weighing scale section", parent, stream.parent)
        val group = parent as android.view.ViewGroup
        assertTrue(
            "the stream should come after the Ending Point row",
            group.indexOfChild(stream) > group.indexOfChild(endPoint)
        )
    }

    /**
     * Draws the section so it can be pulled off and looked at:
     *   adb exec-out run-as com.example.synergic_pos_offline cat files/scale-section.png > s.png
     *
     * Filled the way the screen fills it, through [ScaleStream], so what is drawn is what
     * a real scale would produce rather than a hand-typed approximation of it.
     */
    @Test
    fun theSectionDrawsWithRealScaleText() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = inflate(R.layout.fragment_general_settings)
            var text = ""
            repeat(3) {
                text = com.example.synergic_pos_offline.utils.ScaleStream.append(text, "ST,GS,+  1.250kg\r\n")
            }
            view.findViewById<TextView>(R.id.tvWeighingScaleStream).text = text
            view.findViewById<TextView>(R.id.tvWeighingScaleRuler).text =
                com.example.synergic_pos_offline.utils.ScaleStream.ruler(text)

            view.measure(
                View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)

            // Just the scale section, not the whole settings page: a screenshot of six
            // thousand pixels of unrelated rows is one nobody can read the ruler in.
            val section = view.findViewById<View>(R.id.llWeighingScaleStream)
            assertTrue("the stream row should have height", section.height > 0)
            val top = view.findViewById<View>(R.id.llWeighingScaleStartPoint)

            val bmp = android.graphics.Bitmap.createBitmap(
                view.measuredWidth,
                section.bottom - top.top,
                android.graphics.Bitmap.Config.ARGB_8888
            ).apply { eraseColor(android.graphics.Color.WHITE) }
            val canvas = android.graphics.Canvas(bmp)
            // The section's own offset within the page, so it lands at the top of the
            // bitmap rather than wherever it happens to sit on the screen.
            canvas.translate(0f, -absoluteTopIn(top, view).toFloat())
            view.draw(canvas)
            java.io.File(
                InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "scale-section.png"
            ).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** [child]'s top edge measured against [root], adding up every offset between them. */
    private fun absoluteTopIn(child: View, root: View): Int {
        var y = 0
        var v: View? = child
        while (v != null && v !== root) {
            y += v.top
            v = v.parent as? View
        }
        return y
    }

    @Test
    fun theStreamAndItsRulerAreBothMonospaced() {
        // Column n of one has to be column n of the other, or the ruler points at the
        // wrong character - which is worse than no ruler at all.
        val view = inflate(R.layout.fragment_general_settings)
        val stream = view.findViewById<TextView>(R.id.tvWeighingScaleStream)
        val ruler = view.findViewById<TextView>(R.id.tvWeighingScaleRuler)
        assertEquals("the two must share a typeface", stream.typeface, ruler.typeface)
        assertEquals("and a text size", stream.textSize, ruler.textSize, 0.01f)
    }

    @Test
    fun theProductPopupStillShowsTheWeightItself() {
        // What the popup keeps: the reading and the Use button. Only the diagnostic
        // strip moved.
        val view = inflate(R.layout.dialog_product_entry)
        listOf(R.id.llScaleWeight, R.id.etScaleWeight, R.id.btnUseScaleWeight, R.id.etQty)
            .forEach { assertNotNull(view.findViewById<View>(it)) }
    }
}
