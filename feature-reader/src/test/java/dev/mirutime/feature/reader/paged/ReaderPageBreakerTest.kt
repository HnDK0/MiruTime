package dev.mirutime.feature.reader.paged

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReadingAnchor
import dev.mirutime.app.features.reader.document.SentencePair
import dev.mirutime.app.features.reader.document.SentenceSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPageBreakerTest {
    private fun line(
        block: Int,
        height: Float,
        spacing: Float = 0f,
        kind: BlockKind = BlockKind.Paragraph,
        start: Int = 0,
        end: Int = 10,
        chapterUrl: String = "c",
    ): PageLine = PageLine(
        blockId = BlockId(chapterUrl, block),
        kind = kind,
        start = start,
        end = end,
        heightPx = height,
        spacingBeforePx = spacing,
    )

    @Test
    fun `packs lines with spacing into pages`() {
        val pages = PageBreaker.breakPages(
            lines = listOf(
                line(block = 0, height = 100f),
                line(block = 1, height = 100f, spacing = 20f),
                line(block = 2, height = 100f),
            ),
            pageHeightPx = 230f,
        )

        assertEquals(2, pages.size)
        assertEquals(2, pages[0].lines.size)
        assertEquals(1, pages[1].lines.size)
    }

    @Test
    fun `heading moves when it and two lines do not fit`() {
        val pages = PageBreaker.breakPages(
            lines = listOf(
                line(block = 0, height = 100f),
                line(block = 1, height = 40f, spacing = 20f, kind = BlockKind.Heading),
                line(block = 2, height = 40f),
                line(block = 3, height = 40f),
            ),
            pageHeightPx = 200f,
        )

        assertEquals(2, pages.size)
        assertEquals(listOf(0), pages[0].lines.map { it.blockId.index })
        assertEquals(listOf(1, 2, 3), pages[1].lines.map { it.blockId.index })
    }

    @Test
    fun `heading stays when it and two lines fit`() {
        val pages = PageBreaker.breakPages(
            lines = listOf(
                line(block = 0, height = 80f),
                line(block = 1, height = 30f, spacing = 10f, kind = BlockKind.Heading),
                line(block = 2, height = 30f),
                line(block = 3, height = 30f),
            ),
            pageHeightPx = 300f,
        )

        assertEquals(1, pages.size)
        assertEquals(4, pages[0].lines.size)
    }

    @Test
    fun `over-tall line gets its own page`() {
        val pages = PageBreaker.breakPages(
            lines = listOf(
                line(block = 0, height = 40f),
                line(block = 1, height = 150f),
                line(block = 2, height = 40f),
            ),
            pageHeightPx = 100f,
        )

        assertEquals(3, pages.size)
        assertEquals(listOf(0), pages[0].lines.map { it.blockId.index })
        assertEquals(listOf(1), pages[1].lines.map { it.blockId.index })
        assertEquals(listOf(2), pages[2].lines.map { it.blockId.index })
        assertEquals(0, pages.count { it.lines.isEmpty() })
    }

    @Test
    fun `non-last pages leave less than one line unused`() {
        val pageHeight = 1652f
        val lineHeight = 69.17f
        val pages = PageBreaker.breakPages(
            lines = List(200) { index ->
                line(block = index / 10, height = lineHeight)
            },
            pageHeightPx = pageHeight,
        )

        assertTrue(pages.size > 1)
        pages.dropLast(1).forEach { page ->
            assertTrue(
                "page leaves ${pageHeight - page.usedHeightPx}px unused",
                pageHeight - page.usedHeightPx < lineHeight,
            )
        }
    }

    @Test
    fun `page anchor is the first line anchor`() {
        val pages = PageBreaker.breakPages(
            lines = listOf(
                line(block = 0, height = 100f, start = 0, end = 10),
                line(block = 0, height = 100f, start = 10, end = 20),
            ),
            pageHeightPx = 100f,
        )

        assertEquals(ReadingAnchor("c", BlockId("c", 0), 10), pages[1].anchor)
    }

    @Test
    fun `pageForAnchor prefers offset then block then first page`() {
        val pages = listOf(
            ReaderPage(
                listOf(
                    line(block = 0, height = 10f, start = 0, end = 10),
                    line(block = 1, height = 10f, start = 0, end = 10),
                ),
            ),
            ReaderPage(
                listOf(line(block = 0, height = 10f, start = 10, end = 20)),
            ),
        )

        assertEquals(
            1,
            PageBreaker.pageForAnchor(pages, ReadingAnchor("c", BlockId("c", 0), 12)),
        )
        assertEquals(
            0,
            PageBreaker.pageForAnchor(pages, ReadingAnchor("c", BlockId("c", 1), 5)),
        )
        assertEquals(
            0,
            PageBreaker.pageForAnchor(pages, ReadingAnchor("c", BlockId("c", 0), 999)),
        )
        assertEquals(
            0,
            PageBreaker.pageForAnchor(pages, ReadingAnchor("c", BlockId("c", 9), 0)),
        )
        assertEquals(0, PageBreaker.pageForAnchor(emptyList(), null))
    }

    @Test
    fun `chapter start anchor lands on the page with the header`() {
        val pages = PageBreaker.breakPages(
            lines = listOf(
                line(block = 0, height = 120f, start = 0, end = 8).copy(isChapterHeader = true),
                line(block = 1, height = 100f, spacing = 10f),
            ),
            pageHeightPx = 200f,
        )

        assertEquals(
            0,
            PageBreaker.pageForAnchor(pages, ReadingAnchor("c", BlockId("c", 0), 0)),
        )
        assertEquals(
            1,
            PageBreaker.pageForAnchor(pages, ReadingAnchor("c", BlockId("c", 1), 0)),
        )
    }

    @Test
    fun `split segment start keeps full paragraph spacing`() {
        val pages = PageBreaker.breakPages(
            lines = listOf(
                line(block = 0, height = 100f, spacing = 16f, start = 0, end = 40)
                    .copy(isSegmentStart = true),
                line(block = 0, height = 100f, start = 40, end = 60),
                line(block = 0, height = 100f, spacing = 16f, start = 60, end = 100)
                    .copy(isSegmentStart = true),
            ),
            pageHeightPx = 500f,
        )

        assertEquals(1, pages.size)
        assertEquals(316f, pages[0].usedHeightPx, 0.01f)
        assertTrue(pages[0].lines[0].isSegmentStart)
        assertTrue(!pages[0].lines[1].isSegmentStart)
        assertTrue(pages[0].lines[2].isSegmentStart)
    }

    @Test
    fun `paired line includes the alt copy in the page break`() {
        val pair = line(block = 1, height = 112f).copy(
            alt = PageAlt(start = 0, end = 10, heightPx = 40f),
        )
        val pages = PageBreaker.breakPages(
            lines = listOf(
                line(block = 0, height = 70f),
                pair,
            ),
            pageHeightPx = 170f,
        )

        // Primary alone (66px) would fit; primary + gap + alt (112px) must not.
        assertEquals(2, pages.size)
        assertEquals(listOf(0), pages[0].lines.map { it.blockId.index })
        assertEquals(listOf(1), pages[1].lines.map { it.blockId.index })
        assertEquals(40f, pages[1].lines[0].alt?.heightPx)
    }

    @Test
    fun `translated primary restores by the original offset`() {
        val pages = PageBreaker.breakPages(
            lines = listOf(
                line(block = 0, height = 100f, start = 0, end = 10),
                line(block = 0, height = 100f, start = 100, end = 120).copy(
                    alt = PageAlt(start = 0, end = 10, heightPx = 20f),
                ),
            ),
            pageHeightPx = 100f,
        )

        // The alt offsets (0..10) live on page 0; the original offset must win.
        assertEquals(
            1,
            PageBreaker.pageForAnchor(pages, ReadingAnchor("c", BlockId("c", 0), 105)),
        )
    }

    @Test
    fun `split segment line packing matches measured heights`() {
        val pages = PageBreaker.breakPages(
            lines = listOf(
                line(block = 0, height = 100f, spacing = 16f, start = 0, end = 10)
                    .copy(isSegmentStart = true),
                line(block = 0, height = 100f, start = 10, end = 20),
                line(block = 0, height = 100f, spacing = 16f, start = 20, end = 30)
                    .copy(isSegmentStart = true),
            ),
            pageHeightPx = 210f,
        )

        assertEquals(2, pages.size)
        assertEquals(2, pages[0].lines.size)
        assertEquals(1, pages[1].lines.size)
        assertEquals(20, pages[1].anchor?.charOffset)
    }

    @Test
    fun `parallelPairs splits only when split sentences is on`() {
        val original = "Alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu " +
            "nu xi omicron pi rho sigma tau. Upsilon phi chi psi omega alpha beta gamma " +
            "delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron."
        val block = ReaderBlock(BlockId("c", 0), BlockKind.Paragraph, original)

        val splitOn = parallelPairs(block, original, splitSentences = true)
        val splitOff = parallelPairs(block, original, splitSentences = false)

        assertTrue(splitOn.size > 1)
        assertEquals(1, splitOff.size)
        assertEquals(SentenceSpan(0, original.length), splitOff[0].original)
        assertEquals(SentenceSpan(0, original.length), splitOff[0].translated)
    }

    @Test
    fun `paired rows reserve primary gap and alt height`() {
        val pair = SentencePair(
            original = SentenceSpan(start = 100, end = 140),
            translated = SentenceSpan(start = 0, end = 20),
        )
        val rows = pairedRows(
            blockId = BlockId("c", 0),
            kind = BlockKind.Paragraph,
            pair = pair,
            primaryLines = listOf(PagedVisualLine(start = 0, end = 10, heightPx = 40f)),
            altLines = listOf(PagedVisualLine(start = 5, end = 20, heightPx = 20f)),
            gapPx = 6f,
            spacingBeforePx = 16f,
        )

        assertEquals(1, rows.size)
        assertEquals(66f, rows[0].heightPx, 0.01f)
        assertEquals(16f, rows[0].spacingBeforePx, 0.01f)
        // Anchor offsets stay original-based, the translated span lives in alt.
        assertEquals(105, rows[0].start)
        assertEquals(120, rows[0].end)
        assertEquals(PageAlt(start = 0, end = 10, heightPx = 20f), rows[0].alt)

        val pages = PageBreaker.breakPages(rows + rows.map { it.copy(spacingBeforePx = 0f) }, 70f)
        assertEquals(2, pages.size)
    }

    @Test
    fun `long pair splits into visual rows instead of one clipped line`() {
        val pair = SentencePair(
            original = SentenceSpan(0, 60),
            translated = SentenceSpan(0, 60),
        )
        val primary = List(6) { index ->
            PagedVisualLine(start = index * 10, end = index * 10 + 9, heightPx = 40f)
        }
        val alt = List(6) { index ->
            PagedVisualLine(start = index * 10, end = index * 10 + 9, heightPx = 20f)
        }

        val rows = pairedRows(
            blockId = BlockId("c", 0),
            kind = BlockKind.Paragraph,
            pair = pair,
            primaryLines = primary,
            altLines = alt,
            gapPx = 6f,
            spacingBeforePx = 16f,
        )

        assertEquals(6, rows.size)
        assertTrue(rows.all { it.heightPx == 66f })
        val pages = PageBreaker.breakPages(rows, 100f)
        assertTrue(pages.size > 1)
        assertEquals(0, pages.count { it.lines.isEmpty() })
        assertEquals(6, pages.sumOf { it.lines.size })
    }

    @Test
    fun `original-longer pair emits one row per original line`() {
        val pair = SentencePair(
            original = SentenceSpan(0, 40),
            translated = SentenceSpan(0, 20),
        )
        val rows = pairedRows(
            blockId = BlockId("c", 0),
            kind = BlockKind.Paragraph,
            pair = pair,
            primaryLines = listOf(
                PagedVisualLine(0, 10, 40f),
                PagedVisualLine(10, 20, 40f),
            ),
            altLines = listOf(
                PagedVisualLine(0, 10, 20f),
                PagedVisualLine(10, 20, 20f),
                PagedVisualLine(20, 30, 20f),
                PagedVisualLine(30, 40, 20f),
            ),
            gapPx = 6f,
            spacingBeforePx = 16f,
        )

        // Symmetric rows: the two extra original lines get their own rows.
        assertEquals(4, rows.size)
        assertEquals(listOf(66f, 66f, 20f, 20f), rows.map { it.heightPx })
        val extras = rows.drop(2)
        assertTrue(extras.all { it.alt?.heightPx == 0f })
        assertTrue(extras.all { it.alt?.let { alt -> alt.start == alt.end } == true })
        assertEquals(20, extras.first().start)
        assertEquals(40, extras.last().end)
        assertEquals(20f, rows.last().heightPx, 0.01f)
        // Every original visual line is covered exactly once.
        assertEquals(40, rows.sumOf { it.end - it.start })
        assertTrue(rows.all { it.heightPx <= 100f })
    }

    @Test
    fun `translation-longer pair emits one row per translated line`() {
        val pair = SentencePair(
            original = SentenceSpan(0, 20),
            translated = SentenceSpan(0, 40),
        )
        val rows = pairedRows(
            blockId = BlockId("c", 0),
            kind = BlockKind.Paragraph,
            pair = pair,
            primaryLines = listOf(
                PagedVisualLine(0, 10, 40f),
                PagedVisualLine(10, 20, 40f),
                PagedVisualLine(20, 30, 40f),
                PagedVisualLine(30, 40, 40f),
            ),
            altLines = listOf(
                PagedVisualLine(0, 10, 20f),
                PagedVisualLine(10, 20, 20f),
            ),
            gapPx = 6f,
            spacingBeforePx = 16f,
        )

        assertEquals(4, rows.size)
        assertEquals(listOf(66f, 66f, 40f, 40f), rows.map { it.heightPx })
        val extras = rows.drop(2)
        assertTrue(extras.all { it.alt?.heightPx == 0f })
        // Extras still carry the translation span, so no text is duplicated or lost.
        assertTrue(extras.all { it.alt?.let { alt -> alt.end > alt.start } == true })
        assertTrue(extras.all { it.start == 0 && it.end == 20 })
        assertEquals(40, rows.sumOf { row -> row.alt!!.end - row.alt!!.start })
        assertTrue(rows.all { it.heightPx <= 100f })
    }
}
