package dev.mirutime.app.features.book.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BookChapterOrderTest {

    private fun chapter(index: Int) = BookChapter(
        index = index,
        title = "Chapter $index",
        isRead = false,
        downloaded = false,
        translated = false,
        downloading = false,
    )

    @Test
    fun `newest first reverses the view but keeps chapter indexes`() {
        val chapters = listOf(chapter(1), chapter(2), chapter(3))

        assertEquals(
            listOf(3, 2, 1),
            chapters.inReadingOrder(newestFirst = true).map { it.index },
        )
        assertEquals(
            listOf(1, 2, 3),
            chapters.inReadingOrder(newestFirst = false).map { it.index },
        )
        assertEquals(listOf(1, 2, 3), chapters.map { it.index })
    }
}
