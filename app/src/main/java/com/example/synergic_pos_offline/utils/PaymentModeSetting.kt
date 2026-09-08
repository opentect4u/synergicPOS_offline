package com.example.synergic_pos_offline.utils

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.example.synergic_pos_offline.database.AppSettingsDao

/**
 * App Settings' "Payment Mode": whether this till asks HOW a sale was paid - and what
 * it means when it does not.
 *
 * ## Off does not mean "unknown". It means CASH.
 *
 * A shop that turns the question off is not saying it has stopped taking money. It is
 * saying it only ever takes one kind, so being asked which is a tap it does not need
 * on every sale. Every sale on such a till is a cash sale, and that is a FACT about
 * the bill rather than the absence of one.
 *
 * Two things follow, and they are the whole reason this exists rather than each screen
 * reading the flag its own way:
 *
 * - **At the counter**, the mode tiles come down to Cash alone. Not hidden entirely -
 *   the operator should still see what the sale is being settled as, and a payment
 *   panel with nothing in it reads like something failed to load.
 * - **On the paper**, the slip still says `PAY MODE : CASH`. The mode was not asked,
 *   but it is known, and a till's own copy of a bill that does not say how the money
 *   came in is worth less to the shop than one that does.
 *
 * The three checkout screens - grocery, dine-in and the take-away quick pay - each had
 * their own answer to this before, which is how the restaurant side ended up offering
 * Card and Online on a till that had switched the question off.
 */
object PaymentModeSetting {

    /**
     * What a sale is settled as on a till that does not ask.
     *
     * Upper case because that is how the mode is stored and printed, and it is
     * compared against stored values.
     */
    const val CASH = "CASH"

    /** The same word as the checkout screens spell it in their own tile maps. */
    const val CASH_LABEL = "Cash"

    /**
     * Whether this till asks how a sale was paid.
     *
     * Defaults to TRUE if the setting cannot be read. A till whose settings table is
     * momentarily unreadable should show the operator every mode and let them choose,
     * not silently book a card payment as cash.
     */
    fun asked(context: Context): Boolean = runCatching {
        AppSettingsDao(context).load().paymentMode
    }.getOrDefault(true)

    /**
     * Takes the non-cash tiles off the screen, leaving Cash standing alone.
     *
     * A row left holding nothing but hidden tiles is collapsed too, so the panel
     * closes up instead of leaving a band of empty space where Card and Online were.
     * The check is "every child gone", so a row that still has a visible tile in it -
     * grocery's Cash sits beside Credit - is left exactly as it is.
     */
    fun cashOnly(vararg others: View) {
        others.forEach { it.visibility = View.GONE }
        others.mapNotNull { it.parent as? ViewGroup }.distinct().forEach { row ->
            val empty = (0 until row.childCount).all { row.getChildAt(it).visibility == View.GONE }
            if (empty) row.visibility = View.GONE
        }
    }
}
