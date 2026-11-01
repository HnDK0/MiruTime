package dev.mirutime.feature.reader.ui

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopGapLoadTest {
    private fun gap(
        chapterIndex: Int,
        status: ChapterLoadStatus,
        top: Boolean = true,
    ) = FeedEntry.Gap(
        id = "gap:top:$chapterIndex",
        chapterIndex = chapterIndex,
        title = "Chapter $chapterIndex",
        status = status,
        top = top,
    )

    @Test
    fun `composing a NotLoaded top gap does not invoke onLoadPrevious`() {
        var calls = 0
        val entry = gap(2, ChapterLoadStatus.NotLoaded)

        assertTrue(topGapShowsLoadButton(entry))
        val onClick = { topGapOnTap(entry) { calls++ } }

        assertEquals(0, calls)
        onClick()
        assertEquals(1, calls)
    }

    @Test
    fun `a tap invokes onLoadPrevious once for the gap chapter`() {
        val entry = gap(2, ChapterLoadStatus.NotLoaded)
        val requested = mutableListOf<Int>()

        topGapOnTap(entry) { requested += it }

        assertEquals(listOf(2), requested)
    }

    @Test
    fun `Loading Ready and Failed gaps are no-ops`() {
        val requested = mutableListOf<Int>()
        listOf(
            ChapterLoadStatus.Loading,
            ChapterLoadStatus.Ready,
            ChapterLoadStatus.Failed("nope"),
        ).forEach { status ->
            val entry = gap(2, status)
            assertFalse(topGapShowsLoadButton(entry))
            topGapOnTap(entry) { requested += it }
        }

        assertTrue(requested.isEmpty())
    }

    @Test
    fun `bottom NotLoaded gap has no load button and is a no-op`() {
        val entry = gap(2, ChapterLoadStatus.NotLoaded, top = false)
        val requested = mutableListOf<Int>()

        assertFalse(topGapShowsLoadButton(entry))
        topGapOnTap(entry) { requested += it }

        assertTrue(requested.isEmpty())
    }

    @Test
    fun `loading one previous chapter does not request the next one`() {
        val chapters = (1..4).map { ReaderChapter("b#chapter=$it", it, "Chapter $it", false) }
        fun doc(index: Int): ReaderDocument {
            val url = "b#chapter=$index"
            return ReaderDocument(
                bookUrl = "b",
                chapterUrl = url,
                chapterIndex = index,
                title = "Chapter $index",
                blocks = listOf(
                    ReaderBlock(BlockId(url, 0), BlockKind.Paragraph, "text $index"),
                ),
            )
        }
        val anchor = ReadingAnchor("b#chapter=3", BlockId("b#chapter=3", 0))

        val before = FeedPlanner.plan(
            chapters = chapters,
            documents = mapOf(3 to doc(3)),
            statuses = mapOf(2 to ChapterLoadStatus.Loading),
            anchor = anchor,
        )
        val loadingGap = before.entries.filterIsInstance<FeedEntry.Gap>().first()
        assertEquals(2, loadingGap.chapterIndex)

        val after = FeedPlanner.plan(
            chapters = chapters,
            documents = mapOf(2 to doc(2), 3 to doc(3)),
            statuses = mapOf(2 to ChapterLoadStatus.Ready),
            anchor = anchor,
        )
        val nextGap = after.entries.filterIsInstance<FeedEntry.Gap>().first()
        assertEquals(1, nextGap.chapterIndex)
        assertEquals(ChapterLoadStatus.NotLoaded, nextGap.status)

        val requested = mutableListOf<Int>()
        // Composition only exposes the button; the new gap stays idle.
        assertTrue(topGapShowsLoadButton(nextGap))
        assertTrue(requested.isEmpty())

        topGapOnTap(nextGap) { requested += it }
        assertEquals(listOf(1), requested)
    }
}
