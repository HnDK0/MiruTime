package dev.mirutime.app.features.book.data

import org.junit.Assert.assertEquals
import org.junit.Test

// A manual metadata refresh restores a missing field but never wipes a stored
// one with a blank value from the source (FR-012).
class MetadataMergeTest {

    @Test
    fun `a fresh rating wins and a stored one survives a blank refresh`() {
        assertEquals(4.5f, mergedRating(fresh = 4.5f, stored = 0f))
        assertEquals(3.7f, mergedRating(fresh = 0f, stored = 3.7f))
        assertEquals(2.0f, mergedRating(fresh = 2.0f, stored = 3.7f))
    }

    @Test
    fun `a fresh description wins and a stored one survives a blank refresh`() {
        assertEquals("New", mergedText(fresh = "New", stored = "Old"))
        assertEquals("Old", mergedText(fresh = null, stored = "Old"))
        assertEquals("Old", mergedText(fresh = "   ", stored = "Old"))
    }
}
