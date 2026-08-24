package com.example.synergic_pos_offline.utils

import com.example.synergic_pos_offline.database.StockDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a filled-in Stock In sheet is read: which lines become stock, which are left
 * alone, and which are handed back to the operator to fix.
 *
 * The write itself is [StockDao.receive]'s and is not exercised here - what these
 * pin down is the reading, which is where a sheet that has been round a spreadsheet
 * goes wrong.
 */
class StockBulkImporterTest {

    private val catalogue = listOf(
        StockDao.StockItem(11, "Basmati Rice 5kg", "Groceries", 0.0),
        StockDao.StockItem(13, "Bath Soap", "Personal Care", 0.0),
        StockDao.StockItem(591, "Gulab Jamun", "", 38.0),
        StockDao.StockItem(587, "Johnson's Baby Cream", "", 999.0)
    )

    private fun read(vararg rows: Pair<String, String>) =
        StockBulkImporter.resolveAgainst(
            catalogue,
            rows.map { (name, qty) -> mapOf("product_name" to name, "stock" to qty) }
        )

    @Test
    fun `a filled row becomes a movement against that product`() {
        val (result, movements) = read("Basmati Rice 5kg" to "25")
        assertEquals(1, result.received)
        assertEquals(25.0, result.totalQuantity, 0.0001)
        assertEquals(listOf(StockDao.Movement(11, 25.0)), movements)
        assertFalse(result.hasProblems)
    }

    @Test
    fun `an empty quantity is skipped rather than reported`() {
        // The normal shape of a delivery: a few lines filled in out of a long sheet.
        val (result, movements) = read(
            "Basmati Rice 5kg" to "25",
            "Bath Soap" to "",
            "Gulab Jamun" to "   "
        )
        assertEquals(1, result.received)
        assertEquals(2, result.blank)
        assertEquals(1, movements.size)
        assertFalse(result.hasProblems)
    }

    @Test
    fun `a name the till does not hold is named back`() {
        val (result, movements) = read("Nonexistent Item" to "3")
        assertEquals(0, result.received)
        assertTrue(movements.isEmpty())
        assertEquals(listOf("Nonexistent Item"), result.unknown)
        assertTrue(result.hasProblems)
    }

    @Test
    fun `a quantity that is not a number is named back with what was written`() {
        val (result, _) = read("Bath Soap" to "abc")
        assertEquals(0, result.received)
        assertEquals(listOf("Bath Soap (abc)"), result.invalid)
    }

    @Test
    fun `zero and negative quantities are refused`() {
        // Nothing arrived is not a delivery, and stock does not come in negative -
        // taking it off the shelf is a write-off, which is the other screen.
        val (result, movements) = read("Bath Soap" to "0", "Gulab Jamun" to "-5")
        assertTrue(movements.isEmpty())
        assertEquals(listOf("Bath Soap (0)", "Gulab Jamun (-5)"), result.invalid)
    }

    @Test
    fun `names match past the capitals and spacing a spreadsheet leaves behind`() {
        val (result, movements) = read("  basmati RICE 5kg " to "4")
        assertEquals(1, result.received)
        assertEquals(listOf(StockDao.Movement(11, 4.0)), movements)
    }

    @Test
    fun `a fractional quantity is kept`() {
        val (result, movements) = read("Gulab Jamun" to "5.5")
        assertEquals(5.5, result.totalQuantity, 0.0001)
        assertEquals(listOf(StockDao.Movement(591, 5.5)), movements)
    }

    @Test
    fun `two lines for one item stay two movements`() {
        // How a delivery of two cartons is written, and how the stock history
        // should show it.
        val (result, movements) = read("Gulab Jamun" to "6", "Gulab Jamun" to "6")
        assertEquals(2, result.received)
        assertEquals(12.0, result.totalQuantity, 0.0001)
        assertEquals(listOf(StockDao.Movement(591, 6.0), StockDao.Movement(591, 6.0)), movements)
    }

    @Test
    fun `a whole sheet reports every kind of row at once`() {
        val (result, movements) = read(
            "Basmati Rice 5kg" to "25",
            "Bath Soap" to "abc",
            "Britannia Biscuits" to "",
            "Gulab Jamun" to "12",
            "Johnson's Baby Cream" to "1",
            "Nonexistent Item" to "3",
            "Rogan Josh" to "5.5"
        )
        assertEquals(3, result.received)
        assertEquals(38.0, result.totalQuantity, 0.0001)
        assertEquals(1, result.blank)
        assertEquals(listOf("Nonexistent Item", "Rogan Josh"), result.unknown)
        assertEquals(listOf("Bath Soap (abc)"), result.invalid)
        assertEquals(3, movements.size)
    }

    @Test
    fun `the quantity may be headed quantity or qty as well as stock`() {
        val rows = listOf(
            mapOf("product_name" to "Bath Soap", "quantity" to "7"),
            mapOf("item_name" to "Gulab Jamun", "qty" to "3")
        )
        val (result, _) = StockBulkImporter.resolveAgainst(catalogue, rows)
        assertEquals(2, result.received)
        assertEquals(10.0, result.totalQuantity, 0.0001)
    }

    @Test
    fun `the downloaded template parses back into readable rows`() {
        // The round trip the operator actually makes: download, fill in, upload.
        val filled = """
            product_name,stock
            Basmati Rice 5kg,25
            Bath Soap,
            "Johnson's Baby Cream",2
        """.trimIndent()
        val (result, movements) = StockBulkImporter.resolveAgainst(catalogue, CsvUtils.parse(filled))
        assertEquals(2, result.received)
        assertEquals(27.0, result.totalQuantity, 0.0001)
        assertEquals(listOf(StockDao.Movement(11, 25.0), StockDao.Movement(587, 2.0)), movements)
    }
}
