package com.example.synergic_pos_offline.utils

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Filter

/**
 * Fills a pick-one dropdown - the Material "exposed dropdown menu" this app uses for
 * every Category, Unit, Section, Waiter, Language and filter field.
 *
 * ## Why this exists: the list was arriving one row tall
 *
 * These fields are `AutoCompleteTextView`s, and an AutoCompleteTextView is really a
 * SUGGESTION box: it holds text, and its adapter's Filter narrows the list to whatever
 * matches that text. That is exactly right for a search box and exactly wrong here,
 * because a pick-one field is never empty - it always shows the current choice. So the
 * filter matched the current choice, published one row, and the menu opened as a
 * single line with the value already selected in it. Measured on the Products screen's
 * language picker: an eleven-language menu opened 128px tall - one item - with 1100px
 * of empty screen beneath it.
 *
 * That reads as "the dropdown does not open". It did open. It had nothing in it but
 * the answer already showing in the field above.
 *
 * ## The fix
 *
 * A pick-one dropdown does not filter. The adapter here reports every item whatever
 * the field says, so opening the menu always offers the whole list - which is what a
 * dropdown is for, and what the operator expects when they tap one.
 *
 * Positions stay 1:1 with the list passed in, since nothing is ever removed, so
 * `setOnItemClickListener { _, _, position, _ -> items[position] }` is exact - it was
 * NOT exact before, which is the quieter half of this bug: on a field whose filter had
 * narrowed the list, the position clicked indexed the filtered list while the caller
 * read it against the full one, and picked the wrong item.
 */
object Dropdowns {

    /**
     * Puts [items] into [view] as a pick-one menu, showing them all.
     *
     * [labels] renders each item; the default is [toString], which is what the plain
     * string dropdowns want.
     */
    fun <T> fill(view: AutoCompleteTextView, items: List<T>, labels: (T) -> String = { it.toString() }) {
        view.setAdapter(adapter(view.context, items.map(labels)))
    }

    /**
     * The non-filtering adapter, for a caller that needs the adapter itself.
     *
     * `simple_list_item_1` is kept - it is what every one of these fields already
     * used, so nothing changes about how the rows look; only how many of them there
     * are.
     */
    fun adapter(context: Context, labels: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, labels) {

            /**
             * Publishes the whole list, whatever is typed.
             *
             * Held as one instance rather than built per call: AutoCompleteTextView
             * keeps a reference to whatever [getFilter] returns first, and a fresh
             * object each time would leave it holding one that had been replaced.
             */
            private val everything = object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults =
                    FilterResults().apply {
                        values = labels
                        count = labels.size
                    }

                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    // Nothing was removed, so there is nothing to re-publish - but the
                    // view is told anyway, because it will not draw a list it has not
                    // been told about.
                    notifyDataSetChanged()
                }

                /** What the field shows once a row is picked - the row's own text. */
                override fun convertResultToString(resultValue: Any?): CharSequence =
                    resultValue?.toString().orEmpty()
            }

            override fun getFilter(): Filter = everything
        }
}
