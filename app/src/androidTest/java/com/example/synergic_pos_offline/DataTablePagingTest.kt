package com.example.synergic_pos_offline

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.fragments.DataRow
import com.example.synergic_pos_offline.fragments.DataTableFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The master table pages its rows, and a row's cells say what the row holds.
 *
 * [DataTableFragment] backs every master screen - Products, Customers, Categories - so a
 * fault here is a fault in all of them at once. Two things are checked:
 *
 *  - **Paging.** The table has always rendered a page at a time and appended the next as
 *    the bottom nears; this pins that a large catalogue puts one page on the adapter
 *    rather than all of it, which is what keeps opening the screen instant.
 *  - **Cells.** Row cells are now built once per holder and only re-filled on each bind,
 *    instead of being torn down and re-allocated every time a row crossed the screen.
 *    That is a real change to how rows are rendered, so this checks the rendering: the
 *    right text, in the right column, on the right row - including after recycling,
 *    which is when a half-done rebind shows up as one product wearing another's name.
 *
 * A synthetic subclass supplies the rows, so none of this needs a database or a login.
 */
@RunWith(AndroidJUnit4::class)
class DataTablePagingTest {

    /** A table of [ROWS] rows whose cells say which row and column they are. */
    class FakeTable : DataTableFragment() {
        override val screenTitle = "Fake"
        override val columns = listOf("S.No", "Name", "Code")
        override val showsThumbnails = false

        override fun loadRows(): MutableList<DataRow> = (0 until ROWS).map { i ->
            DataRow(id = "$i", cells = listOf("${i + 1}", "Product $i", "CODE-$i"))
        }.toMutableList()

        companion object {
            const val ROWS = 450
        }
    }

    /**
     * Launches the table and hands the list to [block], once it has really been laid out.
     *
     * The scenario hosts the fragment in a real container, so the view is measured and
     * laid out by the framework. An earlier version of this called `measure`/`layout` by
     * hand with invented sizes, which fought that pass rather than waiting for it.
     */
    private fun onTable(block: (RecyclerView) -> Unit) {
        val scenario =
            launchFragmentInContainer<FakeTable>(themeResId = R.style.Theme_Synergic_POS_Offline)
        idle()
        scenario.onFragment { fragment ->
            val rv = fragment.requireView().findViewById<RecyclerView>(R.id.rvTable)
            assertNotNull("the table should have a list", rv)
            block(rv)
        }
    }

    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()

    /** Runs [action] on the main thread and waits for the frame it causes. */
    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
        idle()
    }

    @Test
    fun opensWithOnePageRatherThanTheWholeCatalogue() {
        onTable { rv ->
            val shown = rv.adapter!!.itemCount
            assertTrue(
                "a ${FakeTable.ROWS}-row table put $shown rows on the adapter at once",
                shown < FakeTable.ROWS
            )
            // Enough to fill any screen this runs on, so the scroll listener can fire.
            assertTrue("only $shown rows on the first page", shown >= 50)
        }
    }

    @Test
    fun appendsTheNextPageWhenScrolledToTheBottom() {
        val scenario =
            launchFragmentInContainer<FakeTable>(themeResId = R.style.Theme_Synergic_POS_Offline)
        idle()

        var list: RecyclerView? = null
        var firstPage = 0
        scenario.onFragment {
            list = it.requireView().findViewById(R.id.rvTable)
            firstPage = list!!.adapter!!.itemCount
        }

        // A real scroll, not scrollToPosition: the table appends its next page from
        // onScrolled, and a position jump does not dispatch one. This is what a flick
        // down the list amounts to.
        repeat(6) { onMain { list!!.scrollBy(0, 4000) } }

        assertTrue(
            "scrolling to the bottom should have appended a page, still $firstPage",
            list!!.adapter!!.itemCount > firstPage
        )
    }

    @Test
    fun aRowShowsItsOwnValuesInTheRightColumns() {
        onTable { rv ->
            val holder = rv.findViewHolderForAdapterPosition(0)
            assertNotNull("the first row should be laid out", holder)
            assertEquals(
                listOf("1", "Product 0", "CODE-0"),
                cellTextsOf(holder!!.itemView)
            )
        }
    }

    /**
     * A recycled holder carries the new row's values, not the old one's.
     *
     * The failure this guards against: cells are no longer rebuilt per bind, so anything
     * left unset in the re-fill keeps the previous row's content - which on a product
     * list is one product shown under another's name and code.
     */
    @Test
    fun aRecycledRowCarriesNoneOfThePreviousRowsText() {
        onTable { rv ->
            val adapter = rv.adapter!!
            val holder = rv.findViewHolderForAdapterPosition(0)!!
            // Bind the same holder to a later row, exactly as recycling would.
            adapter.bindViewHolder(holder, 7)
            assertEquals(
                listOf("8", "Product 7", "CODE-7"),
                cellTextsOf(holder.itemView)
            )
        }
    }

    /** The text of each cell in a row, left to right. */
    private fun cellTextsOf(row: View): List<String> {
        val cells = row.findViewById<LinearLayout>(R.id.llCells)
        return (0 until cells.childCount).mapNotNull {
            (cells.getChildAt(it) as? TextView)?.text?.toString()
        }
    }
}
