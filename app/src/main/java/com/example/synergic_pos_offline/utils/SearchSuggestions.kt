package com.example.synergic_pos_offline.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListPopupWindow
import android.widget.TextView
import com.example.synergic_pos_offline.R

/**
 * The suggestion list that drops out of the sale screen's search box while it is
 * being typed into. One implementation for both trades: a grocery's shelf and a
 * restaurant's menu ask the same question of a search box, so they get the same
 * answer to it and there is one place to change how it behaves.
 *
 * WHY A DROPDOWN AND NOT JUST THE FILTERED GRID. The grid narrows as you type
 * already, and for a shelf of twenty that is enough. It stops being enough as soon
 * as the catalogue is large: seven tiles to a row means the match you want can be
 * three rows down and off the bottom of the screen, so the operator types, looks,
 * scrolls, and looks again. The dropdown puts the best few matches in a column
 * directly under the cursor, in the order they matched, one tap from the order. The
 * grid still filters underneath it - this is the shortcut, not the replacement, and
 * dismissing it leaves the filtered grid exactly as it was.
 *
 * RANKING IS THE POINT. Matching is easy; ordering is what makes a list of eight
 * usable. A search for "chi" should offer Chicken Biryani before Butter Chicken,
 * because a name that STARTS with what was typed is nearly always the one being
 * reached for, and Kadai Chicken before either if that is its exact name. See
 * [rank]. An exact code match sorts above everything, since a scanned barcode is not
 * a guess - it is the operator naming one product exactly.
 *
 * Built on [ListPopupWindow] rather than a panel in the layout. It floats above the
 * grid instead of pushing it down, it dismisses itself on an outside tap or a back
 * press, and it needs no change to either sale screen's layout - both of which
 * arrange the search box in a plain vertical column that has nowhere to overlay
 * anything.
 */
class SearchSuggestions(
    private val context: Context,
    /** The view the list hangs from - the search box's own text field. */
    private val anchor: View,
    /** Theme accent, for the matched run inside a name and for the price. */
    private val accent: Int,
    /** A row was tapped: add this product to the order. */
    private val onPick: (Item) -> Unit
) {

    /**
     * One suggestion. Deliberately flat strings rather than either screen's own
     * product type: the two sale screens model a product differently (a grocery
     * carries stock and a barcode, a restaurant carries prep time and a food type)
     * and the row shows neither of those types - it shows a name, a line of context
     * and a price. Each screen maps its own product into this, and this file never
     * learns what a GridProduct or a Product is.
     */
    data class Item(
        /** Whatever the calling screen needs to find this product again. */
        val id: String,
        val name: String,
        /** The line under the name: category, number, prep time - already joined. */
        val meta: String,
        val price: String,
        /** Searchable codes (SKU, barcode). An exact hit here outranks any name. */
        val codes: List<String> = emptyList(),
        /**
         * The SCANNED code alone - the barcode, never the SKU.
         *
         * Kept apart from [codes] because only this one may fire a product into the
         * cart without being tapped. A SKU is the product's own row id, so the SKUs on
         * a small shelf are "1", "2", "3": auto-adding on an exact SKU match would put
         * product 1 in the cart the instant a "1" was typed, and no name beginning
         * with a digit could ever be searched for. A barcode is long and belongs to
         * the product, which is what makes it safe to act on. See [SCAN_MIN].
         */
        val barcode: String = "",
        /** "LOW" / "OUT", or blank for the rows that have nothing to warn about. */
        val badge: String = "",
        val badgeColor: Int = 0,
        /**
         * The product's picture, however the calling screen happens to hold it.
         *
         * Two fields rather than one because the two sale screens genuinely differ:
         * the grocery decodes every photo once into a cache for its tiles and has a
         * Bitmap to hand, the restaurant carries the raw bytes on each menu item.
         * Making either convert for this would be decoding a JPEG twice, or throwing
         * away a decode that has already been paid for, to satisfy a signature.
         * [bitmap] wins when both are set.
         */
        val bitmap: android.graphics.Bitmap? = null,
        val image: ByteArray? = null
    )

    /**
     * A scanned barcode resolved to exactly one product.
     *
     * What happens next is the screen's to decide, and the two trades answer it
     * differently on purpose. The grocery puts the line straight on the bill - a
     * counter with a gun is scanning to sell, and a popup per item is what the gun
     * was bought to avoid. The restaurant sends it through the same path a tapped
     * tile takes, so Direct Add to Cart and the quantity popup still apply: an order
     * is built by conversation, and a scan there is just a faster way of naming a
     * dish, not a decision to serve one.
     *
     * Null leaves a scan behaving like any other query: it just filters.
     */
    var onExactCode: ((Item) -> Unit)? = null

    private var popup: ListPopupWindow? = null
    private val rows = mutableListOf<Item>()
    private var query = ""
    private val adapter = SuggestionAdapter()

    /** Holds the deferred open, so a scan can outrun it. See [update]. */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingShow: Runnable? = null

    /**
     * Re-runs the search on every keystroke: acts on a scan at once, and opens the
     * list only once typing has stopped.
     *
     * THE TWO HALVES ARE TIMED DIFFERENTLY, AND THAT IS THE WHOLE DESIGN.
     *
     * A barcode gun is a keyboard that types thirteen digits in about a tenth of a
     * second. Run per keystroke, the suggestion list would open on digit two, redraw
     * eleven times against partial codes, and vanish - a panel flashing under the
     * cursor on every scan, offering products nobody was looking for. So the list is
     * held back by [SHOW_DELAY_MS]: each keystroke cancels the pending open and starts
     * the clock again, and a scanner never leaves a gap that long between characters,
     * so it never opens at all for a scan. A person does pause, so they still get it -
     * a sixth of a second after they stop, which reads as instant.
     *
     * The scan itself is NOT delayed. It is checked first and fires immediately, so
     * the item is on the bill by the time the gun beeps.
     */
    fun update(text: String, pool: List<Item>) {
        query = text.trim()
        cancelPendingShow()
        if (query.length < MIN_QUERY) { dismiss(); return }

        // A scanned barcode naming exactly one product goes straight through - no list
        // to pick from, no confirmation of what the gun already said. Checked against
        // the whole catalogue rather than the ranked matches, so the answer does not
        // depend on how the query happened to sort.
        //
        // Both conditions matter. It has to be THE barcode, not any code - see the
        // note on [Item.barcode] for why a SKU may not do this - and it has to resolve
        // to ONE product: two products sharing a barcode is a data problem, and
        // guessing which was meant would put the wrong thing in the cart silently. In
        // that case it falls through and lists them to be chosen from.
        val typed = normalizeCode(query)
        val scanned = if (typed.length < SCAN_MIN) null else pool.singleOrNull {
            it.barcode.isNotBlank() && normalizeCode(it.barcode) == typed
        }
        if (scanned != null) {
            dismiss()
            onExactCode?.invoke(scanned)
            return
        }

        // Everything else: open the list, but only once the typing stops.
        val pending = Runnable { showMatches(pool) }
        pendingShow = pending
        handler.postDelayed(pending, SHOW_DELAY_MS)
    }

    /** Ranks [pool] against the current query and puts the best few under the box. */
    private fun showMatches(pool: List<Item>) {
        val q = query.lowercase()
        val matches = pool
            .mapNotNull { item -> rank(item, q)?.let { it to item } }
            .sortedWith(compareBy({ it.first }, { it.second.name.length }, { it.second.name }))
            .map { it.second }
            .take(MAX_ROWS)

        if (matches.isEmpty()) { dismiss(); return }

        rows.clear(); rows.addAll(matches)
        adapter.notifyDataSetChanged()
        show()
    }

    private fun cancelPendingShow() {
        pendingShow?.let { handler.removeCallbacks(it) }
        pendingShow = null
    }

    /**
     * How well [item] answers [q], lowest first, or null when it does not.
     *
     * The order is the order an operator means things in: the exact code they
     * scanned, the exact name they know, then names that BEGIN with what they have
     * typed so far - which is what typing a prefix is - then a word inside the name,
     * then anywhere at all. The last is kept because a search for "masala" should
     * still find "Paneer Tikka Masala", but it is kept last because that is a guess
     * where the others are not.
     */
    private fun rank(item: Item, q: String): Int? {
        val name = item.name.lowercase()
        return when {
            item.codes.any { it.equals(q, ignoreCase = true) } -> 0
            name == q -> 1
            name.startsWith(q) -> 2
            name.split(' ', '-', '/').any { it.startsWith(q) } -> 3
            name.contains(q) -> 4
            item.codes.any { it.contains(q, ignoreCase = true) } -> 5
            item.meta.contains(q, ignoreCase = true) -> 6
            else -> null
        }
    }

    private fun show() {
        val p = popup ?: ListPopupWindow(context).also {
            it.anchorView = anchor
            it.setAdapter(adapter)
            it.isModal = false            // typing carries on in the box behind it
            it.horizontalOffset = 0
            it.verticalOffset = dp(4)
            it.setBackgroundDrawable(
                androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_suggestion_panel)
            )
            it.setOnItemClickListener { _, _, position, _ ->
                rows.getOrNull(position)?.let { row ->
                    dismiss()
                    // The search is over the moment a row is chosen: the keyboard has
                    // nothing left to type into and is covering the cart the item just
                    // went onto, which is the thing the operator looks at next.
                    hideKeyboard()
                    onPick(row)
                }
            }
            popup = it
        }
        p.width = anchor.width.coerceAtLeast(dp(240))
        // Tall enough for the rows there are, capped so the list can never take the
        // screen it is meant to be a shortcut across.
        p.height = ListPopupWindow.WRAP_CONTENT
        if (!p.isShowing) p.show()
        p.listView?.apply {
            divider = null
            isVerticalScrollBarEnabled = false
        }
    }

    fun dismiss() {
        // The pending open goes too, or a list dismissed at the very moment one was
        // queued would reopen a fraction of a second later on its own.
        cancelPendingShow()
        popup?.takeIf { it.isShowing }?.dismiss()
    }

    /**
     * Puts the soft keyboard away and takes the cursor out of the search box.
     *
     * Public because a search does not only end by picking a row: it also ends on the
     * keyboard's own Search key, and on a hardware scanner, which types a barcode and
     * an Enter. Both sale screens route those to here so a search ends the same way
     * however it was finished.
     *
     * The focus is dropped as well as the keyboard hidden. Hiding alone leaves the
     * cursor in the box, so the next tap anywhere on the shelf brings the keyboard
     * straight back up over the cart.
     */
    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(anchor.windowToken, 0)
        anchor.clearFocus()
    }

    /** Drops the window, for a screen going away. */
    fun release() {
        dismiss(); popup = null
    }

    private fun dp(v: Int): Int = (context.resources.displayMetrics.density * v).toInt()

    private inner class SuggestionAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_search_suggestion, parent, false)
            val item = rows[position]

            v.findViewById<TextView>(R.id.tvSuggestName).text = highlight(item.name)
            v.findViewById<TextView>(R.id.tvSuggestMeta).apply {
                text = item.meta
                visibility = if (item.meta.isBlank()) View.GONE else View.VISIBLE
            }
            v.findViewById<TextView>(R.id.tvSuggestPrice).apply {
                text = item.price
                setTextColor(accent)
            }
            v.findViewById<TextView>(R.id.tvSuggestBadge).apply {
                text = item.badge
                visibility = if (item.badge.isBlank()) View.GONE else View.VISIBLE
                if (item.badgeColor != 0) backgroundTintList =
                    android.content.res.ColorStateList.valueOf(item.badgeColor)
            }

            val thumb = v.findViewById<ImageView>(R.id.ivSuggestThumb)
            val initial = v.findViewById<TextView>(R.id.tvSuggestInitial)
            val bmp = item.bitmap ?: item.image?.let {
                runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
            }
            if (bmp != null) {
                thumb.setImageBitmap(bmp); thumb.visibility = View.VISIBLE
                initial.visibility = View.GONE
            } else {
                thumb.setImageDrawable(null); thumb.visibility = View.GONE
                initial.visibility = View.VISIBLE
                initial.text = item.name.trim().take(1).uppercase()
            }
            return v
        }
    }

    /**
     * The typed run, picked out inside the name in the accent and in bold.
     *
     * This is what stops a list of eight near-identical names from having to be read
     * word by word: the eye is shown where each row matched instead of working it out.
     * Only the first occurrence is marked - marking every one turns a name into a
     * stripe and defeats the point.
     */
    private fun highlight(name: String): CharSequence {
        val at = name.indexOf(query, ignoreCase = true)
        if (at < 0 || query.isEmpty()) return name
        return SpannableString(name).apply {
            val end = at + query.length
            setSpan(ForegroundColorSpan(accent), at, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), at, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    companion object {
        /**
         * One letter is not a search - on any real catalogue it matches most of it,
         * and a panel of eight arbitrary rows opening on the first keystroke is in
         * the way rather than ahead of the operator.
         */
        const val MIN_QUERY = 2

        /**
         * Eight rows. Enough that the item being reached for is nearly always among
         * them; few enough that the list is taken in at a glance rather than scrolled,
         * which is the whole reason it is faster than the grid behind it.
         */
        const val MAX_ROWS = 8

        /**
         * The shortest barcode that may put a product in the cart on its own.
         *
         * A real barcode is 8 to 13 digits. Four is a floor against a product whose
         * barcode field holds something short and typeable - "12", or a single letter
         * - which would otherwise fire mid-search the moment those characters were
         * typed on the way to a name.
         */
        const val SCAN_MIN = 4

        /**
         * A code reduced to what a scanner and a keyboard can agree on.
         *
         * Byte-exact matching loses scans that are plainly the same code. A gun can
         * append a terminator character, a label can be entered with a hyphen or a
         * space in it, and a code with letters can be stored in one case and read in
         * another - and any one of those drops the scan into the suggestion list, to
         * be tapped, which is the popup the operator was trying to get rid of.
         *
         * So both sides are stripped to letters and digits and folded to one case
         * before they are compared. Nothing that distinguishes two real barcodes is
         * removed: it is the punctuation and the case that go, never a digit.
         */
        fun normalizeCode(raw: String): String =
            raw.trim().filter { it.isLetterOrDigit() }.uppercase()

        /**
         * An HSN worth searching on, or null.
         *
         * A product with no HSN entered carries "0000" - the placeholder the whole app
         * writes when the field is left alone. Searching that would be worse than not
         * searching it at all: typing "0" on a shelf where most products were never
         * given a code would match most of the shelf, and a search for a product whose
         * name starts with a digit would drown in placeholders. So a code that is all
         * zeros is treated as no code.
         */
        fun realHsn(raw: String?): String? {
            val v = raw?.trim().orEmpty()
            if (v.isEmpty() || v.all { it == '0' }) return null
            return v
        }

        /**
         * How long the box must be still before the list opens.
         *
         * Sized to sit between the two things that type into a search box. A barcode
         * gun puts characters out a few milliseconds apart, so it never reaches this
         * and the list never opens for a scan. A person types at 100ms a character at
         * their fastest and pauses far longer than this when they stop to read, so
         * the list still feels like it was already there.
         */
        const val SHOW_DELAY_MS = 180L
    }
}
