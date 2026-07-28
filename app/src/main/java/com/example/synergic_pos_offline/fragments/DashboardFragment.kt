package com.example.synergic_pos_offline.fragments

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.ThemeManager

/**
 * The post-login home. A two-tab segmented control switches between:
 *  - "Dashboard" — the live snapshot / KPI screen ([DashboardHomeFragment]), and
 *  - "Menu"      — the existing tile menu grid ([MenuFragment]).
 */
class DashboardFragment : Fragment(), TitledScreen {

    override val screenTitle = "Dashboard"

    private lateinit var tabDashboard: TextView
    private lateinit var tabMenu: TextView
    private lateinit var ulDashboard: View
    private lateinit var ulMenu: View
    private var showingDashboard = true

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tabDashboard = view.findViewById(R.id.tabDashboard)
        tabMenu = view.findViewById(R.id.tabMenu)
        ulDashboard = view.findViewById(R.id.ulDashboard)
        ulMenu = view.findViewById(R.id.ulMenu)

        tabDashboard.setOnClickListener { select(true) }
        tabMenu.setOnClickListener { select(false) }

        if (savedInstanceState == null) select(true) else styleTabs()
    }

    private fun select(dashboard: Boolean) {
        showingDashboard = dashboard
        styleTabs()
        val fragment: Fragment = if (dashboard) DashboardHomeFragment() else MenuFragment()
        childFragmentManager.beginTransaction()
            .replace(R.id.dashboardTabContainer, fragment)
            .commit()
    }

    /** Re-applies the accent to the tabs and rebuilds the visible child on theme change. */
    fun onThemeChanged() {
        if (!isAdded) return
        styleTabs()
        (childFragmentManager.findFragmentById(R.id.dashboardTabContainer) as? DashboardHomeFragment)?.refreshTheme()
    }

    /** Underline-tab styling, matching the Sale page's category strip. */
    private fun styleTabs() {
        val accent = ThemeManager.getThemeColor(requireContext())
        val idle = Color.parseColor("#8A8A8A")
        tabDashboard.setTextColor(if (showingDashboard) accent else idle)
        tabMenu.setTextColor(if (showingDashboard) idle else accent)
        ulDashboard.setBackgroundColor(if (showingDashboard) accent else Color.TRANSPARENT)
        ulMenu.setBackgroundColor(if (showingDashboard) Color.TRANSPARENT else accent)
    }
}
