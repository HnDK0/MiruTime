package dev.mirutime.app.features.book.ui

import dev.mirutime.app.features.library.domain.Shelf
import org.junit.Assert.assertEquals
import org.junit.Test

class BookShelfWiringTest {

    @Test
    fun `real shelves provide labels and unknown ids fall back to the id`() {
        val shelves = listOf(
            Shelf(id = "reading", label = "Reading"),
            Shelf(id = "wuxia", label = "Wuxia stash"),
        )

        assertEquals("Wuxia stash", shelfLabel("wuxia", shelves))
        assertEquals("reading", shelfLabel("reading", emptyList()))
        assertEquals("missing-shelf", shelfLabel("missing-shelf", shelves))
    }
}
