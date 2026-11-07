package dev.mirutime.app.features.library.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryUpdateNotificationTextTest {
    private fun format(title: String, count: Int) = "$title +$count"
    private fun more(count: Int) = "and $count more"

    @Test
    fun `keeps five books and collapses the rest`() {
        val books = (1..7).map { "Book $it" to it }

        assertEquals(
            listOf(
                "Book 1 +1",
                "Book 2 +2",
                "Book 3 +3",
                "Book 4 +4",
                "Book 5 +5",
                "and 2 more",
            ),
            libraryUpdateLines(books, format = ::format, more = ::more),
        )
    }

    @Test
    fun `drops blank titles and zero counts`() {
        val books = listOf("" to 3, "Book" to 0, "Book A" to 2)

        assertEquals(
            listOf("Book A +2"),
            libraryUpdateLines(books, format = ::format, more = ::more),
        )
    }

    @Test
    fun `empty list produces no lines`() {
        assertEquals(emptyList<String>(), libraryUpdateLines(emptyList(), format = ::format, more = ::more))
    }
}
