package com.example.synergic_pos_offline.utils

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.view.ContextThemeWrapper
import com.example.synergic_pos_offline.R

/**
 * Builds a context whose text ignores the device's font-size setting.
 *
 * Text sized in `sp` is multiplied by whatever font scale the device is set to.
 * That is right for a screen laid out to flow - it is the whole point of the
 * setting - and wrong wherever the surroundings cannot grow with the text: the
 * words get bigger, the box they are in does not, and the difference comes off the
 * end of the label.
 *
 * Used in the two places in this app where that is true:
 *
 *  * a receipt, laid out against a paper width the device has no say over (see
 *    [ReceiptContext]);
 *  * the reusable dialogs, whose cards are a fixed number of `dp` across (see
 *    [DialogUtils]).
 *
 * Applied once where the layout is inflated, rather than by converting every `sp`
 * in those layouts to `dp` - which would have to be got right in every layout and
 * every row built in code, and would silently regress the first time someone added
 * a field.
 */
object FixedFontScale {

    /** The scale these layouts are always drawn at, whatever the device is set to. */
    const val STANDARD = 1f

    /**
     * [context] with the device's font scale neutralised, carrying the app theme so
     * the Material views in those layouts still inflate.
     *
     * The wrapper keeps the original as its base, so a dialog built on it still
     * reaches the Activity it belongs to and can find a window to show itself in.
     */
    fun wrap(context: Context): Context {
        // A default Configuration has fontScale 1 and every other field undefined,
        // so merging it over the base changes the scale and nothing else.
        val override = Configuration().apply { fontScale = STANDARD }
        return ContextThemeWrapper(context, R.style.Theme_Synergic_POS_Offline).apply {
            applyOverrideConfiguration(override)
        }
    }
}
