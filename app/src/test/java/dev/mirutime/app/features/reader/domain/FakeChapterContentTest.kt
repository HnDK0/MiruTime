package dev.mirutime.app.features.reader.domain

import dev.mirutime.app.features.book.data.FakeBookDataProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeChapterContentTest {
    @Test
    fun `returns deterministic html for a known chapter`() = runBlocking {
        val provider = FakeBookDataProvider()
        val book = provider.book("fake://novelnest/chronicles-seven-kingdoms")!!
        val chapter = book.chapters.first()
        val content = provider.chapterContent(book.bookUrl, chapter.index)!!
        assertEquals(chapter.title, content.title)
        assertTrue(content.html.contains("<p>"))
        assertEquals(
            content,
            provider.chapterContent(book.bookUrl, chapter.index),
        )
    }

    @Test
    fun `unknown book is synthesized and missing chapter returns null`() = runBlocking {
        val provider = FakeBookDataProvider()
        // FakeBookData synthesizes any bookUrl; only a missing chapter is null.
        assertTrue(provider.chapterContent("fake://nope/nope", 1) != null)
        val bookUrl = "fake://novelnest/chronicles-seven-kingdoms"
        assertNull(provider.chapterContent(bookUrl, 99_999))
    }

    @Test
    fun `markChapterRead persists in the shared runtime`() = runBlocking {
        val provider = FakeBookDataProvider()
        val book = provider.book("fake://novelnest/chronicles-seven-kingdoms")!!
        val index = book.chapters.first().index
        provider.markChapterRead(book.bookUrl, index, true)
        assertTrue(provider.book(book.bookUrl)!!.chapters.first { it.index == index }.isRead)
        provider.markChapterRead(book.bookUrl, index, false)
        assertFalse(provider.book(book.bookUrl)!!.chapters.first { it.index == index }.isRead)
    }
}
