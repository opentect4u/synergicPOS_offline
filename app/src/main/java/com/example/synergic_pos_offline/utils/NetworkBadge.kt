package com.example.synergic_pos_offline.utils

import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.synergic_pos_offline.R

/** Styles the shared network badge (see view_network_badge.xml) for the given state. */
object NetworkBadge {

    fun bind(root: View, online: Boolean) {
        val badge = root.findViewById<View>(R.id.badgeNetwork) ?: return
        val dot = root.findViewById<View>(R.id.badgeDot)
        val text = root.findViewById<TextView>(R.id.badgeText)

        val context = root.context
        val strong = ContextCompat.getColor(
            context, if (online) R.color.menu_sale_icon else R.color.menu_delete_icon
        )
        val light = ContextCompat.getColor(
            context, if (online) R.color.menu_sale else R.color.menu_delete
        )

        badge.backgroundTintList = ColorStateList.valueOf(light)
        dot.backgroundTintList = ColorStateList.valueOf(strong)
        text.setTextColor(strong)
        text.text = if (online) "Online" else "Offline"
    }
}
