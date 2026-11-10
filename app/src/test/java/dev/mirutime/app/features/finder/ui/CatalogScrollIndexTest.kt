package dev.mirutime.app.features.finder.ui

import org.junit.Assert.assertEquals
import org.junit.Test

// The catalogue grid puts the search bar at index 0 and books after it, so a
// saved book index and a grid index differ by exactly one.
class CatalogScrollIndexTest {

    @Test
    fun `the search header is not a book`() {
        assertEquals(0, bookIndexFromGridIndex(0))
        assertEquals(0, bookIndexFromGridIndex(1))
        assertEquals(1, bookIndexFromGridIndex(2))
    }

    @Test
    fun `a book index maps back to its grid slot`() {
        assertEquals(1, gridIndexForBookIndex(0))
        assertEquals(3, gridIndexForBookIndex(2))
    }
}
