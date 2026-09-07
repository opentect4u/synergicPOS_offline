package com.example.synergic_pos_offline.utils

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The order the shop dragged its category tabs into.
 *
 * "All" is part of it. It used to be pinned to the front and excluded from what was
 * remembered, so a shop that wanted it anywhere else could not put it there.
 */
class CategoryOrderTest {

    /** The object outlives a test, so each one starts from nothing dragged. */
    @Before
    fun forgetPreviousDrags() = CategoryOrder.remember(emptyList())

    private val fromCatalogue = listOf("All", "Drinks", "Snacks", "Rice")

    /** Nothing dragged yet: the strip reads as the catalogue hands it over. */
    @Test
    fun untouchedTabsKeepTheCatalogueOrder() {
        assertEquals(fromCatalogue, CategoryOrder.ordered(fromCatalogue))
    }

    /** A dragged order is reproduced on the next rebuild, whatever the catalogue says. */
    @Test
    fun aDraggedOrderSurvivesARebuild() {
        CategoryOrder.remember(listOf("Rice", "Drinks", "All", "Snacks"))
        assertEquals(
            listOf("Rice", "Drinks", "All", "Snacks"),
            CategoryOrder.ordered(fromCatalogue)
        )
    }

    /**
     * THE POINT OF THIS CHANGE: "All" stays where it was dropped.
     *
     * Dragged into the middle, it comes back in the middle - it is not marched to the
     * front again by the next read of the catalogue.
     */
    @Test
    fun allComesBackWhereItWasDropped() {
        CategoryOrder.remember(listOf("Drinks", "All", "Snacks", "Rice"))
        assertEquals(1, CategoryOrder.ordered(fromCatalogue).indexOf("All"))
    }

    /** And it can be dragged to the very end, which is the furthest it can go. */
    @Test
    fun allCanBeDraggedToTheEnd() {
        CategoryOrder.remember(listOf("Drinks", "Snacks", "Rice", "All"))
        assertEquals(
            listOf("Drinks", "Snacks", "Rice", "All"),
            CategoryOrder.ordered(fromCatalogue)
        )
    }

    /**
     * A category added since the last drag goes to the END, keeping its own relative
     * order - the one placement that does not silently push something the shop put
     * where it is on purpose.
     */
    @Test
    fun newCategoriesArriveAtTheEnd() {
        CategoryOrder.remember(listOf("Rice", "All", "Drinks"))
        assertEquals(
            listOf("Rice", "All", "Drinks", "Snacks", "Sweets"),
            CategoryOrder.ordered(listOf("All", "Drinks", "Snacks", "Rice", "Sweets"))
        )
    }
}
