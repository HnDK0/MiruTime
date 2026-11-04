package dev.mirutime.feature.reader

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.ReaderBook
import dev.mirutime.app.features.reader.document.ReaderChapter
import dev.mirutime.app.features.reader.document.ReadingAnchor
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderOpenResolverTest {
    private fun book(vararg read: Int): ReaderBook = ReaderBook(
        bookUrl = "b",
        title = "Book",
        chapters = (1..3).map { index ->
            ReaderChapter("b#chapter=$index", index, "Chapter $index", index in read)
        },
    )

    @Test
    fun `saved anchor wins over unread`() {
        val anchor = ReadingAnchor("b#chapter=2", BlockId("b#chapter=2", 1), 0)
        val saved = SavedReading(anchor, 0, 1, 0)
        val target = ReaderOpenResolver.forContinue(book(1), saved)
        assertEquals(ReaderOpenTarget.Chapter(2, restoreAnchor = true), target)
    }

    @Test
    fun `saved read chapter continues at first unread`() {
        val anchor = ReadingAnchor("b#chapter=1", BlockId("b#chapter=1", 1), 0)
        val saved = SavedReading(anchor, 0, 1, 0)
        val target = ReaderOpenResolver.forContinue(book(1), saved)
        assertEquals(ReaderOpenTarget.Chapter(2, restoreAnchor = false), target)
    }

    @Test
    fun `first unread starts at chapter top`() {
        val target = ReaderOpenResolver.forContinue(book(1), null)
        assertEquals(ReaderOpenTarget.Chapter(2, restoreAnchor = false), target)
    }

    @Test
    fun `all read opens the end`() {
        val target = ReaderOpenResolver.forContinue(book(1, 2, 3), null)
        assertEquals(ReaderOpenTarget.End(3), target)
    }

    @Test
    fun `manual chapter ignores a saved anchor unless asked`() {
        val anchor = ReadingAnchor("b#chapter=1", BlockId("b#chapter=1", 0), 0)
        val saved = SavedReading(anchor, 0, 1, 0)
        assertEquals(
            ReaderOpenTarget.Chapter(1, restoreAnchor = false),
            ReaderOpenResolver.forRequested(book(), 1, restoreSaved = false, saved = saved),
        )
        assertEquals(
            ReaderOpenTarget.Chapter(1, restoreAnchor = true),
            ReaderOpenResolver.forRequested(book(), 1, restoreSaved = true, saved = saved),
        )
    }

    @Test
    fun `re-entry restores the saved chapter even when read`() {
        val anchor = ReadingAnchor("b#chapter=2", BlockId("b#chapter=2", 3), 0)
        val saved = SavedReading(anchor, offsetPx = 480, seq = 7, updatedAt = 0)
        val target = ReaderOpenResolver.forReopened(book(2), requestedIndex = 1, saved = saved)
        assertEquals(ReaderOpenTarget.Chapter(2, restoreAnchor = true), target)
    }

    @Test
    fun `re-entry without a saved chapter keeps the requested chapter`() {
        val target = ReaderOpenResolver.forReopened(book(), requestedIndex = 3, saved = null)
        assertEquals(ReaderOpenTarget.Chapter(3, restoreAnchor = false), target)
    }

    @Test
    fun `re-entry never falls back to chapter one`() {
        val anchor = ReadingAnchor("b#chapter=99", BlockId("b#chapter=99", 0), 0)
        val saved = SavedReading(anchor, offsetPx = 0, seq = 1, updatedAt = 0)
        val target = ReaderOpenResolver.forReopened(book(1), requestedIndex = 42, saved = saved)
        assertEquals(ReaderOpenTarget.Chapter(2, restoreAnchor = false), target)
    }
}
