package com.example.synergic_pos_offline.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.SettingsHighlighter
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.tabs.TabLayout

/**
 * The Printer Settings tile: two tabs over one screen.
 *
 * "Connections" is the printer list that has always been here - which printer, on
 * which transport, at which address ([OperatingPrinterFragment]). "Print Template"
 * is the other half of the same question: what the bill those printers print
 * actually looks like ([PrintTemplateFragment]).
 *
 * Each tab is a child fragment swapped into the content frame rather than a
 * ViewPager page, so the connection list is not kept alive - and re-scanning
 * Bluetooth - while the template is on screen.
 */
class PrintSettingsFragment : Fragment(), TitledScreen {

    override val screenTitle = "Printer Settings"

    /** Tab title paired with the fragment that fills it. Order is the tab order. */
    private val tabs: List<Pair<String, () -> Fragment>> = listOf(
        "Connections" to { OperatingPrinterFragment() },
        "Print Template" to { PrintTemplateFragment() }
    )

    /** Which tab's fragment is currently in the frame; -1 until one is put there. */
    private var currentTab = -1

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_print_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Restored first: the child fragment manager brings its own fragment back
        // across a rotation, so [show] must recognise that tab as already filled
        // rather than replacing it with a fresh, empty one.
        currentTab = savedInstanceState?.getInt(STATE_TAB, -1) ?: -1
        val start = if (currentTab >= 0) currentTab else requestedTab()

        val tabLayout = view.findViewById<TabLayout>(R.id.tabsPrintSettings)
        tabs.forEach { (title, _) -> tabLayout.addTab(tabLayout.newTab().setText(title)) }

        val accent = ThemeManager.getThemeColor(requireContext())
        tabLayout.setSelectedTabIndicatorColor(accent)
        tabLayout.setTabTextColors(
            ContextCompat.getColor(requireContext(), R.color.text_secondary), accent
        )
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = show(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        tabLayout.getTabAt(start)?.select()
        // Selecting the tab that is already selected - the first one - raises no
        // event, so the content is put up here rather than left blank.
        show(start)

        ThemeManager.applyTheme(view)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
    }

    /**
     * Which tab to open on. A direct caller passes [ARG_TAB]; the settings search
     * instead replaces the arguments wholesale with the setting it matched, so the
     * name it searched for is what picks the tab there.
     */
    private fun requestedTab(): Int {
        arguments?.let { args ->
            if (args.containsKey(ARG_TAB)) return args.getInt(ARG_TAB).coerceIn(0, tabs.lastIndex)
            val searched = args.getString(SettingsHighlighter.ARG_SETTING)
            if (searched?.contains("template", ignoreCase = true) == true) return TAB_TEMPLATE
        }
        return TAB_CONNECTIONS
    }

    private fun show(position: Int) {
        val alreadyThere = position == currentTab &&
            childFragmentManager.findFragmentById(R.id.printSettingsContent) != null
        if (alreadyThere) return
        currentTab = position
        childFragmentManager.beginTransaction()
            .replace(R.id.printSettingsContent, tabs[position].second())
            .commit()
    }

    companion object {
        const val ARG_TAB = "print_settings_tab"
        const val TAB_CONNECTIONS = 0
        const val TAB_TEMPLATE = 1

        private const val STATE_TAB = "print_settings_current_tab"

        /** Opens the screen on [tab] - see [TAB_CONNECTIONS] / [TAB_TEMPLATE]. */
        fun newInstance(tab: Int): PrintSettingsFragment = PrintSettingsFragment().apply {
            arguments = Bundle().apply { putInt(ARG_TAB, tab) }
        }
    }
}
