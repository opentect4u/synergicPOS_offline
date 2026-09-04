package com.example.synergic_pos_offline.utils

import android.content.Context
import com.example.synergic_pos_offline.database.ProductNameDao

/**
 * Which name a product prints under, and in which language.
 *
 * ## The product's language is the MASTER's
 *
 * Not the print language. Those are two separate questions and the split is the point:
 * the print language decides what the slip's own words say - ITEM, QUANTITY, TOTAL -
 * and the Products master's language decides what the goods are called. A shop that
 * prints its headings in English still sells टाटा नमक.
 *
 * So everything here is asked in [AppLanguage]'s language, never [PrintLanguage]'s.
 *
 * ## The shop's name first, a translation second
 *
 * A name the shop WROTE, in that language, wins. Product names have otherwise always
 * been machine-translated as they printed ([ProductName]), which is right for a
 * catalogue nobody has been through - a Hindi till prints Hindi from its first day -
 * and wrong for the names a shop actually uses. A lexicon does not know the local word
 * for a regional sweet, a brand respelled letter by letter is not what is on the
 * packet, and neither could be corrected: the guess was made afresh on every slip.
 *
 * A product with nothing written falls back to the translation, so nothing regresses
 * and filling one product in changes that one product.
 *
 * ## Why the fallback translates into the MASTER's language too
 *
 * Because otherwise a half-finished catalogue prints two languages on one bill: the
 * products somebody had got to in the shop's own words, the rest in whatever the
 * printer was set to. Written or guessed, a product name comes out in the language the
 * master is on, and the slip reads as one document.
 */
object RegionalName {

    /**
     * Every name the shop has written for the language the Products master is on, keyed
     * by product name in upper case.
     *
     * ONE QUERY for a whole document - see [ProductNameDao.namesFor]. Empty in English,
     * where no name is translated or written at all.
     */
    fun map(context: Context): Map<String, String> {
        val language = AppLanguage.of(context)
        if (!ProductName.applies(language)) return emptyMap()
        return ProductNameDao(context).namesFor(language.code)
    }

    /**
     * The language product names print in - the Products master's, not the printer's.
     *
     * Read by the renderers so that the choice is made in one place and they cannot
     * quietly disagree about it.
     */
    fun language(context: Context): PrintLanguage.Language = AppLanguage.of(context)

    /**
     * How [name] should be printed: the shop's own name where there is one, otherwise
     * [ProductName]'s translation into [language].
     *
     * [saved] is a map from [map], built once per document by the caller, and
     * [language] is [language] - both passed in rather than read here so this stays
     * free of a Context and can be tested.
     */
    fun forPrint(
        saved: Map<String, String>,
        language: PrintLanguage.Language,
        name: String
    ): String = saved[name.trim().uppercase()] ?: ProductName.inPrintLanguage(language, name)
}
