package dev.mirutime.feature.reader

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderChapter
import dev.mirutime.app.features.reader.document.ReaderDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaFeedPlannerTest {
    private val chapters = (1..5).map { ReaderChapter("b#chapter=$it", it, "Chapter $it", false) }

    private fun doc(index: Int, pageCount: Int): ReaderDocument {
        val chapterUrl = "b#chapter=$index"
        return ReaderDocument(
            bookUrl = "b",
            chapterUrl = chapterUrl,
            chapterIndex = index,
            title = "Chapter $index",
            blocks = (0 until pageCount).map { page ->
                ReaderBlock(
                    id = BlockId(chapterUrl, page),
                    kind = BlockKind.Image,
                    original = "",
                    imageRef = "https://img/$index/$page.jpg",
                )
            },
        )
    }

    @Test
    fun `loaded neighbours are stitched around the active chapter in order`() {
        val feed = MangaFeedPlanner.plan(
            chapters = chapters,
            documents = mapOf(1 to doc(1, 2), 2 to doc(2, 2), 3 to doc(3, 2)),
            activeIndex = 2,
        )

        assertEquals(listOf(1, 1, 2, 2, 3, 3), feed.map { it.chapterIndex })
        assertEquals(listOf(0, 1, 0, 1, 0, 1), feed.map { it.pageIndex })
        assertEquals(listOf(true, false, true, false, true, false), feed.map { it.chapterStart })
        assertEquals(listOf(2, 2, 2, 2, 2, 2), feed.map { it.chapterPageCount })
        assertEquals("Chapter 2", feed[2].chapterTitle)
        assertEquals(
            listOf("m:1:0", "m:1:1", "m:2:0", "m:2:1", "m:3:0", "m:3:1"),
            feed.map { it.id },
        )
    }

    @Test
    fun `only loaded chapters inside the window are stitched`() {
        val feed = MangaFeedPlanner.plan(
            chapters = chapters,
            documents = mapOf(1 to doc(1, 1), 4 to doc(4, 1)),
            activeIndex = 2,
        )

        assertEquals(listOf(1), feed.map { it.chapterIndex })
    }

    @Test
    fun `single chapter keeps ids and start flags`() {
        val feed = MangaFeedPlanner.single(doc(4, 2))

        assertEquals(listOf("m:4:0", "m:4:1"), feed.map { it.id })
        assertTrue(feed[0].chapterStart)
        assertFalse(feed[1].chapterStart)
        assertEquals(2, feed[0].chapterPageCount)
    }
}
