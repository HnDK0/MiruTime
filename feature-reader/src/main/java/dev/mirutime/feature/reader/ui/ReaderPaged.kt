package dev.mirutime.feature.reader.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mirutime.feature.reader.R
import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import coil3.compose.AsyncImage
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.reader.document.ReadingAnchor
import dev.mirutime.app.features.reader.document.SentenceSpan
import dev.mirutime.app.features.reader.document.SentenceSplitter
import dev.mirutime.feature.reader.ChapterLoadStatus
import dev.mirutime.feature.reader.ReaderStyle
import dev.mirutime.feature.reader.paged.PageAlt
import dev.mirutime.feature.reader.paged.PageBreaker
import dev.mirutime.feature.reader.paged.PageLine
import dev.mirutime.feature.reader.paged.PagedVisualLine
import dev.mirutime.feature.reader.paged.ReaderPage
import dev.mirutime.feature.reader.paged.pairedRows
import dev.mirutime.feature.reader.paged.parallelPairs
import dev.mirutime.feature.reader.ui.ChapterHeaderMetrics
import dev.mirutime.feature.reader.ui.chapterTitleStyle
import dev.mirutime.feature.reader.ui.ReaderChapterHeader
import dev.mirutime.feature.reader.ui.headingTextStyle
import dev.mirutime.feature.reader.ui.paragraphText
import dev.mirutime.feature.reader.ui.selectionSearchMenu
import dev.mirutime.feature.reader.ui.toTextStyle
import dev.mirutime.feature.tts.TtsChunker
import dev.mirutime.feature.tts.api.TtsHighlight
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Gap between the two copies of a parallel pair; included in the page height. */
private val PagedAltGap = 6.dp
private const val PAGED_ALT_SCALE = 0.85f
private const val PAGED_ALT_ALPHA = 0.72f

@Composable
internal fun ReaderPaged(
    document: ReaderDocument?,
    status: ChapterLoadStatus?,
    style: ReaderStyle,
    contentPadding: PaddingValues,
    chapterIndex: Int,
    chapterTitle: String,
    translationLabel: String?,
    translations: Map<BlockId, String>,
    parallel: Boolean = false,
    translationFirst: Boolean = true,
    ttsHighlight: TtsHighlight? = null,
    focusToken: Int = 0,
    doubleTapMenu: Boolean,
    anchor: ReadingAnchor?,
    scrollToken: Int,
    canPrevChapter: Boolean,
    canNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onRetry: (Int) -> Unit,
    onOpenAuth: (String) -> Unit = {},
    onAnchor: (ReadingAnchor, Int) -> Unit,
    onChapterFinished: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    onSearchSelection: (String) -> Unit = {},
) {
    val textColor = Color(style.effectiveTextColor())
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        if (document == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (status is ChapterLoadStatus.Failed) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        status.title?.takeIf { it.isNotBlank() }?.let { failureTitle ->
                            Text(
                                text = failureTitle,
                                color = textColor,
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        Text(
                            text = stringResource(R.string.rd_chapter_error),
                            color = textColor,
                        )
                        status.message.takeIf { it.isNotBlank() }?.let { detail ->
                            Text(
                                text = detail,
                                color = textColor,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                        if (status.authUrl != null) {
                            TextButton(onClick = { onOpenAuth(status.authUrl) }) {
                                Text(stringResource(R.string.rd_open_login), color = textColor)
                            }
                        }
                        TextButton(onClick = { onRetry(chapterIndex) }) {
                            Text(stringResource(R.string.rd_retry), color = textColor)
                        }
                    }
                } else {
                    CircularProgressIndicator(color = textColor)
                }
            }
        } else {
            // Pagination failure is a layout fact, not a reading-mode change:
            // retry re-measures locally and the Paged mode stays selected.
            var paginationRetry by remember(document.chapterUrl, document.revision) {
                mutableStateOf(0)
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val layoutDirection = LocalLayoutDirection.current
                val textMeasurer = rememberTextMeasurer()
                val widthPx = with(density) { maxWidth.toPx() }
                val heightPx = with(density) { maxHeight.toPx() }
                val locale = LocalConfiguration.current.locales[0].toLanguageTag()
                val layoutKey = buildString {
                    append(widthPx)
                    append('|')
                    append(heightPx)
                    append('|')
                    append(style.hashCode())
                    append('|')
                    append(density.fontScale)
                    append('|')
                    append(locale)
                    append('|')
                    append(document.revision)
                    append('|')
                    append(style.splitSentences)
                    append('|')
                    append(parallel)
                    append('|')
                    append(translationFirst)
                    append('|')
                    append(chapterTitle)
                    append('|')
                    append(translationLabel)
                    append('|')
                    // A late translation must re-paginate the whole chapter.
                    append(translations.hashCode())
                }
                val pages = remember(layoutKey, document, translations, paginationRetry) {
                    try {
                        measurePages(
                            document = document,
                            style = style,
                            textMeasurer = textMeasurer,
                            widthPx = widthPx,
                            pageHeightPx = heightPx,
                            density = density,
                            layoutDirection = layoutDirection,
                            chapterTitle = chapterTitle,
                            translationLabel = translationLabel,
                            translations = translations,
                            parallel = parallel,
                        )
                    } catch (throwable: Throwable) {
                        MiruLog.e("reader", "pagination failed", throwable)
                        null
                    }
                }
                if (pages.isNullOrEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.rd_paged_failed),
                            color = textColor,
                        )
                        TextButton(onClick = { paginationRetry += 1 }) {
                            Text(stringResource(R.string.rd_retry), color = textColor)
                        }
                    }
                } else {
                    ReaderPagedPager(
                        pages = pages,
                        document = document,
                        style = style,
                        density = density,
                        anchor = anchor,
                        scrollToken = scrollToken,
                        chapterIndex = chapterIndex,
                        chapterTitle = chapterTitle,
                        translationLabel = translationLabel,
                        translations = translations,
                        translationFirst = translationFirst,
                        parallel = parallel,
                        ttsHighlight = ttsHighlight,
                        focusToken = focusToken,
                        doubleTapMenu = doubleTapMenu,
                        canPrevChapter = canPrevChapter,
                        canNextChapter = canNextChapter,
                        onPrevChapter = onPrevChapter,
                        onNextChapter = onNextChapter,
                        onAnchor = onAnchor,
                        onChapterFinished = onChapterFinished,
                        onToggleChrome = onToggleChrome,
                        onSearchSelection = onSearchSelection,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderPagedPager(
    pages: List<ReaderPage>,
    document: ReaderDocument,
    style: ReaderStyle,
    density: Density,
    anchor: ReadingAnchor?,
    scrollToken: Int,
    chapterIndex: Int,
    chapterTitle: String,
    translationLabel: String?,
    translations: Map<BlockId, String>,
    translationFirst: Boolean = true,
    parallel: Boolean = false,
    ttsHighlight: TtsHighlight? = null,
    focusToken: Int = 0,
    doubleTapMenu: Boolean,
    canPrevChapter: Boolean,
    canNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onAnchor: (ReadingAnchor, Int) -> Unit,
    onChapterFinished: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    onSearchSelection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialPage = remember(pages, anchor) {
        PageBreaker.pageForAnchor(pages, anchor)
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { pages.size },
    )
    val scope = rememberCoroutineScope()
    val blocks = remember(document) { document.blocks.associateBy { it.id } }

    // Restore on reflow and mode switches. state.anchor is deliberately not a
    // key: settle updates it, and keying on it would fight the pager.
    val previousParallel = remember { mutableStateOf(parallel) }
    LaunchedEffect(pages, scrollToken, parallel) {
        val switched = previousParallel.value != parallel
        previousParallel.value = parallel
        // The anchor offset was measured against the other layout (translated
        // only vs paired), so a parallel switch restores the block start.
        val target = PageBreaker.pageForAnchor(
            pages = pages,
            anchor = if (switched) anchor?.copy(charOffset = 0) else anchor,
        )
        if (target in pages.indices) pagerState.scrollToPage(target)
    }

    // Focus: jump to the page that owns the spoken block. The page is already
    // laid out, so animateScrollToPage keeps the turning animation consistent
    // with manual navigation.
    LaunchedEffect(focusToken) {
        if (focusToken <= 0) return@LaunchedEffect
        val blockId = ttsHighlight?.blockId ?: return@LaunchedEffect
        val target = pages.indexOfFirst { page ->
            page.lines.any { it.blockId == blockId }
        }
        if (target in pages.indices) pagerState.animateScrollToPage(target)
    }

    // TTS auto-follow: turn to the page that owns the spoken cursor, exactly
    // like the feed. Pages are sized by text, so a long block may span several
    // pages; matching the char offset (not just the block) turns the page as
    // soon as the cursor crosses the boundary.
    val ttsTargetPage = remember(pages, ttsHighlight) {
        ttsFollowPage(pages, ttsHighlight)
    }
    LaunchedEffect(ttsTargetPage, pages) {
        if (ttsTargetPage in pages.indices && ttsTargetPage != pagerState.currentPage) {
            pagerState.animateScrollToPage(ttsTargetPage)
        }
    }

    LaunchedEffect(pagerState, pages, chapterIndex) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { pageIndex ->
                val page = pages.getOrNull(pageIndex) ?: return@collect
                val pageAnchor = page.anchor ?: return@collect
                onAnchor(pageAnchor, 0)
                if (pageIndex == pages.lastIndex) onChapterFinished(chapterIndex)
            }
    }

    Box(
        modifier = modifier.pointerInput(
            pages,
            canPrevChapter,
            canNextChapter,
            doubleTapMenu,
        ) {
            // Pending center tap in two-tap mode. It belongs to this gesture
            // loop, so changing the setting or the pages drops it.
            var lastCenterTapAt = 0L
            var lastHandledTap: ReaderTap? = null
            awaitEachGesture {
                // SelectionContainer sits inside the pager, so taps are read
                // at the Initial pass: handled short taps consume their up
                // before selection sees them, while long presses and swipes
                // pass through untouched.
                val tap = awaitReaderTap(lastHandledTap) { x ->
                    val width = size.width.toFloat()
                    when {
                        x < width * 0.25f -> {
                            // Edge taps turn pages immediately, even in
                            // two-tap mode, and cancel a pending center tap.
                            lastCenterTapAt = 0L
                            if (pagerState.currentPage > 0) {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            } else if (canPrevChapter) {
                                onPrevChapter()
                            }
                        }
                        x > width * 0.75f -> {
                            lastCenterTapAt = 0L
                            if (pagerState.currentPage < pages.lastIndex) {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            } else if (canNextChapter) {
                                onNextChapter()
                            }
                        }
                        else -> {
                            if (!doubleTapMenu) {
                                onToggleChrome()
                            } else {
                                val now = SystemClock.uptimeMillis()
                                val isDouble = isMenuDoubleTap(
                                    now = now,
                                    lastTapAt = lastCenterTapAt,
                                    timeout = viewConfiguration.doubleTapTimeoutMillis,
                                )
                                if (isDouble) {
                                    lastCenterTapAt = 0L
                                    onToggleChrome()
                                } else {
                                    lastCenterTapAt = now
                                }
                            }
                        }
                    }
                }
                lastHandledTap = tap
                if (tap == null) lastCenterTapAt = 0L
            }
        },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { pageIndex ->
            val page = pages.getOrNull(pageIndex) ?: return@HorizontalPager
            ReaderPageContent(
                page = page,
                blocks = blocks,
                style = style,
                density = density,
                chapterTitle = chapterTitle,
                translationLabel = translationLabel,
                translations = translations,
                translationFirst = translationFirst,
                ttsHighlight = ttsHighlight,
                onSearchSelection = onSearchSelection,
            )
        }
    }
}

@Composable
private fun ReaderPageContent(
    page: ReaderPage,
    blocks: Map<BlockId, ReaderBlock>,
    style: ReaderStyle,
    density: Density,
    chapterTitle: String,
    translationLabel: String?,
    translations: Map<BlockId, String>,
    translationFirst: Boolean = true,
    ttsHighlight: TtsHighlight? = null,
    onSearchSelection: (String) -> Unit,
) {
    val textColor = Color(style.effectiveTextColor())
    val paragraphStyle = style.toTextStyle()
    val headingStyle = style.headingTextStyle()
    val searchLabel = stringResource(R.string.rd_selection_search)
    Column(
        Modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        page.lines.forEachIndexed { index, line ->
            key(line.blockId, line.start, line.isChapterHeader, index) {
                val spacing = if (index == 0) 0f else line.spacingBeforePx
                if (spacing > 0f) {
                    Spacer(Modifier.height(with(density) { spacing.toDp() }))
                }
                if (line.isChapterHeader) {
                    // The reserved height equals the measured header height, so
                    // the painted header fills the page exactly.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(with(density) { line.heightPx.toDp() }),
                    ) {
                        PagedSelection(
                            style = style,
                            searchable = false,
                            searchLabel = searchLabel,
                            onSearchSelection = onSearchSelection,
                        ) {
                            ReaderChapterHeader(
                                title = chapterTitle,
                                style = style,
                                translationLabel = translationLabel,
                            )
                        }
                    }
                } else {
                    when (line.kind) {
                        BlockKind.Image -> {
                            val imageBlock = blocks[line.blockId]
                            val ref = imageBlock?.imageRef
                            if (ref.isNullOrBlank()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(with(density) { line.heightPx.toDp() })
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(textColor.copy(alpha = 0.08f)),
                                )
                            } else {
                                AsyncImage(
                                    model = ref,
                                    contentDescription = imageBlock.original.ifBlank { null },
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(with(density) { line.heightPx.toDp() })
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                            }
                        }
                        BlockKind.Divider -> PagedSelection(
                            style = style,
                            searchable = false,
                            searchLabel = searchLabel,
                            onSearchSelection = onSearchSelection,
                        ) {
                            Text(
                                text = "· · ·",
                                style = paragraphStyle,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.height(with(density) { line.heightPx.toDp() }),
                            )
                        }
                        BlockKind.Heading, BlockKind.Paragraph -> {
                            val block = blocks[line.blockId]
                            val translated = translations[line.blockId]?.takeIf { it.isNotBlank() }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .then(pagedLineHighlightModifier(line, style, ttsHighlight)),
                            ) {
                                PagedSelection(
                                    style = style,
                                    searchable = true,
                                    searchLabel = searchLabel,
                                    onSearchSelection = onSearchSelection,
                                ) {
                                    val paired = line.alt
                                    if (paired != null) {
                                        PairedText(
                                            line = line,
                                            alt = paired,
                                            block = block,
                                            translated = translated,
                                            style = style,
                                            density = density,
                                            translationFirst = translationFirst,
                                        )
                                    } else {
                                        val raw = lineText(block, line, translated)
                                        val baseStyle = if (line.kind == BlockKind.Heading) {
                                            headingStyle
                                        } else {
                                            paragraphStyle
                                        }
                                        val indent = line.start == 0 || line.isSegmentStart
                                        Text(
                                            text = if (
                                                line.kind == BlockKind.Paragraph &&
                                                translated == null &&
                                                !line.isSegmentStart
                                            ) {
                                                style.paragraphText(
                                                    raw = raw,
                                                    applyFirstLetterBold = line.start == 0,
                                                )
                                            } else {
                                                AnnotatedString(raw)
                                            },
                                            style = baseStyle.copy(
                                                textIndent = if (indent) baseStyle.textIndent else null,
                                            ),
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Clip,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(with(density) { line.heightPx.toDp() }),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One parallel row: a translated visual line, an original visual line, or both.
 * Rows are built per visual line by [pairedRows] (symmetric, one line per row),
 * so a long pair breaks across pages instead of being clipped as a whole.
 */
@Composable
private fun PairedText(
    line: PageLine,
    alt: PageAlt,
    block: ReaderBlock?,
    translated: String?,
    style: ReaderStyle,
    density: Density,
    translationFirst: Boolean,
) {
    val primary = slice(translated.orEmpty(), alt.start, alt.end)
    val secondary = slice(block?.original.orEmpty(), line.start, line.end)
    val gapPx = with(density) { PagedAltGap.toPx() }
    // An empty translated span marks an original-only row from pairedRows.
    val hasPrimary = alt.end > alt.start
    val hasBoth = hasPrimary && alt.heightPx > 0f
    val hasAlt = !hasPrimary || alt.heightPx > 0f
    val primaryHeightPx = when {
        hasBoth -> (line.heightPx - gapPx - alt.heightPx).coerceAtLeast(0f)
        hasPrimary -> line.heightPx
        else -> 0f
    }
    val originalHeightPx = when {
        hasBoth -> alt.heightPx
        hasAlt -> line.heightPx
        else -> 0f
    }
    val translatedBase = if (line.kind == BlockKind.Heading) {
        style.headingTextStyle()
    } else {
        style.toTextStyle()
    }
    val originalBase = style.pagedSecondaryTextStyle(line.kind)
    val translatedStyle = translatedBase.copy(
        textIndent = if (line.isSegmentStart) translatedBase.textIndent else null,
    )
    val originalStyle = originalBase.copy(
        textIndent = if (line.isSegmentStart) originalBase.textIndent else null,
    )
    val translatedText: @Composable () -> Unit = {
        Text(
            text = AnnotatedString(primary),
            style = translatedStyle,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { primaryHeightPx.toDp() }),
        )
    }
    val originalText: @Composable () -> Unit = {
        Text(
            text = AnnotatedString(secondary),
            style = originalStyle,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { originalHeightPx.toDp() }),
        )
    }
    Column(Modifier.fillMaxWidth()) {
        if (translationFirst) {
            if (hasPrimary) translatedText()
            if (hasBoth) Spacer(Modifier.height(PagedAltGap))
            if (hasAlt) originalText()
        } else {
            if (hasAlt) originalText()
            if (hasBoth) Spacer(Modifier.height(PagedAltGap))
            if (hasPrimary) translatedText()
        }
    }
}

/** Secondary copy of a parallel pair: the same metrics as the feed stack. */
internal fun ReaderStyle.pagedSecondaryTextStyle(kind: BlockKind): TextStyle {
    val base = if (kind == BlockKind.Heading) headingTextStyle() else toTextStyle()
    val size = fontSizeSp * PAGED_ALT_SCALE
    return base.copy(
        fontSize = size.sp,
        lineHeight = (size * lineHeight).sp,
        color = Color(effectiveTextColor()).copy(alpha = PAGED_ALT_ALPHA),
    )
}

private fun slice(source: String, start: Int, end: Int): String {
    if (source.isEmpty()) return ""
    val from = start.coerceIn(0, source.length)
    val to = end.coerceIn(from, source.length)
    return source.substring(from, to)
}

/** Visual lines of a measured run, offsets relative to that run. */
private fun TextLayoutResult.visualLines(): List<PagedVisualLine> =
    List(lineCount) { index ->
        val start = getLineStart(index)
        val end = getLineEnd(lineIndex = index, visibleEnd = true).coerceAtLeast(start)
        PagedVisualLine(
            start = start,
            end = end,
            heightPx = (getLineBottom(index) - getLineTop(index)).coerceAtLeast(0f),
        )
    }

/**
 * One selectable paged line with its own SelectionState. A SelectionState must
 * never be shared between containers, and each line is its own container so
 * the search menu item belongs to exactly the text that was selected.
 */
@Composable
private fun PagedSelection(
    style: ReaderStyle,
    searchable: Boolean,
    searchLabel: String,
    onSearchSelection: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    if (!style.selectableText) {
        content()
        return
    }
    val state = rememberSelectionState()
    val modifier = if (searchable) {
        Modifier.selectionSearchMenu(state, searchLabel, onSearchSelection)
    } else {
        Modifier
    }
    SelectionContainer(state = state, modifier = modifier) { content() }
}

/** Cursor comparison in the space the line paints (alt span for translations). */
private fun PageLine.containsTtsCursor(translated: Boolean, cursor: Int): Boolean {
    val altSpan = if (translated) alt else null
    return containsCursor(
        start = altSpan?.start ?: start,
        end = altSpan?.end ?: end,
        cursor = cursor,
    )
}

/**
 * Page that owns the spoken cursor. Matching the char offset (not just the
 * block) matters because one block can span several pages: the page must turn
 * when the cursor crosses the boundary, not when the next block starts.
 * Falls back to the block's first page when offsets are in another space
 * (for example translated speech over original-based parallel lines).
 */
internal fun ttsFollowPage(pages: List<ReaderPage>, highlight: TtsHighlight?): Int {
    if (highlight == null) return -1
    val exact = pages.indexOfFirst { page ->
        page.lines.any { line ->
            line.blockId == highlight.blockId &&
                line.containsTtsCursor(highlight.translated, highlight.start)
        }
    }
    if (exact >= 0) return exact
    return pages.indexOfFirst { page ->
        page.lines.any { it.blockId == highlight.blockId }
    }
}

private fun pagedLineHighlightModifier(
    line: PageLine,
    style: ReaderStyle,
    highlight: TtsHighlight?,
): Modifier {
    if (highlight == null || highlight.blockId != line.blockId) return Modifier
    // With split on, one block is several visible paragraphs: tint only the one
    // the cursor is in (FR-027). Without split the whole block stays tinted.
    if (style.splitSentences) {
        // A paired line slices the original by line.start/end and the translated
        // text by its alt offsets, so the cursor is compared in its own space.
        val alt = if (highlight.translated) line.alt else null
        val start = alt?.start ?: line.start
        val end = alt?.end ?: line.end
        if (!containsCursor(start, end, highlight.start)) return Modifier
    }
    return Modifier.background(
        Color(highlight.colorArgb).copy(alpha = 0.10f),
        RoundedCornerShape(6.dp),
    )
}

private fun lineText(block: ReaderBlock?, line: PageLine, translated: String?): String {
    if (translated != null) {
        val start = line.start.coerceIn(0, translated.length)
        val end = line.end.coerceIn(start, translated.length)
        return translated.substring(start, end)
    }
    val raw = block?.original.orEmpty()
    val source = if (block?.kind == BlockKind.Divider && raw.isBlank()) "· · ·" else raw
    if (source.isEmpty()) return ""
    val start = line.start.coerceIn(0, source.length)
    val end = line.end.coerceIn(start, source.length)
    return source.substring(start, end)
}

private fun measurePages(
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
                    val pairs = parallelPairs(block, translated, style.splitSentences)
                    val secondaryStyle = style.pagedSecondaryTextStyle(block.kind)
                    val gapPx = with(density) { PagedAltGap.toPx() }
                    val primaryStyle = if (block.kind == BlockKind.Heading) {
                        headingStyle
                    } else {
                        paragraphStyle
                    }
                    pairs.forEach { pair ->
                        val primary = slice(translated, pair.translated.start, pair.translated.end)
                        val secondary = slice(
                            block.original,
                            pair.original.start,
                            pair.original.end,
                        )
                        if (primary.isBlank() && secondary.isBlank()) return@forEach
                        val primaryLines = textMeasurer.measure(
                            text = AnnotatedString(primary),
                            style = primaryStyle,
                            layoutDirection = layoutDirection,
                            density = density,
                            constraints = Constraints(maxWidth = width),
                        ).visualLines()
                        val altLines = textMeasurer.measure(
                            text = AnnotatedString(secondary),
                            style = secondaryStyle,
                            layoutDirection = layoutDirection,
                            density = density,
                            constraints = Constraints(maxWidth = width),
                        ).visualLines()
                        measured += pairedRows(
                            blockId = block.id,
                            kind = block.kind,
                            pair = pair,
                            primaryLines = primaryLines,
                            altLines = altLines,
                            gapPx = gapPx,
                            spacingBeforePx = blockSpacing,
                        )
                    }
                    return@forEach
                }
                val source = translated ?: block.original
                if (source.isBlank()) return@forEach
                val split = block.kind == BlockKind.Paragraph && style.splitSentences
                val segments = if (split) {
                    if (translated != null) {
                        TtsChunker.sentenceSpans(source)
                    } else {
                        block.sentences.ifEmpty { SentenceSplitter.spans(source) }
                    }
                } else {
                    listOf(SentenceSpan(0, source.length))
                }
                val baseStyle = if (block.kind == BlockKind.Heading) headingStyle else paragraphStyle
                segments.forEach { segment ->
                    if (segment.end <= segment.start) return@forEach
                    val raw = source.substring(segment.start, segment.end)
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
                        val start = segment.start + lineStart
                        val end = segment.start + result.getLineEnd(
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
                            // Full paragraph spacing between split sentences, none
                            // between the wrapped lines of one sentence.
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

/**
 * True when a center tap lands within [timeout] of the previous one. Nothing
 * pending is 0, so the first tap never counts as a double tap.
 */
internal fun isMenuDoubleTap(now: Long, lastTapAt: Long, timeout: Long): Boolean =
    lastTapAt != 0L && now - lastTapAt <= timeout

/**
 * One short tap handled by the reader, kept so the next gesture can recognize
 * a double tap before SelectionContainer sees its down.
 */
internal data class ReaderTap(val at: Long, val position: Offset)

/**
 * Waits for one reader tap and returns it when handled.
 *
 * The down and up are read at the Initial pass, before SelectionContainer.
 * A short tap consumes its up so selection never sees it. A tap that follows
 * another handled tap within the double-tap timeout and near the same spot
 * also consumes its down: SelectionContainer triggers word selection on the
 * second down, so consuming only the up still left double-click selection
 * active. A long press elsewhere consumes nothing, so text selection and
 * pager swipes keep working.
 */
internal suspend fun AwaitPointerEventScope.awaitReaderTap(
    previousTap: ReaderTap?,
    onTap: (x: Float) -> Unit,
): ReaderTap? {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    val secondTap = previousTap != null &&
        isMenuDoubleTap(
            now = down.uptimeMillis,
            lastTapAt = previousTap.at,
            timeout = viewConfiguration.doubleTapTimeoutMillis,
        ) &&
        (down.position - previousTap.position).getDistance() <= viewConfiguration.touchSlop * 4f
    if (secondTap) down.consume()
    val up = waitForUpOrCancellation(pass = PointerEventPass.Initial) ?: return null
    val moved = (up.position - down.position).getDistance()
    if (moved > viewConfiguration.touchSlop) return null
    if (up.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) return null
    up.consume()
    onTap(up.position.x)
    return ReaderTap(up.uptimeMillis, up.position)
}
