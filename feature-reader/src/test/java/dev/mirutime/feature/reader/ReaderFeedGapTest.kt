package dev.mirutime.feature.reader

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderChapter
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.reader.document.ReadingAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Neighbours come from the chapter list position, not from idx arithmetic: a
 * stored list with a hole (a removed chapter) must still expose the previous
 * and the next chapter instead of ending the feed.
 */
class ReaderFeedGapTest {
    private val chapters = listOf(1, 2, 5, 6).map {
        ReaderChapter("b#chapter=$it", it, "Chapter $it", false)
    }

    private fun doc(index: Int): ReaderDocument {
        val chapterUrl = "b#chapter=$index"
        return ReaderDocument(
            bookUrl = "b",
            chapterUrl = chapterUrl,
            chapterIndex = index,
            title = "Chapter $index",
            blocks = listOf(
                ReaderBlock(BlockId(chapterUrl, 0), BlockKind.Paragraph, "text $index"),
            ),
        )
    }

    @Test
    fun `a hole in idx does not hide the next chapter`() {
        val plan = FeedPlanner.plan(
            chapters = chapters,
            documents = mapOf(2 to doc(2)),
            statuses = emptyMap(),
            anchor = ReadingAnchor("b#chapter=2", BlockId("b#chapter=2", 0)),
        )

        val bottom = plan.entries.filterIsInstance<FeedEntry.Gap>().single { !it.top }
        assertEquals(5, bottom.chapterIndex)
        assertEquals(ChapterLoadStatus.NotLoaded, bottom.status)
    }

    @Test
    fun `a hole in idx does not hide the previous chapter`() {
        val plan = FeedPlanner.plan(
            chapters = chapters,
            documents = mapOf(5 to doc(5)),
            statuses = emptyMap(),
            anchor = ReadingAnchor("b#chapter=5", BlockId("b#chapter=5", 0)),
        )

        val top = plan.entries.filterIsInstance<FeedEntry.Gap>().single { it.top }
        assertEquals(2, top.chapterIndex)
    }

    @Test
    fun `the loaded window spans positions, not idx range`() {
        val plan = FeedPlanner.plan(
            chapters = chapters,
            documents = mapOf(2 to doc(2), 5 to doc(5)),
            statuses = mapOf(2 to ChapterLoadStatus.Ready, 5 to ChapterLoadStatus.Ready),
            anchor = ReadingAnchor("b#chapter=2", BlockId("b#chapter=2", 0)),
        )

        val headers = plan.entries.filterIsInstance<FeedEntry.Header>().map { it.chapterIndex }
        assertEquals(listOf(2, 5), headers)
        assertTrue(plan.entries.none { it is FeedEntry.Gap && it.chapterIndex in setOf(2, 5) })
    }

    @Test
    fun `manga strip keeps the next chapter when idx has a hole`() {
        val mangaChapters = chapters
        val feed = MangaFeedPlanner.plan(
            chapters = mangaChapters,
            documents = mapOf(
                2 to mangaDoc(2),
                5 to mangaDoc(5),
            ),
            activeIndex = 2,
        )

        assertEquals(listOf(2, 5), feed.map { it.chapterIndex })
    }

    private fun mangaDoc(index: Int): ReaderDocument {
        val chapterUrl = "b#chapter=$index"
        return ReaderDocument(
            bookUrl = "b",
            chapterUrl = chapterUrl,
            chapterIndex = index,
            title = "Chapter $index",
            blocks = listOf(
                ReaderBlock(
                    id = BlockId(chapterUrl, 0),
                    kind = BlockKind.Image,
                    original = "",
                    imageRef = "https://img/$index/0.jpg",
                ),
            ),
        )
    }
}
