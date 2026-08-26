package com.example.synergic_pos_offline.utils

import com.example.synergic_pos_offline.utils.CouponPrinter.CategorisedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a bill is split into counter coupons.
 *
 * The drawing needs Android; the splitting does not, and the splitting is what has
 * to be right - a line on the wrong coupon is a customer sent to the wrong counter.
 * Both print paths meet at this function, so these cover the saved bill and the
 * restaurant draft at once.
 */
class CouponPrinterTest {

    private fun group(vararg lines: CategorisedLine) =
        CouponPrinter.group(lines.toList(), "42", "26-08-2026 12:38")

    @Test
    fun `six items across three counters come off as three coupons`() {
        val coupons = group(
            CategorisedLine("Sweets", "Kaju Katli", 1.0),
            CategorisedLine("Sweets", "Rasgulla", 1.0),
            CategorisedLine("Snacks", "Samosa", 1.0),
            CategorisedLine("Snacks", "Bread Pakora", 1.0),
            CategorisedLine("Snacks", "Paneer Pakora", 1.0),
            CategorisedLine("Beverages", "Masala Chai", 1.0)
        )
        assertEquals(3, coupons.size)
        assertEquals(listOf(2, 3, 1), coupons.map { it.lines.size })
        assertEquals(listOf("Sweets", "Snacks", "Beverages"), coupons.map { it.category })
    }

    @Test
    fun `the counters come off in the order the bill rang them up`() {
        val coupons = group(
            CategorisedLine("Zebra", "Item A", 1.0),
            CategorisedLine("Apple", "Item B", 1.0)
        )
        assertEquals(listOf("Zebra", "Apple"), coupons.map { it.category })
    }

    @Test
    fun `an item with no category still gets a coupon`() {
        val coupons = group(CategorisedLine("", "Loose Item", 2.0))
        assertEquals(1, coupons.size)
        assertEquals("OTHER", coupons.single().category)
        assertEquals(2.0, coupons.single().lines.single().quantity, 0.0001)
    }

    @Test
    fun `a category named over two lines is flattened, not split in two`() {
        // Real data on this till has one: a line break in a category name would
        // otherwise make two coupons that are the same counter.
        val coupons = group(
            CategorisedLine("Baker's\nBest", "Cake", 1.0),
            CategorisedLine("Baker's Best", "Bun", 1.0)
        )
        assertEquals(1, coupons.size)
        assertEquals("Baker's Best", coupons.single().category)
        assertEquals(2, coupons.single().lines.size)
    }

    @Test
    fun `a nameless line is dropped rather than printed blank`() {
        val coupons = group(
            CategorisedLine("Sweets", "  ", 1.0),
            CategorisedLine("Sweets", "Peda", 1.0)
        )
        assertEquals(listOf("Peda"), coupons.single().lines.map { it.name })
    }

    @Test
    fun `every coupon carries the bill it came off`() {
        val coupons = group(
            CategorisedLine("Sweets", "Peda", 1.0),
            CategorisedLine("Snacks", "Samosa", 1.0)
        )
        assertTrue(coupons.all { it.billNumber == "42" && it.dateTime == "26-08-2026 12:38" })
    }

    @Test
    fun `an empty bill produces no coupons`() {
        assertTrue(CouponPrinter.group(emptyList(), "42", "").isEmpty())
    }
}
