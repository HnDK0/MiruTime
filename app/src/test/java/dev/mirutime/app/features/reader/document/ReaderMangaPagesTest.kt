package dev.mirutime.app.features.reader.document

import dev.mirutime.feature.reader.webtoonAnchorIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderMangaPagesTest {
    private fun block(
        index: Int,
        kind: BlockKind,
        imageRef: String? = null,
        chapterUrl: String = "chapter",
    ) = ReaderBlock(
        id = BlockId(chapterUrl, index),
        kind = kind,
        original = "",
        imageRef = imageRef,
    )

    private fun document(blocks: List<ReaderBlock>) = ReaderDocument(
        bookUrl = "book",
        chapterUrl = "chapter",
        chapterIndex = 0,
        title = "Chapter",
        blocks = blocks,
    )

    @Test
    fun `manga pages keep every image in block order`() {
        val doc = document(
            listOf(
                block(0, BlockKind.Image, "page-1.jpg"),
                block(1, BlockKind.Image, "page-2.jpg"),
                block(2, BlockKind.Image, "page-3.jpg"),
            ),
        )

        assertEquals(listOf(0, 1, 2), doc.mangaPages().map { it.id.index })
        assertEquals(
            listOf("page-1.jpg", "page-2.jpg", "page-3.jpg"),
            doc.mangaPages().map { it.imageRef },
        )
    }

    @Test
    fun `manga pages skip text, dividers and blank images`() {
        val doc = document(
            listOf(
                block(0, BlockKind.Heading),
                block(1, BlockKind.Image, "page-1.jpg"),
                block(2, BlockKind.Paragraph),
                block(3, BlockKind.Divider),
                block(4, BlockKind.Image, " "),
                block(5, BlockKind.Image),
                block(6, BlockKind.Image, "page-2.jpg"),
            ),
        )

        assertEquals(listOf(1, 6), doc.mangaPages().map { it.id.index })
    }

    @Test
    fun `no image blocks falls back to an empty page list`() {
        val doc = document(
            listOf(
                block(0, BlockKind.Heading),
                block(1, BlockKind.Paragraph),
            ),
        )

        assertTrue(doc.mangaPages().isEmpty())
        assertEquals(0, doc.pageIndexForAnchor(ReadingAnchor("chapter", BlockId("chapter", 1), 0)))
    }

    @Test
    fun `anchor block id resolves to its page index`() {
        val doc = document(
            listOf(
                block(0, BlockKind.Heading),
                block(1, BlockKind.Image, "page-1.jpg"),
                block(2, BlockKind.Paragraph),
                block(3, BlockKind.Image, "page-2.jpg"),
            ),
        )

        assertEquals(0, doc.pageIndexForAnchor(ReadingAnchor("chapter", BlockId("chapter", 1), 0)))
        assertEquals(1, doc.pageIndexForAnchor(ReadingAnchor("chapter", BlockId("chapter", 3), 0)))
    }

    @Test
    fun `missing or foreign anchor starts at the first page`() {
        val doc = document(
            listOf(
                block(0, BlockKind.Image, "page-1.jpg"),
                block(1, BlockKind.Image, "page-2.jpg"),
            ),
        )

        assertEquals(0, doc.pageIndexForAnchor(null))
        assertEquals(0, doc.pageIndexForAnchor(ReadingAnchor("chapter", BlockId("chapter", 9), 0)))
        assertEquals(
            0,
            doc.pageIndexForAnchor(ReadingAnchor("other-chapter", BlockId("other-chapter", 1), 0)),
        )
    }

    @Test
    fun `paged and webtoon restore from the same page index`() {
        val doc = document(
            listOf(
                block(0, BlockKind.Image, "page-1.jpg"),
                block(1, BlockKind.Image, "page-2.jpg"),
                block(2, BlockKind.Image, "page-3.jpg"),
            ),
        )
        val anchor = ReadingAnchor("chapter", BlockId("chapter", 2), 0)

        val pagedIndex = doc.pageIndexForAnchor(anchor)
        val webtoonIndex = webtoonAnchorIndex(pagedIndex, doc.mangaPages().size)

        assertEquals(2, pagedIndex)
        assertEquals(pagedIndex, webtoonIndex)
    }
}
