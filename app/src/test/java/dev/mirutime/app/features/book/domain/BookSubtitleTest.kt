package dev.mirutime.app.features.book.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookSubtitleTest {

    @Test
    fun `explicit subtitle wins over alt titles`() {
        assertEquals(
            "Lord of the Mysteries",
            displaySubtitle("Lord of the Mysteries", listOf("Lord of Mysteries")),
        )
    }

    @Test
    fun `first non-blank alt title is the fallback`() {
        assertEquals(
            "Lord of the Mysteries",
            displaySubtitle("  ", listOf("", "Lord of the Mysteries", "LOTM")),
        )
    }

    @Test
    fun `missing values hide the subtitle`() {
        assertNull(displaySubtitle(null, emptyList()))
        assertNull(displaySubtitle("   ", listOf("  ")))
    }

    @Test
    fun `subtitle equal to the title is dropped`() {
        assertNull(displaySubtitle("Vladyka Tayn", emptyList(), "  vladyka tayn "))
    }

    @Test
    fun `values are trimmed`() {
        assertEquals("Lord of the Mysteries", displaySubtitle("  Lord of the Mysteries  ", emptyList()))
    }
}
