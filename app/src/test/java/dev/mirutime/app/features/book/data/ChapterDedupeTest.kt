package dev.mirutime.app.features.book.data

import dev.mirutime.app.features.book.domain.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterDedupeTest {

    private fun chapter(index: Int, url: String?, title: String = "Chapter $index") = BookChapter(
        index = index,
        title = title,
        isRead = false,
        downloaded = false,
        translated = false,
        downloading = false,
        url = url,
    )

    @Test
    fun `duplicate chapter urls keep the first and reindex contiguously`() {
        val deduped = dedupeChapters(
            listOf(
                chapter(1, "https://site/ch/1", "One"),
                chapter(2, "https://site/ch/1", "Duplicate"),
                chapter(3, "https://site/ch/2", "Two"),
            ),
        )

        assertEquals(listOf(1, 2), deduped.map { it.index })
        assertEquals(listOf("https://site/ch/1", "https://site/ch/2"), deduped.map { it.url })
        assertEquals(listOf("One", "Two"), deduped.map { it.title })
    }

    @Test
    fun `missing urls are not collapsed together`() {
        val deduped = dedupeChapters(
            listOf(
                chapter(1, null),
                chapter(2, null),
                chapter(3, "https://site/ch/1"),
                chapter(4, "https://site/ch/1"),
            ),
        )

        assertEquals(listOf(1, 2, 3), deduped.map { it.index })
        assertEquals(listOf(null, null, "https://site/ch/1"), deduped.map { it.url })
    }
}
