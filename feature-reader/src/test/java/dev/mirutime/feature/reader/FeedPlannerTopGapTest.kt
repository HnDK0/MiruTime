package dev.mirutime.feature.reader

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderChapter
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.reader.document.ReadingAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedPlannerTopGapTest {
    private val chapters = (1..4).map { ReaderChapter("b#chapter=$it", it, "Chapter $it", false) }

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
    fun `chapter before the loaded window becomes a NotLoaded top gap`() {
        val plan = FeedPlanner.plan(
            chapters = chapters,
            documents = mapOf(3 to doc(3)),
            statuses = emptyMap(),
            anchor = ReadingAnchor("b#chapter=3", BlockId("b#chapter=3", 0)),
        )

        val top = plan.entries.filterIsInstance<FeedEntry.Gap>().first()
        assertTrue(top.top)
        assertEquals(2, top.chapterIndex)
        assertEquals(ChapterLoadStatus.NotLoaded, top.status)
    }

    @Test
    fun `loading the top gap chapter replaces it with content`() {
        val before = FeedPlanner.plan(
            chapters = chapters,
            documents = mapOf(3 to doc(3)),
            statuses = mapOf(2 to ChapterLoadStatus.Loading),
            anchor = ReadingAnchor("b#chapter=3", BlockId("b#chapter=3", 0)),
        )
        val topBefore = before.entries.filterIsInstance<FeedEntry.Gap>().first()
        assertTrue(topBefore.top)
        assertEquals(2, topBefore.chapterIndex)
        assertEquals(ChapterLoadStatus.Loading, topBefore.status)

        val after = FeedPlanner.plan(
            chapters = chapters,
            documents = mapOf(2 to doc(2), 3 to doc(3)),
            statuses = mapOf(2 to ChapterLoadStatus.Ready),
            anchor = ReadingAnchor("b#chapter=3", BlockId("b#chapter=3", 0)),
        )
        assertFalse(after.entries.any { it is FeedEntry.Gap && it.chapterIndex == 2 })
        assertTrue(after.entries.any { it is FeedEntry.Header && it.chapterIndex == 2 })
        // The next chapter back becomes the new NotLoaded top gap.
        val topAfter = after.entries.filterIsInstance<FeedEntry.Gap>().first()
        assertTrue(topAfter.top)
        assertEquals(1, topAfter.chapterIndex)
        assertEquals(ChapterLoadStatus.NotLoaded, topAfter.status)
    }
}
