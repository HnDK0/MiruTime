package dev.mirutime.feature.reader.paged

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReadingAnchor
import dev.mirutime.app.features.reader.document.SentencePair
import dev.mirutime.app.features.reader.document.SentencePairs
import dev.mirutime.app.features.reader.document.SentenceSpan

data class PageLine(
    val blockId: BlockId,
    val kind: BlockKind,
    /**
     * Offset into the source this line paints. Parallel pairs always keep the
     * original offsets so anchors restore the same page.
     */
    val start: Int,
    val end: Int,
    val heightPx: Float,
    val spacingBeforePx: Float,
    /** Synthetic line that paints the shared chapter header instead of a block. */
    val isChapterHeader: Boolean = false,
    /** First line of a sentence segment inside a split paragraph. */
    val isSegmentStart: Boolean = false,
    /** Translated copy paired with this original span in parallel paged mode. */
    val alt: PageAlt? = null,
)

/** Translated sentence painted inside the same item as its original [PageLine]. */
data class PageAlt(
    val start: Int,
    val end: Int,
    val heightPx: Float,
)

data class ReaderPage(val lines: List<PageLine>) {
    val anchor: ReadingAnchor?
        get() = lines.firstOrNull()?.let {
            ReadingAnchor(it.blockId.chapterUrl, it.blockId, it.start)
        }

    val usedHeightPx: Float
        get() = lines.foldIndexed(0f) { i, acc, line ->
            acc + line.heightPx + if (i == 0) 0f else line.spacingBeforePx
        }
}

/** One visual line of a measured text run, offsets relative to that run. */
data class PagedVisualLine(
    val start: Int,
    val end: Int,
    val heightPx: Float,
)

/**
 * Sentence pairs for one paragraph. Only a paragraph with sentence split on is
 * split by [SentencePairs]; every other block stays one whole-block pair, the
 * same shape the feed paints when split is off.
 */
fun parallelPairs(
    block: ReaderBlock,
    translated: String,
    splitSentences: Boolean,
): List<SentencePair> {
    if (block.kind == BlockKind.Paragraph && splitSentences) {
        val pairs = SentencePairs.pair(block.original, translated)
        if (pairs.isNotEmpty()) return pairs
    }
    return listOf(
        SentencePair(
            original = SentenceSpan(0, block.original.length),
            translated = SentenceSpan(0, translated.length),
        ),
    )
}

/**
 * Builds parallel rows from the visual lines of one sentence pair: one row per
 * visual-line index, symmetric for both sides. Both-side rows pair a translated
 * line with an original line; lines that exist only on one side become a
 * single-copy row. Pair offsets stay original-based, while [PageAlt] keeps the
 * translated offsets (empty for an original-only row).
 */
fun pairedRows(
    blockId: BlockId,
    kind: BlockKind,
    pair: SentencePair,
    primaryLines: List<PagedVisualLine>,
    altLines: List<PagedVisualLine>,
    gapPx: Float,
    spacingBeforePx: Float,
): List<PageLine> {
    val rowCount = maxOf(primaryLines.size, altLines.size)
    if (rowCount == 0) return emptyList()
    val rows = ArrayList<PageLine>(rowCount)
    for (index in 0 until rowCount) {
        val primary = primaryLines.getOrNull(index)
        val alt = altLines.getOrNull(index)
        val first = index == 0
        when {
            primary != null && alt != null -> rows += PageLine(
                blockId = blockId,
                kind = kind,
                start = pair.original.start + alt.start,
                end = pair.original.start + alt.end,
                heightPx = primary.heightPx + gapPx + alt.heightPx,
                spacingBeforePx = if (first) spacingBeforePx else 0f,
                isSegmentStart = first,
                alt = PageAlt(
                    start = pair.translated.start + primary.start,
                    end = pair.translated.start + primary.end,
                    heightPx = alt.heightPx,
                ),
            )
            primary != null -> rows += PageLine(
                blockId = blockId,
                kind = kind,
                // Anchor at the pair start; the translation line has no original
                // visual line to point at.
                start = pair.original.start,
                end = pair.original.end,
                heightPx = primary.heightPx,
                spacingBeforePx = if (first) spacingBeforePx else 0f,
                isSegmentStart = first,
                alt = PageAlt(
                    start = pair.translated.start + primary.start,
                    end = pair.translated.start + primary.end,
                    heightPx = 0f,
                ),
            )
            alt != null -> rows += PageLine(
                blockId = blockId,
                kind = kind,
                start = pair.original.start + alt.start,
                end = pair.original.start + alt.end,
                heightPx = alt.heightPx,
                spacingBeforePx = if (first) spacingBeforePx else 0f,
                isSegmentStart = first,
                alt = PageAlt(
                    start = pair.translated.start,
                    end = pair.translated.start,
                    heightPx = 0f,
                ),
            )
        }
    }
    return rows
}

object PageBreaker {
    fun breakPages(lines: List<PageLine>, pageHeightPx: Float): List<ReaderPage> {
        if (lines.isEmpty()) return emptyList()
        val maxHeight = pageHeightPx.coerceAtLeast(1f)
        val pages = mutableListOf<ReaderPage>()
        val current = mutableListOf<PageLine>()
        var used = 0f

        fun flush() {
            if (current.isEmpty()) return
            pages += ReaderPage(current.toList())
            current.clear()
            used = 0f
        }

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val height = line.heightPx.coerceAtLeast(0f)
            val spacing = if (current.isEmpty()) 0f else line.spacingBeforePx.coerceAtLeast(0f)
            if (current.isNotEmpty() && used + spacing + height > maxHeight) {
                flush()
                continue
            }
            if (
                line.kind == BlockKind.Heading &&
                current.isNotEmpty() &&
                headingOrphaned(lines, index, used + spacing, maxHeight)
            ) {
                flush()
                continue
            }
            current += line
            used += spacing + height
            index += 1
            if (used >= maxHeight) flush()
        }
        flush()
        return pages
    }

    fun pageForAnchor(pages: List<ReaderPage>, anchor: ReadingAnchor?): Int {
        if (pages.isEmpty() || anchor == null) return 0
        val containing = pages.indexOfFirst { page ->
            page.lines.any { line ->
                line.blockId == anchor.blockId &&
                    anchor.charOffset >= line.start &&
                    anchor.charOffset < line.end
            }
        }
        if (containing >= 0) return containing
        // End-of-chapter anchors sit exactly on the last line's end offset.
        val atLineEnd = pages.indexOfFirst { page ->
            page.lines.any { line ->
                line.blockId == anchor.blockId && anchor.charOffset == line.end
            }
        }
        if (atLineEnd >= 0) return atLineEnd
        val blockPage = pages.indexOfFirst { page ->
            page.lines.any { it.blockId == anchor.blockId }
        }
        return if (blockPage >= 0) blockPage else 0
    }

    // A heading may move to the next page only when the heading plus the
    // following two lines do not fit in the current page's remaining space.
    private fun headingOrphaned(
        lines: List<PageLine>,
        headingIndex: Int,
        usedBefore: Float,
        maxHeight: Float,
    ): Boolean {
        var required = usedBefore + lines[headingIndex].heightPx.coerceAtLeast(0f)
        var following = 0
        var index = headingIndex + 1
        while (index < lines.size && following < 2) {
            required += lines[index].spacingBeforePx.coerceAtLeast(0f) +
                lines[index].heightPx.coerceAtLeast(0f)
            following += 1
            index += 1
        }
        return required > maxHeight
    }
}
