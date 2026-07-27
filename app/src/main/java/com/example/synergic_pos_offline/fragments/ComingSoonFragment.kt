package com.example.synergic_pos_offline.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.ThemeManager

/**
 * Generic placeholder for side-menu items that don't have a real screen yet.
 * Shows the item's title so the header and body stay in sync, and a "coming soon"
 * note — reached via [newInstance] with the menu title.
 */
class ComingSoonFragment : Fragment(), TitledScreen {

    override val screenTitle: String get() = arguments?.getString(ARG_TITLE).orEmpty().ifBlank { "Coming soon" }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_coming_soon, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.tvComingTitle).text = screenTitle
        ThemeManager.applyTheme(view)
    }

    companion object {
        private const val ARG_TITLE = "title"
        fun newInstance(title: String) = ComingSoonFragment().apply {
            arguments = Bundle().apply { putString(ARG_TITLE, title) }
        }
    }
}
