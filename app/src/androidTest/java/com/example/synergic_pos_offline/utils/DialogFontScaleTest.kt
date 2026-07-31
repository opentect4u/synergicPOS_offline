package com.example.synergic_pos_offline.utils

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reusable dialogs are laid out in cards a fixed number of `dp` across, so their
 * text must not follow the device's font size: a device set to large text would
 * otherwise have the same width to fit bigger words into, and the surplus would come
 * off the ends of the labels.
 *
 * Each dialog is inflated from a context pretending the device is set to large and
 * to small text, and has to come out the size it does at normal text with nothing
 * clipped - see [FixedFontScale].
 */
@RunWith(AndroidJUnit4::class)
class DialogFontScaleTest {

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

    /** The app's context as it would be on a device set to [scale] text size. */
    private fun atFontScale(scale: Float): Context =
        ContextThemeWrapper(context, R.style.Theme_Synergic_POS_Offline).apply {
            applyOverrideConfiguration(Configuration().apply { fontScale = scale })
        }

    /** The scales a device can realistically be set to, either side of normal. */
    private val scales = listOf(0.85f, 1f, 1.3f, 2f)

    /** Inflates [layout] the way DialogUtils does and lays it out at its natural size. */
    private fun layOut(ctx: Context, layout: Int, fill: (View) -> Unit): View {
        val view = LayoutInflater.from(FixedFontScale.wrap(ctx)).inflate(layout, null)
        fill(view)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    /** Every character the labels in [root] were asked to draw but had to drop. */
    private fun ellipsised(root: View): List<String> = buildList {
        fun walk(v: View) {
            if (v is TextView && v.visibility == View.VISIBLE) {
                val layout = v.layout
                val dropped = (0 until (layout?.lineCount ?: 0))
                    .sumOf { layout!!.getEllipsisCount(it) }
                if (dropped > 0) add("\"${v.text}\" lost $dropped char(s)")
            }
            if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
        }
        walk(root)
    }

    /**
     * Lays the dialog out at every scale and asserts nothing about it moves or is
     * clipped.
     */
    private fun assertHolds(label: String, layout: Int, fill: (View) -> Unit) {
        val sizes = scales.map { scale ->
            val view = onMain { layOut(atFontScale(scale), layout, fill) }
            val lost = ellipsised(view)
            assertTrue("$label truncated at font scale $scale: $lost", lost.isEmpty())
            scale to (view.measuredWidth to view.measuredHeight)
        }

        val normal = sizes.first { it.first == 1f }.second
        sizes.forEach { (scale, size) ->
            assertEquals(
                "$label changed size at font scale $scale: $size vs $normal at normal text",
                normal, size
            )
        }
        assertTrue("$label collapsed to $normal", normal.first > 0 && normal.second > 0)
    }

    /**
     * Guards the tests below from passing for the wrong reason.
     *
     * They assert that something does *not* change with the font scale, which is
     * what a harness that never applied one would report too. This checks the
     * opposite directly: an `sp`-sized view built from these contexts really does
     * grow, so a dialog that holds its size is holding it against a scale that was
     * applied.
     */
    @Test
    fun theHarnessReallyChangesTheFontScale() {
        val heights = listOf(1f, 2f).map { scale ->
            onMain {
                val text = TextView(atFontScale(scale)).apply {
                    text = "Dialog"
                    textSize = 16f
                }
                text.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                text.measuredHeight
            }
        }
        assertTrue(
            "the test harness does not actually change the font scale ($heights)",
            heights[1] > heights[0]
        )
    }

    /**
     * The bug these tests guard, reproduced: inflated straight from a large-text
     * context - as the dialogs were before [FixedFontScale] was applied to them -
     * the card cannot hold its size, because its width is fixed and its text is not.
     *
     * Without this, the tests below would pass just as happily on a build where the
     * fix had been taken out and nothing scaled at all.
     */
    @Test
    fun withoutTheFixTheCardCannotHoldItsSize() {
        val sizes = listOf(1f, 2f).map { scale ->
            onMain {
                val view = LayoutInflater.from(atFontScale(scale))
                    .inflate(R.layout.dialog_common, null)
                view.findViewById<TextView>(R.id.tvDialogMessage).text =
                    "Ramesh Kumar still has an outstanding balance of 3,300.00."
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                view.measuredHeight
            }
        }
        assertTrue(
            "an unpinned dialog should grow with the font scale, so the pinned one " +
                "holding its size means something ($sizes)",
            sizes[1] > sizes[0]
        )
    }

    /** The confirm / success card, with the longest labels a caller realistically passes. */
    @Test
    fun theMessageDialogHoldsItsSizeAtAnyDeviceFontScale() {
        assertHolds("dialog_common", R.layout.dialog_common) { view ->
            view.findViewById<TextView>(R.id.tvDialogTitle).text = "Delete this customer?"
            view.findViewById<TextView>(R.id.tvDialogMessage).text =
                "Ramesh Kumar still has an outstanding balance of 3,300.00. " +
                    "Deleting the record cannot be undone."
            view.findViewById<TextView>(R.id.btnDialogPositive).text = "Delete permanently"
            view.findViewById<TextView>(R.id.btnDialogNegative).text = "Keep customer"
        }
    }

    /** The form card, filled the way [DialogUtils.showForm] fills it. */
    @Test
    fun theFormDialogHoldsItsSizeAtAnyDeviceFontScale() {
        assertHolds("dialog_form", R.layout.dialog_form) { view ->
            view.findViewById<TextView>(R.id.tvFormTitle).text = "Edit customer record"
            view.findViewById<TextView>(R.id.btnFormPositive).text = "Save changes"
            view.findViewById<TextView>(R.id.btnFormNegative).text = "Cancel"

            val grid = view.findViewById<android.widget.GridLayout>(R.id.glFields)
            val inflater = LayoutInflater.from(grid.context)
            listOf("Customer name", "Phone number", "GSTIN", "Billing address").forEach { label ->
                val til = inflater.inflate(R.layout.item_form_field, grid, false)
                    as com.google.android.material.textfield.TextInputLayout
                til.hint = label
                grid.addView(til)
            }
        }
    }

    /**
     * The buttons are free to take a second line rather than being pinned to one and
     * ellipsised - a label the caller chose is worth more than a tidy single row, and
     * "Delete perm..." is not a button anyone should have to tap.
     */
    @Test
    fun theButtonsWrapRatherThanTruncate() {
        val view = onMain {
            layOut(atFontScale(1f), R.layout.dialog_common) { root ->
                root.findViewById<TextView>(R.id.btnDialogPositive).text =
                    "Print and complete this sale"
            }
        }
        val button = view.findViewById<TextView>(R.id.btnDialogPositive)
        assertTrue("the button label was truncated", ellipsised(view).isEmpty())
        assertTrue("the button should be able to take a second line", button.maxLines > 1)
    }
}
