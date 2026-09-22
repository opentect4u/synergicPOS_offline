package com.example.synergic_pos_offline.fragments

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.ThemeManager
import com.example.synergic_pos_offline.utils.UserManual
import com.example.synergic_pos_offline.utils.UserManual.Block
import com.example.synergic_pos_offline.utils.UserManualDoc
import com.example.synergic_pos_offline.utils.UserManualPdf
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout

/**
 * About App > User Manual: the operator's manual, a tab per chapter.
 *
 * ## A page rather than a dialog
 *
 * The manual is read WHILE something is being done - a till set up, a printer chased
 * down - so it wants the room a screen has and a Back that behaves like every other
 * Back in the app. A modal over the screen the reader is working on is the wrong shape
 * for that, and a modal per chapter would make moving between chapters a matter of
 * closing one and finding the next.
 *
 * ## Tabs rather than one long scroll
 *
 * The two ways it gets read are different jobs. Somebody setting a new till up works
 * through Getting Started once, front to back. Somebody at a counter with a printer
 * that will not print wants Troubleshooting and nothing else, now. A single scroll
 * serves the first and buries the second.
 *
 * ## This class owns the type, [UserManual] owns the words
 *
 * A chapter arrives as [Block]s - heading, paragraph, steps, bullets, caution - and the
 * builders below decide what each looks like. Everything to do with size, weight and
 * spacing is in the constants at the bottom, so making the manual bigger is one edit
 * rather than six chapters of re-indenting.
 */
class UserManualFragment : Fragment(), TitledScreen {

    override val screenTitle = "User Manual"

    private val chapters = UserManual.ALL

    /** Which chapter is showing, kept across a rotation. */
    private var currentTab = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_user_manual, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Held in a local, because adding the tabs below selects the first one, and the
        // listener writes that back over currentTab before we have used it.
        val restored = (savedInstanceState?.getInt(STATE_TAB, 0) ?: 0)
            .coerceIn(chapters.indices)

        val tabs = view.findViewById<TabLayout>(R.id.tabsUserManual)
        val body = view.findViewById<LinearLayout>(R.id.containerManualBody)
        val scroll = view.findViewById<ScrollView>(R.id.scrollUserManual)

        val accent = ThemeManager.getThemeColor(requireContext())
        tabs.setSelectedTabIndicatorColor(accent)
        tabs.setTabTextColors(
            ContextCompat.getColor(requireContext(), R.color.text_secondary), accent
        )

        bindTabs(
            tabs = tabs,
            titles = chapters.map { it.first },
            restored = restored,
            onShow = { position ->
                currentTab = position
                render(body, chapters.getOrNull(position)?.second.orEmpty(), accent)
                // Back to the top on every change. Left where it was, a short chapter
                // opens already scrolled past its own beginning, which reads as a
                // chapter that starts halfway through a sentence.
                scroll.scrollTo(0, 0)
            },
            // Re-tapping the tab already showing is a request to start again from the
            // top - the reader has scrolled down and wants the beginning back.
            onTop = { scroll.scrollTo(0, 0) }
        )

        download(view, R.id.btnDownloadManual, accent, "PDF") {
            UserManualPdf.save(requireContext())
        }
        download(view, R.id.btnDownloadManualDoc, accent, "Word document") {
            UserManualDoc.save(requireContext())
        }
    }

    /**
     * Wires one of the download buttons to [write].
     *
     * Each writes the WHOLE manual, not the chapter showing. A reader who wants the file
     * wants it to take away or to print, and a manual missing the five chapters they
     * were not looking at when they pressed the button is not a manual.
     *
     * Written on the calling thread, as the report exports are: it is a dozen pages of
     * text, and a progress spinner over work that finishes before it can be drawn is
     * worse than nothing.
     */
    private fun download(
        view: View,
        id: Int,
        accent: Int,
        format: String,
        write: () -> String
    ) {
        view.findViewById<MaterialButton>(id).apply {
            backgroundTintList = ColorStateList.valueOf(accent)
            setOnClickListener {
                val saved = runCatching(write)
                toast(
                    saved.fold(
                        onSuccess = { "User manual saved to $it" },
                        onFailure = { "Could not save the user manual as a $format" }
                    )
                )
            }
        }
    }

    private fun toast(message: String) =
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

    /** Replaces whatever is in [container] with [blocks], laid out for reading. */
    private fun render(container: LinearLayout, blocks: List<Block>, accent: Int) {
        container.removeAllViews()
        blocks.forEachIndexed { index, block ->
            // A heading opens a section, so it wants air above it - except at the very
            // top, where that air would only push the chapter down the screen.
            val first = index == 0
            when (block) {
                is Block.Heading -> container.addView(
                    heading(block.text, accent), spacing(top = if (first) 0f else GAP_SECTION)
                )

                is Block.Para -> container.addView(
                    paragraph(block.text), spacing(top = if (first) 0f else GAP_BLOCK)
                )

                is Block.Steps -> block.items.forEachIndexed { n, item ->
                    container.addView(
                        listRow("${n + 1}.", item, accent, bold = true),
                        // Steps of one procedure sit closer together than separate
                        // blocks do, so the list reads as one thing.
                        spacing(top = if (n == 0) GAP_BLOCK else GAP_LIST_ITEM)
                    )
                }

                is Block.Bullets -> block.items.forEachIndexed { n, item ->
                    container.addView(
                        listRow("•", item, accent, bold = false),
                        spacing(top = if (n == 0) GAP_BLOCK else GAP_LIST_ITEM)
                    )
                }

                is Block.Caution -> container.addView(
                    caution(block.text), spacing(top = if (first) 0f else GAP_SECTION)
                )
            }
        }
    }

    private fun heading(text: String, accent: Int) = TextView(requireContext()).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_HEADING)
        setTypeface(null, Typeface.BOLD)
        // The accent rather than the body colour: a reader thumbing for a section is
        // looking for the shape of the page, and colour is what they find first.
        setTextColor(accent)
    }

    private fun paragraph(text: String) = TextView(requireContext()).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_BODY)
        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_main))
        setLineSpacing(dp(LINE_SPACING_DP), 1f)
    }

    /**
     * One list line: its marker in a column of its own, the text beside it.
     *
     * The marker is a sibling view rather than characters at the front of the string so
     * that a wrapped line lines up under the words above it and not under the number -
     * a hanging indent, which no amount of spaces inside the text can produce at a width
     * you do not know in advance.
     */
    private fun listRow(marker: String, text: String, accent: Int, bold: Boolean) =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(requireContext()).apply {
                this.text = marker
                setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_BODY)
                setTextColor(accent)
                if (bold) setTypeface(null, Typeface.BOLD)
                gravity = Gravity.END
                width = dp(MARKER_WIDTH_DP).toInt()
                setLineSpacing(dp(LINE_SPACING_DP), 1f)
            })
            addView(paragraph(text).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    marginStart = dp(MARKER_SPACING_DP).toInt()
                }
            })
        }

    /**
     * A caution: an amber panel with a bar down its left edge.
     *
     * Set apart deliberately. These are the lines that cost a shop its data - a restore
     * over live books, a barcode replaced under labels already on the shelf - and a
     * warning that looks like the paragraph above it is a warning that gets skimmed.
     */
    private fun caution(text: String) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.menu_report))
        addView(View(requireContext()).apply {
            setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.menu_report_icon)
            )
            layoutParams = LinearLayout.LayoutParams(dp(CAUTION_BAR_DP).toInt(), MATCH)
        })
        addView(paragraph(text).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            val pad = dp(CAUTION_PADDING_DP).toInt()
            setPadding(pad, pad, pad, pad)
        })
    }

    /** Full-width layout params with [top] dp of margin above. */
    private fun spacing(top: Float) = LinearLayout.LayoutParams(MATCH, WRAP).apply {
        topMargin = dp(top).toInt()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
    }

    companion object {

        /**
         * Fills [tabs] with [titles] and opens on [restored], calling [onShow] with the
         * chapter that should be painted - once on the way in, and again on every tap.
         *
         * ## The order of the two statements is the whole of this function
         *
         * TabLayout selects the first tab as part of adding it. With the tabs added
         * before the listener, that selection happened with nothing listening, and the
         * `select()` afterwards then found tab 0 already selected - which dispatches a
         * RE-selection, not a selection. So [onShow] never ran and the manual opened on
         * a blank Getting Started, coming to life only when a tab was tapped.
         *
         * Pulled out of [onViewCreated] and given a name because the bug was an ordering
         * anyone would reorder back: here the two lines are three lines apart with the
         * reason between them, and a test drives this same function.
         */
        fun bindTabs(
            tabs: TabLayout,
            titles: List<String>,
            restored: Int,
            onShow: (Int) -> Unit,
            onTop: () -> Unit
        ) {
            tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) = onShow(tab.position)
                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) = onTop()
            })

            // Listener first. See above.
            titles.forEach { tabs.addTab(tabs.newTab().setText(it)) }

            // The first chapter is showing by now. This moves off it only when a rotation
            // is being restored onto another one; on tab 0 it is a reselection that does
            // no more than put the scroll back at the top.
            tabs.getTabAt(restored.coerceIn(titles.indices))?.select()
        }

        private const val STATE_TAB = "user_manual_tab"

        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        /**
         * The type scale, in sp.
         *
         * Read at arm's length on a till bolted to a counter, not held at reading
         * distance, and often by someone who has come to the screen because something
         * has gone wrong. Both argue for larger than an app's ordinary body text.
         */
        private const val SIZE_BODY = 16f
        private const val SIZE_HEADING = 19f

        private const val LINE_SPACING_DP = 5f
        private const val GAP_SECTION = 22f
        private const val GAP_BLOCK = 12f
        private const val GAP_LIST_ITEM = 9f

        private const val MARKER_WIDTH_DP = 20f
        private const val MARKER_SPACING_DP = 10f
        private const val CAUTION_BAR_DP = 4f
        private const val CAUTION_PADDING_DP = 14f
    }
}
