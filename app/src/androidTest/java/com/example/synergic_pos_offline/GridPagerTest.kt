package com.example.synergic_pos_offline

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.utils.GridPager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sale screens' pager hands over a page at a time - on a grid AND on a plain list.
 *
 * [GridPager] backs the grocery tile grid, the restaurant tile grid and the category
 * product list off Sales. It used to cast its layout manager straight to
 * [GridLayoutManager] and give up if that failed, so a single-column list got no paging
 * at all and nothing said so - it simply laid the whole category on the adapter. That is
 * the case these tests exist for.
 */
@RunWith(AndroidJUnit4::class)
class GridPagerTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val items = (1..400).map { "Item $it" }

    /** A list showing whatever the pager has submitted. */
    private class Adapter(val shown: MutableList<String>) :
        RecyclerView.Adapter<Adapter.Holder>() {
        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 60
                )
            }
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.text.text = shown[position]
        }

        override fun getItemCount() = shown.size
    }

    /** Builds a laid-out list with [lm], paged, and returns the pieces. */
    private fun paged(lm: RecyclerView.LayoutManager): Triple<RecyclerView, Adapter, GridPager<String>> {
        lateinit var out: Triple<RecyclerView, Adapter, GridPager<String>>
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val shown = mutableListOf<String>()
            val adapter = Adapter(shown)
            val rv = RecyclerView(ctx).apply {
                layoutManager = lm
                this.adapter = adapter
            }
            val pager = GridPager<String>(rv) { page ->
                shown.clear()
                shown.addAll(page)
                adapter.notifyDataSetChanged()
            }
            rv.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
            )
            rv.layout(0, 0, 1000, 800)
            pager.set(items)
            out = Triple(rv, adapter, pager)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return out
    }

    @Test
    fun aPlainListGetsOnePageRatherThanTheWholeCategory() {
        val (_, adapter, _) = paged(LinearLayoutManager(ctx))
        assertTrue(
            "a ${items.size}-item category put ${adapter.shown.size} rows on the adapter",
            adapter.shown.size < items.size
        )
    }

    @Test
    fun aGridGetsOnePageToo() {
        val (_, adapter, _) = paged(GridLayoutManager(ctx, 4))
        assertTrue(
            "a ${items.size}-item grid put ${adapter.shown.size} tiles on the adapter",
            adapter.shown.size < items.size
        )
    }

    @Test
    fun aPlainListAppendsTheNextPageWhenScrolled() {
        // The regression this guards: with the old GridLayoutManager cast, scrolling a
        // linear list appended nothing, because the listener returned early every time.
        val (rv, adapter, _) = paged(LinearLayoutManager(ctx))
        val firstPage = adapter.shown.size

        repeat(8) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { rv.scrollBy(0, 3000) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }

        assertTrue(
            "scrolling a linear list should have appended a page, still $firstPage",
            adapter.shown.size > firstPage
        )
    }

    @Test
    fun everythingIsReachableByScrollingToTheEnd() {
        // Paging must not LOSE anything: what it changes is how much is drawn at once,
        // never what the category contains.
        val (rv, adapter, _) = paged(LinearLayoutManager(ctx))
        repeat(60) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { rv.scrollBy(0, 6000) }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals("every item should be reachable", items.size, adapter.shown.size)
        assertEquals("and in their original order", items, adapter.shown.toList())
    }

    @Test
    fun aShortListIsShownWhole() {
        // Fewer items than a page: nothing to page, and nothing hidden behind a scroll
        // that will never happen.
        lateinit var adapter: Adapter
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val shown = mutableListOf<String>()
            adapter = Adapter(shown)
            val rv = RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx)
                this.adapter = adapter
            }
            val pager = GridPager<String>(rv) { page ->
                shown.clear(); shown.addAll(page); adapter.notifyDataSetChanged()
            }
            rv.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
            )
            rv.layout(0, 0, 1000, 800)
            pager.set(listOf("only", "three", "items"))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(3, adapter.shown.size)
    }
}
