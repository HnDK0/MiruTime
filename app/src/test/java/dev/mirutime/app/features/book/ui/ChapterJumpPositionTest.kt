package dev.mirutime.app.features.book.ui

import dev.mirutime.app.features.book.domain.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterJumpPositionTest {

    private fun chapter(index: Int) = BookChapter(
        index = index,
        title = "Chapter $index",
        isRead = false,
        downloaded = false,
        translated = false,
        downloading = false,
    )

    @Test
    fun `jump position follows the visible reading order`() {
        val visible = listOf(chapter(10), chapter(9), chapter(8))
        assertEquals(1, chapterJumpPosition(visible, 9))
        assertEquals(0, chapterJumpPosition(visible, 10))
        assertEquals(-1, chapterJumpPosition(visible, 11))
    }
}
