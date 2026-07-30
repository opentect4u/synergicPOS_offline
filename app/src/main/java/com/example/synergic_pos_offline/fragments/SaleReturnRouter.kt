package com.example.synergic_pos_offline.fragments

import android.content.Context
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.database.GeneralSettingsDao

/**
 * Decides which Sale Return screen the operator gets.
 *
 * The two are genuinely different jobs - one starts from a bill, the other from an
 * item - so rather than one screen with a mode switch inside it, the setting picks
 * the screen. Both entry points (the dashboard tile and the sidebar) come through
 * here, so they cannot disagree about which is in force.
 */
object SaleReturnRouter {

    /**
     * The return screen for the current settings, or null when Sale Return is off -
     * in which case the caller should say so rather than open anything.
     */
    fun screenFor(context: Context): Fragment? {
        val settings = GeneralSettingsDao(context).load()
        if (!settings.saleReturn) return null
        return when (settings.returnMode) {
            GeneralSettingsDao.ReturnMode.ITEM_WISE -> ItemWiseReturnFragment()
            // Bill-wise starts by finding the bill, which is the bill list in
            // picker mode - the same search and filters history uses.
            GeneralSettingsDao.ReturnMode.BILL_WISE -> BillListFragment.forReturn()
        }
    }

    /** Why there is no screen to open, for the caller to show. */
    const val DISABLED_MESSAGE = "Sale Return is switched off. Turn it on under Settings → General Settings."
}
