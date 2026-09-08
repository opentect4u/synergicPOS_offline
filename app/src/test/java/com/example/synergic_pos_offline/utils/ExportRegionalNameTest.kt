package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Products download writes into PRODUCT_UNI_NAME.
 *
 * The download used to read `md_product_names` and nothing else, so a product nobody
 * had typed a name for came out with an empty cell - even though the Products screen
 * was showing that product's name in the chosen language perfectly well. An operator
 * picked a language on that page, downloaded, and got a file with no sign of it.
 *
 * [ProductCsvExport] needs a database, so what is pinned here is the rule it now
 * follows for a product with no name of its own: the same translation the screen puts
 * in its Regional Name column.
 */
class ExportRegionalNameTest {

    private val hindi = PrintLanguage.Language.HINDI
    private val english = PrintLanguage.Language.ENGLISH

    /** A product with no saved name still exports a name, in the chosen language. */
    @Test
    fun aProductWithNoSavedNameStillExportsOne() {
        val exported = ProductName.inPrintLanguage(hindi, "Coffee")
        assertTrue("nothing was written for Coffee", exported.isNotBlank())
        assertNotEquals("Coffee came out unchanged - not in Hindi at all", "Coffee", exported)
    }

    /**
     * English writes the name through untouched.
     *
     * English is the absence of a regional name rather than one to translate into, so
     * the column carries the product's own name and the upload writes nothing back -
     * see ProductBulkImporter, which only files names for a language that has them.
     */
    @Test
    fun englishExportsTheNameAsItIs() {
        assertEquals("Coffee", ProductName.inPrintLanguage(english, "Coffee"))
    }

    /** A blank name stays blank rather than becoming a translated empty string. */
    @Test
    fun nothingInNothingOut() {
        assertEquals("", ProductName.inPrintLanguage(hindi, ""))
        assertEquals("", ProductName.inPrintLanguage(hindi, null))
    }

    /**
     * The shop's OWN word wins over the translation.
     *
     * The rule the export follows around this helper: a saved name is what the shop
     * calls the product, and a lexicon has no business overwriting it. Checked here as
     * the `ifBlank` the export applies - a non-empty saved name is never replaced.
     */
    @Test
    fun aSavedNameIsNeverOverwrittenByATranslation() {
        val saved = "कॉफी"
        val exported = saved.ifBlank { ProductName.inPrintLanguage(hindi, "Coffee") }
        assertEquals(saved, exported)
    }
}
