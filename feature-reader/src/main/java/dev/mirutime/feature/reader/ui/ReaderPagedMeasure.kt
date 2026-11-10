package dev.mirutime.feature.reader.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.DisplayGroup
import dev.mirutime.app.features.reader.document.ReaderDisplay
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.reader.document.ReaderSplit
import dev.mirutime.app.features.reader.document.SentenceSpan
import dev.mirutime.app.features.reader.document.SentenceSplitter
import dev.mirutime.feature.reader.ReaderStyle
import dev.mirutime.feature.reader.paged.PageBreaker
import dev.mirutime.feature.reader.paged.PageLine
import dev.mirutime.feature.reader.paged.ReaderPage
import dev.mirutime.feature.reader.paged.pairedRows
import dev.mirutime.feature.reader.paged.parallelPairs
import kotlin.math.min
import kotlin.math.roundToInt

internal fun measurePages(
    document: ReaderDocument,
    style: ReaderStyle,
    textMeasurer: TextMeasurer,
    widthPx: Float,
    pageHeightPx: Float,
    density: Density,
    layoutDirection: LayoutDirection,
    chapterTitle: String,
    translationLabel: String?,
    translations: Map<BlockId, String>,
    parallel: Boolean = false,
    translationFirst: Boolean = true,
): List<ReaderPage> {
    val width = widthPx.roundToInt().coerceAtLeast(1)
    val paragraphSpacing = with(density) { style.paragraphSpacingDp.dp.toPx() }
    val paragraphStyle = style.toTextStyle()
    val headingStyle = style.headingTextStyle()
    val measured = mutableListOf<PageLine>()

    // The chapter header is a line of its own. It is measured with the same
    // measurer, the same styles and the same width the composable paints it
    // with, so the reserved height is exactly the painted height.
    val titleBlock = document.titleBlock()
    val bodyBlocks = document.bodyBlocks()
    measured += PageLine(
        blockId = titleBlock?.id
            ?: bodyBlocks.firstOrNull()?.id
            ?: BlockId(document.chapterUrl, 0),
        kind = BlockKind.Heading,
        start = 0,
        end = titleBlock?.original?.length ?: 0,
        heightPx = measureChapterHeaderHeight(
            title = chapterTitle,
            translationLabel = translationLabel,
            style = style,
            textMeasurer = textMeasurer,
            width = width,
            density = density,
            layoutDirection = layoutDirection,
        ),
        spacingBeforePx = 0f,
        isChapterHeader = true,
    )

    bodyBlocks.forEach { block ->
        val blockSpacing = paragraphSpacing
        when (block.kind) {
            BlockKind.Image -> {
                val imageHeight = min(widthPx * 0.66f, pageHeightPx * 0.55f)
                    .coerceAtLeast(0f)
                measured += PageLine(
                    blockId = block.id,
                    kind = block.kind,
                    start = 0,
                    end = block.original.length,
                    heightPx = imageHeight,
                    spacingBeforePx = blockSpacing,
                )
            }
            BlockKind.Divider -> {
                val text = "· · ·"
                val result = textMeasurer.measure(
                    text = AnnotatedString(text),
                    style = paragraphStyle,
                    layoutDirection = layoutDirection,
                    density = density,
                    constraints = Constraints(maxWidth = width),
                )
                val height = if (result.lineCount == 0) {
                    0f
                } else {
                    result.getLineBottom(0) - result.getLineTop(0)
                }
                measured += PageLine(
                    blockId = block.id,
                    kind = block.kind,
                    start = 0,
                    end = block.original.length,
                    heightPx = height,
                    spacingBeforePx = blockSpacing,
                )
            }
            BlockKind.Heading, BlockKind.Paragraph -> {
                val translated = translations[block.id]?.takeIf { it.isNotBlank() }
                // Parallel: every visual line of the translation is paired with
                // the original line at the same index. Pair offsets stay
                // original-based, so anchors restore the same page in both modes.
                if (parallel && translated != null) {
                    val pairs = ReaderDisplay.mergePunctuationPairs(
                        block.original,
                        parallelPairs(block, translated, style.splitSentences),
                    )
                    val secondaryStyle = style.pagedSecondaryTextStyle(block.kind)
                    val gapPx = with(density) { PagedAltGap.toPx() }
                    val primaryStyle = if (block.kind == BlockKind.Heading) {
                        headingStyle
                    } else {
                        paragraphStyle
                    }
                    val translatedStyle = if (translationFirst) primaryStyle else secondaryStyle
                    val originalStyle = if (translationFirst) secondaryStyle else primaryStyle
                    // Pairs inside one display group flow together; the group
                    // start carries the paragraph gap. Pair construction is
                    // unchanged, so sentence-level highlight/pairing stays.
                    val groupStarts = ReaderDisplay.groupStarts(
                        ReaderDisplay.groupsForBlock(block, style.splitSentences),
                    )
                    pairs.forEach { pair ->
                        val primary = slice(translated, pair.translated.start, pair.translated.end)
                        val secondary = slice(
                            block.original,
                            pair.original.start,
                            pair.original.end,
                        )
                        if (primary.isBlank() && secondary.isBlank()) return@forEach
                        val translatedLines = textMeasurer.measure(
                            text = AnnotatedString(primary),
                            style = translatedStyle,
                            layoutDirection = layoutDirection,
                            density = density,
                            constraints = Constraints(maxWidth = width),
                        ).visualLines()
                        val originalLines = textMeasurer.measure(
                            text = AnnotatedString(secondary),
                            style = originalStyle,
                            layoutDirection = layoutDirection,
                            density = density,
                            constraints = Constraints(maxWidth = width),
                        ).visualLines()
                        measured += pairedRows(
                            blockId = block.id,
                            kind = block.kind,
                            pair = pair,
                            primaryLines = if (translationFirst) translatedLines else originalLines,
                            altLines = if (translationFirst) originalLines else translatedLines,
                            gapPx = gapPx,
                            spacingBeforePx = blockSpacing,
                            startsGroup = pair.original.start in groupStarts,
                            originalPrimary = !translationFirst,
                        )
                    }
                    return@forEach
                }
                val source = translated ?: block.original
                if (source.isBlank()) return@forEach
                val split = ReaderSplit.shouldSplit(block, style.splitSentences)
                // Display groups: quoted paragraphs and punctuation-only
                // fragments stay one visual paragraph; narration splits per
                // sentence.
                val groups = if (split) {
                    if (translated != null) {
                        ReaderDisplay.groupsForText(source, SentenceSplitter.spans(source), true)
                    } else {
                        ReaderDisplay.groupsForBlock(block, true)
                    }
                } else {
                    listOf(DisplayGroup(0, source.length, listOf(SentenceSpan(0, source.length))))
                }
                val baseStyle = if (block.kind == BlockKind.Heading) headingStyle else paragraphStyle
                groups.forEach { group ->
                    if (group.end <= group.start) return@forEach
                    val raw = source.substring(group.start, group.end)
                    val text = if (
                        translated == null &&
                        block.kind == BlockKind.Paragraph &&
                        !split
                    ) {
                        style.paragraphText(raw)
                    } else {
                        AnnotatedString(raw)
                    }
                    val result = textMeasurer.measure(
                        text = text,
                        style = baseStyle,
                        layoutDirection = layoutDirection,
                        density = density,
                        constraints = Constraints(maxWidth = width),
                    )
                    for (lineIndex in 0 until result.lineCount) {
                        val lineStart = result.getLineStart(lineIndex)
                        val start = group.start + lineStart
                        val end = group.start + result.getLineEnd(
                            lineIndex = lineIndex,
                            visibleEnd = true,
                        ).coerceAtLeast(lineStart)
                        val top = result.getLineTop(lineIndex)
                        val bottom = result.getLineBottom(lineIndex)
                        measured += PageLine(
                            blockId = block.id,
                            kind = block.kind,
                            start = start,
                            end = end,
                            heightPx = (bottom - top).coerceAtLeast(0f),
                            // Full paragraph spacing between display groups, none
                            // between the wrapped lines of one group.
                            spacingBeforePx = if (lineIndex == 0) blockSpacing else 0f,
                            isSegmentStart = split && lineIndex == 0,
                        )
                    }
                }
            }
        }
    }
    return PageBreaker.breakPages(measured, pageHeightPx)
}

/**
 * Height of the shared chapter header: top padding, the ornament row and the
 * bottom padding - the same parts [ReaderChapterHeader] paints. The row is as
 * tall as the taller of the ornament and the title column, and the column
 * holds the title plus, when translation is on, the badge.
 */
private fun measureChapterHeaderHeight(
    title: String,
    translationLabel: String?,
    style: ReaderStyle,
    textMeasurer: TextMeasurer,
    width: Int,
    density: Density,
    layoutDirection: LayoutDirection,
): Float {
    // The title column is narrower than the page by the ornament and its gap.
    val ornamentPx = with(density) { ChapterHeaderMetrics.ornamentSize.roundToPx() }
    val ornamentGapPx = with(density) { ChapterHeaderMetrics.ornamentGap.roundToPx() }
    val contentWidth = (width - ornamentPx - ornamentGapPx).coerceAtLeast(1)
    val heading = textMeasurer.measure(
        text = AnnotatedString(title),
        style = style.chapterTitleStyle(),
        layoutDirection = layoutDirection,
        density = density,
        constraints = Constraints(maxWidth = contentWidth),
    )
    return with(density) {
        val badge = if (translationLabel != null) {
            ChapterHeaderMetrics.badgeGap.toPx() + ChapterHeaderMetrics.badgeHeight.toPx()
        } else {
            0f
        }
        val column = heading.size.height + badge
        ChapterHeaderMetrics.topPadding.toPx() +
            maxOf(ChapterHeaderMetrics.ornamentSize.toPx(), column) +
            ChapterHeaderMetrics.bottomPadding.toPx()
    }
}
