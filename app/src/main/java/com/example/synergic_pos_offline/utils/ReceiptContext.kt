package com.example.synergic_pos_offline.utils

import android.content.Context

/**
 * The context a receipt is built with.
 *
 * Receipt text is sized in `sp`, which the platform scales by whatever font size
 * the device is set to. That is right for the app's own screens and wrong for a
 * receipt: the slip is laid out against a fixed paper width, so a device set to
 * large text prints a bill whose columns no longer fit the roll, and one set to
 * small text prints a bill nobody can read - neither of which is anything the
 * operator asked for.
 *
 * Wrapping the context here pins the font scale at 1, so a receipt comes off the
 * printer identically on every device. It is applied once, where the receipt is
 * inflated and measured, rather than by converting every `sp` in the layouts to
 * `dp` - which would have to be got right in four layouts and every line built in
 * code, and would silently regress the first time someone added a row.
 */
object ReceiptContext {

    /**
     * [context] with the device's font scale neutralised, carrying the app theme so
     * the Material views in the receipt layouts still inflate.
     */
    fun standardFontScale(context: Context): Context = FixedFontScale.wrap(context)
}
