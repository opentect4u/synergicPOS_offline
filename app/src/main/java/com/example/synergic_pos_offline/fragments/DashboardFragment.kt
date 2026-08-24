package com.example.synergic_pos_offline.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R

/**
 * The post-login home: the live snapshot / KPI screen ([DashboardHomeFragment]).
 *
 * It used to be a two-tab screen - "Dashboard" beside "Menu", the second holding a
 * grid of tiles leading to the same destinations the sidebar leads to. The tabs are
 * gone. The sidebar is on every screen in the app and the tile grid was only on this
 * one, so the second tab was a duplicate route that also made the home screen ask a
 * question ("figures, or menu?") before it showed anything.
 *
 * [MenuFragment] is left in the codebase but is no longer reached from here.
 */
class DashboardFragment : Fragment(), TitledScreen {

    override val screenTitle = "Dashboard"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Only on a fresh open. A rotation already has the child in place, and
        // replacing it would throw away whatever it had loaded.
        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.dashboardTabContainer, DashboardHomeFragment())
                .commit()
        }
    }

    /** Rebuilds the dashboard's own colours when the palette changes. */
    fun onThemeChanged() {
        if (!isAdded) return
        (childFragmentManager.findFragmentById(R.id.dashboardTabContainer)
            as? DashboardHomeFragment)?.refreshTheme()
    }
}
