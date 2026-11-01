package dev.mirutime.app.features.reader.domain

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderChapter
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.reader.document.ReadingAnchor
import dev.mirutime.feature.reader.ChapterLoadStatus
import dev.mirutime.feature.reader.FeedEntry
import dev.mirutime.feature.reader.FeedPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedPlannerTest {
    private val chapters = (1..3).map { ReaderChapter("b#chapter=$it", it, "Chapter $it", false) }

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
    fun `loaded chapters produce headers blocks and a next gap`() {
        val plan = FeedPlanner.plan(
            chapters = chapters,
            documents = mapOf(1 to doc(1)),
            statuses = mapOf(2 to ChapterLoadStatus.Loading),
            anchor = ReadingAnchor("b#chapter=1", BlockId("b#chapter=1", 0)),
        )

        assertTrue(plan.entries.any { it is FeedEntry.Header && it.chapterIndex == 1 })
        assertTrue(plan.entries.any { it is FeedEntry.Block && it.chapterIndex == 1 })
        val gap = plan.entries.filterIsInstance<FeedEntry.Gap>().last()
        assertEquals(2, gap.chapterIndex)
        assertTrue(gap.status is ChapterLoadStatus.Loading)
        assertNotNull(plan.anchorEntryId)
    }

    @Test
    fun `prune keeps two chapters behind and ahead`() {
        val documents = (1..8).associateWith { doc(it) }
        val pruned = FeedPlanner.prune(documents, anchorChapter = 5, back = 2, ahead = 2)
        assertEquals(setOf(3, 4, 5, 6, 7), pruned.keys)
    }

    @Test
    fun `duplicate chapter urls still produce distinct feed ids`() {
        val duplicated = listOf(1, 2).map { index ->
            ReaderChapter("https://site/ch/1", index, "Chapter $index", false)
        }
        val document = ReaderDocument(
            bookUrl = "b",
            chapterUrl = "https://site/ch/1",
            chapterIndex = 1,
            title = "Chapter 1",
            blocks = listOf(
                ReaderBlock(BlockId("https://site/ch/1", 0), BlockKind.Paragraph, "one"),
            ),
        )

        val plan = FeedPlanner.plan(
            chapters = duplicated,
            documents = mapOf(1 to document, 2 to document.copy(chapterIndex = 2)),
            statuses = emptyMap(),
            anchor = null,
        )

        val ids = plan.entries.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.contains("b:1:0"))
        assertTrue(ids.contains("b:2:0"))
    }

    @Test
    fun `title block is planned as header only`() {
        val chapterUrl = "b#chapter=1"
        val document = ReaderDocument(
            bookUrl = "b",
            chapterUrl = chapterUrl,
            chapterIndex = 1,
            title = "Chapter 1",
            blocks = listOf(
                ReaderBlock(BlockId(chapterUrl, 0), BlockKind.Heading, "Chapter 1"),
                ReaderBlock(BlockId(chapterUrl, 1), BlockKind.Paragraph, "text 1"),
            ),
        )

        val plan = FeedPlanner.plan(
            chapters = chapters,
            documents = mapOf(1 to document),
            statuses = mapOf(1 to ChapterLoadStatus.Ready),
            anchor = ReadingAnchor(chapterUrl, BlockId(chapterUrl, 0)),
        )

        val blocks = plan.entries.filterIsInstance<FeedEntry.Block>()
        assertEquals(1, blocks.size)
        assertEquals(1, blocks.first().block.id.index)
        assertTrue(plan.entries.any { it is FeedEntry.Header && it.chapterIndex == 1 })
    }
}
