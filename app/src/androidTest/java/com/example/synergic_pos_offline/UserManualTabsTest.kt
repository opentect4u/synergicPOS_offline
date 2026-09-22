package com.example.synergic_pos_offline

import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.fragments.UserManualFragment
import com.example.synergic_pos_offline.utils.UserManual
import com.google.android.material.tabs.TabLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The manual paints its first chapter without waiting to be tapped.
 *
 * The regression this guards: the tabs were added before the listener was attached, so
 * TabLayout's automatic selection of the first tab went unheard, and the `select()`
 * afterwards found tab 0 already selected - a RE-selection, which paints nothing. The
 * screen opened on an empty Getting Started.
 *
 * Nothing about that is visible in a view-tree assertion or a compile: both orderings
 * build, both leave six tabs on screen, and only one of them has any text under them.
 * The test drives [UserManualFragment.bindTabs] itself rather than a copy of it, so
 * putting the two statements back the wrong way round fails here.
 */
@RunWith(AndroidJUnit4::class)
class UserManualTabsTest {

    private val titles = UserManual.ALL.map { it.first }

    /**
     * A context carrying the app's theme.
     *
     * TabLayout will not inflate against a bare application context: it reads Material
     * attributes that only a Material theme defines - the same theme the screen itself
     * is built under.
     */
    private fun themed() = ContextThemeWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext,
        R.style.Theme_Synergic_POS_Offline
    )

    /** Binds on the main thread and returns the chapters it asked to paint, in order. */
    private fun shownChapters(restored: Int): List<Int> {
        val shown = mutableListOf<Int>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            UserManualFragment.bindTabs(
                tabs = TabLayout(themed()),
                titles = titles,
                restored = restored,
                onShow = { shown += it },
                onTop = {}
            )
        }
        return shown
    }

    @Test
    fun paintsTheFirstChapterOnOpening() {
        // Exactly the bug reported: opening the manual left Getting Started blank.
        assertEquals(listOf(0), shownChapters(restored = 0))
    }

    @Test
    fun opensOnTheChapterARotationWasLeftOn() {
        // The last one asked for is the one the reader ends on; tab 0 is painted on the
        // way past because adding it is what selects it.
        assertEquals(3, shownChapters(restored = 3).last())
    }

    @Test
    fun putsUpATabForEveryChapter() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val tabs = TabLayout(themed())
            UserManualFragment.bindTabs(tabs, titles, 0, {}, {})
            assertEquals(titles.size, tabs.tabCount)
            titles.forEachIndexed { i, title ->
                assertEquals(title, tabs.getTabAt(i)?.text)
            }
        }
    }

    @Test
    fun survivesAChapterIndexThatIsNoLongerThere() {
        // A rotation restoring a tab from a build with more chapters than this one. The
        // coerce is what keeps that from throwing on an empty screen.
        assertEquals(titles.size - 1, shownChapters(restored = 99).last())
    }
}
