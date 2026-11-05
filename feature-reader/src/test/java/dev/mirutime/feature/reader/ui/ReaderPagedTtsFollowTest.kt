package dev.mirutime.feature.reader.ui

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.feature.reader.paged.PageAlt
import dev.mirutime.feature.reader.paged.PageLine
import dev.mirutime.feature.reader.paged.ReaderPage
import dev.mirutime.feature.tts.api.TtsHighlight
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPagedTtsFollowTest {
    private val chapterUrl = "local://book#chapter=1"

    private fun block(index: Int) = BlockId(chapterUrl, index)

    private fun line(
        blockId: BlockId,
        start: Int,
        end: Int,
        alt: PageAlt? = null,
    ) = PageLine(
        blockId = blockId,
        kind = BlockKind.Paragraph,
        start = start,
        end = end,
        heightPx = 10f,
        spacingBeforePx = 0f,
        alt = alt,
    )

    private fun highlight(
        blockId: BlockId,
        start: Int,
        translated: Boolean = false,
    ) = TtsHighlight(
        blockId = blockId,
        start = start,
        end = start + 1,
        colorArgb = 0L,
        translated = translated,
    )

    @Test
    fun `long block turns the page when the cursor crosses its boundary`() {
        val pages = listOf(
            ReaderPage(listOf(line(block(0), 0, 50))),
            ReaderPage(listOf(line(block(0), 50, 100))),
        )

        assertEquals(0, ttsFollowPage(pages, highlight(block(0), 10)))
        assertEquals(1, ttsFollowPage(pages, highlight(block(0), 60)))
    }

    @Test
    fun `translated cursor is matched in the alt span`() {
        val pages = listOf(
            ReaderPage(
                listOf(line(block(0), 0, 100, alt = PageAlt(0, 20, 10f))),
            ),
            ReaderPage(
                listOf(line(block(0), 0, 100, alt = PageAlt(20, 40, 10f))),
            ),
        )

        assertEquals(1, ttsFollowPage(pages, highlight(block(0), 30, translated = true)))
        assertEquals(0, ttsFollowPage(pages, highlight(block(0), 30, translated = false)))
    }

    @Test
    fun `falls back to the page that contains the block`() {
        val pages = listOf(
            ReaderPage(listOf(line(block(0), 0, 50))),
            ReaderPage(listOf(line(block(1), 0, 50))),
        )

        assertEquals(1, ttsFollowPage(pages, highlight(block(1), 999)))
    }

    @Test
    fun `no highlight selects no page`() {
        val pages = listOf(ReaderPage(listOf(line(block(0), 0, 50))))

        assertEquals(-1, ttsFollowPage(pages, null))
    }
}
